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
    /** A Ghana phone number: 0 and nine digits, or +233 / 233 and nine, together or in groups (spaces or dashes). */
    private val PHONE = Regex("(?<![\\d.,])(?:\\+233|233|0)(?:[ -]?\\d){9}(?![\\d.,]?\\d)")
    private val ENDING = Regex("(?i)\\b(ending(?: in)?)\\s+(\\d{2,4})\\b")
    private val DATA_SIZE = Regex("(?i)\\b(\\d+(?:\\.\\d+)?)\\s?(GB|MB)\\b")
    /** "15.64Mins", "6.7 min" — a bundle's minutes, said whole ("sixteen minutes"), never "fifteen point six four mins". */
    private val MINS = Regex("(?i)\\b(\\d+(?:\\.\\d+)?)\\s?mins?\\b")
    private val CUR_ALONE = Regex("(?<![\\p{L}\\p{N}])(?:GH₵|GH¢|GHS|GHC)(?![\\p{L}\\p{N}])")

    /** "0552093690" / "+233 55 209 3690" → "zero five five, two zero nine, three six nine zero" (the local 3-3-4 grouping). */
    private fun phone(raw: String): String {
        val d = raw.filter { it.isDigit() }
        val local = when {
            raw.startsWith("+233") || (d.length == 12 && d.startsWith("233")) -> d.substring(3)
            else -> d.drop(1)
        }
        if (local.length != 9) return raw
        val lead = if (raw.startsWith("+")) "plus two three three, " else if (d.length == 12) "two three three, " else "zero "
        fun said(s: String) = s.map { ONES[it - '0'] }.joinToString(" ")
        return if (lead == "zero ") "zero " + said(local.substring(0, 2)) + ", " + said(local.substring(2, 5)) + ", " + said(local.substring(5))
        else lead + said(local.substring(0, 2)) + ", " + said(local.substring(2, 5)) + ", " + said(local.substring(5))
    }

    private val DEFAULT_LEXICON = mapOf(
        "Tɛkyerɛma" to "Tekyerema",
        "MoMo" to "Momo",
        // Said letter by letter: the system voice ran "MTN" together (owner, 2026-10-02: "it struggles to say MTN").
        "MTN" to "M T N",
        "AirtelTigo" to "Airtel Tigo",
        "USSD" to "U S S D",
        "OTP" to "O T P",
        "SMS" to "S M S",
        // MTN's own menu words, as read on the A20e on 13 October 2026 ("MashUp GHC 5", "Y'ello!", "Enter MM PIN", "No 4G
        // bonus", "MyMTN App"): said as the words they stand for, not spelled or run together.
        "MyMTN" to "My M T N",
        "MashUp" to "Mash Up",
        "Mashup" to "Mash Up",
        "Y'ello" to "Yellow",     // MTN's greeting is said "yellow"; a dictionary word is the one spelling every voice knows (R-A12)
        "Y'ello!" to "Yellow!",
        "MM PIN" to "Mobile Money PIN",
        "MoMoPay" to "Momo Pay",
        "Me2U" to "Me to You",
        "4G" to "four G",
        "3G" to "three G",
        "5G" to "five G",
        "a/c" to "account"
    )

    fun apply(text: String): String {
        var t = text.replace(URL, "a link").replace(EMOJI, "").replace(MARKDOWN, "")
        t = lexicon(t)
        t = USSD.replace(t) { m -> m.value.map { c -> when (c) { '*' -> "star"; '#' -> "hash"; else -> ONES[c - '0'] } }.joinToString(" ") }
        // Phone numbers digit by digit in their groups ("055 209 3690" was "fifty-five, two hundred nine, three thousand six
        // hundred ninety"; "+233…" kept a "+"), the last digits of a contact's number too ("ending 323") — robustness
        // audit, 5 October 2026.
        t = PHONE.replace(t) { m -> phone(m.value) }
        t = ENDING.replace(t) { m -> m.groupValues[1] + " " + m.groupValues[2].map { ONES[it - '0'] }.joinToString(" ") }
        // Data in figures a listener can hold (owner, 13 October 2026: the reading of MTN's bundles was "so bad"): megabytes
        // whole ("522.32 MB" → "522 megabytes"), a thousand and more of them as gigabytes to one decimal ("3133.94 MB" →
        // "about 3.1 gigabytes"), gigabytes to one decimal; minutes whole ("15.64Mins" → "16 minutes").
        t = DATA_SIZE.replace(t) { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return@replace m.value
            val gb = m.groupValues[2].equals("GB", true)
            val toGb = !gb && n >= 1024
            val value = when {
                toGb -> Math.round(n / 1024 * 10) / 10.0
                gb -> Math.round(n * 10) / 10.0
                else -> Math.round(n).toDouble()
            }
            val shown = if (value == Math.floor(value)) value.toLong().toString() else value.toString()
            (if (toGb) "about " else "") + shown + (if (gb || toGb) " gigabyte" else " megabyte") + (if (shown == "1") "" else "s")
        }
        t = MINS.replace(t) { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return@replace m.value
            val whole = Math.round(n)
            "$whole minute" + if (whole == 1L) "" else "s"
        }
        t = MONEY_BEFORE.replace(t) { m -> money(m.groupValues[1], m.groupValues[2]) }
        t = MONEY_AFTER.replace(t) { m -> money(m.groupValues[1], m.groupValues[2]) }
        // the currency on its own ("Balance in GHS") is a word, not letters
        t = CUR_ALONE.replace(t, "cedis")
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
            // phone-like, or a group that starts with 0 ("055") → digit-by-digit
            if (digits.length >= 7 || (digits.length > 1 && digits[0] == '0')) digits.map { ONES[it - '0'] }.joinToString(" ")
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
