package com.tekyerema.memory

/** Which unnamed control the user means. */
sealed class LabelTarget {
    /** "that button": the one pressed a moment ago. */
    object Recent : LabelTarget()
    /** "the third button" (the [n]th unnamed button in reading order; [fromEnd]: "the last button"). */
    data class Nth(val n: Int, val fromEnd: Boolean) : LabelTarget()
    /** "the unnamed button" when there is exactly one. */
    object Only : LabelTarget()
    /** "the button next to the message box" ([side]: "left"/"right" of the [anchor], or null for either). */
    data class Beside(val anchor: String, val side: String?) : LabelTarget()
}

/** What the user asked about button names, in so many words (pure; see [LabelCommands] for what is done about it). */
sealed class LabelCmd {
    /** [instruction]: said as an order ("call the third button Search"), not just "the third button is …" (which may be a remark). */
    data class Name(val target: LabelTarget, val name: String, val instruction: Boolean) : LabelCmd()
    data class ListNames(val app: String?) : LabelCmd()
    /** [all]: every app. [app] null and not [all]: the app in front. */
    data class Forget(val app: String?, val all: Boolean) : LabelCmd()
    /** "forget that button name": the one just taught. */
    object ForgetLast : LabelCmd()
}

/** Whole-utterance patterns only: a longer or compound request never matches and still reaches the agent. */
object LabelParse {
    private fun norm(raw: String): String =
        raw.trim().replace('’', '\'').replace('“', '"').replace('”', '"').replace(Regex("""\s+"""), " ").trimEnd('.', '!', '?').trim()
            .replace(Regex("""(?i)^(?:(?:please|okay|ok|hey)[, ]+)+"""), "")
            .replace(Regex("""(?i)^(?:remember|note|know)[, ]+(?:that )?(?=(?:that|this|the|first|second|third|fourth|fifth|sixth|last|unnamed|unlabell?ed|nameless)\b)"""), "").trim()

    private const val ORD = """(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|\d{1,2}(?:st|nd|rd|th))"""
    private const val KIND = """(?:unnamed |unlabell?ed |nameless |unmarked )?(?:button|icon)"""
    private const val IS = """(?:is for|is called|is named|is now|should be called|should be|means|is)"""
    private const val NAME = """(?:called |named |as )?(.{2,40})"""

    private val RECENT_IS = Regex("""(?i)^(?:that|this) $KIND $IS $NAME$""")
    private val RECENT_CALL = Regex("""(?i)^(?:call|name|label|rename) (?:that|this) $KIND (?:as |to )?$NAME$""")
    private val RECENT_WAS = Regex("""(?i)^(?:that|this) (?:was|is) (?:the |a |an )?(.{2,40}?) (?:button|icon)$""")
    private val NTH_IS = Regex("""(?i)^(?:that )?(?:the )?$ORD $KIND(?: on (?:this|the) screen)? $IS $NAME$""")
    private val NTH_CALL = Regex("""(?i)^(?:call|name|label|rename) (?:the )?$ORD $KIND (?:as |to )?$NAME$""")
    private val NUM_IS = Regex("""(?i)^(?:that )?(?:the )?$KIND (?:number |no\.? )?(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten) $IS $NAME$""")
    private val ONLY_IS = Regex("""(?i)^(?:that )?(?:the )?(?:only )?(?:unnamed|unlabell?ed|nameless) (?:button|icon) $IS $NAME$""")
    private val ONLY_CALL = Regex("""(?i)^(?:call|name|label|rename) (?:the )?(?:only )?(?:unnamed|unlabell?ed|nameless) (?:button|icon) (?:as |to )?$NAME$""")
    private val BESIDE_IS = Regex("""(?i)^(?:that )?(?:the )?$KIND (next to|beside|by|right of|left of|to the right of|to the left of|on the right of|on the left of|after|before) (?:the )?(.{2,30}?) $IS $NAME$""")

