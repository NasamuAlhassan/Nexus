package com.tekyerema.teach

import com.tekyerema.accessibility.TelephonyWindows
import com.tekyerema.policy.TargetSemantics

/**
 * Teach mode — what the accessibility service saw the user do, as plain data. The service's adapter
 * ([TeachEventSource]) fills this from an `AccessibilityEvent`; everything below is pure Kotlin and unit-tested.
 */
data class RawEvent(
    val type: Type,
    val pkg: String?,
    val timeMs: Long,
    /** What a person calls [pkg]. */
    val appName: String? = null,
    val screenTitle: String? = null,
    /** The source node's class, simple name ("Button", "EditText"). */
    val role: String? = null,
    /** A clicked element's visible text / description (never an editable's contents). */
    val label: String? = null,
    val viewId: String? = null,
    /** An editable's placeholder hint. */
    val hint: String? = null,
    val editable: Boolean = false,
    val password: Boolean = false,
    /** TEXT_CHANGED: the box's new contents. Held in memory only until the user says whether it may be kept. */
    val text: String? = null,
    /** SCROLLED: how far the content moved (positive dy = revealed content below / dx = to the right). */
    val scrollDx: Int = 0,
    val scrollDy: Int = 0,
    // ---- classified by the adapter from the system, so this file needs no Context ----
    /** The package is the on-screen keyboard (its key presses are never steps). */
    val isIme: Boolean = false,
    /** The package is the home screen / System UI (a tap on an app icon is not a step; the app opening is). */
    val isShell: Boolean = false,
    /** The app has a launcher icon, so "open <app>" can start it. */
    val launchable: Boolean = false,
    /** The user's deny list names this app (J-11): nothing in it is recorded. */
    val denied: Boolean = false
) {
    enum class Type { CLICK, LONG_CLICK, TEXT_CHANGED, SCROLLED, WINDOW_CHANGED }
}

/** Why recording stopped by itself (the user's own "stop" is not one of these). */
enum class StopReason(val spoken: String) {
    PRIVATE("That is a private screen, a PIN, a password or a code, so I stopped watching and kept nothing from it."),
    PHONE_DIALOG("That is the phone's own dialog, where a PIN can be typed, so I stopped watching and kept nothing from it."),
    DENIED_APP("That app is on your do-not-touch list, so I stopped watching."),
    TOO_LONG("That is a lot of steps, so I stopped watching here."),
    TIMEOUT("It has been a long time, so I stopped watching.")
}

/**
 * Teach mode — the recorder. Turns [RawEvent]s into [TeachStep]s while it is on, and stops by itself at anything private.
 *
 * Privacy (a hard boundary in code, like Secure Input):
 *  - a password field, a field whose hint / id / name says PIN, OTP, password, code…, the phone's own USSD / dialer / SIM
 *    windows, an app on the deny list, and any moment a secure step is running, STOP the recording. The event that
 *    caused the stop is never turned into a step, and its text is never held.
 *  - typed text is not part of a step: only "typed into the Search box, N characters". The words are held in memory until
 *    the user is asked whether they are private ([TeachSession]); nothing is stored without that answer.
 *  - [stopForPrivacy] is also how the adapter reports a credential SCREEN found by looking at the whole tree a moment
 *    after a step: the steps recorded since then are dropped.
 *  - nothing about what is recorded is logged beyond counts and kinds.
 */
