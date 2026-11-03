package com.tekyerema.voice

import com.tekyerema.core.config.AppConfig

/**
 * B-02 / B-09 — lexical end-of-turn checks on the transcript (the on-device recognizer is batch, so the words are
 * only known after the silence ended the capture):
 *  - [looksUnfinished]: the request stops mid-phrase ("call my", "send a message to") — a hesitation, not the end.
 *    The controller then listens a little longer and joins the two parts instead of acting on half a request.
 *  - [isHold]: the whole utterance is "wait" / "hold on" — the user needs a moment, so listen patiently.
 * Word lists are data (`speech.turn.trailingWords`, `speech.turn.holdPhrases`) with built-in defaults.
 */
object TurnDetector {
    // Only words that (almost) never END a complete request. Verbs, pronouns and particles are excluded on purpose:
    // "call her", "turn the torch on", "read my message", "tap send", "who is that" are all complete.
    private val DEFAULT_TRAILING = listOf(
        "and", "or", "but", "to", "the", "a", "an", "my", "your", "their", "for", "with", "of", "from", "if",
        "because", "is", "are", "was", "um", "uh", "er",
        // speech repair #5 (research note 3 Nov 2026 §4): more words a sentence can't end on. Not "so"/"then" ("I think so",
        // "okay then"), "on"/"in" ("turn it on", "log in") or "call" ("I missed a call"): those end real requests.
        "at", "about", "into", "than", "saying", "tell", "ask", "whether", "when", "where", "which",
        // Twi: na (and/then), ne (and/with), ma (to/for)
        "na", "ne", "ma"
    )
    private val DEFAULT_HOLD = listOf(
        "wait", "hold on", "hold up", "one moment", "just a moment", "a moment", "one second", "just a second",
        "give me a second", "give me a moment", "give me a minute", "let me think", "twɛn", "twen", "twɛn kakra"
    )

    private fun norm(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}ɛɔ'\\s,]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** A request that ends in the START of a Ghana phone number — "send money to 055", "call 055 323" — was a pause
     *  between digit groups, not the end (a judge's session, A20e 2026-09-29: the number arrived in pieces). */
    private val RE_PARTIAL_NUMBER = Regex("(?:^|\\s)((?:\\+?233|0)[\\d\\s-]*)$")

    fun looksUnfinished(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        if (t.endsWith(",")) return true
        RE_PARTIAL_NUMBER.find(t.trimEnd(' '))?.let { m ->
            val d = m.groupValues[1].filter { it.isDigit() }.let { if (it.startsWith("233")) "0" + it.drop(3) else it }
            if (t.contains(' ') && d.length in 1..9) return true
        }
        val words = AppConfig.stringList("speech.turn.trailingWords", DEFAULT_TRAILING)
        val last = t.trimEnd(',', ' ').substringAfterLast(' ')
        // A lone "call" or "open" is a (complete) one-word command to the agent; only a trailing word AFTER
        // something else is a cut-off.
        return t.contains(' ') && last in words
    }

    fun isHold(text: String): Boolean {
        val t = norm(text).trimEnd(',').removeSuffix(" please").trim()
        return t in AppConfig.stringList("speech.turn.holdPhrases", DEFAULT_HOLD)
    }

    /** Join a request with its continuation into one transcript. */
    fun join(first: Transcript, rest: Transcript): Transcript = first.copy(
        raw = (first.raw.trimEnd(',', ' ') + " " + rest.raw).trim(),
        canonical = (first.canonical.trimEnd(',', ' ') + " " + rest.canonical).trim(),
        asrMs = first.asrMs + rest.asrMs,
        reason = first.reason + "+continued",
        asSaid = if (first.asSaid.isBlank() && rest.asSaid.isBlank()) "" else
            (first.asSaid.ifBlank { first.canonical }.trimEnd(',', ' ') + " " + rest.asSaid.ifBlank { rest.canonical }).trim()
    )
}
