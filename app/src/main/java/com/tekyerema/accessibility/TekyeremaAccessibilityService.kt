package com.tekyerema.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tekyerema.agent.AgentEngine
import com.tekyerema.core.Sanitize
import com.tekyerema.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * The control spine. Reads the active window into a compact [UiSnapshot] and performs
 * node/gesture/global actions. Registers itself with [AgentEngine] while connected.
 *
 * Nothing here knows about any app. Every method is a generic Android mechanism.
 */
class TekyeremaAccessibilityService : AccessibilityService(), AccessibilityController {

    @Volatile private var connected = false
    @Volatile private var lastEventTime = 0L
    @Volatile private var lastScreenshotMs = 0L
    /** Set when the platform refused a screenshot because the window is FLAG_SECURE. */
    /** J-10: the package whose window refused a screenshot as FLAG_SECURE — scoped to that package, so one secure
     *  screen doesn't mark every later app secure (it used to stay set until the next successful screenshot). */
    @Volatile private var secureWindowPkg: String? = null
    @Volatile private var lastScreenshotError: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { r -> mainHandler.post(r) }
    private val nodeMap = HashMap<Int, AccessibilityNodeInfo>()
    private val boundsMap = HashMap<Int, Box>()

    override val ready: Boolean get() = connected

    // The persistent trigger (floating bubble + system accessibility button) lives with the service:
    // it exists exactly as long as screen control is on.
    private val triggerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var triggerJob: Job? = null
    @Volatile private var ourAppInFront = false
    /** The last real app in front before our own UI took over (so a handoff can return there, not HOME). */
    @Volatile private var priorApp: String? = null
    private val buttonCallback = if (Build.VERSION.SDK_INT >= 26) object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) { TriggerBubble.launch(this@TekyeremaAccessibilityService) }
    } else null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        lastEventTime = SystemClock.uptimeMillis()
        live = this
        AgentEngine.attachController(this)
        // System accessibility button (navigation bar / floating shortcut) → the voice experience.
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                serviceInfo = serviceInfo?.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON }
                buttonCallback?.let { accessibilityButtonController.registerAccessibilityButtonCallback(it) }
            } catch (_: Throwable) {}
        }
        registerFingerprintGestures()   // A-04
        // Floating "Talk to Tɛkyerɛma" bubble, following the user's Accessibility setting live.
        val settings = Settings(this)
        triggerJob?.cancel()
        triggerJob = triggerScope.launch {
            settings.changes.collect {
                if (settings.floatingTrigger) { TriggerBubble.show(this@TekyeremaAccessibilityService); foregroundPackage()?.let { refreshTriggerVisibility(it) }; TriggerBubble.setVisible(!ourAppInFront) }
                else TriggerBubble.hide(this@TekyeremaAccessibilityService)
                // Hands-free wake word: arm/disarm live with the setting + AccessKey. No-op when not configured.
                if (settings.wakeWordActive) com.tekyerema.voice.WakeWordService.start(this@TekyeremaAccessibilityService)
                else com.tekyerema.voice.WakeWordService.stop(this@TekyeremaAccessibilityService)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventTime = SystemClock.uptimeMillis()
        event?.let { noteAnnouncement(it) }   // F-17: "Message sent", toasts — evidence for verification
        // Hide the trigger while a Tɛkyerɛma screen is in front; show it over everything else.
        // Decided from the window that actually has focus, so our own overlay windows, the
        // keyboard, the shade and permission dialogs (which all report window-state changes
        // too) can't flip it the wrong way.
        if (com.tekyerema.BuildConfig.DEBUG && (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            android.util.Log.d("TkyWin", "type=${event.eventType} pkg=${event.packageName} cls=${event.className} ch=${event.contentChangeTypes} wch=${if (Build.VERSION.SDK_INT >= 28) event.windowChanges else -1}")
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            // An app can come to front with a window-state event whose class ISN'T one of its Activities: the Play
            // Store brought forward reported cls="gn" (obfuscated) on the A20e, the foreground stayed "launcher", the
            // fast path waited 6 s, gave up and the agent opened it a second time. So an ordinary APPLICATION window
            // (asked of the system's window list — never a binder into the app) counts too; overlays, the keyboard and
            // the shade are other window types and still can't flip it.
            if (pkg != null && cls != null && (isActivity(pkg, cls) || isAppWindow(event.windowId))) {
                fgActivityPkg = pkg
                refreshTriggerVisibility(pkg)
                // A-11: a user-configured offer for this app (flag off by default; cheap no-op for the same package).
                if (pkg != packageName) try { com.tekyerema.agent.triggers.ProactiveRules.onForeground(this, pkg) } catch (_: Throwable) {}
            }
        }
    }

    /** The trigger is hidden exactly while a Tɛkyerɛma activity owns the screen. [active] = the package of the
     *  activity that just came to front (overlays, keyboard and shade never get here — see [isActivity]). */
    private fun refreshTriggerVisibility(active: String) {
        // Remember the last REAL app in front (not ourselves, not the system shade/keyguard) so a handoff can
        // return the user to what they were actually looking at (e.g. Messages) instead of slamming HOME.
        if (active != packageName && active != "com.android.systemui") priorApp = active
        val ours = active == packageName
        if (ours != ourAppInFront) { ourAppInFront = ours; TriggerBubble.setVisible(!ours) }
    }

    override fun onInterrupt() {}

    companion object {
        /** The connected service, for windows it must own: the reply panel is a TYPE_ACCESSIBILITY_OVERLAY (no
         *  "display over other apps" permission, and not hidden by Settings or permission screens). */
        @Volatile var live: TekyeremaAccessibilityService? = null
            private set
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        if (live === this) live = null
        AgentEngine.detachController()
        triggerJob?.cancel()
        TriggerBubble.hide(this)
        com.tekyerema.voice.WakeWordService.stop(this)
        if (Build.VERSION.SDK_INT >= 26) try { buttonCallback?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) } } catch (_: Throwable) {}
        return super.onUnbind(intent)
    }

    /** The package of the activity in front, tracked from window-state events (see [onAccessibilityEvent]).
     *  Falls back to the live root only before the first event. Never call [getRootInActiveWindow] just to
     *  learn the package: it is a synchronous binder round-trip INTO the foreground app, and while that app is
     *  cold-starting it blocks until the app's UI thread answers — seconds, on the caller's thread (often ours). */
    override fun foregroundPackage(): String? =
        fgActivityPkg ?: topAppWindowPackage()?.also { fgActivityPkg = it }
            ?: try { rootInActiveWindow?.packageName?.toString() } catch (t: Throwable) { null }

    /** Before the first activity event (e.g. the service bound after the last app switch): the topmost APPLICATION
     *  window in z-order. Unlike the active window this never picks an overlay — our own floating bubble, the
     *  keyboard or the shade (seen on-device: the eval read "fg=tekyerema" from the bubble). */
    private fun topAppWindowPackage(): String? = try {
        windows?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root?.packageName?.toString()
    } catch (_: Throwable) { null }

    override fun activeWindowPackage(): String? =
        try { rootInActiveWindow?.packageName?.toString() } catch (t: Throwable) { null } ?: foregroundPackage()

    @Volatile private var fgActivityPkg: String? = null

    /** Our own screen just went away (OwnUi). The app under the translucent voice screen never sends a window-state
     *  event, so "ours" would linger here as the foreground; forget it and read the live windows next time. */
    fun forgetOwnForeground() { if (fgActivityPkg == packageName) fgActivityPkg = null }
    private val activityClassCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** True when [cls] is a real Activity of [pkg] — i.e. this window-state event is an app screen coming to
     *  front, not an overlay, dialog pane, keyboard or the notification shade. PackageManager only (system
     *  server), cached; never touches the foreground app. */
    /** The event's window is an ordinary app window (from the system's window list; the app itself isn't asked). */
    private fun isAppWindow(windowId: Int): Boolean = windowId >= 0 && try {
        windows?.firstOrNull { it.id == windowId }?.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
    } catch (_: Throwable) { false }

    private fun isActivity(pkg: String, cls: String): Boolean = activityClassCache.getOrPut("$pkg/$cls") {
        try { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0); true } catch (_: Throwable) { false }
    }

    override fun priorForegroundPackage(): String? = priorApp

    /** K-09: full-screen magnification for low-vision users (MagnificationController, API 24; needs
     *  canControlMagnification). Steps of 1.5x up to 5x; "out" below 1.2x resets. */
    override fun magnify(action: String): Float? = try {
        val mc = magnificationController
        val cur = mc.scale
        when (action) {
            "in" -> { val s = (cur * 1.5f).coerceIn(1.5f, 5f); mc.setScale(s, true); s }
            "out" -> { val s = cur / 1.5f; if (s < 1.2f) { mc.reset(true); 1f } else { mc.setScale(s, true); s } }
            else -> { mc.reset(true); 1f }
        }
    } catch (t: Throwable) { Log.w("Tekyerema", "[MAGNIFY] unavailable: ${t.message}"); null }

    // ---------------------------------------------------------------- A-01 / A-04 / J-14 activation + kill switch
    private val keyPattern = KeyPattern()

    /** A-01 / J-14: volume-down twice → talk, or stop a running task. The keys are never swallowed. */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        val e = event ?: return false
        if (e.action != android.view.KeyEvent.ACTION_UP) return false
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled("A-01", true) ||
            !com.tekyerema.core.config.AppConfig.bool("activation.keys.enabled", true)) return false
        if (keyPattern.onKeyUp(e.keyCode, e.eventTime)) {
            restoreVolume(e.keyCode, steps = 2)   // the gesture's two presses moved the volume; put it back
            talkOrStop("keys")
        }
        return false
    }

    private fun restoreVolume(code: Int, steps: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val dir = if (code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(steps) { try { am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, 0) } catch (_: Throwable) {} }
    }

    /** One gesture, two meanings: when Tɛkyerɛma is busy it STOPS everything (J-14 kill switch); otherwise it opens
     *  the voice screen (in front, so the microphone is allowed) and listens. */
    private fun talkOrStop(source: String) {
        val busy = com.tekyerema.voice.VoiceEngine.isRunning || AgentEngine.isBusy
        Log.i("Tekyerema", "[TRIGGER] $source → ${if (busy) "STOP (kill switch)" else "talk"}")
        if (busy) android.os.Handler(mainLooper).post { com.tekyerema.voice.VoiceEngine.cancel() }
        else try {
            startActivity(android.content.Intent(this, com.tekyerema.ui.VoiceActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) { Log.w("Tekyerema", "[TRIGGER] couldn't open the voice screen: ${t.message}") }
    }

    /** A-04: swipe down on the fingerprint sensor = talk (or stop); swipe up = stop. Only where the sensor supports
     *  gestures (isGestureDetectionAvailable) — otherwise nothing is registered. */
    private fun registerFingerprintGestures() {
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled("A-04", true)) return
        try {
            val fgc = fingerprintGestureController
            if (!fgc.isGestureDetectionAvailable) { Log.i("Tekyerema", "[TRIGGER] fingerprint gestures not available on this sensor"); return }
            fgc.registerFingerprintGestureCallback(object : android.accessibilityservice.FingerprintGestureController.FingerprintGestureCallback() {
                override fun onGestureDetected(gesture: Int) {
                    when (gesture) {
                        android.accessibilityservice.FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> talkOrStop("fingerprint")
                        android.accessibilityservice.FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP ->
                            android.os.Handler(mainLooper).post { com.tekyerema.voice.VoiceEngine.cancel() }
                    }
                }
            }, null)
            Log.i("Tekyerema", "[TRIGGER] fingerprint gestures registered")
        } catch (t: Throwable) { Log.w("Tekyerema", "[TRIGGER] fingerprint gestures unavailable: ${t.message}") }
    }

    /** J-08: app overlays (TYPE_APPLICATION_OVERLAY, drawn via SYSTEM_ALERT_WINDOW) are reported to accessibility as
     *  TYPE_SYSTEM windows owned by the drawing app's package. Anything not the system UI, the platform, the keyboard
     *  or Tɛkyerɛma itself that covers (x,y) is foreign. Window list requires flagRetrieveInteractiveWindows (set). */
    override fun foreignOverlayAt(x: Int, y: Int): String? = try {
        val ime = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        val trusted = setOf(packageName, "com.android.systemui", "android", ime)
        windows?.firstOrNull { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM && run {
                val r = android.graphics.Rect(); w.getBoundsInScreen(r)
                r.contains(x, y)
            } && (w.root?.packageName?.toString()?.let { it !in trusted } ?: false)
        }?.root?.packageName?.toString()
    } catch (_: Throwable) { null }

    override fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    override suspend fun waitForSettle(timeoutMs: Long) {
        val start = SystemClock.uptimeMillis()
        val quietMs = com.tekyerema.core.config.AppConfig.long("executor.settle.quietMs", 350L)
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (SystemClock.uptimeMillis() - lastEventTime >= quietMs) return
            delay(100)
        }
    }

    override fun lastEventUptime(): Long = lastEventTime

    override suspend fun waitForChange(sinceUptime: Long, reactMs: Long, timeoutMs: Long) {
        val start = SystemClock.uptimeMillis()
        // Phase 1: did anything react to the action? Poll until an event lands after the mark.
        while (lastEventTime <= sinceUptime) {
            if (SystemClock.uptimeMillis() - start >= reactMs) return
            delay(50)
        }
        // Phase 2: it reacted — wait for it to finish changing (bounded by what's left of the timeout).
        waitForSettle((timeoutMs - (SystemClock.uptimeMillis() - start)).coerceAtLeast(0L))
    }

    // ------------------------------------------------------------------ perception

    override suspend fun buildSnapshot(): UiSnapshot {
        val root = try { rootInActiveWindow } catch (t: Throwable) { null } ?: return UiSnapshot.EMPTY
        nodeMap.clear(); boundsMap.clear()
        val (sw, sh) = screenSize()
        val rootPkg = root.packageName?.toString()
        val elements = ArrayList<UiElement>()
        var idCounter = 0
        var visited = 0
        var stripped = 0
        val maxVisited = 1200
        val maxCollected = 220
        val maxDepth = com.tekyerema.core.config.AppConfig.int("perception.tree.maxDepth", 60).coerceIn(10, 120)

        // Each stack entry carries whether an ancestor is clickable, so "tap-able via parent" is
        // known without walking parent chains (every getParent() is an IPC round trip).
        val stack = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Boolean>>()
        stack.addLast(Triple(root, 0, false))
        while (stack.isNotEmpty() && visited < maxVisited && elements.size < maxCollected) {
            val (node, depth, ancestorClickable) = stack.removeLast()
            visited++
            val rawLabel = rawLabelOf(node)
            if (Sanitize.hadHiddenContent(rawLabel)) stripped++
            val label = Sanitize.label(rawLabel)
            val hasRange = try { node.rangeInfo != null } catch (t: Throwable) { false }
            val clickable = node.isClickable || ancestorClickable
            val longClickable = node.isLongClickable
            val interesting = clickable || longClickable || node.isEditable || node.isScrollable ||
                node.isCheckable || hasRange || label != null
            if (interesting && node.isVisibleToUser) {
                val r = Rect(); node.getBoundsInScreen(r)
                val b = Box(r.left, r.top, r.right, r.bottom)
                // Drop zero-size and fully off-screen nodes: a classic hidden-text carrier.
                val onScreen = !b.isEmpty() && b.right > 0 && b.bottom > 0 && b.left < sw && b.top < sh
                // Research §2.2 h: a sliver of a node (tiny), a paragraph crammed into a dot, or a description that tells
                // TalkBack something other than the visible text (Perceivability). Tiny ones go the way of off-screen ones.
                val unseen = if (!onScreen) null else Perceivability.check(b, sw, sh,
                    Sanitize.label(node.text?.toString()), Sanitize.label(node.contentDescription?.toString()))
                if (onScreen && unseen != Perceivability.TINY) {
                    val id = idCounter++
                    nodeMap[id] = node
                    boundsMap[id] = b
                    val ri = try { node.rangeInfo } catch (t: Throwable) { null }
                    elements.add(
                        UiElement(
                            id = id,
                            role = roleOf(node.className),
                            label = if (node.isPassword) "(password field, redacted)" else label,
                            bounds = b,
                            source = ElementSource.TREE,
                            editable = node.isEditable,
                            clickable = clickable,
                            longClickable = longClickable,
                            scrollable = node.isScrollable,
                            checkable = node.isCheckable,
                            checked = node.isChecked,
                            selected = node.isSelected,
                            enabled = node.isEnabled,
                            focused = node.isFocused,
                            password = node.isPassword,
                            range = ri?.let { UiRange(it.min, it.max, it.current) },
                            viewId = try { node.viewIdResourceName } catch (t: Throwable) { null },
                            collection = collectionHint(node),
                            hint = if (node.isEditable) try { node.hintText?.toString()?.takeIf { it.isNotBlank() } } catch (t: Throwable) { null } else null,
                            heading = isHeading(node),
                            link = webRole(node)?.contains("link", true) == true,
                            customActions = customActionsOf(node),
                            unseen = unseen,
                            pkg = node.packageName?.toString()?.takeIf { it != rootPkg }
                        )
                    )
                } else if (label != null) {
                    stripped++ // labelled but invisible/off-screen: treated as a hidden channel
                }
            }
            // Depth 60, not 25: Compose apps nest deeply — ChatGPT's message box sits 35 levels down (A20e 2026-09-29),
            // so the app looked like it had no text box at all. The visit budget above still bounds the walk; and an
            // invisible node's subtree (a closed drawer, an off-screen page) is skipped so it can't use that budget up.
            if (depth < maxDepth && (depth == 0 || node.isVisibleToUser)) {
                val childClickable = node.isClickable || ancestorClickable
                for (i in node.childCount - 1 downTo 0) {
                    val c = try { node.getChild(i) } catch (t: Throwable) { null }
                    if (c != null) stack.addLast(Triple(c, depth + 1, childClickable))
                }
            }
        }
        val window = try { root.window } catch (t: Throwable) { null }
        val title = try { window?.title?.toString() } catch (t: Throwable) { null }
        val pkg = root.packageName?.toString()
        // F-13: say when the focused window isn't an ordinary app window (a system dialog, the keyboard, an overlay).
        val wtype = when (try { window?.type } catch (_: Throwable) { null }) {
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split-screen divider"
            else -> null
        }
        return UiSnapshot(
            appPackage = pkg,
            screenTitle = title,
            elements = elements,
            timestampMs = System.currentTimeMillis(),
            perception = PerceptionRoute("tree", "tree traversal"),
            secureWindow = pkg != null && pkg == secureWindowPkg,
            strippedHiddenText = stripped,
            appName = pkg?.let { appNameOf(it) },
            windowType = wtype,
            truncated = visited >= maxVisited || elements.size >= maxCollected,   // F-01
            announcements = recentAnnouncements()                                  // F-17
        )
    }

    private fun isHeading(n: AccessibilityNodeInfo): Boolean =
        (Build.VERSION.SDK_INT >= 28 && try { n.isHeading } catch (_: Throwable) { false }) ||
            webRole(n)?.startsWith("heading", true) == true

    /** F-14: Chrome/WebView put the web role ("link", "heading 2"…) in the node's extras. */
    private fun webRole(n: AccessibilityNodeInfo): String? = try {
        n.extras?.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()
    } catch (_: Throwable) { null }

    /** G-03: app-defined actions (custom ids with a label) — not the platform's own ids (bit flags, android.R.id). */
    private fun customActionsOf(n: AccessibilityNodeInfo): List<String> = try {
        n.actionList.filter { a -> a.label != null && a.id >= 0x01000000 && (a.id and 0xFFFF0000.toInt()) != 0x01020000 }
            .mapNotNull { Sanitize.label(it.label?.toString()) }
    } catch (_: Throwable) { emptyList() }

    // ---------------------------------------------------------------- F-17 announcements and toasts
    private val announced = ArrayDeque<Pair<Long, String>>()

    private fun noteAnnouncement(e: AccessibilityEvent) {
        val isToast = e.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            e.className?.toString()?.contains("Toast", true) == true
        if (e.eventType != AccessibilityEvent.TYPE_ANNOUNCEMENT && !isToast) return
        val text = Sanitize.label(e.text?.joinToString(" ")) ?: return
        synchronized(announced) { announced.addLast(SystemClock.uptimeMillis() to text.take(120)); while (announced.size > 5) announced.removeFirst() }
    }

    private fun recentAnnouncements(): List<String> {
        val cutoff = SystemClock.uptimeMillis() - 10_000L
        return synchronized(announced) { announced.filter { it.first >= cutoff }.map { it.second } }
    }

    private val appNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Human name for a package, cached (label lookups hit PackageManager). The default launcher is
     *  "the home screen" — nobody calls it "Pixel Launcher" or com.google.android.apps.nexuslauncher. */
    private fun appNameOf(pkg: String): String? = appNames[pkg] ?: try {
        val home = packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        val name = if (pkg == home) "the home screen"
            else packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        name.takeIf { it.isNotBlank() && it != pkg }?.also { appNames[pkg] = it }
    } catch (_: Throwable) { null }

    private fun rawLabelOf(n: AccessibilityNodeInfo): String? {
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        // Empty editable fields expose their placeholder via hintText (e.g. a compose box's "Message").
        try { n.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return it } } catch (t: Throwable) {}
        return null
    }

    private fun collectionHint(n: AccessibilityNodeInfo): String? = try {
        n.collectionInfo?.let { "list ${it.rowCount} rows x ${it.columnCount} cols" }
            ?: n.collectionItemInfo?.let { "row ${it.rowIndex}" }
    } catch (t: Throwable) { null }

    private fun roleOf(cn: CharSequence?): String {
        val s = cn?.toString() ?: return "view"
        return s.substringAfterLast('.').ifEmpty { s }
    }

    private fun ancestorWhere(node: AccessibilityNodeInfo?, hops: Int, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var n = node
        var i = 0
        while (n != null && i < hops) {
            if (pred(n)) return n
            n = try { n.parent } catch (t: Throwable) { null }
            i++
        }
        return null
    }

    override fun boundsOf(id: Int): Box? = boundsMap[id]

    override fun lastScreenshotFailure(): String? = lastScreenshotError

    @android.annotation.SuppressLint("NewApi")   // guarded on the next line; lint can't follow it into the callback
    override suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        // Never continuous: the platform rate-limits, and so do we.
        val now = SystemClock.uptimeMillis()
        if (now - lastScreenshotMs < 1000L) delay(1000L - (now - lastScreenshotMs))
        lastScreenshotMs = SystemClock.uptimeMillis()
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        secureWindowPkg = null
                        lastScreenshotError = null
                        val bmp = try {
                            val hb = screenshot.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            val sw = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            sw
                        } catch (t: Throwable) { null }
                        if (cont.isActive) cont.resume(bmp)
                    }
                    override fun onFailure(errorCode: Int) {
                        // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW only exists from API 34; on API 30 a secure window is
                        // never reported here, so credential detection relies on password fields and wording (J-10).
                        if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) secureWindowPkg = activeWindowPackage()
                        lastScreenshotError = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure window (FLAG_SECURE)"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate limited"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no screenshot access"
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error"
                            else -> "error $errorCode"
                        }
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    // ------------------------------------------------------------------ node actions

    override suspend fun clickElement(id: Int): Boolean {
        val node = nodeMap[id] ?: return false
        val target = if (node.isClickable) node else ancestorWhere(node, 6) { it.isClickable } ?: node
        val clicked = try { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (t: Throwable) { false }
        if (clicked) return true
        // Fallback for unlabeled/custom-drawn controls with no clickable ancestor: gesture-tap the
        // element's own bounds center.
        val b = boundsMap[id] ?: return false
        if (b.isEmpty()) return false
        return tap(b.centerX, b.centerY)
    }

    override suspend fun longClickElement(id: Int): Boolean {
        val node = nodeMap[id] ?: return false
        val target = if (node.isLongClickable) node else ancestorWhere(node, 6) { it.isLongClickable }
        if (target != null) {
            val ok = try { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } catch (t: Throwable) { false }
            if (ok) return true
        }
        return false // caller falls back to a timed gesture with the node's bounds
    }

    override suspend fun setText(id: Int, text: String, submit: Boolean): Boolean {
        val node = nodeMap[id] ?: return false
        val set = try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) { false }
        if (set && submit && Build.VERSION.SDK_INT >= 30) {   // ACTION_IME_ENTER is API 30
            try {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } catch (t: Throwable) { /* best-effort — text was still set */ }
        }
        return set
    }

    override suspend fun scrollElement(id: Int, direction: String): Boolean {
        val node = nodeMap[id] ?: return false
        val scrollable = if (node.isScrollable) node else ancestorWhere(node, 8) { it.isScrollable } ?: return false
        // G-02: keep the AXIS — a horizontal carousel scrolled "down" used to be scrolled forward on its only axis.
        val directional = when (direction.lowercase()) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> null
        }
        if (directional != null && scrollable.actionList.any { it.id == directional.id }) {
            if (try { scrollable.performAction(directional.id) } catch (_: Throwable) { false }) return true
        }
        val backward = direction.equals("up", true) || direction.equals("backward", true) || direction.equals("left", true)
        val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        return try { scrollable.performAction(action) } catch (t: Throwable) { false }
    }

    override suspend fun nodeAction(id: Int, action: String): Boolean {
        val node = nodeMap[id] ?: return false
        val a = action.trim().lowercase()
        if (a.startsWith("custom:")) {
            // G-03: an action the app defines on this element (its label is what TalkBack's actions menu shows).
            val label = action.substringAfter(':').trim()
            val custom = node.actionList.firstOrNull { it.label?.toString()?.equals(label, true) == true }
                ?: ancestorWhere(node, 4) { n -> n.actionList.any { it.label?.toString()?.equals(label, true) == true } }
                    ?.actionList?.firstOrNull { it.label?.toString()?.equals(label, true) == true }
                ?: return false
            val owner = if (node.actionList.any { it.id == custom.id }) node else ancestorWhere(node, 4) { n -> n.actionList.any { it.id == custom.id } } ?: node
            return try { owner.performAction(custom.id) } catch (_: Throwable) { false }
        }
        val id2 = when (a) {
            "select" -> AccessibilityNodeInfo.ACTION_SELECT
            "expand" -> AccessibilityNodeInfo.ACTION_EXPAND
            "collapse" -> AccessibilityNodeInfo.ACTION_COLLAPSE
            "dismiss" -> AccessibilityNodeInfo.ACTION_DISMISS
            "copy" -> AccessibilityNodeInfo.ACTION_COPY
            "paste" -> AccessibilityNodeInfo.ACTION_PASTE
            "cut" -> AccessibilityNodeInfo.ACTION_CUT
            "show_on_screen" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            "context_click" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.id
            "page_up" -> if (Build.VERSION.SDK_INT >= 29) AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id else return false
            "page_down" -> if (Build.VERSION.SDK_INT >= 29) AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id else return false
            "page_left" -> if (Build.VERSION.SDK_INT >= 29) AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id else return false
            "page_right" -> if (Build.VERSION.SDK_INT >= 29) AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id else return false
            else -> return false
        }
        // The action may live on the element or a close ancestor (a row that owns "expand").
        val target = if (node.actionList.any { it.id == id2 }) node else ancestorWhere(node, 4) { n -> n.actionList.any { it.id == id2 } } ?: node
        return try { target.performAction(id2) } catch (_: Throwable) { false }
    }

    override suspend fun focusElement(id: Int): Boolean {
        val node = nodeMap[id] ?: return false
        return try { node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) } catch (t: Throwable) { false }
    }

    override suspend fun setRangeValue(id: Int, value: Float): Boolean {
        val node = nodeMap[id] ?: return false
        val ri = try { node.rangeInfo } catch (t: Throwable) { null } ?: return false
        val target = value.coerceIn(ri.min, ri.max)

        // Preferred: direct value set (API 24+; most modern SeekBar/Slider widgets support it).
        try {
            val args = Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target)
            }
            val actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
            if (node.performAction(actionId, args)) return true
        } catch (t: Throwable) { /* fall through to stepped scroll */ }

        // Fallback: repeated scroll-forward/backward steps (SeekBar/Slider respond as +/- one step).
        val span = (ri.max - ri.min).takeIf { it > 0f } ?: return false
        val steps = 20
        val stepSize = span / steps
        var current = ri.current
        var guard = steps * 2
        while (guard-- > 0) {
            val diff = target - current
            if (kotlin.math.abs(diff) <= stepSize / 2f) break
            val action = if (diff > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val ok = try { node.performAction(action) } catch (t: Throwable) { false }
            if (!ok) return false
            delay(80)
            current = try { node.rangeInfo?.current ?: current } catch (t: Throwable) { current }
        }
        return true
    }

    // ------------------------------------------------------------------ gestures

    // REL-P2-4: a stray touch (the user or another service touching the screen) during the stroke cancels it.
    // Retry once before failing — a cancelled gesture never registered, so a retry can't double-tap/-send.
    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        if (dispatchOnce(gesture)) return true
        delay(120)
        return dispatchOnce(gesture)
    }

    private suspend fun dispatchOnce(gesture: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, mainHandler)
            if (!dispatched && cont.isActive) cont.resume(false)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(false)
        }
    }

    private fun clampX(x: Int) = x.coerceIn(0, screenSize().first - 1)
    private fun clampY(y: Int) = y.coerceIn(0, screenSize().second - 1)

    override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        val fx = clampX(x).toFloat(); val fy = clampY(y).toFloat()
        val path = Path().apply { moveTo(fx, fy); lineTo(fx, fy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(20L, 10_000L))
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(clampX(x1).toFloat(), clampY(y1).toFloat())
            lineTo(clampX(x2).toFloat(), clampY(y2).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 10_000L))
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * A continuous multi-point touch. Uses the platform's continued-stroke mechanism (API 26+):
     * each segment is a StrokeDescription with willContinue=true chained via continueStroke(), so
     * the finger never lifts between points — required for reorder/drag-and-drop targets.
     */
    override suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long): Boolean {
        val pts = points.map { clampX(it.first).toFloat() to clampY(it.second).toFloat() }
        if (pts.size < 2) return false
        val segments = pts.size - 1
        val segMs = (durationMs.coerceIn(100L, 20_000L) / segments).coerceAtLeast(40L)
        // Hold briefly at the first point so drag-start recognizers (long-press-to-drag) engage.
        val holdMs = 250L
        var stroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(pts[0].first, pts[0].second); lineTo(pts[0].first, pts[0].second) },
            0, holdMs, true
        )
        if (!dispatch(GestureDescription.Builder().addStroke(stroke).build())) return false
        for (i in 0 until segments) {
            val last = i == segments - 1
            val p = Path().apply { moveTo(pts[i].first, pts[i].second); lineTo(pts[i + 1].first, pts[i + 1].second) }
            stroke = stroke.continueStroke(p, 0, segMs, !last)
            if (!dispatch(GestureDescription.Builder().addStroke(stroke).build())) return false
        }
        return true
    }

    override suspend fun pinch(cx: Int, cy: Int, startGap: Int, endGap: Int, durationMs: Long): Boolean {
        val x = clampX(cx); val y = clampY(cy)
        val dur = durationMs.coerceIn(100L, 5000L)
        val s = (startGap / 2).coerceAtLeast(1); val e = (endGap / 2).coerceAtLeast(1)
        // Two fingers on a horizontal axis, moving symmetrically in/out at the same time.
        val left = Path().apply { moveTo(clampX(x - s).toFloat(), y.toFloat()); lineTo(clampX(x - e).toFloat(), y.toFloat()) }
        val right = Path().apply { moveTo(clampX(x + s).toFloat(), y.toFloat()); lineTo(clampX(x + e).toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0, dur))
            .addStroke(GestureDescription.StrokeDescription(right, 0, dur))
            .build()
        return dispatch(g)
    }

    // ------------------------------------------------------------------ system

    override fun globalAction(action: String): Boolean {
        val a = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            // Lock screen / screenshot are API 28+; on older devices report failure (never fake it).
            "lock", "lock_screen" -> if (android.os.Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (android.os.Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            "power_menu", "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> return false
        }
        // E-18: on API 30 the system lists the global actions this device supports — don't press a newer one it lacks.
        // (Only for the newer actions: some OEM lists omit the classic back/home/lock even though they work.)
        val newer = setOf(GLOBAL_ACTION_POWER_DIALOG, GLOBAL_ACTION_TAKE_SCREENSHOT, GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        if (android.os.Build.VERSION.SDK_INT >= 30 && a in newer) {
            val supported = try { systemActions.map { it.id } } catch (_: Throwable) { null }
            if (supported != null && supported.isNotEmpty() && a !in supported) return false
        }
        return try { performGlobalAction(a) } catch (t: Throwable) { false }
    }

    override fun adjustVolume(direction: String): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val dir = when (direction.lowercase()) {
            "up", "volume_up" -> AudioManager.ADJUST_RAISE
            "down", "volume_down" -> AudioManager.ADJUST_LOWER
            "mute", "volume_mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return false
        }
        return try {
            am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            true
        } catch (t: Throwable) { false }
    }
}