class TeachRecorder(
    private val ownPackage: String = "com.tekyerema",
    private val maxSteps: Int = 30,
    private val maxMs: Long = 10 * 60_000L
) {
    /** A recorded step and the moment it was recorded; [typed] is the held, unconfirmed text of a TYPE step. */
    class Draft(var step: TeachStep, val at: Long, var typed: String? = null)

    sealed class Result {
        object Ignored : Result()
        /** A step was added or extended; [index] in [steps]. */
        data class Recorded(val index: Int) : Result()
        data class Stopped(val reason: StopReason) : Result()
    }

    private val drafts = ArrayList<Draft>()
    @Volatile var recording = false; private set
    @Volatile var stopReason: StopReason? = null; private set
    private var startedAt = 0L
    private var lastPkg: String? = null
    private var lastClick: Pair<String?, Long>? = null

    @Synchronized fun start(now: Long) {
        drafts.clear(); recording = true; stopReason = null; startedAt = now; lastPkg = null; lastClick = null
    }

    /** The steps so far (copies — the held text is not in them). */
    @Synchronized fun steps(): List<TeachStep> = drafts.map { it.step }

    @Synchronized fun drafts(): List<Draft> = drafts.toList()

    /** The user's own stop. Returns the drafts (the held text is still attached for the privacy questions). */
    @Synchronized fun stop(): List<Draft> { recording = false; return drafts.toList() }

    /** Forget everything, including held text. */
    @Synchronized fun discard() { recording = false; stopReason = null; wipe(); drafts.clear(); lastPkg = null; lastClick = null }

    private fun wipe() { drafts.forEach { it.typed = null } }

    /**
     * Stop recording because something private came up. Steps recorded at or after [sinceMs] are dropped (they were done ON
     * the private screen); the held text of all steps ending there is wiped when [wipeText].
     */
    @Synchronized fun stopForPrivacy(reason: StopReason, sinceMs: Long = Long.MAX_VALUE) {
        if (!recording) return
        recording = false; stopReason = reason
        while (drafts.isNotEmpty() && drafts.last().at >= sinceMs) drafts.removeAt(drafts.lastIndex)
        // A typed step right before the private screen may be the first digits of it — its text is never asked about.
        drafts.lastOrNull()?.let { if (it.step.kind == StepKind.TYPE) it.typed = null }
    }

    /** Is this event itself private? The reason, or null. */
    fun privateReason(e: RawEvent, secureNow: Boolean): StopReason? {
        if (secureNow) return StopReason.PRIVATE
        if (e.denied) return StopReason.DENIED_APP
        if (TelephonyWindows.matches(e.pkg)) return StopReason.PHONE_DIALOG
        if (e.password) return StopReason.PRIVATE
        if (e.editable || e.type == RawEvent.Type.TEXT_CHANGED) {
            val hint = e.hint.orEmpty()
            val id = StepWords.idWords(e.viewId)
            val label = e.label.orEmpty()
            if (hint.isNotEmpty() && TargetSemantics.credentialField.containsMatchIn(hint) &&
                !TargetSemantics.communicationField.containsMatchIn(hint)) return StopReason.PRIVATE
            if (TargetSemantics.credentialField.containsMatchIn(id)) return StopReason.PRIVATE
            if (hint.isEmpty() && label.length <= 30 && TargetSemantics.credentialField.containsMatchIn(label)) return StopReason.PRIVATE
        }
        return null
    }

    @Synchronized fun onEvent(e: RawEvent, secureNow: Boolean = false): Result {
        if (!recording) return Result.Ignored
        val pkg = e.pkg ?: return Result.Ignored
        if (pkg == ownPackage) return Result.Ignored
        // The phone's own system dialogs ("isn't responding" / crash, the permission and install prompts) are not the skill's:
        // a tap on an ANR dialog's "Wait" became step 1 on the emulator (2 Nov 2026).
        if (pkg in SYSTEM_DIALOGS) return Result.Ignored
        if (e.timeMs - startedAt > maxMs) { stopTimeout(); return Result.Stopped(StopReason.TIMEOUT) }
        // Privacy first — before the event can become a step or its text can be held.
        privateReason(e, secureNow)?.let { r -> recording = false; stopReason = r; drafts.lastOrNull()?.let { d -> if (d.step.kind == StepKind.TYPE) d.typed = null }; return Result.Stopped(r) }
        if (e.isIme) return Result.Ignored
        return when (e.type) {
            RawEvent.Type.WINDOW_CHANGED -> windowChanged(e, pkg)
            RawEvent.Type.CLICK, RawEvent.Type.LONG_CLICK -> clicked(e, pkg)
            RawEvent.Type.TEXT_CHANGED -> typed(e, pkg)
            RawEvent.Type.SCROLLED -> scrolled(e, pkg)
        }
    }

    private val SYSTEM_DIALOGS = setOf("android", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.android.packageinstaller")

    private fun stopTimeout() { recording = false; stopReason = StopReason.TIMEOUT }

    private fun add(d: Draft): Result {
        if (drafts.size >= maxSteps) { recording = false; stopReason = StopReason.TOO_LONG; return Result.Stopped(StopReason.TOO_LONG) }
        drafts.add(d)
        return Result.Recorded(drafts.lastIndex)
    }

    /** An app came to the front: "open it" — but only for apps with a launcher icon, and not the home screen. */
    private fun windowChanged(e: RawEvent, pkg: String): Result {
        if (e.isShell) return Result.Ignored
        val last = drafts.lastOrNull()
        // The screen moved on right after typing, with no button tapped: the field was submitted (Search / Go on the keyboard).
        if (last != null && last.step.kind == StepKind.TYPE && last.step.pkg == pkg && !last.step.submit && e.timeMs - last.at < 4_000L) {
            last.step = last.step.copy(submit = true, sensitive = StepWords.isSensitive(last.step.copy(submit = true)))
            return Result.Recorded(drafts.lastIndex)
        }
        if (pkg == lastPkg || !e.launchable) { return Result.Ignored }
        lastPkg = pkg
        return add(Draft(TeachStep(StepKind.OPEN_APP, pkg, appName = e.appName, screenTitle = e.screenTitle), e.timeMs))
    }

    /** Make sure the step is preceded by "open <app>" when the app changed without a window event we saw. */
    private fun ensureApp(e: RawEvent, pkg: String) {
        if (pkg == lastPkg) return
        lastPkg = pkg
        if (e.launchable && !e.isShell) drafts.add(Draft(TeachStep(StepKind.OPEN_APP, pkg, appName = e.appName), e.timeMs))
    }

    private fun clicked(e: RawEvent, pkg: String): Result {
        if (e.isShell) return Result.Ignored                     // a tap on an app icon: the app opening is the step
        val label = e.label?.trim()?.takeIf { it.isNotEmpty() }
        // The same control twice within a moment is one press reported twice.
        lastClick?.let { (l, t) -> if (l == (label ?: e.viewId) && e.timeMs - t < 400L) return Result.Ignored }
        lastClick = (label ?: e.viewId) to e.timeMs
        ensureApp(e, pkg)
        val step = TeachStep(if (e.type == RawEvent.Type.LONG_CLICK) StepKind.LONG_CLICK else StepKind.CLICK, pkg,
            appName = e.appName, screenTitle = e.screenTitle, label = label, viewId = e.viewId, role = e.role,
            hint = e.hint?.takeIf { e.editable && it.isNotBlank() })
        return add(Draft(step.copy(sensitive = StepWords.isSensitive(step)), e.timeMs))
    }

    private fun sameField(a: TeachStep, e: RawEvent, pkg: String): Boolean =
        a.kind == StepKind.TYPE && a.pkg == pkg &&
            (if (!a.viewId.isNullOrBlank() || !e.viewId.isNullOrBlank()) a.viewId == e.viewId else a.hint == e.hint?.takeIf { it.isNotBlank() })

    private fun typed(e: RawEvent, pkg: String): Result {
        val text = e.text.orEmpty()
        val last = drafts.lastOrNull()
        // Every keystroke is an event: they all go into the one step for that box.
        if (last != null && sameField(last.step, e, pkg)) {
            if (text.isEmpty()) { drafts.removeAt(drafts.lastIndex); return Result.Ignored }
            last.typed = text
            last.step = last.step.copy(textLength = text.length)
            return Result.Recorded(drafts.lastIndex)
        }
        if (text.isEmpty()) return Result.Ignored
        ensureApp(e, pkg)
        // Clicking into the box and then typing in it is one step: typing finds the box itself.
        val prev = drafts.lastOrNull()
        if (prev != null && prev.step.kind == StepKind.CLICK && prev.step.pkg == pkg &&
            ((!prev.step.viewId.isNullOrBlank() && prev.step.viewId == e.viewId) || (prev.step.hint != null && prev.step.hint == e.hint))) drafts.removeAt(drafts.lastIndex)
        // The box's name is its hint, never its contents (a text box's text IS its label to the tree).
        val name = (e.hint ?: e.label)?.trim()?.takeIf { it.isNotEmpty() && it != text.trim() }
        val step = TeachStep(StepKind.TYPE, pkg, appName = e.appName, screenTitle = e.screenTitle, label = name, viewId = e.viewId,
            role = e.role, hint = e.hint?.takeIf { it.isNotBlank() && it != text.trim() }, textLength = text.length)
        val r = add(Draft(step.copy(sensitive = StepWords.isSensitive(step)), e.timeMs, typed = text))
        return r
    }

    private fun scrolled(e: RawEvent, pkg: String): Result {
        val dir = when {
            kotlin.math.abs(e.scrollDy) >= kotlin.math.abs(e.scrollDx) && e.scrollDy != 0 -> if (e.scrollDy > 0) "down" else "up"
            e.scrollDx != 0 -> if (e.scrollDx > 0) "right" else "left"
            else -> return Result.Ignored
        }
        val last = drafts.lastOrNull()
        if (last != null && last.step.kind == StepKind.SCROLL && last.step.pkg == pkg && last.step.direction == dir) return Result.Recorded(drafts.lastIndex)
        // Scrolling before anything else was done in this app is just looking around.
        if (last == null || last.step.pkg != pkg) return Result.Ignored
        return add(Draft(TeachStep(StepKind.SCROLL, pkg, appName = e.appName, screenTitle = e.screenTitle, direction = dir, optional = true), e.timeMs))
    }
}
