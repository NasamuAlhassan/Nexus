package com.tekyerema.camera

/**
 * Counting SEVERAL notes and coins with the cedi model on the phone, one crop per item (27 Oct 2026: three cloud reads of one
 * 50 cedi note and a coin gave the coin as 2, 1 and 0.5 cedis and the app refused to answer, and offline it could name one
 * note at a time only). Each item is read by itself and kept in the total only when its class clears that class's own gate
 * ([CurrencyAnnouncer.gateFor]); an item that does not is counted as "not read", never guessed. A total is never spoken over a
 * wrong item: it is "for the ones I read". Pure → MoneyItemsTest.
 */
object MoneyItems {
    /** One item's class from the model and its confidence. */
    data class Reading(val cls: String, val conf: Double)

    /** A note or coin's value in cedis. */
    data class Item(val value: Double, val note: Boolean)

    /** What the frame holds: items read for sure, items seen but not read, and boxes that are not money at all. */
    data class Count(val sure: List<Item>, val unsure: Int, val ignored: Int) {
        val total: Double get() = sure.sumOf { it.value }
        /** Money seen, read or not. */
        val seen: Int get() = sure.size + unsure
        /** Stable across frames: the items read, whatever order they came in. */
        val signature: String get() = sure.map { "${it.value}${if (it.note) "n" else "c"}" }.sorted().joinToString(",")
    }

    /** A box the model calls "not money" with at least this confidence is a shadow, a hand or a table edge — not an unread item. */
    const val NOT_MONEY_SURE = 0.5

    /** "note_50" → 50 cedis note, "coin_20_pesewas" → 0.2 coin, "coin_2_cedis" → 2 coin; null for "not_money" and anything unknown. */
    fun itemOf(cls: String): Item? {
        val p = cls.split('_')
        if (p.size < 2) return null
        val n = p[1].toDoubleOrNull() ?: return null
        return when (p[0]) {
            "note" -> Item(n, true)
            "coin" -> Item(if (p.getOrNull(2)?.startsWith("pesewa") == true) n / 100.0 else n, false)
            else -> null
        }
    }

    /** The item for [r] when it clears its class's gate, else null. */
    fun sureItem(r: Reading, base: Double = 0.85): Item? =
        itemOf(r.cls)?.takeIf { r.conf >= CurrencyAnnouncer.gateFor(r.cls, base) }

    /**
     * One piece read in the four quarter-turns of its crop (a note is held any way up). 27 Oct 2026: a 50 cedis note was read as 200.
     * Sure only when at least two turns give the same item and no turn gives a different one; otherwise null — the cloud's value stays.
     */
    fun consensus(turns: List<Reading>, base: Double = 0.85): Item? {
        val items = turns.mapNotNull { sureItem(it, base) }
        val first = items.firstOrNull() ?: return null
        val need = if (turns.size >= 8) 3 else 2     // two crops (tight and wide) in four turns each: three readings must agree
        return first.takeIf { items.size >= need && items.all { it == first } }
    }

    /**
     * The live single-item reading checked in the other three quarter-turns (a note is held any way up; 27 Oct 2026: a held 50 was
     * said as 200). A reading that is sure keeps its confidence only when at least two of the three other turns give the same item
     * and none gives a different one; otherwise its confidence drops to 0 and nothing is announced. Readings that were not sure to
     * begin with are returned unchanged.
     */
    fun crossChecked(first: Reading, others: List<Reading>, base: Double = 0.85): Reading {
        val item = sureItem(first, base) ?: return first
        val sure = others.mapNotNull { sureItem(it, base) }
        val agree = sure.count { it == item }
        return if (sure.any { it != item } || agree < 2) Reading(first.cls, 0.0) else first
    }

    /** Sort the readings of a frame's boxes into read, not read, and not money. */
    fun count(readings: List<Reading>, base: Double = 0.85): Count {
        val sure = ArrayList<Item>()
        var unsure = 0
        var ignored = 0
        for (r in readings) {
            val item = sureItem(r, base)
            when {
                item != null -> sure.add(item)
                r.cls == CurrencyAnnouncer.NOT_MONEY && r.conf >= NOT_MONEY_SURE -> ignored++
                else -> unsure++
            }
        }
        return Count(sure, unsure, ignored)
    }

    // ------------------------------------------------------------------------------------------------------------ wording

