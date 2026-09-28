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
        AgentEngine.attachController(this)
        // System accessibility button (navigation bar / floating shortcut) → the voice experience.
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                serviceInfo = serviceInfo?.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON }
                buttonCallback?.let { accessibilityButtonController.registerAccessibilityButtonCallback(it) }
            } catch (_: Throwable) {}
        }
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
            if (pkg != null && cls != null && isActivity(pkg, cls)) {
                fgActivityPkg = pkg
                refreshTriggerVisibility(pkg)
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

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
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
    private val activityClassCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** True when [cls] is a real Activity of [pkg] — i.e. this window-state event is an app screen coming to
     *  front, not an overlay, dialog pane, keyboard or the notification shade. PackageManager only (system
     *  server), cached; never touches the foreground app. */
    private fun isActivity(pkg: String, cls: String): Boolean = activityClassCache.getOrPut("$pkg/$cls") {
        try { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0); true } catch (_: Throwable) { false }
    }

    override fun priorForegroundPackage(): String? = priorApp

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
        val quietMs = 350L
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (SystemClock.uptimeMillis() - lastEventTime >= quietMs) return
            delay(100)
        }
    }

    // ------------------------------------------------------------------ perception

    override suspend fun buildSnapshot(): UiSnapshot {
        val root = try { rootInActiveWindow } catch (t: Throwable) { null } ?: return UiSnapshot.EMPTY
        nodeMap.clear(); boundsMap.clear()
        val (sw, sh) = screenSize()
        val elements = ArrayList<UiElement>()
        var idCounter = 0
        var visited = 0
        var stripped = 0
        val maxVisited = 1200
        val maxCollected = 220

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
                if (onScreen) {
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
                            hint = if (node.isEditable) try { node.hintText?.toString()?.takeIf { it.isNotBlank() } } catch (t: Throwable) { null } else null
                        )
                    )
                } else if (label != null) {
                    stripped++ // labelled but invisible/off-screen: treated as a hidden channel
                }
            }
            if (depth < 25) {
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
        return UiSnapshot(
            appPackage = pkg,
            screenTitle = title,
            elements = elements,
            timestampMs = System.currentTimeMillis(),
            perception = PerceptionRoute("tree", "tree traversal"),
            secureWindow = pkg != null && pkg == secureWindowPkg,
            strippedHiddenText = stripped,
            appName = pkg?.let { appNameOf(it) }
        )
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
        if (set && submit) {
            try {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } catch (t: Throwable) { /* best-effort — text was still set */ }
        }
        return set
    }

    override suspend fun scrollElement(id: Int, direction: String): Boolean {
        val node = nodeMap[id] ?: return false
        val scrollable = if (node.isScrollable) node else ancestorWhere(node, 8) { it.isScrollable } ?: return false
        val backward = direction.equals("up", true) || direction.equals("backward", true) || direction.equals("left", true)
        val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        return try { scrollable.performAction(action) } catch (t: Throwable) { false }
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
            // Lock screen is API 28+; on older devices report failure (never fake it).
            "lock" -> if (android.os.Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            else -> return false
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
