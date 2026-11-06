package com.tekyerema.agent

/**
 * A request that dictates a message carries two kinds of words: the instruction ("text Prince:", "tell Ama that") and the
 * message itself. Only the instruction may steer the ROUTE. A20e, 6 Nov 2026: "text Prince: … on WhatsApp, send this phone a
 * message … voice note on Telegram" was routed by the words in the body — WhatsApp opened and the brain tapped towards a call.
 * Pure → DictatedBodyTest.
 */
object DictatedBody {
    private val LEAD = Regex("(?i)^\\s*(?:please |can you |could you |kindly )?(?:text|sms|message|whatsapp|tell|send|reply(?: to)?|write(?: to)?|dm)\\b")
    /** Where the dictation starts: a colon, or "saying" / "that says" / "say" / "that" after the recipient. */
    private val CUT = Regex("(?i)\\s*(?::|\\bsaying\\b|\\bthat says\\b|\\bto say\\b|\\bsay\\b|\\bthat\\b)")

    /** The instruction part of a dictating request ("text Prince"); the whole request when it dictates nothing. */
    fun head(goal: String): String {
        if (!LEAD.containsMatchIn(goal)) return goal
        val m = CUT.find(goal, LEAD.find(goal)!!.range.last + 1) ?: return goal
        val head = goal.substring(0, m.range.first).trim()
        return if (head.length >= 5) head else goal
    }
}
