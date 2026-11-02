package com.tekyerema.interop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * The share-sheet target ("Tɛkyerɛma") and the text-selection action ("Read with Tɛkyerɛma") — research note §1.6.
 *
 * Exported because other apps start it, so everything it receives is untrusted: it accepts text/plain only, keeps the
 * text as CONTENT in [SharedInbox] (memory only, 15 minutes), reads it aloud and offers what the user can ask about it,
 * then closes. It starts no task, answers no confirmation and runs no tool — whatever the text says, it stays text
 * (ManifestHardeningTest and InteropArchitectureTest pin that). For PROCESS_TEXT it never returns a result, so the
 * other app's text is never changed.
 */
class SharedTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { receive(intent) } catch (_: Throwable) {}
        finish()   // Theme.NoDisplay: must finish before onResume
    }

    private fun receive(i: Intent?) {
        i ?: return
        val shared = SharedInbox.parse(i.action, i.type, i.getCharSequenceExtra(Intent.EXTRA_TEXT),
            i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT), i.getCharSequenceExtra(Intent.EXTRA_SUBJECT), sourceApp(),
            System.currentTimeMillis())
        if (shared == null) {
            say("There was no text in that for me to read.")
            return
        }
        SharedInbox.put(shared)
        say(SharedInbox.spoken(shared))
    }

    /** The sending app's name, for "from Chrome" — PROCESS_TEXT is started for a result (callingPackage), a share names
     *  itself as the referrer. A label only, never used to decide anything. */
    private fun sourceApp(): String? {
        val pkg = callingPackage ?: try { referrer?.takeIf { it.scheme == "android-app" }?.host } catch (_: Throwable) { null }
        pkg ?: return null
        if (pkg == packageName) return null
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { null }
    }

    /** Spoken when replies are read aloud; a short toast either way so a screen reader user hears that it arrived. */
    private fun say(text: String) {
        com.tekyerema.voice.VoiceEngine.announce(text)
        Toast.makeText(applicationContext, "Nexus has the text — ask me about it.", Toast.LENGTH_SHORT).show()
    }
}
