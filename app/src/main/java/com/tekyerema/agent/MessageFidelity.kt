package com.tekyerema.agent

import com.tekyerema.voice.understand.MessageBody

/**
 * The words of a message are the USER's (S-05, handover 7 October 2026: "nothing checks that the message text the planner
 * writes is faithful to the user's words"). When the request dictates the message — "text Ama saying I'm on my way",
 * "tell Kofi that he should call me", "message Prince: meeting moved to 3" — the text that goes out must be those words:
 *
 *  - a planner `send_sms` / `reply_to_notification` whose text says something else is sent with the dictated words instead
 *    (the planner's capitalisation or punctuation of the same words is left alone — [sameWords]);
 *  - a Send tap in a chat whose composer holds different words is read back with both, so the user decides.
 *
 * A request that only ASKS for a message ("tell Ama to call me", "let Kofi know I'm late" without a marker) dictates
 * nothing here; the planner composes, and the read-back still quotes the text. Pure, Android-free, unit-tested.
 */
object MessageFidelity {
    /** The dictated words [raw], and [adapted] for a tell-form ("tell Kofi that he should call me" → "you should call me"). */
    data class Dictated(val raw: String, val adapted: String) {
        /** What goes out when the planner's text is not the user's words. */
        val preferred: String get() = adapted
        fun matches(text: String?): Boolean = text != null && (sameWords(text, raw) || sameWords(text, adapted))
    }

    private val LEADING_MARKER = Regex("(?i)^(?:saying|that\\s+says|which\\s+says|and\\s+say|(?:and\\s+)?tell(?:ing)?\\s+(?:him|her|them)(?:\\s+that)?|" +
        "telling\\s+(?:him|her|them)|with\\s+the\\s+message|the\\s+message\\s+is|message\\s+is|that)\\b\\s*|^[:\"“]\\s*")
    private val TELL_FORM = Regex("(?i)\\btell(?:ing)?\\s+(?:him|her|them)\\b|^\\s*(?:please\\s+|kindly\\s+)?tell\\s")
    private val TRAILING_QUOTE = Regex("[\"”']+$")

    /** The words the request dictates for the message, or null when it dictates none. */
    fun dictated(goal: String): Dictated? {
        val g = goal.trim()
        if (!MessageBody.isMessaging(g)) return null
        val (head, body) = MessageBody.split(g)
        if (body.isBlank()) return null
        val words = LEADING_MARKER.replace(body.trim(), "").trim().replace(TRAILING_QUOTE, "").trim().trimEnd('.', '!')
            .trim().takeIf { it.isNotEmpty() } ?: return null
        val tellForm = TELL_FORM.containsMatchIn(head) || TELL_FORM.containsMatchIn(body.substring(0, body.length - words.length))
        return Dictated(words, if (tellForm) LocalBrain.adaptForRecipient(words) else words)
    }

    /** The same words, ignoring case, punctuation, quotes and spacing. */
    fun sameWords(a: String, b: String): Boolean = norm(a) == norm(b) && norm(a).isNotEmpty()
    private fun norm(s: String) = s.lowercase().replace(Regex("[’']"), "'").replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** The text a planner's messaging tool call should carry: the dictated words when its own differ, else unchanged. */
    fun enforced(toolName: String, text: String?, goal: String): String? {
        if (toolName !in TEXT_TOOLS) return text
        val d = dictated(goal) ?: return text
        return if (d.matches(text)) text else d.preferred
    }

    /** The disclosure for a Send tap whose composer holds other words than the user dictated; null when they agree. */
    fun composerNotice(composer: String?, goal: String): String? {
        val d = dictated(goal) ?: return null
        if (composer.isNullOrBlank() || d.matches(composer)) return null
        return "The message in the box says “${composer.trim().take(120)}” — you said “${d.preferred.take(120)}”."
    }

    val TEXT_TOOLS = setOf("send_sms", "reply_to_notification")
}
