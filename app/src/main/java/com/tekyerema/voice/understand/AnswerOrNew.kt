package com.tekyerema.voice.understand

/**
 * I-26 (rehearsal, 15 Oct 2026): while a task waits on a question ("How much do you want to send?"), the next thing said
 * was always taken as its answer — "now call him" was read as an amount, asked again, and the task ended with "I didn't
 * get that". A reply that is plainly a NEW request of another kind — it starts with a command for something else than the
 * waiting task does — drops the question and runs as a request of its own. Everything else is still the answer: a name,
 * an amount, "send it to Kofi" for a send, "call Kofi Demo" for a call. Confirmations (yes / no) never come here. Pure.
 */
object AnswerOrNew {
    enum class Kind { CALL, MESSAGE, MONEY, READ, OPEN }

    private const val LEAD = "^(?:(?:ok(?:ay)?|now|and|then|so|please|also|no,?|actually,?|wait,?)\\s+)*"
    // First match wins: "send him a message" is a message, "send him the money" a payment.
    private val KINDS = listOf(
        Kind.CALL to Regex("$LEAD(?:call|ring|phone|dial)\\b", RegexOption.IGNORE_CASE),
        Kind.MESSAGE to Regex("$LEAD(?:(?:text|message|tell|sms|whatsapp|reply)\\b|send (?:\\S+ )?(?:an? )?(?:text|message|sms|whatsapp|note)\\b)",
            RegexOption.IGNORE_CASE),
        Kind.MONEY to Regex("$LEAD(?:send\\b[^.?!]*?\\b(?:money|cedis?|ghs|momo|mobile money)\\b|send \\d|pay\\b|transfer\\b|buy airtime\\b|momo\\b)",
            RegexOption.IGNORE_CASE),
        Kind.READ to Regex("$LEAD(?:(?:can|could) you )?(?:read|check)\\b", RegexOption.IGNORE_CASE),
        Kind.OPEN to Regex("$LEAD(?:open|launch|go to)\\b", RegexOption.IGNORE_CASE),
    )

    /** The kind of request [text] starts with, or null when it isn't a command. */
    fun kindOf(text: String): Kind? = KINDS.firstOrNull { (_, re) -> re.containsMatchIn(text.trim()) }?.first

    /** [reply] to the question of a task whose request was [goal] is a new request of another kind. */
    fun isNewRequest(reply: String, goal: String?): Boolean {
        val words = reply.trim().split(Regex("\\s+"))
        if (words.size < 2) return false                       // "Kofi", "five", "transport": answers
        val said = kindOf(reply) ?: return false
        val task = goal?.let { kindOf(it) } ?: return false     // no idea what the task is: keep it an answer
        return said != task
    }
}