    private val COUNT = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.US, v)

    /** "a 50 cedis note", "two 10 cedis notes", "a 20 pesewas coin", "a 1 cedi coin". */
    private fun phrase(item: Item, n: Int): String {
        val unit = if (item.value < 1) "${Math.round(item.value * 100)} pesewas" else if (item.value == 1.0) "1 cedi" else "${fmt(item.value)} cedis"
        val what = "$unit ${if (item.note) "note" else "coin"}"
        return if (n == 1) "a $what" else "${COUNT.getOrElse(n) { n.toString() }} ${what}s"
    }

    private fun join(parts: List<String>): String = when (parts.size) {
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    private fun spokenTotal(total: Double): String {
        val cedis = Math.floor(total + 1e-9).toLong()
        val pesewas = Math.round((total - cedis) * 100)
        return when {
            cedis == 0L -> "$pesewas pesewas"
            pesewas == 0L -> "$cedis ${if (cedis == 1L) "cedi" else "cedis"}"
            else -> "$cedis ${if (cedis == 1L) "cedi" else "cedis"} $pesewas pesewas"
        }
    }

    /** The unsure part, said the same for notes and coins. */
    fun unsureLine(unsure: Int): String = when (unsure) {
        0 -> ""
        1 -> "I could not read one item — put it on its own, flat, and closer."
        else -> "I could not read $unsure items — spread them out, flat, and closer."
    }

    /** What the phone says for [c]: what it read, the total of those, and plainly what it did not read. Null with nothing seen. */
    fun spoken(c: Count): String? {
        if (c.seen == 0) return null
        if (c.sure.isEmpty()) return "I can see ${if (c.unsure == 1) "one item" else "${c.unsure} items"} but I could not read " +
            "${if (c.unsure == 1) "it" else "any of them"}. Spread them out on a plain surface, flat, a hand away."
        val parts = c.sure.groupingBy { it }.eachCount().entries.sortedByDescending { it.key.value }.map { (item, n) -> phrase(item, n) }
        val read = "I read " + join(parts) + "."
        return if (c.unsure == 0) "$read That's ${spokenTotal(c.total)} in all."
        else "$read That's ${spokenTotal(c.total)} for the ones I read. ${unsureLine(c.unsure)}"
    }

    // ------------------------------------------------------------------------------------------------------------ Twi
    // Short lines the Twi voice can say with no translator and no internet: the figures are Twi words (TwiSpeechText), the rest is
    // fixed. [owner to listen: every Twi line here, written by the agent, not by a speaker]

    private fun twiAmount(v: Double): String =
        com.tekyerema.voice.understand.TwiSpeechText.cedis("%.2f".format(java.util.Locale.US, v)) ?: ""

    private fun twiItem(item: Item): String = twiAmount(item.value) + if (item.note) " krataa" else " sika dadeɛ"

    /** The Twi line for [c]; null with nothing seen. Counts above one are said by repeating the item. */
    fun spokenTwi(c: Count): String? {
        if (c.seen == 0) return null
        if (c.sure.isEmpty()) return "Mahu sika, nanso mintumi nkenkan. Fa no si fam, baako baako."
        val items = c.sure.sortedByDescending { it.value }.joinToString(" ne ") { twiItem(it) }
        val total = "Nyinaa yɛ ${twiAmount(c.total)}."
        return if (c.unsure == 0) "Mahu $items. $total"
        else "Mahu $items. $total Baako manhu no yiye."
    }

    /** One denomination as the single-item reader says it in Twi ("sidi aduonum krataa"); null when [cls] is not money. */
    fun spokenTwiOf(cls: String): String? = itemOf(cls)?.let { twiItem(it) + "." }

    /** The "I see money but cannot tell which" coaching, for notes and coins alike. */
    const val UNSURE_EN = "I can see money but I can't tell which one. Put the note or coin flat on a plain surface, a hand away, with all of it in view."
    const val UNSURE_TW = "Mahu sika, nanso minhu sɛ ɛyɛ dɛn. Fa no si fam, nsa baako kwan."
}

/**
 * Said once per steady set of readings: the same items read on [agreeFrames] frames in a row, and not again for [repeatMs]
 * while that set stays the same (counting cash note after note must speak each new set). Pure → MoneyItemsTest.
 */
class ItemsAnnouncer(private val agreeFrames: Int = 2, private val repeatMs: Long = 6_000L) {
    private var run: String? = null
    private var runLength = 0
    private var spoken: String? = null
    private var spokenAt = 0L

    /** The count to speak now, or null (nothing read yet, still settling, or just said). */
    fun offer(c: MoneyItems.Count, nowMs: Long): MoneyItems.Count? {
        if (c.sure.isEmpty()) { run = null; runLength = 0; return null }
        val sig = c.signature
        if (sig == run) runLength++ else { run = sig; runLength = 1 }
        if (runLength < agreeFrames) return null
        if (sig == spoken && nowMs - spokenAt < repeatMs) return null
        spoken = sig
        spokenAt = nowMs
        return c
    }
}
