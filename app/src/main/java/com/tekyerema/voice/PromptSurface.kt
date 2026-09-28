package com.tekyerema.voice

import com.tekyerema.agent.AgentStatus

/**
 * What the reply panel shows for the agent's state. Owner (A20e, 2026-09-28): "when it needs feedback, sometimes the
 * thing doesn't appear for you to talk back". The panel used to be shown and hidden from several places (the engine
 * for confirmations, the voice layer for questions, nothing at all for the secure step) — now it simply follows the
 * agent's state, with buttons that fit the prompt (a question never offers Yes/No). Pure, unit-tested.
 */
object PromptSurface {
    enum class Kind { CONFIRM, QUESTION, SECURE }

    /** [seq] = which confirmation a CONFIRM panel is for (J-02): its Yes approves that one only, and a new confirmation
     *  with the same words is still a new panel. */
    data class Prompt(val kind: Kind, val text: String, val seq: Long = 0L)

    /** No text, no panel: the engine clears the prompt a moment before it leaves the waiting status (the reply was
     *  given), and a panel re-shown in that gap would sit over the app while the approved action runs. */
    fun of(status: AgentStatus, confirmation: String?, question: String?, seq: Long = 0L): Prompt? = when (status) {
        AgentStatus.WAITING_CONFIRMATION -> confirmation?.takeIf { it.isNotBlank() }?.let { Prompt(Kind.CONFIRM, it, seq) }
        AgentStatus.ASKING -> question?.takeIf { it.isNotBlank() }?.let { Prompt(Kind.QUESTION, it) }
        AgentStatus.SECURE_INPUT -> Prompt(Kind.SECURE, "Secure step — enter it yourself. Tɛkyerɛma isn't looking.")
        else -> null
    }

    /** Button labels, left to right. The right-hand one is the main action. */
    fun buttons(kind: Kind): List<String> = when (kind) {
        Kind.CONFIRM -> listOf("No", "Yes, do it")
        Kind.QUESTION -> listOf("Stop", "Answer")
        Kind.SECURE -> listOf("Stop", "Done")
    }

    fun header(kind: Kind): String = when (kind) {
        Kind.CONFIRM -> "NEEDS YOUR OK"
        Kind.QUESTION -> "TƐKYERƐMA IS ASKING"
        Kind.SECURE -> "SECURE STEP"
    }
}
