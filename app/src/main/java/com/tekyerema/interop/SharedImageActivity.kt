package com.tekyerema.interop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.tekyerema.camera.CameraReadActivity
import com.tekyerema.camera.ImportedImage

/**
 * The share sheet's "Tɛkyerɛma" for a PICTURE (mission P5-02): WhatsApp, Photos, Files or a browser hand us an image and it
 * is read aloud the way the camera reads — on-device OCR first; describe on request. The picture is copied into the app's
 * cache at once (the sender's grant dies with this activity) and shown to [CameraReadActivity]; it is CONTENT, never a
 * command — nothing in it starts a task or answers a confirmation. Exported because other apps start it; it trusts nothing
 * it receives beyond "an image stream".
 */
class SharedImageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { receive(intent) } catch (_: Throwable) { say("I couldn't open that picture.") }
        finish()   // Theme.NoDisplay: must finish before onResume
    }

    private fun receive(i: Intent?) {
        val type = i?.type.orEmpty()
        val stream: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i?.getParcelableExtra(Intent.EXTRA_STREAM)
        if (i?.action != Intent.ACTION_SEND || !type.startsWith("image/") || stream == null) {
            android.util.Log.i("Tekyerema", "[IMPORT] share refused: action=${i?.action} type=$type stream=${stream != null}")   // no content
            say("There was no picture in that for me to read.")
            return
        }
        val copy = ImportedImage.copyIn(this, stream)
        // One line per share, no content: the Toast was the only feedback and left a failed import undiagnosable (13 Oct 2026).
        android.util.Log.i("Tekyerema", "[IMPORT] share type=$type scheme=${stream.scheme} copied=${copy != null}")
        if (copy == null) { say("I couldn't open that picture."); return }
        startActivity(CameraReadActivity.imageIntent(this, copy, CameraReadActivity.MODE_TEXT))
    }

    private fun say(line: String) { try { Toast.makeText(this, line, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }
}
