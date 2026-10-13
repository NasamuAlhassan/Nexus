package com.tekyerema.camera

/**
 * P5-03 (2), 13 October 2026 — the furniture of a scanned or printed page that a listener does not want read out: a bare
 * page number at the top or bottom ("3", "- 3 -", "Page 3 of 10", "3/10") and a running header or footer, recognised as
 * the first or last line that the previous page also had. Pure, one page at a time (the reader prepares pages in order);
 * a page is never emptied, and a one-line page is never touched.
 */
object PageFurniture {
    private val PAGE_NUMBER = Regex(
        "^\\s*(?:[-–—]\\s*)?(?:page\\s+)?\\d{1,4}(?:\\s*(?:of|/)\\s*\\d{1,4})?(?:\\s*[-–—])?\\s*$", RegexOption.IGNORE_CASE)

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ")

    /** [text] with its page furniture removed; [previous] = the previous page's text as read (before stripping), or null. */
    fun strip(text: String, previous: String?): String {
        val lines = text.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }.toMutableList()
        if (lines.size < 2) return text
        if (PAGE_NUMBER.matches(lines.first())) lines.removeAt(0)
        if (lines.size > 1 && PAGE_NUMBER.matches(lines.last())) lines.removeAt(lines.size - 1)
        val prev = previous?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (prev.isNotEmpty() && lines.size > 1 && norm(lines.first()) == norm(prev.first())) lines.removeAt(0)
        if (prev.size > 1 && lines.size > 1 && norm(lines.last()) == norm(prev.last())) lines.removeAt(lines.size - 1)
        val out = lines.joinToString("\n").trim()
        return if (out.isEmpty()) text else out
    }
}
