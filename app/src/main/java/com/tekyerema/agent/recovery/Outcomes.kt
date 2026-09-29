package com.tekyerema.agent.recovery

import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.Progress
import com.tekyerema.verifier.Expectation

/**
 * Workstream B — what KIND of outcome one executed step had, so each kind gets its own fix
 * (docs/agent/research/agenticness_mobile_agents.md §2, §4, §13). Today's verdicts say verified / partial / no change /
 * unverified / failed; "unverified" lumped together a wrong app, an unmet claim and a popup that covered the screen
 * (A20e: a carrier "your SMS cost…" dialog covered the camera). The classes and the fix each one gets:
 *
 *  - [OK] / [PARTIAL] / [OBSERVED] — no recovery (verified; moved on to new content; a read).
 *  - [WRONG_PAGE]    an unexpected app came to the front → the engine backtracks to the last verified screen.
 *  - [DIALOG]        a dialog/popup the step didn't claim appeared → the engine closes it with its safe dismiss button
 *                    (through the policy gate), never by following text inside it.
 *  - [CLAIM_NOT_MET] the screen changed but the claim isn't met → ladder: closest matches, vision, back, another route.
 *  - [NOT_FOUND]     the named target isn't on the screen → ladder: closest matches, scroll-to-find, OCR, vision locate…
 *  - [NO_EFFECT]     the action ran and nothing changed → ladder: another grounding (OCR, vision locate), back…
 *  - [BLOCKED]       the environment refused (permission, not installed, dead end) → another route, or ask the user.
 *  - [FAILED]        the tool failed for another reason → another route, back, ask.
 *
 * Pure: classified from the step's verdict, the tool result and the before/after snapshots (the after one taken once
 * the screen settled — the engine re-observes before acting on WRONG_PAGE or DIALOG).
 */
enum class OutcomeClass(val failure: Boolean) {
    OK(false), PARTIAL(false), OBSERVED(false),
    WRONG_PAGE(true), DIALOG(true), CLAIM_NOT_MET(true), NOT_FOUND(true), NO_EFFECT(true), BLOCKED(true), FAILED(true)
}

object OutcomeClassifier {
    data class Step(
        val tool: String,
        val verdict: Progress.Verdict,
        val success: Boolean,
        /** the tool said the failure is recoverable (another route may work) */
        val recoverable: Boolean = true,
        /** only the user can settle it (an app that isn't installed, two close matches) */
        val needsUser: Boolean = false,
        val message: String = "",
        /** the call named a target (label / id / point) that wasn't resolved on the screen the decision saw */
        val targetMissing: Boolean = false,
        val expectation: Expectation? = null,
        val before: UiSnapshot? = null,
        val after: UiSnapshot? = null,
        /** the home screen's package, when known */
        val launcherPkg: String? = null
    )

    /** Tools whose purpose is to bring an app (or the home screen) to the front — an app switch is what they're for. */
    private val SWITCHERS = setOf("open_app", "launch_app", "open_url", "open_settings", "install_app", "ask_app", "take_photo",
        "make_call", "google_sign_in", "web_search", "share", "send_email", "compose_email")

    private val NOT_FOUND_MSG = Regex("(?i)couldn'?t find|could not find|no element labell?ed|not in the current screen listing|" +
        "no clear match|isn'?t here|do not fall inside any vision element|could not resolve target|no target:")

    fun classify(s: Step): OutcomeClass {
        if (!s.success) return when {
            s.targetMissing || NOT_FOUND_MSG.containsMatchIn(s.message) -> OutcomeClass.NOT_FOUND
            s.needsUser || !s.recoverable -> OutcomeClass.BLOCKED
            else -> OutcomeClass.FAILED
        }
        return when (s.verdict) {
            Progress.Verdict.VERIFIED -> OutcomeClass.OK
            Progress.Verdict.OBSERVED -> OutcomeClass.OBSERVED
            Progress.Verdict.FAILED -> OutcomeClass.FAILED
            Progress.Verdict.NO_CHANGE -> OutcomeClass.NO_EFFECT
            // Changed but not verified: a popup first (in-app dialogs share the app's package and can look like "new
            // content"), then an unexpected app, then exploration, else the claim simply isn't met.
            Progress.Verdict.PARTIAL, Progress.Verdict.UNVERIFIED -> when {
                DialogDetector.appeared(s.before, s.after, s.expectation) != null -> OutcomeClass.DIALOG
                unexpectedApp(s) -> OutcomeClass.WRONG_PAGE
                s.verdict == Progress.Verdict.PARTIAL -> OutcomeClass.PARTIAL
                else -> OutcomeClass.CLAIM_NOT_MET
            }
        }
    }

    /**
     * The front app changed and the step wasn't meant to change it: not an app-switching tool, and the claim didn't name
     * the new app. The home screen counts (a stray Back or Home that left the task's app).
     */
    internal fun unexpectedApp(s: Step): Boolean {
        val from = s.before?.appPackage ?: return false
        val to = s.after?.appPackage ?: return false
        if (from == to || s.tool in SWITCHERS) return false
        val e = s.expectation
        if (e is Expectation.ForegroundIs && (to.contains(e.token, true) || s.after.appName?.contains(e.token, true) == true ||
                s.after.screenTitle?.contains(e.token, true) == true)) return false
        // The keyboard or the system UI (quick settings shade, a notification) isn't a different app of the task.
        if (to == "com.android.systemui" || s.after.windowType == "keyboard") return false
        // key_event home/recents: going to the launcher was the point.
        if (s.tool == "key_event" && to == s.launcherPkg) return false
        return true
    }
}

