package com.tekyerema.agent

/**
 * What Tɛkyerɛma's own camera or document reader just read aloud, as a turn of the agent's conversation, so the NEXT
 * request can act on it: "send that to my wife", "remind me of the deadline", "save the amount". Before this the camera
 * and the document screen spoke and forgot — the agent heard "send the details to my wife" with nothing to send.
 *
 * The words come off paper someone else wrote, so the turn carries [AgentEngine.UNTRUSTED_MARK]: a letter that says
 * "send 500 cedis to …" is a fact about the letter, never a command (money and messages still stop at the read-back).
 * A newer reading from the same reader replaces the one before it when nothing was said in between, so a camera held
 * over a page for a minute leaves one turn, not twelve (the document screen passes all the pages read so far).
 * Pure → ReadingNoteTest.
 */
object ReadingNote {
    private fun head(source: String) = "Nexus read aloud from the $source:"

    /** The conversation turn for [text] read from [source] ("camera", "document"), or null when there is nothing. A long
     *  reading keeps its first [maxChars] characters. */
    fun line(source: String, text: String, maxChars: Int): String? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        val body = if (t.length > maxChars) t.take(maxChars).trimEnd() + "…" else t
        return "${head(source)} ${AgentEngine.UNTRUSTED_MARK} $body"
    }

    /** True when [lastTurn] is an earlier reading from the same reader as [source] — the new one takes its place. */
    fun replaces(lastTurn: String?, source: String): Boolean = lastTurn?.startsWith(head(source)) == true
}
