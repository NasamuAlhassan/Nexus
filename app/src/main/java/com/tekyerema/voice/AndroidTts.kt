package com.tekyerema.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Native TTS via Android's system engine (Google TTS is present on the test device). Provides a
 * real Ghanaian-English-*adjacent* neutral English voice (§22 documents that no truly Ghanaian
 * English voice exists — this is the honest fallback, swappable behind [TtsProvider]).
 *
 * Twi (§20-21): the system engine has no Twi voice, and the permissive `nano-twi` (MIT) is not yet
 * packaged on-device; `mms-tts-aka` is CC-BY-NC and must NOT ship. So [speak] for Twi reports
 * `false` (spoke=false) rather than pretend an English voice is Twi — the controller surfaces that
 * honestly instead of mis-speaking.
 */
class AndroidTts(context: Context) : TtsProvider {
    override val name = "android-system-tts (English; Twi=gap)"

    private val ready = CompletableDeferred<Boolean>()
    private var loggedEngine = false
    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    private fun logEngineOnce() {
        if (loggedEngine) return; loggedEngine = true
        try { Log.i("Tekyerema", "[TTS] system engine=${engine.defaultEngine}") } catch (_: Throwable) {}
    }

    /**
     * DEBUG (§B.4): enumerate the device's TTS voices and give a DEFINITIVE yes/no on whether any
     * Ghanaian / African-accented English voice exists — so it's known before the demo, not a surprise.
     */
    suspend fun logVoices() {
        if (!ready.await()) { Log.w("Tekyerema", "[TTS-VOICES] engine init failed"); return }
        val voices = try { engine.voices?.toList() ?: emptyList() } catch (t: Throwable) { emptyList() }
        Log.i("Tekyerema", "[TTS-VOICES] engine=${engine.defaultEngine} total=${voices.size}")
        val english = voices.filter { it.locale?.language.equals("en", true) }
        english.forEach { v ->
            Log.i("Tekyerema", "[TTS-VOICES] en voice: ${v.name} locale=${v.locale} quality=${v.quality} network=${v.isNetworkConnectionRequired}")
        }
        val african = english.filter { v ->
            (v.locale?.country ?: "").uppercase() in setOf("GH", "NG", "KE", "ZA", "TZ", "UG")
        }
        val ghana = english.filter { (it.locale?.country ?: "").equals("GH", true) }
        Log.i("Tekyerema", "[TTS-VOICES] === VERDICT: Ghanaian(en-GH) voice=${ghana.isNotEmpty()}; " +
            "African-accented English voice=${african.isNotEmpty()} (${african.map { it.locale }.distinct()}); " +
            "en-GH language available=${runCatching { engine.isLanguageAvailable(Locale("en","GH")) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)}")
    }

    private fun supports(language: String): Boolean {
        if (language == Lang.TWI) return false        // no Twi voice; do not fake it
        val locale = Locale("en", "GH")               // prefer en-GH; fall back to en
        val r = try { engine.isLanguageAvailable(locale) } catch (t: Throwable) { TextToSpeech.LANG_NOT_SUPPORTED }
        val use = if (r >= TextToSpeech.LANG_AVAILABLE) locale else Locale.ENGLISH
        return try { engine.setLanguage(use) >= TextToSpeech.LANG_AVAILABLE } catch (t: Throwable) { false }
    }

    override suspend fun speak(text: String, language: String): Boolean {
        if (text.isBlank()) return false
        if (!ready.await()) { Log.w("Tekyerema", "[TTS] engine init failed"); return false }
        logEngineOnce()
        if (!supports(language)) {
            Log.i("Tekyerema", "[TTS] no voice for '$language'; not speaking (honest gap)")
            return false
        }
        // B-15/K-07: our stream (accessibility when TalkBack drives the phone) + may-duck focus for the duration.
        try { engine.setAudioAttributes(focus.attributes(AudioFocus.Kind.SPEAK)) } catch (_: Throwable) {}
        focus.acquire(AudioFocus.Kind.SPEAK)
        val chunks = SpeechChunks.split(text, com.tekyerema.core.config.AppConfig.int("speech.tts.chunkChars", 180))
        val t0 = android.os.SystemClock.uptimeMillis()
        try {
            return suspendCancellableCoroutine { cont ->
                // B-06: queue every chunk now (the engine pipelines synthesis) and finish on the LAST one's onDone.
                val ids = chunks.indices.map { "tk-" + System.nanoTime() + "-" + it }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == ids.first()) lastFirstAudioMs = android.os.SystemClock.uptimeMillis() - t0   // Q-01
                    }
                    override fun onDone(utteranceId: String?) { if (utteranceId == ids.last() && cont.isActive) cont.resume(true) }
                    // Flushed by a newer utterance or stop(): end this one instead of leaving the caller suspended.
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { if (cont.isActive) cont.resume(false) }
                    @Deprecated("deprecated in API 21")
                    override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(false) }
                })
                for ((i, c) in chunks.withIndex()) {
                    val rc = engine.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, ids[i])
                    if (rc != TextToSpeech.SUCCESS) { if (cont.isActive) cont.resume(false); break }
                }
                cont.invokeOnCancellation { engine.stop() }
            }
        } finally {
            focus.release()
        }
    }

    private val focus = AudioFocus(context)

    /** Q-01: time from speak() to the first audible chunk of the last utterance (ms), or -1. */
    @Volatile var lastFirstAudioMs: Long = -1; private set

    /** 1.0 = the engine's normal pace; the Voice settings page offers slow/normal/fast. */
    fun setRate(rate: Float) { try { engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f)) } catch (_: Throwable) {} }

    /** B-13: 1.0 = the engine's normal pitch. */
    fun setPitch(pitch: Float) { try { engine.setPitch(pitch.coerceIn(0.5f, 2.0f)) } catch (_: Throwable) {} }

    override fun stop() { try { engine.stop() } catch (_: Throwable) {} }
    override fun shutdown() { try { engine.stop(); engine.shutdown() } catch (_: Throwable) {} }
}
