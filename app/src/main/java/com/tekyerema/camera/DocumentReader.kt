package com.tekyerema.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * P5-03 v0 (D-013, 13 October 2026): a PDF read page by page with NO new dependency — the platform `PdfRenderer` draws each
 * page onto a white bitmap and the bundled ML Kit recogniser reads it, scanned or born-digital alike (there is no platform
 * text API below Android 15, and `androidx.pdf`'s text methods throw there). One page bitmap lives at a time; the text of a
 * page is kept only while it is being read. The words are content, never a command: nothing here reaches the agent.
 */
class DocumentReader(file: File) : ReadableDocument {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override val pageCount: Int get() = renderer.pageCount
    override val hasPictures: Boolean get() = true

    /** The page's words from its drawn picture (a PDF page without one has nothing to read). */
    override suspend fun text(index: Int, bmp: Bitmap?): String = if (bmp == null) "" else text(bmp)

    /** Page [index] (0-based) drawn on white, longest side [maxSide] px (ML Kit wants ≥16 px per character; 2,000 is plenty). */
    override fun render(index: Int, maxSide: Int): Bitmap = synchronized(renderer) {
        val page = renderer.openPage(index)
        try {
            val scale = maxSide.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)   // a transparent page would read black on black
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally { page.close() }
    }

    /** The page's words in reading order (top to bottom, then left to right); "" when nothing was found or OCR failed. */
    suspend fun text(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r -> if (cont.isActive) cont.resume(ImportedImage.readingOrder(r.textBlocks)) }
                .addOnFailureListener { if (cont.isActive) cont.resume("") }
        } catch (_: Throwable) { if (cont.isActive) cont.resume("") }
    }

    override fun close() {
        try { renderer.close() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
    }

    companion object {
        /** The first bytes of a PDF — the only thing that makes a file "a document" here (the share sheet's type is a claim). */
        fun looksLikePdf(file: File): Boolean = try {
            file.inputStream().use { s -> val b = ByteArray(5); s.read(b) == 5 && String(b, Charsets.ISO_8859_1) == "%PDF-" }
        } catch (_: Throwable) { false }
    }
}

/** What the document reader says around the words — pure, unit-tested. */
object DocumentLines {
    /** "A document with 2 pages. Page 1." / "A one-page document." */
    fun intro(pages: Int): String = if (pages <= 1) "A one-page document." else "A document with $pages pages. Page 1."
    /** Said after a page's words. */
    fun pageEnd(index: Int, pages: Int): String = when {
        pages <= 1 -> "That's the whole document. Tap to hear it again, or press back to leave."
        index + 1 >= pages -> "That was the last page, ${index + 1} of $pages. Press back to leave."
        else -> "End of page ${index + 1} of $pages. Tap for the next page."
    }
    /** Said when a page has no readable text. */
    fun empty(index: Int, pages: Int): String =
        if (pages <= 1) "I couldn't find any text in this document." else "I couldn't find any text on page ${index + 1}."
    fun pageStart(index: Int): String = "Page ${index + 1}."
}
