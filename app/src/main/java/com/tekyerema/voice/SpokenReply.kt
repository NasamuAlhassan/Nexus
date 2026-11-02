package com.tekyerema.voice

/**
 * Shapes what a planner WROTE (an ask_user question, a done summary) into what a listener can follow, before
 * [Verbalize] turns numbers, money and dates into words (2 Nov 2026, for the on-device planner; MASTER_STATUS #20).
 *
 * WHY a separate step: a small model's text is usually plain (agent/llm/ToolReply strips the JSON shell), but leftovers
 * get through — a `{"question": …}` shell, a code fence, "Step 1:", a privacy tag, an opener like "Sure!", one 40-word
 * sentence. Verbalize reads any text a screen shows; this only cleans planner prose, and never drops content: a message
 * being read out stays whole. Rules, in order:
 *  1. a JSON shell or code fence → its text (`question`, `message`, `summary`, `text`, `answer`, `reply`, `content`);
 *  2. step and list numbering at a line start ("Step 1:", "1.", "2)") is dropped — numbers of steps are never spoken;
 *  3. a privacy-gate tag left in ([NUM1], [EMAIL2], [ID1]) becomes "that number" / "that email";
 *  4. a filler opener ("Sure!", "Certainly,", "Of course.") before more text is dropped, "successfully" goes;
 *  5. lines become sentences; ";" ends a sentence; a sentence over [LONG] words is split at the comma nearest its middle.
 * Pure Kotlin, unit-tested (SpokenReplyTest).
 */
object SpokenReply {
    private const val LONG = 24
    private val FENCE = Regex("```[a-zA-Z]*\\s*|```")
    private val TEXT_FIELD = Regex("\"(?:question|message|summary|text|answer|reply|content|say)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val NUMBERING = Regex("(?im)^\\s*(?:step\\s+(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\s*[:.)-]\\s*|\\d{1,2}[.)]\\s+(?=\\p{L}))")
    private val TAG_NUM = Regex("\\[(?:NUM|ID)\\d+]")
    private val TAG_EMAIL = Regex("\\[EMAIL\\d+]")
    private val OPENER = Regex("^(?:sure|certainly|of course|okay|ok|alright|absolutely|great)\\s*[!,.]\\s+(?=\\S)", RegexOption.IGNORE_CASE)
    private val SUCCESSFULLY = Regex("\\s+successfully(?=\\s)", RegexOption.IGNORE_CASE)

    fun shape(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var t = text.trim()
        // 1. JSON shell / code fence.
        if (t.contains("```")) t = FENCE.replace(t, "").trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            TEXT_FIELD.find(t)?.let { m -> t = unescape(m.groupValues[1]).trim() }
        }
        // 2–4.
        t = NUMBERING.replace(t, "")
        t = TAG_EMAIL.replace(TAG_NUM.replace(t, "that number"), "that email")
        t = OPENER.replace(t, "")
        t = SUCCESSFULLY.replace(t, "")
        // 5. One sentence per line or semicolon; long ones split.
        val sentences = t.split(Regex("\\s*\\n+\\s*|;\\s+|(?<=[.?!])\\s+(?=[\\p{Lu}\\d\"“])"))
            .map { it.trim().trimEnd(';', ',') }
            .filter { it.isNotEmpty() }
            .flatMap { split(it) }
            .map { s -> s.replaceFirstChar { it.uppercaseChar() }.let { if (it.last() in ".?!:…\"”)") it else "$it." } }
        return sentences.joinToString(" ")
    }

    /** A sentence over [LONG] words → two, at the ", " nearest the middle; none → as it is. Recurses on each half. */
    private fun split(s: String): List<String> {
        val words = s.split(' ').size
        if (words <= LONG) return listOf(s)
        val mid = s.length / 2
        val cut = Regex(", (?!(?:and|or|but) \\S+$)").findAll(s).map { it.range.first }
            .filter { s.substring(0, it).split(' ').size >= 6 && s.substring(it + 2).split(' ').size >= 6 }
            .minByOrNull { kotlin.math.abs(it - mid) } ?: return listOf(s)
        val first = s.substring(0, cut).trim()
        val rest = s.substring(cut + 2).trim().removePrefix("and ").removePrefix("but ").trim()
        return split(first) + split(rest)
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
}
