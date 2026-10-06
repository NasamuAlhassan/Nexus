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

    /**
     * An amount glued into one word by the recogniser: "tempɛsos" (ten pesos — "ten" said "tem" before the p; the HCI Lab,
     * A20e 5 October 2026), "fivecedis", "10pesewas", "5GHS" → "10 pesewas", "5 cedis", "10 pesewas", "5 GHS". Only a number
     * word or figure directly joined to a money unit — never anything else. Used by the English and the Twi normaliser.
     */
    fun unglueAmounts(text: String): String {
        if (text.isBlank()) return text
        var s = GLUED_FIGURE.replace(text) { m -> m.groupValues[1] + " " + unit(m.groupValues[2]) }
        s = GLUED_WORD.replace(s) { m ->
            val n = NUMBER_WORD[m.groupValues[1].lowercase().replace("tem", "ten")] ?: return@replace m.value
            "$n ${unit(m.groupValues[2])}"
        }
        return s
    }

    private fun unit(u: String): String = u.lowercase().let { when {
        it.startsWith("p") -> "pesewas"
        it == "ghs" || it == "ghc" -> u.uppercase()
        else -> "cedis"
    } }

    private val NUMBER_WORD = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "fifteen" to "15",
        "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50", "hundred" to "100")
    private const val UNIT = "(p[eɛ]s(?:[eɛ]?was?|[eɛ]wa?s?|os)|c[eɛ]dis?|sidi|ghs|ghc)"
    private val GLUED_FIGURE = Regex("(?i)(?<![\\p{L}\\d.])(\\d+(?:\\.\\d{1,2})?)$UNIT(?![\\p{L}])")
    private val GLUED_WORD = Regex("(?i)(?<![\\p{L}])(one|two|three|four|five|six|seven|eight|nine|te[nm]|eleven|twelve|fifteen|twenty|thirty|forty|fifty|hundred)$UNIT(?![\\p{L}])")

    /**
     * A phone number the recogniser destroyed: digit words glued into nonsense ("zeroffvtziro ne tsi sir" — "0552093690" said
     * in English inside Twi, the HCI Lab, 5 October 2026), or a run of digit words / a few digits that can't be a whole
     * number. Not a name, and not something to look up: the flow asks for the number again. Pure.
     */
    fun looksLikeLostDigits(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (GLUED_ZERO.containsMatchIn(t)) return true
        val toks = t.split(Regex("\\s+")).map { it.trim(',', '.', '!', '?') }.filter { it.isNotEmpty() }
        val digitish = toks.count { DIGITISH.matches(it) }
        val digits = t.count { it.isDigit() }
        return (digitish >= 2 && digitish * 2 >= toks.size) || (digits in 3..8 && digits * 2 >= t.count { !it.isWhitespace() })
    }

    private val GLUED_ZERO = Regex("(?i)z[iɛe]ro\\p{L}{2,}|\\p{L}{2,}z[iɛe]ro")
    private val DIGITISH = Regex("(?i)zero|ziro|oh|one|two|three|four|five|six|seven|eight|nine|double|triple|\\d+")

    /** Word-level repairs, before the number parser. */
    fun beforeParse(text: String): String {
        if (text.isBlank()) return text
        var s = digitRuns(unglueAmounts(text))
        // 3. "half a cedi" → 50 pesewas; "a cedi" / "one cedi" → 1 cedi (an amount word the parser has no number for).
        s = HALF_CEDI.replace(s) { "50 pesewas" }
        s = A_CEDI.replace(s) { m -> "1 ${m.groupValues[1].ifEmpty { "" }}cedi" }
        return s
    }

    /** Only the digit-run rules (1, 2): "double five", "oh" for zero — all a MESSAGE command gets (its recipient's number
     *  said aloud), never the money rules ([MessageBody]). */
    fun digitRuns(text: String): String {
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
        return toks.joinToString(" ")
    }

    private val HALF_CEDI = Regex("(?i)\\bhalf\\s+(?:a|of\\s+a|one)\\s+(?:ghana\\s+)?cedi\\b")
    private val A_CEDI = Regex("(?i)\\b(?:a|one)\\s+(ghana\\s+)?cedi\\b(?!s)")

    /** Figure-level repairs, after the number parser: "2 point 5" → "2.5", "1 point 5 0" → "1.50". */
    fun afterParse(text: String): String {
        var s = POINT.replace(text) { m -> m.groupValues[1] + "." + m.groupValues[2].replace(" ", "") }
        // "point 5 cedis" (no whole part said) is 0.5 cedis — only with the money word right after it
        s = LEADING_POINT.replace(s) { m -> "0." + m.groupValues[1].replace(" ", "") + " " + m.groupValues[2] }
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
    private val LEADING_POINT = Regex("(?<![\\d.]\\s?)\\bpoint\\s+(\\d(?:\\s\\d)?)\\s+((?:ghana\\s+)?(?:cedis?|ghs|ghc))\\b", RegexOption.IGNORE_CASE)
    private val AND_AMOUNT = Regex("(?<![\\d.,])([1-9]\\d*00)\\s+and\\s+(\\d{1,3})\\s+((?:ghana\\s+)?(?:cedis?|pesewas?|ghs|ghc))\\b",
        RegexOption.IGNORE_CASE)
}
