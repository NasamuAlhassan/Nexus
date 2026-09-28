package com.tekyerema.agent

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Simple arithmetic answered on the phone — "what is 15 times 12", "250 divided by 5", "20 percent of 150". The device
 * suite (A20e, 2026-09-28) timed "what is 15 times 12" at 38.7 s through the cloud model on a slow network; this needs
 * no model and works offline. Only whole utterances of plain numbers and one operation match — "how much is 5 dollars
 * in cedis" (a rate), "5 times a day" or anything longer goes on as before. Pure, unit-tested.
 */
object LocalMath {
    private const val NUM = "(-?\\d+(?:[.,]\\d+)?)"
    private const val LEAD = "^(?:(?:what(?:'s| is)|how much is|calculate|compute|work out|tell me)\\s+)?"
    private val BINARY = Regex(LEAD + NUM + "\\s*(plus|\\+|add|minus|-|less|times|x|×|\\*|multiplied by|divided by|over|/|÷)\\s*" + NUM + "$")
    private val PERCENT = Regex(LEAD + NUM + "\\s*(?:%|percent|per cent)\\s+of\\s+" + NUM + "$")

    /** The spoken answer, or null when [g] (lower-case, trimmed) isn't a simple sum. */
    fun answer(g: String): String? {
        val s = g.trim().trimEnd('?', '.', '!').trim().replace(Regex("\\s+"), " ")
        PERCENT.matchEntire(s)?.let { m ->
            val p = num(m.groupValues[1]) ?: return null; val of = num(m.groupValues[2]) ?: return null
            return "${say(p)} percent of ${say(of)} is ${say(p.multiply(of).divide(BigDecimal(100), MC))}."
        }
        val m = BINARY.matchEntire(s) ?: return null
        val a = num(m.groupValues[1]) ?: return null; val b = num(m.groupValues[3]) ?: return null
        val (word, result) = when (m.groupValues[2]) {
            "plus", "+", "add" -> "plus" to a.add(b)
            "minus", "-", "less" -> "minus" to a.subtract(b)
            "times", "x", "×", "*", "multiplied by" -> "times" to a.multiply(b)
            else -> {
                if (b.signum() == 0) return "You can't divide by zero."
                "divided by" to a.divide(b, MC)
            }
        }
        return "${say(a)} $word ${say(b)} is ${say(result)}."
    }

    private val MC = MathContext(12, RoundingMode.HALF_UP)

    private fun num(s: String): BigDecimal? = try { BigDecimal(s.replace(',', '.')) } catch (_: NumberFormatException) { null }

    /** 180 not 180.000; at most 4 decimals ("0.3333"). */
    private fun say(n: BigDecimal): String {
        val r = n.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (r.scale() <= 0) r.toBigInteger().toString() else r.toPlainString()
    }
}
