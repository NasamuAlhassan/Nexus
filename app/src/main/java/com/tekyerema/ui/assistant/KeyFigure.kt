package com.tekyerema.ui.assistant

/**
 * The one figure an answer is about — an amount, a time, a percentage, a phone number — so the card can show it in large type
 * above the sentence (design round 2, 2 Nov 2026: a low-vision user reads "GH₵ 120.50" at a glance, without waiting for the
 * voice or reading a sentence in small print).
 *
 * Honest by construction: when it is not clear WHICH figure the answer is about, there is none. Two amounts with neither after
 * "balance" → none; two times → none. A wrong number in giant type is worse than no big number. Pure, unit-tested.
 */
object KeyFigure {
    enum class Kind { MONEY, TIME, PERCENT, PHONE }
    data class Figure(val kind: Kind, val shown: String)

    /** Long answers are about more than one thing; the big figure is for short, direct ones. */
    const val MAX_ANSWER_CHARS = 280

    private const val NUM = "\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?"
    private val MONEY_BEFORE = Regex("(?:GH₵|GH¢|GHS|GHC|₵|¢)\\s?($NUM)", RegexOption.IGNORE_CASE)
    private val MONEY_AFTER = Regex("\\b($NUM)\\s?(?:Ghana\\s)?(?:cedis?|GHS)\\b", RegexOption.IGNORE_CASE)
    private val TIME = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\s?(a\\.?m\\.?|p\\.?m\\.?)?(?![\\w:])", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("\\b(\\d{1,3}(?:\\.\\d)?)\\s?(?:%|per ?cent\\b)", RegexOption.IGNORE_CASE)
    // Ghana numbers: 0XX XXX XXXX (10 digits) or +233 XX XXX XXXX, with spaces or dashes; not inside a longer digit run.
    private val PHONE = Regex("(?<![\\d+])(?:\\+233[\\s-]?|0)(\\d{2})[\\s-]?(\\d{3})[\\s-]?(\\d{4})(?!\\d)")
    private val BALANCE = Regex("\\bbalance\\b", RegexOption.IGNORE_CASE)

    fun of(answer: String): Figure? {
        val t = answer.trim()
        if (t.isEmpty() || t.length > MAX_ANSWER_CHARS) return null
        money(t)?.let { return it }
        // A sentence with an amount we could not choose between says nothing big, rather than show a time or a number from it.
        if (moneyCount(t) > 1) return null
        single(TIME.findAll(t).map { m -> time(m) }.toList())?.let { return Figure(Kind.TIME, it) }
        single(PERCENT.findAll(t).map { m -> "${m.groupValues[1]}%" }.toList())?.let { return Figure(Kind.PERCENT, it) }
        single(PHONE.findAll(t).map { m -> "0${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}" }.toList())?.let {
            return Figure(Kind.PHONE, it)
        }
        return null
    }

    private data class Amount(val start: Int, val value: String)

    private fun amounts(t: String): List<Amount> {
        val list = (MONEY_BEFORE.findAll(t) + MONEY_AFTER.findAll(t)).map { Amount(it.range.first, it.groupValues[1]) }
            .sortedBy { it.start }.toList()
        // "GHS 50 cedis" would match both patterns at nearly the same place: keep one.
        return list.fold(mutableListOf()) { acc, a -> if (acc.lastOrNull()?.let { a.start - it.start < 8 && it.value == a.value } == true) acc else acc.apply { add(a) } }
    }

    private fun moneyCount(t: String) = amounts(t).map { it.value.replace(",", "") }.distinct().size

    private fun money(t: String): Figure? {
        val all = amounts(t)
        if (all.isEmpty()) return null
        val distinct = all.map { it.value.replace(",", "") }.distinct()
        val pick = if (distinct.size == 1) all.first() else {
            // Several amounts: the one right after the word "balance" ("Your balance is GHS 120.50; you sent GHS 20") — or none.
            val b = BALANCE.findAll(t).map { it.range.last }.toList()
            all.firstOrNull { a -> b.any { a.start > it && a.start - it <= 24 } } ?: return null
        }
        return Figure(Kind.MONEY, "GH₵ ${pick.value}")
    }

    private fun time(m: MatchResult): String {
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2]
        val ampm = m.groupValues[3].lowercase().replace(".", "")
        return when {
            ampm.isNotEmpty() -> "$h:$min ${ampm.uppercase()}"
            else -> "${m.groupValues[1]}:$min"
        }
    }

    private fun single(found: List<String>): String? = found.distinct().singleOrNull()
}