/**
 * A dialog or popup that appeared after a step and wasn't what the step claimed: a small window of buttons like
 * OK / Cancel / Not now, or a system window. Pure, structural — never reads the dialog's text as an instruction.
 */
object DialogDetector {
    data class Dialog(
        /** its first text line (for the log and the model's note — data, not instructions) */
        val text: String?,
        val buttons: List<UiElement>,
        /** the button that closes it without doing anything, or null when there's no safe one */
        val dismiss: UiElement?,
        /** a permission prompt (Allow / Deny) — the task decides, never auto-dismissed */
        val permission: Boolean,
        /** a stable key so the same dialog isn't auto-dismissed twice */
        val key: String
    )

    private fun norm(s: String?) = s?.lowercase()?.replace(Regex("[\\s ]+"), " ")?.trim()?.trimEnd('.', '!').orEmpty()

    /** Words of a button that closes a popup without acting — in order of preference. */
    private val DISMISS = listOf("close", "dismiss", "not now", "no thanks", "no, thanks", "maybe later", "later", "remind me later",
        "skip", "got it", "ok", "okay", "cancel")
    /** Buttons that only appear on popups. */
    private val POPUP_BUTTONS = DISMISS.toSet() + setOf("allow", "deny", "don't allow", "dont allow", "while using the app",
        "only this time", "never", "accept", "decline", "continue", "yes", "no", "retry", "try again", "report", "wait", "close app")
    private val PERMISSION = setOf("allow", "deny", "don't allow", "dont allow", "while using the app", "only this time")
    /** Never picked as a "dismiss": they act. */
    private val ACTING = Regex("(?i)\\b(?:send|pay|buy|delete|remove|erase|confirm|accept|agree|allow|install|update|call|transfer|" +
        "subscribe|sign in|log in|continue|yes|proceed|submit|approve|report)\\b")

    /**
     * The dialog [after] shows that [before] didn't, or null. Only when the step's own claim isn't about it (a claim
     * that names one of its words means the step MEANT to open it).
     */
    fun appeared(before: UiSnapshot?, after: UiSnapshot?, claim: Expectation? = null): Dialog? {
        if (after == null) return null
        val tree = after.elements.filter { it.source == ElementSource.TREE }
        val buttons = tree.filter { (it.clickable || it.role.endsWith("Button")) && norm(it.label) in POPUP_BUTTONS }
        if (buttons.isEmpty()) return null
        val beforeLabels = before?.elements?.mapNotNull { norm(it.label).ifEmpty { null } }?.toSet().orEmpty()
        val newButtons = buttons.filter { norm(it.label) !in beforeLabels }
        if (newButtons.isEmpty()) return null
        // A dialog window is small: the snapshot is built from the active window, which becomes the dialog.
        val interactive = tree.count { it.interactive }
        val systemWindow = after.windowType == "system" && before?.windowType != "system"
        val otherApp = before?.appPackage != null && after.appPackage != null && before.appPackage != after.appPackage
        if (!systemWindow && interactive > 10) return null
        if (!systemWindow && !otherApp && tree.size > 16) return null
        // An input prompt ("Name this playlist") is a form, not an interruption.
        if (tree.any { it.editable }) return null
        val text = tree.firstOrNull { !it.clickable && !it.label.isNullOrBlank() && norm(it.label) !in POPUP_BUTTONS }?.label
        val claimWords = claimLabel(claim)?.let { norm(it) }
        if (claimWords != null && claimWords.isNotEmpty() &&
            (tree.any { norm(it.label).contains(claimWords) } || (text != null && norm(text).contains(claimWords)))) return null
        val permission = buttons.any { norm(it.label) in PERMISSION }
        val dismiss = if (permission) null else pickDismiss(buttons)
        val key = (after.appPackage ?: "?") + "|" + norm(text).take(60) + "|" + buttons.joinToString(",") { norm(it.label) }
        return Dialog(text?.take(120), buttons, dismiss, permission, key)
    }

    /** The safe way out: a single OK on an info popup, else the most "close"-like button; never an acting word. */
    internal fun pickDismiss(buttons: List<UiElement>): UiElement? {
        val safe = buttons.filter { b -> !ACTING.containsMatchIn(b.label.orEmpty()) }
        for (w in DISMISS) {
            val hit = safe.firstOrNull { norm(it.label) == w } ?: continue
            // "OK"/"Okay" also confirm things ("Delete this? Cancel / OK"): only when it's the popup's only button.
            if ((w == "ok" || w == "okay") && buttons.size > 1) continue
            return hit
        }
        return null
    }

    private fun claimLabel(e: Expectation?): String? = when (e) {
        is Expectation.ElementPresent -> e.label
        is Expectation.NewElement -> e.label
        is Expectation.Toggled -> e.label
        else -> null
    }
}
