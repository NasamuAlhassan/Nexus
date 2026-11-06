package com.tekyerema.accessibility

/**
 * List rows the app draws itself and never names to accessibility. Telegram's chat list (13.1.1 on the A20e, 6 Nov 2026): seven
 * rows in the tree as focusable ViewGroups with no text, no description and no click, so the agent saw a screen with no chats
 * on it. Asked about Richmond, whose chat was right there, the brain tapped the "All Chats" tab three times; that is why the
 * owner kept seeing it search for someone already on the screen.
 *
 * The tree still says WHERE the rows are; on-device OCR says what they read. One row becomes one tappable element, as if the
 * app had named it ("Richmond Apenensu, 10:42, Ok see you"), instead of a scatter of loose text lines. Pure → BlindRowsTest.
 */
object BlindRows {
    /** Fewer than this is not a list: a stray blank box must not cost a screenshot. */
    const val MIN_ROWS = 3

    /** Row-sized: most of the screen's width, and no taller than a quarter of it. */
    fun isRow(b: Box, screenW: Int, screenH: Int): Boolean =
        !b.isEmpty() && b.width * 10 >= screenW * 6 && b.height >= 40 && b.height * 4 <= screenH

    /** Lines as a person reads them: top line first, left to right within a line (a name, then its time). */
    internal fun readingOrder(lines: List<UiElement>): List<UiElement> {
        val out = ArrayList<UiElement>()
        var rest = lines.sortedBy { it.bounds.centerY }
        while (rest.isNotEmpty()) {
            val top = rest.first().bounds
            val (same, below) = rest.partition { kotlin.math.abs(it.bounds.centerY - top.centerY) * 2 <= top.height }
            out += same.sortedBy { it.bounds.left }
            rest = below
        }
        return out
    }

    /** A letter or two at the row's left edge is the avatar's initials, not the row's name. */
    private fun avatarInitials(line: UiElement, row: Box): Boolean =
        line.bounds.right * 100 <= row.left * 100 + row.width * 18 && (line.label?.trim()?.length ?: 0) <= 3

    /**
     * @return the rows that read something, as tappable elements numbered from [startId], and the OCR lines outside every row.
     * A row with no text is dropped: there is nothing to tell the brain about it.
     */
    fun merge(rows: List<Box>, ocr: List<UiElement>, startId: Int): Pair<List<UiElement>, List<UiElement>> {
        val used = HashSet<UiElement>()
        var id = startId
        val named = rows.sortedBy { it.top }.mapNotNull { row ->
            val inside = ocr.filter { row.contains(it.bounds.centerX, it.bounds.centerY) }
            used += inside
            val words = readingOrder(inside.filter { !avatarInitials(it, row) }).mapNotNull { it.label?.trim()?.takeIf { l -> l.isNotEmpty() } }
            if (words.isEmpty()) null
            else UiElement(id = id++, role = "row", label = words.joinToString(", "), bounds = row, source = ElementSource.VISION, clickable = true)
        }
        return named to ocr.filterNot { it in used }
    }
}
