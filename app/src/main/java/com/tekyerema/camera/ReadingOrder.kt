package com.tekyerema.camera

/**
 * Reading order for text found on a page (P5-03 (3), R-A7 — 13 October 2026). The old rule, "top to bottom, then left to
 * right", interleaves the lines of two columns: the left column's line 2 would be read after the right column's line 1.
 * This is the XY-cut in its simplest useful form, pure and unit-tested:
 *  - a block spanning most of the page width (a title, a full-width paragraph) is a BAND boundary: everything above it is
 *    finished before it, and it is finished before anything below it;
 *  - inside a band, blocks are grouped into COLUMNS by horizontal overlap (a block joins the column whose span it overlaps
 *    most; otherwise it starts a new one); columns are read left to right, each top to bottom.
 * A single-column notice with ragged indents is one column, so its order is unchanged; a two-column leaflet reads the left
 * column whole, then the right.
 */
object ReadingOrder {
    /** One recognised block of text with its box on the page (any unit, top-left origin). */
    data class Item(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(1)
        val centreY: Int get() = (top + bottom) / 2
    }

    /** The items in reading order. [pageWidth] = the page's width (the widest extent when unknown). */
    fun order(items: List<Item>, pageWidth: Int = 0, fullWidthFraction: Double = 0.62, minOverlap: Double = 0.4): List<Item> {
        if (items.size <= 1) return items
        val page = if (pageWidth > 0) pageWidth else (items.maxOf { it.right } - items.minOf { it.left }).coerceAtLeast(1)
        val byTop = items.sortedWith(compareBy({ it.top }, { it.left }))
        val out = ArrayList<Item>(items.size)
        val band = ArrayList<Item>()
        fun flush() { if (band.isNotEmpty()) { out += columns(band, minOverlap); band.clear() } }
        for (it in byTop) {
            if (it.width.toDouble() / page >= fullWidthFraction) { flush(); out += it } else band += it
        }
        flush()
        return out
    }

    /** Group a band's blocks into columns by horizontal overlap; columns left to right, each top to bottom. */
    private fun columns(band: List<Item>, minOverlap: Double): List<Item> {
        class Column(var left: Int, var right: Int, val items: ArrayList<Item> = ArrayList())
        val cols = ArrayList<Column>()
        for (it in band.sortedWith(compareBy({ it.top }, { it.left }))) {
            var best: Column? = null; var bestOverlap = 0.0
            for (c in cols) {
                val o = (minOf(c.right, it.right) - maxOf(c.left, it.left)).toDouble()
                val frac = o / minOf(it.width, (c.right - c.left).coerceAtLeast(1))
                if (frac > bestOverlap) { bestOverlap = frac; best = c }
            }
            if (best != null && bestOverlap >= minOverlap) {
                best.items += it; best.left = minOf(best.left, it.left); best.right = maxOf(best.right, it.right)
            } else cols += Column(it.left, it.right).also { c -> c.items += it }
        }
        return cols.sortedBy { it.left }.flatMap { c -> c.items.sortedWith(compareBy({ it.top }, { it.left })) }
    }

    /** The ordered items as one text, a block per line. */
    fun text(items: List<Item>, pageWidth: Int = 0): String = order(items, pageWidth).joinToString("\n") { it.text }.trim()
}
