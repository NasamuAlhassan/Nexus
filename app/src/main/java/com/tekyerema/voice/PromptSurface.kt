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
    fun of(status: AgentStatus, confirmation: String?, question: String?, seq: Long = 0L, twi: Boolean = false): Prompt? = when (status) {
        AgentStatus.WAITING_CONFIRMATION -> confirmation?.takeIf { it.isNotBlank() }?.let { Prompt(Kind.CONFIRM, it, seq) }
        AgentStatus.ASKING -> question?.takeIf { it.isNotBlank() }?.let { Prompt(Kind.QUESTION, it) }
        AgentStatus.SECURE_INPUT -> Prompt(Kind.SECURE, secureText(twi))
        else -> null
    }

    /**
     * The confirmation or question as the panel SHOWS it: ONE language, the user's (owner, 5 October 2026: "it's supposed to
     * be only one language based on what I chose in my setting") — the Twi line in a Twi conversation, else the English.
     * (It used to show the Twi with the English underneath.) The Twi line only exists in a Twi conversation (AgentEngine).
     */
    fun confirmationText(english: String?, twi: String?): String? =
        twi?.takeIf { it.isNotBlank() }?.let { com.tekyerema.language.EntityTags.display(it) } ?: english

    /** A question or a confirmation can be answered by keyboard on the panel itself; the secure step never offers a
     *  text box (it could be taken for where the PIN goes). */
    fun canType(kind: Kind): Boolean = kind != Kind.SECURE

    /** Button labels, left to right. The right-hand one is the main action. In Twi in a Twi conversation (one language,
     *  owner 5 October 2026). [owner: verify the Twi] */
    fun buttons(kind: Kind, twi: Boolean = false): List<String> = if (twi) when (kind) {
        Kind.CONFIRM -> listOf("Daabi", "Aane, yɛ")
        Kind.QUESTION -> listOf("Gyae", "Bua")
        Kind.SECURE -> listOf("Gyae", "Mawie")
    } else when (kind) {
        Kind.CONFIRM -> listOf("No", "Yes, do it")
        Kind.QUESTION -> listOf("Stop", "Answer")
        Kind.SECURE -> listOf("Stop", "Done")
    }

    fun header(kind: Kind, twi: Boolean = false): String = if (twi) when (kind) {
        Kind.CONFIRM -> "MA ME KWAN?"
        Kind.QUESTION -> "TƐKYERƐMA REBISA"
        Kind.SECURE -> "AHOTOSO ADEYƐ"
    } else when (kind) {
        Kind.CONFIRM -> "NEEDS YOUR OK"
        Kind.QUESTION -> "TƐKYERƐMA IS ASKING"
        Kind.SECURE -> "SECURE STEP"
    }

    /** The secure step's panel line. */
    fun secureText(twi: Boolean): String = if (twi) "Ahotoso adeyɛ — wo ankasa hyɛ mu. Merenhwɛ screen no."
        else "Secure step — enter it yourself. Nexus isn't looking."
}
