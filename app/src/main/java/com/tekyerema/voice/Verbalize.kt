package com.tekyerema.voice

import com.tekyerema.core.config.AppConfig

/**
 * TTS-output verbalization (§23, B-20, B-17), mirroring phase2/asr.py `verbalize()`: text a screen shows is turned
 * into text a voice should say. In order:
 *  1. links become "a link", emoji and markdown symbols are dropped;
 *  2. the pronunciation lexicon (`speech.tts.lexicon`) respells names and acronyms the system voice mangles;
 *  3. USSD codes are read symbol by symbol ("*170#" → "star one seven zero hash");
 *  4. money is read as cedis and pesewas ("GH₵ 5.50" → "five cedis fifty pesewas");
 *  5. dates, times, percentages and decimals get their spoken forms;
 *  6. remaining quantities become words ("1500" → "one thousand five hundred"), but long digit runs
 *     (phone-number-like, ≥7 digits) stay digit-by-digit so 0552093690 is read as digits.
 * Pure Kotlin so it is unit-testable and identical on-device.
 */
object Verbalize {
    private val ONES = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December")

    private val URL = Regex("(?i)\\bhttps?://\\S+|\\bwww\\.\\S+")
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{FE0F}\\x{200D}]")
    private val MARKDOWN = Regex("\\*\\*|__|`+|^\\s*#{1,6}\\s+|^\\s*[-*•]\\s+", RegexOption.MULTILINE)
    private val USSD = Regex("\\*[0-9*]+#")
    private val MONEY_BEFORE = Regex("(?i)(?:GH₵|GH¢|GHS|GHC|₵|¢)\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?")
    private val MONEY_AFTER = Regex("(?i)\\b(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?\\s?(?:ghana cedis|cedis|cedi|GHS)\\b")
    private val DATE = Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b")
    private val TIME = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
    private val PERCENT = Regex("(\\d+(?:\\.\\d+)?)\\s?%")
    private val DECIMAL = Regex("\\b(\\d+)\\.(\\d+)\\b")

    private val DEFAULT_LEXICON = mapOf(
        "Tɛkyerɛma" to "Tekyerema",
        "MoMo" to "Momo",
        // Said letter by letter: the system voice ran "MTN" together (owner, 2026-10-02: "it struggles to say MTN").
        "MTN" to "M T N",
        "AirtelTigo" to "Airtel Tigo",
        "USSD" to "U S S D",
        "OTP" to "O T P",
        "SMS" to "S M S"
    )

    fun apply(text: String): String {
        var t = text.replace(URL, "a link").replace(EMOJI, "").replace(MARKDOWN, "")
        t = lexicon(t)
        t = USSD.replace(t) { m -> m.value.map { c -> when (c) { '*' -> "star"; '#' -> "hash"; else -> ONES[c - '0'] } }.joinToString(" ") }
        t = MONEY_BEFORE.replace(t) { m -> money(m.groupValues[1], m.groupValues[2]) }
        t = MONEY_AFTER.replace(t) { m -> money(m.groupValues[1], m.groupValues[2]) }
        t = DATE.replace(t) { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            if (mo in 1..12 && d in 1..31) "$d ${MONTHS[mo - 1]} ${m.groupValues[3]}" else m.value
        }
        t = TIME.replace(t) { m ->
            val h24 = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            // A 24-hour time is said the human way: "13:45" → "one forty-five in the afternoon" (micro_details.md
            // quick win 3). 1:00–12:59 is left as written — it usually already carries its AM or PM.
            val h = when { h24 == 0 -> 12L; h24 > 12 -> (h24 - 12).toLong(); else -> h24.toLong() }
            val period = when {
                h24 == 0 || h24 >= 21 -> " at night"
                h24 in 13..16 -> " in the afternoon"
                h24 in 17..20 -> " in the evening"
                else -> ""
            }
            intToWords(h) + when {
                min == 0 && period.isEmpty() -> " o'clock"
                min == 0 -> ""
                min < 10 -> " oh " + ONES[min]
                else -> " " + intToWords(min.toLong())
            } + period
        }
        t = PERCENT.replace(t) { m -> m.groupValues[1] + " percent" }
        t = DECIMAL.replace(t) { m ->
            if (m.groupValues[1].length >= 7) m.value
            else intToWords(m.groupValues[1].toLong()) + " point " + m.groupValues[2].map { ONES[it - '0'] }.joinToString(" ")
        }
        t = Regex("\\d+").replace(t) { m ->
            val digits = m.value
            if (digits.length >= 7) digits.map { ONES[it - '0'] }.joinToString(" ")   // phone-like → digit-by-digit
            else intToWords(digits.toLongOrNull() ?: return@replace digits)
        }
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /** "5", "50" → "five cedis fifty pesewas"; "0", "10" → "ten pesewas"; "1", "" → "one cedi". */
    private fun money(whole: String, fraction: String): String {
        val cedis = whole.replace(",", "").toLongOrNull() ?: return whole
        val pesewas = if (fraction.isEmpty()) 0 else (if (fraction.length == 1) fraction + "0" else fraction).toInt()
        val c = if (cedis == 0L && pesewas > 0) "" else intToWords(cedis) + if (cedis == 1L) " cedi" else " cedis"
        val p = if (pesewas == 0) "" else intToWords(pesewas.toLong()) + if (pesewas == 1) " pesewa" else " pesewas"
        return listOf(c, p).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** B-17: whole-word respellings from `speech.tts.lexicon` (config wins over the built-in defaults). */
    private fun lexicon(text: String): String {
        val entries = LinkedHashMap(DEFAULT_LEXICON)
        AppConfig.obj("speech.tts.lexicon")?.let { o -> for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { entries[k] = it } }
        var t = text
        for ((word, spoken) in entries) t = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])").replace(t, Regex.escapeReplacement(spoken))
        return t
    }

    fun intToWords(n: Long): String {
        if (n < 0) return "minus " + intToWords(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + (if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else "")
        if (n < 1000) return ONES[(n / 100).toInt()] + " hundred" + (if (n % 100 != 0L) " " + intToWords(n % 100) else "")
        if (n < 1_000_000) return intToWords(n / 1000) + " thousand" + (if (n % 1000 != 0L) " " + intToWords(n % 1000) else "")
        if (n < 1_000_000_000) return intToWords(n / 1_000_000) + " million" + (if (n % 1_000_000 != 0L) " " + intToWords(n % 1_000_000) else "")
        return n.toString()   // beyond a billion: leave as-is
    }
}
