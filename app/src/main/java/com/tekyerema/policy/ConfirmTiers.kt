package com.tekyerema.policy

/**
 * Risk-tiered confirmation (research for the owner, 15 Oct 2026 — "Keep it Short", CHI 2022; action guards that pause only
 * on risky actions): money and texts keep their full spoken read-back and a yes; a CALL to a saved contact — reversible
 * (hang up), and asked for by name a moment ago — is announced instead of asked: "Calling Kofi Demo, ending 1 1 1 1. Say
 * stop to cancel." and placed when the short window passes without a no. Any warning (a repeat call, a recipient the
 * recogniser guessed, a request from a paired computer) keeps the question. Pure (unit-tested).
 */
object ConfirmTiers {
    private val CALL_Q = Regex("^((?:Taking '[^']+' as [^.]+\\.\\s*)?)Call (.+?)\\?\\s*$")

    /** "[Taking 'him' as Kofi Demo, from before.] Call Kofi Demo, ending 1 1 1 1?" → "Calling Kofi Demo, ending 1 1 1 1. Say stop
     *  to cancel."; null if not that shape. */
    fun callingLine(prompt: String): String? {
        val m = CALL_Q.matchEntire(prompt.trim()) ?: return null
        // ("Taking 'him' as Kofi Demo, from before." is left out: the name is said straight away.)
        return "Calling ${m.groupValues[2]}. Say stop to cancel."
    }

    /** The cancel window: the line's own reading time (about 65 ms a character at the default rate) plus 2.2 s to object. */
    fun windowMs(line: String): Long = (line.length * 65L + 2_200L).coerceIn(3_500L, 9_000L)
}
