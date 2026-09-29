package com.tekyerema.agent

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Unit conversions answered on the phone — "convert 10 km to miles", "how many pounds is 5 kg", "30 degrees Celsius in
 * Fahrenheit", "how many centimetres in an inch", "180 cm in feet", "how many minutes in 3 hours". The factors are
 * exact definitions (1 inch = 2.54 cm, 1 pound = 0.45359237 kg), so the answer is always right, instant and offline.
 *
 * Length, weight, volume (millilitres and litres only — a "gallon" differs between countries), time up to weeks
 * (months and years aren't fixed lengths) and temperature. Money is never converted here: rates change, so "5 dollars
 * in cedis" goes on to the web. Only whole, clear utterances with two known units of the same kind match; anything
 * else returns null. Answers say the units in full ("kilometres", "degrees Celsius") so the voice reads them right.
 * Pure, unit-tested.
 */
object UnitConvert {
    private enum class Dim { LENGTH, MASS, VOLUME, TIME, TEMP }

    /** [perBase]: how many base units (metre, kilogram, litre, second) one of this unit is. Temperatures use none. */
    private class U(val dim: Dim, perBase: String, val one: String, val many: String) {
        val per: BigDecimal = BigDecimal(perBase)
    }

    private val MM = U(Dim.LENGTH, "0.001", "millimetre", "millimetres")
    private val CM = U(Dim.LENGTH, "0.01", "centimetre", "centimetres")
    private val M = U(Dim.LENGTH, "1", "metre", "metres")
    private val KM = U(Dim.LENGTH, "1000", "kilometre", "kilometres")
    private val IN = U(Dim.LENGTH, "0.0254", "inch", "inches")
    private val FT = U(Dim.LENGTH, "0.3048", "foot", "feet")
    private val YD = U(Dim.LENGTH, "0.9144", "yard", "yards")
    private val MI = U(Dim.LENGTH, "1609.344", "mile", "miles")
    private val G = U(Dim.MASS, "0.001", "gram", "grams")
    private val KG = U(Dim.MASS, "1", "kilogram", "kilograms")
    private val T = U(Dim.MASS, "1000", "tonne", "tonnes")
    private val LB = U(Dim.MASS, "0.45359237", "pound", "pounds")
    private val OZ = U(Dim.MASS, "0.028349523125", "ounce", "ounces")
    private val ML = U(Dim.VOLUME, "0.001", "millilitre", "millilitres")
    private val L = U(Dim.VOLUME, "1", "litre", "litres")
    private val SEC = U(Dim.TIME, "1", "second", "seconds")
    private val MIN = U(Dim.TIME, "60", "minute", "minutes")
    private val HR = U(Dim.TIME, "3600", "hour", "hours")
    private val DAY = U(Dim.TIME, "86400", "day", "days")
    private val WK = U(Dim.TIME, "604800", "week", "weeks")
    private val C = U(Dim.TEMP, "0", "degree Celsius", "degrees Celsius")
    private val F = U(Dim.TEMP, "0", "degree Fahrenheit", "degrees Fahrenheit")
    /** "30 degrees in Fahrenheit": the scale is the other one. */
    private val DEG = U(Dim.TEMP, "0", "degree", "degrees")

    private val WORDS: Map<String, U> = buildMap {
        fun put(u: U, vararg w: String) = w.forEach { put(it, u) }
        put(MM, "mm", "millimetre", "millimetres", "millimeter", "millimeters")
        put(CM, "cm", "cms", "centimetre", "centimetres", "centimeter", "centimeters")
        put(M, "m", "metre", "metres", "meter", "meters")
        put(KM, "km", "kms", "kilometre", "kilometres", "kilometer", "kilometers", "kilo metre", "kilo metres", "kilo meter", "kilo meters")
        put(IN, "inch", "inches")
        put(FT, "ft", "foot", "feet")
        put(YD, "yd", "yds", "yard", "yards")
        put(MI, "mile", "miles")
        put(G, "g", "gram", "grams", "gramme", "grammes")
        put(KG, "kg", "kgs", "kilogram", "kilograms", "kilogramme", "kilogrammes", "kilo", "kilos")
        put(T, "tonne", "tonnes", "metric ton", "metric tons")
        put(LB, "lb", "lbs", "pound", "pounds")
        put(OZ, "oz", "ounce", "ounces")
        put(ML, "ml", "mls", "millilitre", "millilitres", "milliliter", "milliliters")
        put(L, "litre", "litres", "liter", "liters")
        put(SEC, "second", "seconds", "sec", "secs")
        put(MIN, "minute", "minutes", "min", "mins")
        put(HR, "hour", "hours", "hr", "hrs")
        put(DAY, "day", "days")
        put(WK, "week", "weeks")
        put(C, "celsius", "centigrade", "degrees celsius", "degree celsius", "degrees centigrade", "degrees c", "degree c", "°c")
        put(F, "fahrenheit", "degrees fahrenheit", "degree fahrenheit", "degrees f", "degree f", "°f")
        put(DEG, "degrees", "degree")
    }

    // Longest first, so "degrees celsius" wins over "degrees" and "kilo metres" over "kilo".
    private val UNIT = "(" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")"
    private const val NUM = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|(?:-|minus )?\\d+(?:\\.\\d+)?|an?\\b|one\\b)"
    private val SRC = "$NUM\\s*$UNIT"
    private const val LEAD = "^(?:(?:please|can you|could you|kindly|help me|tell me)\\s+)*"

    private val RE_CONVERT = Regex(LEAD + "(?:convert|change|turn)\\s+$SRC\\s+(?:to|into|in)\\s+$UNIT$")
    private val RE_IN = Regex(LEAD + "(?:(?:what(?:'s| is| are)|how much is|how much are|how long is|how far is|how heavy is|how tall is|" +
        "how hot is|how cold is)\\s+)?$SRC\\s+(?:in|to|into|in terms of|as)\\s+$UNIT$")
    private val RE_HOW_MANY = Regex(LEAD + "how many\\s+$UNIT\\s+(?:is|are|in|make|makes|equals?|make up|makes up|are there in|is there in|" +
        "are in|is in|go into|goes into|fit in|fit into)\\s+$SRC$")
    private val RE_IS_HOW_MANY = Regex(LEAD + "$SRC\\s+(?:is|are|equals?|makes?)\\s+how many\\s+$UNIT$")
    // Height: "5 feet 10 in centimetres", "what is 5 foot 10 inches in cm".
    private val RE_FEET_INCHES = Regex(LEAD + "(?:convert\\s+|what(?:'s| is)\\s+|how tall is\\s+)?(\\d{1,2})\\s*(?:feet|foot|ft)\\s*(\\d{1,2}(?:\\.\\d+)?)" +
        "(?:\\s*(?:inches|inch))?\\s+(?:in|to|into)\\s+$UNIT$")

    private val MC = MathContext(20, RoundingMode.HALF_UP)
    private val SPACES = Regex("\\s+")
    private val TAIL = Regex(" (?:please|for me)$")

    /** The spoken answer, or null when [g] (lower-case) isn't a conversion between two known units of one kind. */
    fun answer(g: String): String? {
        val s = g.lowercase().replace('’', '\'').trim().trimEnd('?', '.', '!').trim().replace(SPACES, " ").replace(TAIL, "")
        RE_FEET_INCHES.matchEntire(s)?.let { m ->
            val ft = BigDecimal(m.groupValues[1]); val inch = BigDecimal(m.groupValues[2])
            val to = WORDS[m.groupValues[3]]?.takeIf { it.dim == Dim.LENGTH && it !== FT && it !== IN } ?: return null
            val base = ft.multiply(FT.per).add(inch.multiply(IN.per))
            val said = "${plain(ft)} ${if (ft.compareTo(BigDecimal.ONE) == 0) "foot" else "feet"} ${plain(inch)} ${if (inch.compareTo(BigDecimal.ONE) == 0) "inch" else "inches"}"
            return "$said is ${amount(base, to) ?: return null}."
        }
        RE_CONVERT.matchEntire(s)?.let { m -> return convert(m.groupValues[1], m.groupValues[2], m.groupValues[3], article = false) }
        RE_IN.matchEntire(s)?.let { m -> return convert(m.groupValues[1], m.groupValues[2], m.groupValues[3], article = false) }
        RE_IS_HOW_MANY.matchEntire(s)?.let { m -> return convert(m.groupValues[1], m.groupValues[2], m.groupValues[3], article = false) }
        RE_HOW_MANY.matchEntire(s)?.let { m ->
            return convert(m.groupValues[2], m.groupValues[3], m.groupValues[1], article = m.groupValues[2] in setOf("a", "an", "one"))
        }
        return null
    }

    private fun convert(numTok: String, fromTok: String, toTok: String, article: Boolean): String? {
        var from = WORDS[fromTok] ?: return null
        var to = WORDS[toTok] ?: return null
        val x = number(numTok) ?: return null
        if (from.dim != to.dim) return null
        if (from.dim == Dim.TEMP) {
            if (from === DEG && to === DEG) return null
            if (from === DEG) from = if (to === F) C else F
            if (to === DEG) to = if (from === F) C else F
            if (from === to) return null
            val r = if (from === C) x.multiply(BigDecimal(9)).divide(BigDecimal(5), MC).add(BigDecimal(32))
                else x.subtract(BigDecimal(32)).multiply(BigDecimal(5)).divide(BigDecimal(9), MC)
            val rounded = r.setScale(1, RoundingMode.HALF_UP)
            val about = if (rounded.compareTo(r) == 0) "" else "about "
            return "${temp(x, from)} is $about${temp(rounded, to)}."
        }
        if (from === to || x.signum() < 0) return null
        val result = amount(x.multiply(from.per), to, metricFrom = from) ?: return null
        if (article) {
            val a = if (from.one.first() in "aeiou" || from.one == "hour") "an" else "a"
            val isAre = if (result.endsWith(" ${to.one}") && !result.startsWith("about")) "is" else "are"
            return "There $isAre $result in $a ${from.one}."
        }
        return "${plain(x)} ${if (x.compareTo(BigDecimal.ONE) == 0) from.one else from.many} is $result."
    }

    /**
     * [base] (in metres, kilograms, litres or seconds, exact) said in [to]: "about 6.21 miles", "180 minutes", "1 hour and
     * 40 minutes", "about 5 feet 11 inches"; null if too small to say.
     */
    private fun amount(base: BigDecimal, to: U, metricFrom: U? = null): String? {
        val r = base.divide(to.per, MC)
        // A height in feet is said in feet and inches.
        if (to === FT && metricFrom != null && metricFrom.dim == Dim.LENGTH && metricFrom !== IN && metricFrom !== YD && metricFrom !== MI &&
            r >= BigDecimal.ONE && r < BigDecimal(10)) {
            val inches = base.divide(IN.per, MC)
            var ft = inches.divide(BigDecimal(12), 0, RoundingMode.DOWN).toInt()
            var inch = inches.subtract(BigDecimal(ft * 12)).setScale(0, RoundingMode.HALF_UP).toInt()
            if (inch == 12) { ft += 1; inch = 0 }
            val exact = inches.stripTrailingZeros().scale() <= 0
            val said = "$ft ${if (ft == 1) "foot" else "feet"}" + if (inch > 0) " $inch ${if (inch == 1) "inch" else "inches"}" else ""
            return (if (exact) "" else "about ") + said
        }
        // Time: "1 hour and 40 minutes", not "1.67 hours".
        if (to.dim == Dim.TIME && to !== SEC && r.stripTrailingZeros().scale() > 0 && r >= BigDecimal.ONE) {
            val next = when (to) { WK -> DAY; DAY -> HR; HR -> MIN; else -> SEC }
            val whole = base.divide(to.per, 0, RoundingMode.DOWN)
            val rest = base.subtract(whole.multiply(to.per)).divide(next.per, MC)
            if (rest.stripTrailingZeros().scale() <= 0) {
                return "${plain(whole)} ${if (whole.compareTo(BigDecimal.ONE) == 0) to.one else to.many} and " +
                    "${plain(rest)} ${if (rest.compareTo(BigDecimal.ONE) == 0) next.one else next.many}"
            }
        }
        val (text, exact) = round(r) ?: return null
        val unit = if (BigDecimal(text).compareTo(BigDecimal.ONE) == 0) to.one else to.many
        return (if (exact) "" else "about ") + "$text $unit"
    }

    /** Exact (at most 2 decimals) → as is; else 1 decimal from 100 up, 2 from 1 up, 3 significant figures below 1. */
    private fun round(r: BigDecimal): Pair<String, Boolean>? {
        val a = r.abs()
        if (r.stripTrailingZeros().scale() <= 2) return plain(r) to true
        if (a < BigDecimal("0.001")) return null
        val v = when {
            a >= BigDecimal(10_000) -> r.setScale(0, RoundingMode.HALF_UP)
            a >= BigDecimal(100) -> r.setScale(1, RoundingMode.HALF_UP)
            a >= BigDecimal.ONE -> r.setScale(2, RoundingMode.HALF_UP)
            else -> r.round(MathContext(3, RoundingMode.HALF_UP))
        }
        return plain(v) to false
    }

    private fun temp(x: BigDecimal, u: U): String {
        val n = if (x.signum() < 0) "minus ${plain(x.negate())}" else plain(x)
        return "$n ${if (x.abs().compareTo(BigDecimal.ONE) == 0) u.one else u.many}"
    }

    private fun number(t: String): BigDecimal? = when (t) {
        "a", "an", "one" -> BigDecimal.ONE
        else -> try { BigDecimal(t.replace(",", "").replace("minus ", "-")) } catch (_: NumberFormatException) { null }
    }

    /** 180 not 180.00; no exponent ("0.000254", not "2.54E-4"). */
    private fun plain(n: BigDecimal): String {
        val s = n.stripTrailingZeros()
        return if (s.scale() <= 0) s.toBigInteger().toString() else s.toPlainString()
    }
}