    private val LIST_A = Regex("""(?i)^(?:what|which) (?:button|icon) (?:names|labels) (?:do you|have you) (?:know|remember|learned|saved|kept)(?: (?:for|in|on) (?:the )?(.{2,30}?)(?: app)?)?$""")
    private val LIST_B = Regex("""(?i)^(?:list|read|show me|tell me|what are) (?:the |my )?(?:button|icon) (?:names|labels)(?: you (?:know|have|remember))?(?: (?:for|in|on) (?:the )?(.{2,30}?)(?: app)?)?$""")
    private val FORGET = Regex("""(?i)^(?:forget|clear|delete|erase|remove|reset) (all |every )?(?:the |my )?(?:button|icon) (?:names|labels)(?: (?:for|in|on|of) (?:the )?(.{2,30}?)(?: app)?)?$""")
    private val FORGET_LAST = Regex("""(?i)^(?:(?:forget|undo|remove|delete) (?:that|the last) button (?:name|label)|forget what i called that button)$""")

    private val ORDINALS = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10)
    private val NUMBER_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    private fun ordinal(word: String): LabelTarget.Nth? {
        val w = word.lowercase()
        if (w == "last") return LabelTarget.Nth(1, fromEnd = true)
        val n = ORDINALS[w] ?: w.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return if (n in 1..20) LabelTarget.Nth(n, fromEnd = false) else null
    }

    private fun sideOf(rel: String): String? {
        val r = rel.lowercase()
        return when {
            r.contains("right") || r == "after" -> "right"
            r.contains("left") || r == "before" -> "left"
            else -> null
        }
    }

    private fun anchorOf(raw: String): String =
        raw.trim().replace(Regex("""(?i)\s+(?:text )?(?:box|field|bar|area|button|icon)$"""), "").trim()

    private fun nameOf(raw: String): String = raw.trim().trim('"').replace(Regex("""(?i)^(?:called|named|as) """), "").trim()

    fun classify(raw: String): LabelCmd? {
        val g = norm(raw)
        if (g.isEmpty()) return null
        if (FORGET_LAST.matches(g)) return LabelCmd.ForgetLast
        FORGET.matchEntire(g)?.let { m ->
            val app = m.groupValues[2].trim().ifEmpty { null }
            return LabelCmd.Forget(app, all = app == null && m.groupValues[1].isNotBlank())
        }
        (LIST_A.matchEntire(g) ?: LIST_B.matchEntire(g))?.let { m -> return LabelCmd.ListNames(m.groupValues[1].trim().ifEmpty { null }) }
        RECENT_CALL.matchEntire(g)?.let { m -> return LabelCmd.Name(LabelTarget.Recent, nameOf(m.groupValues[1]), instruction = true) }
        RECENT_IS.matchEntire(g)?.let { m -> return LabelCmd.Name(LabelTarget.Recent, nameOf(m.groupValues[1]), instruction = false) }
        RECENT_WAS.matchEntire(g)?.let { m -> return LabelCmd.Name(LabelTarget.Recent, nameOf(m.groupValues[1]), instruction = false) }
        ONLY_IS.matchEntire(g)?.let { m -> return LabelCmd.Name(LabelTarget.Only, nameOf(m.groupValues[1]), instruction = false) }
        ONLY_CALL.matchEntire(g)?.let { m -> return LabelCmd.Name(LabelTarget.Only, nameOf(m.groupValues[1]), instruction = true) }
        BESIDE_IS.matchEntire(g)?.let { m ->
            return LabelCmd.Name(LabelTarget.Beside(anchorOf(m.groupValues[2]), sideOf(m.groupValues[1])), nameOf(m.groupValues[3]), instruction = false)
        }
        NTH_IS.matchEntire(g)?.let { m -> ordinal(m.groupValues[1])?.let { return LabelCmd.Name(it, nameOf(m.groupValues[2]), instruction = false) } }
        NTH_CALL.matchEntire(g)?.let { m -> ordinal(m.groupValues[1])?.let { return LabelCmd.Name(it, nameOf(m.groupValues[2]), instruction = true) } }
        NUM_IS.matchEntire(g)?.let { m ->
            val w = m.groupValues[1].lowercase()
            val n = NUMBER_WORDS[w] ?: w.toIntOrNull()
            if (n != null && n in 1..20) return LabelCmd.Name(LabelTarget.Nth(n, fromEnd = false), nameOf(m.groupValues[2]), instruction = false)
        }
        return null
    }
}
