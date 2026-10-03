package com.tekyerema.money.ussd

import com.tekyerema.phonecare.MoneyAlerts
import java.math.BigDecimal

/**
 * The amounts a network's entry screen says it will take — read BEFORE anything is typed. Pure → unit-tested.
 *
 * Why (live A20e, 2 October 2026 03:41, airtime for a Telecel number): MTN's screen read "Enter amount (GHS) minimum:
 * 0.20, maximum: 500.00"; the user had asked for 10 pesewas; the navigator typed 0.1 anyway, MTN answered "The amount you
 * have entered is less than or greater than allowed amount", and the same refused amount was typed three more times
 * until the session timed out. Only the "0.07 - 4.99" shape of a range was understood, and only on the guided path.
 */
object AmountRange {
    data class Range(val min: BigDecimal?, val max: BigDecimal?) {
        fun allows(amount: BigDecimal): Boolean = (min == null || amount >= min) && (max == null || amount <= max)

        /** "between 20 pesewas and 500 cedis" / "at least 20 pesewas" / "at most 500 cedis". */
        val spoken: String get() = when {
            min != null && max != null -> "between ${cedis(min)} and ${cedis(max)}"
            min != null -> "at least ${cedis(min)}"
            else -> "at most ${cedis(max!!)}"
        }
    }

    private const val FIG = "(\\d+(?:\\.\\d+)?)"
    private const val CUR = "(?:GH[SC₵¢]?\\s*)?"
    private val MIN = Regex("(?i)\\bmin(?:imum)?\\b[^\\d]{0,14}$FIG")
    private val MAX = Regex("(?i)\\bmax(?:imum)?\\b[^\\d]{0,14}$FIG")
    private val BETWEEN = Regex("(?i)\\bbetween\\s+$CUR$FIG\\s+and\\s+$CUR$FIG")
    private val DASH = Regex("(?i)$CUR$FIG\\s*(?:-|–|to)\\s*$CUR$FIG")

    /** The range [screen] states — "minimum: 0.20, maximum: 500.00", "(GHC 0.07 - 4.99)", "between 1 and 50" — or null. */
    fun of(screen: String): Range? {
        val min = MIN.find(screen)?.groupValues?.get(1)?.toBigDecimalOrNull()
        val max = MAX.find(screen)?.groupValues?.get(1)?.toBigDecimalOrNull()
        if (min != null || max != null) return Range(min, max)
        (BETWEEN.find(screen) ?: DASH.find(screen))?.let { m ->
            val lo = m.groupValues[1].toBigDecimalOrNull() ?: return null
            val hi = m.groupValues[2].toBigDecimalOrNull() ?: return null
            if (lo <= hi) return Range(lo, hi)
        }
        return null
    }

    /** True when [amount] is inside the range the screen states, or the screen states none (or the amount isn't a figure). */
    fun allows(amount: String?, screen: String): Boolean {
        val v = amount?.replace(",", "")?.trim()?.toBigDecimalOrNull() ?: return true
        return of(screen)?.allows(v) ?: true
    }

    internal fun cedis(v: BigDecimal): String = MoneyAlerts.spokenCedis(v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())

    /** The network's words that the last entry was refused — what was typed is never typed again. */
    val REFUSED = Regex("(?i)\\b(?:wrong|invalid|incorrect|not valid|not allowed|not accepted)\\b|\\btry again\\b|enter .{0,24}\\bagain\\b|" +
        "less than or greater than|\\bexceeds?\\b|below the minimum|above the maximum|not within|out of range")
}
