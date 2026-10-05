package com.tekyerema.voice.understand

/**
 * Spoken numbers as a Ghanaian says them, put into the shape the number parser ([com.tekyerema.voice.Normalizer]) reads —
 * BEFORE it runs, on words. Reusable rules, not a list of mishearings:
 *
 *  - repeats: "double five", "triple zero" → "five five", "zero zero zero" (how phone numbers are read out here);
 *  - "oh" / "o" / "nought" as zero, only inside a run of digits ("oh five five two" → "zero five five two");
 *  - "a cedi" / "one cedi" → "1 cedi"; "half a cedi" → "50 pesewas";
 *
 * and AFTER the parser, on figures:
 *  - decimals: "2 point 5" → "2.5", "1 point 5 0 cedis" → "1.50 cedis" (only digits on both sides of "point").
 *
 * Pure → SpeechRobustnessTest.
 */
object SpokenNumbers {
    private val DIGIT_WORDS = mapOf("zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9")
    /** Zero as people say it inside a number. "o" and "oh" are words too — read as zero only beside another digit. */
    private val ZERO_SAID = setOf("oh", "o", "nought", "naught", "nil")
    private val REPEAT = mapOf("double" to 2, "triple" to 3, "treble" to 3)
    private const val PUNCT = ".,;:!?"

    private fun core(t: String) = t.lowercase().trim(*PUNCT.toCharArray())
    private fun isDigitToken(c: String) = c in DIGIT_WORDS || (c.isNotEmpty() && c.all { it.isDigit() })
    private fun isDigitish(c: String) = isDigitToken(c) || c in ZERO_SAID

    /** Word-level repairs, before the number parser. */
    fun beforeParse(text: String): String {
        if (text.isBlank()) return text
        var toks = text.split(" ").filter { it.isNotEmpty() }.toMutableList()
        // 1. "double five" / "triple 0" → the digit said 2 / 3 times — only inside a number (a digit before it or just after
        //    the repeated digit), so "a double room" or "triple check" are left alone.
        val out = ArrayList<String>(toks.size + 4)
        var i = 0
        while (i < toks.size) {
            val c = core(toks[i])
            val times = REPEAT[c]
            val target = toks.getOrNull(i + 1)?.let(::core)
            if (times != null && target != null && (isDigitToken(target) || target in ZERO_SAID) && (target.length == 1 || target in DIGIT_WORDS || target in ZERO_SAID)) {
                val prevDigit = out.lastOrNull()?.let { isDigitish(core(it)) } == true
                val nextDigit = toks.getOrNull(i + 2)?.let { isDigitish(core(it)) } == true
                if (prevDigit || nextDigit) {
                    val word = if (target in ZERO_SAID) "zero" else target
                    val trail = toks[i + 1].takeLastWhile { it in PUNCT }
                    repeat(times) { k -> out.add(if (k == times - 1) word + trail else word) }
                    i += 2
                    continue
                }
            }
            out.add(toks[i]); i++
        }
        toks = out
        // 2. "oh five five" → "zero five five": a zero-word beside a digit.
        for (j in toks.indices) {
            val c = core(toks[j])
            if (c !in ZERO_SAID) continue
            val prev = toks.getOrNull(j - 1)?.let(::core)
            val next = toks.getOrNull(j + 1)?.let(::core)
            val next2 = toks.getOrNull(j + 2)?.let(::core)
            // inside a run of digits: one before it, or two after it ("Oh, five cedis?" keeps its "oh")
            if ((prev != null && isDigitToken(prev) && !toks[j - 1].endsWith(",")) ||
                (next != null && next2 != null && isDigitish(next) && isDigitish(next2) && !toks[j].endsWith(",")))
                toks[j] = toks[j].replace(Regex("(?i)\\b(?:oh|o|nought|naught|nil)\\b"), "zero")
        }
        var s = toks.joinToString(" ")
        // 3. "half a cedi" → 50 pesewas; "a cedi" / "one cedi" → 1 cedi (an amount word the parser has no number for).
        s = HALF_CEDI.replace(s) { "50 pesewas" }
        s = A_CEDI.replace(s) { m -> "1 ${m.groupValues[1].ifEmpty { "" }}cedi" }
        return s
    }

    private val HALF_CEDI = Regex("(?i)\\bhalf\\s+(?:a|of\\s+a|one)\\s+(?:ghana\\s+)?cedi\\b")
    private val A_CEDI = Regex("(?i)\\b(?:a|one)\\s+(ghana\\s+)?cedi\\b(?!s)")

    /** Figure-level repairs, after the number parser: "2 point 5" → "2.5", "1 point 5 0" → "1.50". */
    fun afterParse(text: String): String {
        var s = POINT.replace(text) { m -> m.groupValues[1] + "." + m.groupValues[2].replace(" ", "") }
        // "100 and 50 cedis": the recogniser wrote "a hundred" as a figure and "and fifty" as words, so the parser saw two
        // numbers. A round hundred/thousand, "and", a smaller number, then a money word, is ONE amount (150) — the way
        // the amount is said; anything else ("100 and 50 people", "2 and 3") is left alone.
        s = AND_AMOUNT.replace(s) { m ->
            val big = m.groupValues[1].toLong(); val small = m.groupValues[2].toLong()
            val step = if (big % 1000 == 0L) 1000L else 100L
            if (small in 1 until step) "${big + small} ${m.groupValues[3]}" else m.value
        }
        return s
    }

    private val POINT = Regex("(?<![\\d.])(\\d+)\\s+point\\s+(\\d(?:\\s\\d)*)(?![\\d.])", RegexOption.IGNORE_CASE)
    private val AND_AMOUNT = Regex("(?<![\\d.,])([1-9]\\d*00)\\s+and\\s+(\\d{1,3})\\s+((?:ghana\\s+)?(?:cedis?|pesewas?|ghs|ghc))\\b",
        RegexOption.IGNORE_CASE)
}
