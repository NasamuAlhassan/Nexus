package com.tekyerema

import com.tekyerema.camera.DocxText
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-03 (2): a Word file's words, page by page, with no library. */
class DocxTextTest {
    private fun p(vararg runs: String) = "<w:p w14:paraId=\"1\">" + runs.joinToString("") + "</w:p>"
    private fun t(s: String) = "<w:r><w:t xml:space=\"preserve\">$s</w:t></w:r>"
    private fun doc(body: String) = "<?xml version=\"1.0\"?><w:document xmlns:w=\"x\"><w:body>$body<w:sectPr/></w:body></w:document>"

    @Test fun paragraphs_become_lines_and_runs_join() {
        val xml = doc(p(t("Notice to "), t("all students")) + p(t("The library closes at 6 pm.")) + "<w:p/>")
        assertEquals(listOf("Notice to all students\nThe library closes at 6 pm."), DocxText.pagesOf(xml, 1800))
    }

    @Test fun tabs_breaks_and_entities_are_text() {
        val xml = doc(p(t("Name:"), "<w:r><w:tab/></w:r>", t("Kofi &amp; Ama"), "<w:r><w:br/></w:r>", t("&#8220;Akwaaba&#8221; &lt;3")))
        assertEquals(listOf("Name: Kofi & Ama\n“Akwaaba” <3"), DocxText.pagesOf(xml, 1800))
    }

    @Test fun a_page_break_starts_a_new_page_once_even_when_word_wrote_it_twice() {
        val xml = doc(p(t("Page one.")) + p("<w:r><w:br w:type=\"page\"/></w:r>") + p("<w:r><w:lastRenderedPageBreak/></w:r>", t("Page two.")) + p(t("Still two.")))
        assertEquals(listOf("Page one.", "Page two.\nStill two."), DocxText.pagesOf(xml, 1800))
    }

    @Test fun a_long_document_without_breaks_is_cut_into_pages_at_paragraph_boundaries() {
        val para = "x".repeat(400)
        val xml = doc((1..10).joinToString("") { p(t("$it $para")) })
        val pages = DocxText.pagesOf(xml, 1000)
        assertEquals(5, pages.size)
        assertTrue(pages.all { it.length in 800..810 })   // two paragraphs of 402 + the newline each
        assertTrue(pages[0].startsWith("1 ") && pages[4].startsWith("9 "))
    }

    @Test fun nothing_to_read_is_no_pages() {
        assertEquals(emptyList<String>(), DocxText.pagesOf(doc("<w:p/><w:p><w:r><w:tab/></w:r></w:p>"), 1800))
    }

    @Test fun a_real_zip_is_read_and_recognised_while_a_bare_zip_or_a_text_file_is_not() {
        val docx = File.createTempFile("notice", ".docx").apply { deleteOnExit() }
        ZipOutputStream(FileOutputStream(docx)).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<Types/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("word/header1.xml")); z.write(doc(p(t("UNIVERSITY OF GHANA"))).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(doc(p(t("Dear Kofi,")) + p(t("See you Monday."))).toByteArray()); z.closeEntry()
        }
        assertTrue(DocxText.looksLikeDocx(docx))
        assertEquals(listOf("Dear Kofi,\nSee you Monday."), DocxText.pages(docx))   // the header part is never read
        val zip = File.createTempFile("plain", ".zip").apply { deleteOnExit() }
        ZipOutputStream(FileOutputStream(zip)).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write("hi".toByteArray()); z.closeEntry() }
        assertFalse(DocxText.looksLikeDocx(zip))
        val txt = File.createTempFile("plain", ".txt").apply { deleteOnExit(); writeText("PK is not a zip") }
        assertFalse(DocxText.looksLikeDocx(txt))
    }

    @Test fun entities_unescape() {
        assertEquals("a & b < c > \"d\" 'e' é", DocxText.unescape("a &amp; b &lt; c &gt; &quot;d&quot; &apos;e&apos; &#xE9;"))
    }
}
