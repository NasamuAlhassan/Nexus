package com.tekyerema.interop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.tekyerema.camera.DocumentReadActivity
import com.tekyerema.camera.DocumentReader
import java.io.File

/**
 * The share sheet's / "Open with" Tɛkyerɛma for a PDF (P5-03 v0): WhatsApp, Files, a browser or Drive hand us a document and
 * it is read aloud page by page. The file is copied into the app's own files at once (the sender's grant dies with this
 * activity), checked to BE a PDF by its first bytes (the sheet's type is a claim), and handed to [DocumentReadActivity]. It is
 * content, never a command — nothing in it starts a task or answers a confirmation. Exported because other apps start it.
 */
class SharedDocumentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { receive(intent) } catch (_: Throwable) { say("I couldn't open that document.") }
        finish()   // Theme.NoDisplay: must finish before onResume
    }

    private fun receive(i: Intent?) {
        val stream: Uri? = when (i?.action) {
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (stream == null) {
            Log.i("Tekyerema", "[DOC] share refused: action=${i?.action} type=${i?.type}")   // no content
            say("There was no document in that for me to read."); return
        }
        val copy = copyIn(stream)
        Log.i("Tekyerema", "[DOC] share type=${i?.type} scheme=${stream.scheme} copied=${copy != null}")
        if (copy == null) { say("I couldn't open that document."); return }
        startActivity(DocumentReadActivity.intent(this, copy))
    }

    /** A private copy under files/documents (replaced on the next import); null unless its bytes are a PDF or a Word file
     *  (P5-03 (2)): the copy is named by what it IS, not by what the sender called it. */
    private fun copyIn(uri: Uri): File? = try {
        val dir = File(filesDir, "documents").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "shared-${System.currentTimeMillis()}.bin")
        val input = contentResolver.openInputStream(uri)
        if (input == null) null else {
            input.use { src -> f.outputStream().use { src.copyTo(it) } }
            val kind = if (f.length() == 0L) null else com.tekyerema.camera.Documents.kindOf(f)
            if (kind == null) { f.delete(); null } else File(dir, f.nameWithoutExtension + "." + kind).also { f.renameTo(it) }
        }
    } catch (t: Throwable) {
        Log.w("Tekyerema", "[DOC] copy failed: ${t.javaClass.simpleName}"); null
    }

    private fun say(line: String) { try { Toast.makeText(this, line, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }
}
