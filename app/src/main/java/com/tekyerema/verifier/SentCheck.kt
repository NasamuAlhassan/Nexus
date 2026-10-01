package com.tekyerema.verifier

import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.tools.SendEvidence

/**
 * Was a message typed into ANY chat app actually sent? One rule for the general operator (a `type` with submit, a tap on
 * the app's Send button), the same one the send accelerators use ([SendEvidence]):
 *
 *  - SENT only when the text box no longer holds the words AND a new, non-editable element (the bubble) shows them;
 *  - NOT SENT when the words are still in a text box — whatever else is on screen;
 *  - otherwise UNKNOWN: nothing is claimed either way.
 *
 * A20e 2026-10-01 (Telegram): `type(submit=true)` left "hello from the Tekyerema audit" in the box, the claim
 * `element_present: hello…` matched the BOX, the task was recorded as sent, the real Send tap was refused as a repeat,
 * and the user heard "I sent the Telegram message". Pure → SentCheckTest.
 */
object SentCheck {
    enum class State { SENT, STILL_IN_BOX, UNKNOWN }

    fun state(before: UiSnapshot?, after: UiSnapshot?, text: String): State = when (val seen = SendEvidence.check(before, after, text)) {
        is SendEvidence.Seen.Shown -> if (seen.status == SendEvidence.Status.FAILED) State.UNKNOWN else State.SENT
        SendEvidence.Seen.StillInBox -> State.STILL_IN_BOX
        SendEvidence.Seen.NotSeen -> State.UNKNOWN
    }

    const val STILL_IN_BOX_DETAIL = "the words are still in the text box and nowhere else — NOT sent. Press the app's own " +
        "Send button (the arrow next to the box), then check the message shows in the chat"

    /**
     * The tool-side verdict for a step that should have sent [text]: an observed outcome either way (authoritative), or
     * null when the screen doesn't say.
     */
    fun verdict(before: UiSnapshot?, after: UiSnapshot?, text: String): VerificationResult? = when (state(before, after, text)) {
        State.SENT -> VerificationResult(true, "the message left the text box and now shows in the chat", authoritative = true)
        State.STILL_IN_BOX -> VerificationResult(false, STILL_IN_BOX_DETAIL, stillInField = true, authoritative = true)
        State.UNKNOWN -> null
    }
}
