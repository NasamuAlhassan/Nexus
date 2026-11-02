package com.tekyerema.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * The keyboard's own read-back voice (contract §3.4: a blind user always hears what is about to be typed). Two voices:
 *  - [playWav]: the Space's local voice, played from memory (a [MediaDataSource] — never written to a file);
 *  - [speakEnglish]: the phone's own English voice (TextToSpeech, Ghanaian English when the phone has it).
 * [onDone] is called on the main thread when the speech ends or fails, so the keyboard types right after it. Main thread only.
 */
class ReadBackVoice(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: Pair<String, () -> Unit>? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun playWav(wav: ByteArray, onDone: () -> Unit) {
        stop()
        val once = Once(onDone)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= wav.size) return -1
                    val n = minOf(size.toLong(), wav.size - position).toInt()
                    System.arraycopy(wav, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun getSize(): Long = wav.size.toLong()
                override fun close() {}
            })
            mp.setOnCompletionListener { release(mp); once() }
            mp.setOnErrorListener { _, _, _ -> release(mp); once(); true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (_: Throwable) { release(mp); once() }
    }

    fun speakEnglish(text: String, onDone: () -> Unit) {
        stop()
        val t = tts
        if (t == null) {
            pending = text to onDone
            tts = TextToSpeech(ctx.applicationContext) { status ->
                main.post {
                    ttsReady = status == TextToSpeech.SUCCESS
                    tts?.let { e ->
                        if (ttsReady) {
                            val gh = Locale("en", "GH")
                            e.language = if (e.isLanguageAvailable(gh) >= TextToSpeech.LANG_AVAILABLE) gh else Locale.UK
                        }
                    }
                    pending?.let { (txt, done) -> pending = null; say(txt, done) }
                }
            }
            return
        }
        say(text, onDone)
    }

    private fun say(text: String, onDone: () -> Unit) {
        val e = tts
        val once = Once(onDone)
        if (e == null || !ttsReady) { once(); return }
        e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { once() } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { once() } }
            override fun onError(id: String?, code: Int) { main.post { once() } }
        })
        if (e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kb-readback") != TextToSpeech.SUCCESS) once()
    }

    /** Stop any read-back (the field changed or the keyboard closed). A stopped read-back does not call its onDone. */
    fun stop() {
        player?.let { release(it) }
        pending = null
        try { tts?.stop() } catch (_: Throwable) {}
    }

    fun release() {
        stop()
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null; ttsReady = false
    }

    private fun release(mp: MediaPlayer) {
        if (player === mp) player = null
        try { mp.reset(); mp.release() } catch (_: Throwable) {}
    }

    private class Once(private val f: () -> Unit) { private var done = false; operator fun invoke() { if (!done) { done = true; f() } } }
}
