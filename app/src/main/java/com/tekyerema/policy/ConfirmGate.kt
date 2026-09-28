package com.tekyerema.policy

import com.tekyerema.core.config.AppConfig

/**
 * J-02 — only a FRESH, explicit yes to the confirmation that is pending RIGHT NOW approves anything (pure, unit-tested).
 *
 * Each confirmation the engine raises gets a number ([com.tekyerema.agent.AgentUiState.confirmationSeq]). Every surface
 * that can say yes — the reply panel over other apps, the Home card, the voice listener — says which confirmation it
 * was showing or asking about. A yes given to an EARLIER confirmation (a voice job still listening from the last one, a
 * panel or card that hadn't redrawn, two confirmations with the same words) is refused instead of approving one the user
 * never heard. A "no" is always honoured while something is waiting: it can only stop things.
 *
 * The reply panel's Yes also has to have been on screen for a moment ([armed]) — a tap already on its way when the panel
 * appeared (a double-tap meant for the app underneath, or an app timing a lure for exactly where Yes will appear) must
 * not approve an action (tapjacking; Android's own permission and install dialogs delay their buttons the same way).
 */
object ConfirmGate {
    /** Minimum time the reply panel is up before its Yes counts. Config may only RAISE it (to at most 3 s). */
    const val ARM_MS = 600L

    /** A reply that can never approve anything (a panel that was not drawn for a pending confirmation). */
    const val NO_CONFIRMATION = -1L

    fun armMs(): Long = AppConfig.long("policy.confirm.armMs", ARM_MS).coerceIn(ARM_MS, 3_000L)

    /**
     * @param waiting a confirmation is pending now (status WAITING_CONFIRMATION)
     * @param currentSeq the number of the pending confirmation
     * @param replySeq the confirmation the reply was given to; null = a caller that cannot know (the ADB test hook)
     */
    fun accepts(approved: Boolean, waiting: Boolean, currentSeq: Long, replySeq: Long?): Boolean = when {
        !waiting -> false
        !approved -> true
        else -> replySeq == null || replySeq == currentSeq
    }

    fun armed(shownAtMs: Long, nowMs: Long, armMs: Long = armMs()): Boolean = nowMs - shownAtMs >= armMs
}
