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
        // Twi: na (and/then), ne (and/with), ma (to/for)
        "na", "ne", "ma"
    )
    private val DEFAULT_HOLD = listOf(
        "wait", "hold on", "hold up", "one moment", "just a moment", "a moment", "one second", "just a second",
        "give me a second", "give me a moment", "give me a minute", "let me think", "twɛn", "twen", "twɛn kakra"
    )

    private fun norm(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}ɛɔ'\\s,]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun looksUnfinished(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        if (t.endsWith(",")) return true
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
        reason = first.reason + "+continued"
    )
}
