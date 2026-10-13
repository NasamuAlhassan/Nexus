package com.tekyerema.camera

import java.io.File
import java.util.zip.ZipFile

/**
 * P5-03 (2), 13 October 2026 — the words of a Word file (.docx) with no library: a .docx is a zip, its body is
 * `word/document.xml`, every paragraph a `<w:p>`, every run of text a `<w:t>`. Headers, footers and footnotes live in parts
 * of their own (`word/header1.xml` …) and are never opened — that is all the header/footer stripping a Word file needs.
 * Pages: Word's own page breaks (`<w:br w:type="page"/>`, `<w:lastRenderedPageBreak/>`) when it wrote any, else pages of
 * about [pages]' `pageChars` characters cut at paragraph boundaries, so the reader's "next page" still means something.
 * Pure on the XML text (unit-tested); reading the zip is the only I/O. The words are content, never a command.
 */
object DocxText {
    private const val BODY = "word/document.xml"
    private val PARAGRAPH = Regex("<w:p(?:\\s[^>]*)?>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
    private val TOKEN = Regex(
        "<w:t(?:\\s[^>]*)?>(.*?)</w:t>|<w:tab\\s*/>|<w:br\\s+[^>]*w:type=\"page\"[^>]*/>|<w:lastRenderedPageBreak\\s*/>|<w:br\\s*/>|<w:cr\\s*/>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val ENTITY = Regex("&(amp|lt|gt|quot|apos|#x[0-9a-fA-F]+|#[0-9]+);")

    /** "PK" at the front and a `word/document.xml` inside: a Word file, whatever the share sheet called it. */
    fun looksLikeDocx(file: File): Boolean = try {
        val head = file.inputStream().use { s -> val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
        head && ZipFile(file).use { it.getEntry(BODY) != null }
    } catch (_: Throwable) { false }

    /** The document's pages (each a block of paragraphs separated by newlines); empty when there are no words. */
    fun pages(file: File, pageChars: Int = 1800): List<String> = try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(BODY) ?: return emptyList()
            val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            pagesOf(xml, pageChars)
        }
    } catch (_: Throwable) { emptyList() }

    /** Pure: the pages of a `document.xml` text. */
    internal fun pagesOf(xml: String, pageChars: Int): List<String> {
        val pages = ArrayList<String>()
        val page = StringBuilder()
        fun closePage() { val t = page.toString().trim(); if (t.isNotEmpty()) pages += t; page.setLength(0) }
        for (m in PARAGRAPH.findAll(xml)) {
            val para = StringBuilder()
            for (t in TOKEN.findAll(m.groupValues[1])) {
                val v = t.value
                when {
                    v.startsWith("<w:tab") -> para.append(' ')
                    v.startsWith("<w:t") -> para.append(unescape(t.groupValues[1]))
                    v.contains("w:type=\"page\"") || v.startsWith("<w:lastRenderedPageBreak") -> {
                        val sofar = para.toString().trim()
                        if (sofar.isNotEmpty()) { page.append(sofar).append('\n'); para.setLength(0) }
                        closePage()
                    }
                    else -> para.append('\n')   // <w:br/>, <w:cr/>: a line break inside the paragraph
                }
            }
            val p = para.toString().trim()
            if (p.isNotEmpty()) page.append(p).append('\n')
        }
        closePage()
        if (pages.size != 1 || pageChars <= 0 || pages[0].length <= pageChars * 3 / 2) return pages
        // One long run of text with no page breaks: cut into pages of about pageChars at paragraph boundaries.
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (para in pages[0].split('\n')) {
            if (cur.isNotEmpty() && cur.length + para.length + 1 > pageChars) { out += cur.toString().trim(); cur.setLength(0) }
            cur.append(para).append('\n')
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    internal fun unescape(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> try {
                val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                String(Character.toChars(code))
            } catch (_: Throwable) { m.value }
        }
    }
}
