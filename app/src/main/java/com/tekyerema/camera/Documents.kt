package com.tekyerema.camera

import android.graphics.Bitmap
import java.io.File

/**
 * A document the reader can page through (P5-03 (2), 13 October 2026): a PDF, drawn page by page and read by OCR
 * ([DocumentReader]), or a Word file, whose own words are the pages ([DocxDocument]). The reader screen treats both the
 * same way: a page is shown (the picture, or the words themselves), spoken in chunks, and the next one prepared meanwhile.
 */
interface ReadableDocument : AutoCloseable {
    val pageCount: Int
    /** True when a page is a picture to show (PDF); false when the words are all there is (Word). */
    val hasPictures: Boolean
    /** The page drawn, longest side [maxSide] px — null for a document with no pictures. */
    fun render(index: Int, maxSide: Int): Bitmap?
    /** The page's words; [bmp] is the drawn page when there is one. */
    suspend fun text(index: Int, bmp: Bitmap?): String
}

/** A .docx: its paragraphs in pages at Word's page breaks, else pages of about [pageChars] characters. */
class DocxDocument(file: File, pageChars: Int) : ReadableDocument {
    private val pages: List<String> = DocxText.pages(file, pageChars)
    override val pageCount: Int get() = pages.size
    override val hasPictures: Boolean get() = false
    override fun render(index: Int, maxSide: Int): Bitmap? = null
    override suspend fun text(index: Int, bmp: Bitmap?): String = pages.getOrNull(index) ?: ""
    override fun close() {}
}

object Documents {
    /** "pdf" or "docx" by the file's own bytes (the share sheet's type is a claim), else null. */
    fun kindOf(file: File): String? = when {
        DocumentReader.looksLikePdf(file) -> "pdf"
        DocxText.looksLikeDocx(file) -> "docx"
        else -> null
    }

    fun looksLikeDocument(file: File): Boolean = kindOf(file) != null

    fun open(file: File): ReadableDocument? = when (kindOf(file)) {
        "pdf" -> DocumentReader(file)
        "docx" -> DocxDocument(file, try { com.tekyerema.core.config.AppConfig.int("document.pageChars", 1800) } catch (_: Throwable) { 1800 })
        else -> null
    }
}
