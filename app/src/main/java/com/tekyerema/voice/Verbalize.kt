package com.tekyerema.voice

/**
 * TTS-output verbalization (§23), mirroring phase2/asr.py `verbalize()`: quantities become spoken
 * words ("1500" → "one thousand five hundred"), but long digit runs (phone-number-like, ≥7 digits)
 * stay digit-by-digit so a number like 0552093690 is read as digits, not a giant quantity. Pure
 * Kotlin so it is unit-testable and identical on-device.
 */
object Verbalize {
    private val ONES = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    fun apply(text: String): String =
        Regex("\\d+").replace(text) { m ->
            val digits = m.value
            if (digits.length >= 7) digits.map { ONES[it - '0'] }.joinToString(" ")   // phone-like → digit-by-digit
            else intToWords(digits.toLongOrNull() ?: return@replace digits)
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
