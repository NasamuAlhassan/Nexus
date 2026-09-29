package com.tekyerema.agent

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Simple arithmetic answered on the phone — "what is 15 times 12", "250 divided by 5", "20 percent of 150", "half of
 * 250", "a quarter of 80 cedis", "the square root of 144", "12 squared", "2 to the power of 10". The device suite
 * (A20e, 2026-09-28) timed "what is 15 times 12" at 38.7 s through the cloud model on a slow network; this needs no
 * model and works offline. Only whole utterances of plain numbers and one operation match — "how much is 5 dollars in
 * cedis" (a rate), "5 times a day" or anything longer goes on as before. Pure, unit-tested.
 */
object LocalMath {
    private const val NUM = "(-?\\d+(?:[.,]\\d+)?)"
    private const val LEAD = "^(?:(?:what(?:'s| is)|how much is|calculate|compute|work out|tell me)\\s+)?"
    /** Money said with the number is carried into the answer ("30 cedis"); the sum itself doesn't change. */
    private const val MONEY = "(?:\\s+(ghana cedis|cedis|cedi|pesewas|dollars|euros|pounds|naira))?"
    private val BINARY = Regex(LEAD + NUM + "\\s*(plus|\\+|add|minus|-|less|times|x|×|\\*|multiplied by|divided by|over|/|÷)\\s*" + NUM + "$")
    private val PERCENT = Regex(LEAD + NUM + "\\s*(?:%|percent|per cent)\\s+of\\s+" + NUM + MONEY + "$")
    private val FRACTION = Regex(LEAD + "(half|(?:a|one|1) (?:half|third|quarter|fifth|tenth)|(?:two|2) thirds|(?:three|3) quarters) of " + NUM + MONEY + "$")
    private val SQRT = Regex(LEAD + "(?:the )?square root of " + NUM + "$")
    private val POWER = Regex(LEAD + NUM + " (squared|cubed|to the power of (\\d{1,2})|to the power (\\d{1,2})|raised to(?: the power of)? (\\d{1,2}))$")

    /** The spoken answer, or null when [g] (lower-case, trimmed) isn't a simple sum. */
    fun answer(g: String): String? {
        val s = g.trim().trimEnd('?', '.', '!').trim().replace(Regex("\\s+"), " ")
        PERCENT.matchEntire(s)?.let { m ->
            val p = num(m.groupValues[1]) ?: return null; val of = num(m.groupValues[2]) ?: return null
            val unit = m.groupValues[3].let { if (it.isEmpty()) "" else " $it" }
            return "${say(p)} percent of ${say(of)}$unit is ${say(p.multiply(of).divide(BigDecimal(100), MC))}$unit."
        }
        FRACTION.matchEntire(s)?.let { m ->
            val of = num(m.groupValues[2]) ?: return null
            val unit = m.groupValues[3].let { if (it.isEmpty()) "" else " $it" }
            val (name, top, bottom) = when (m.groupValues[1].substringAfter(' ')) {
                "half" -> Triple("Half", 1, 2)
                "third" -> Triple("A third", 1, 3)
                "quarter" -> Triple("A quarter", 1, 4)
                "fifth" -> Triple("A fifth", 1, 5)
                "tenth" -> Triple("A tenth", 1, 10)
                "thirds" -> Triple("Two thirds", 2, 3)
                "quarters" -> Triple("Three quarters", 3, 4)
                else -> return null
            }
            return "$name of ${say(of)}$unit is ${say(of.multiply(BigDecimal(top)).divide(BigDecimal(bottom), MC))}$unit."
        }
        SQRT.matchEntire(s)?.let { m ->
            val n = num(m.groupValues[1]) ?: return null
            if (n.signum() < 0) return null
            val root = BigDecimal(Math.sqrt(n.toDouble())).round(MC)
            val exact = root.setScale(0, RoundingMode.HALF_UP).let { r -> r.multiply(r).compareTo(n) == 0 }
            return if (exact) "The square root of ${say(n)} is ${say(root.setScale(0, RoundingMode.HALF_UP))}."
                else "The square root of ${say(n)} is about ${say(root)}."
        }
        POWER.matchEntire(s)?.let { m ->
            val base = num(m.groupValues[1]) ?: return null
            val how = m.groupValues[2]
            val e = when (how) { "squared" -> 2; "cubed" -> 3; else -> (m.groupValues[3] + m.groupValues[4] + m.groupValues[5]).toIntOrNull() ?: return null }
            val r = base.pow(e)
            if (r.abs() >= BigDecimal("1e15")) return null   // too long to say
            return when (how) {
                "squared", "cubed" -> "${say(base)} $how is ${say(r)}."
                else -> "${say(base)} to the power of $e is ${say(r)}."
            }
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
