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
        // Our OWN focus request, released only by us: a newer line used to acquire focus, then this line's finally
        // released it, and the newer line played with the music at full volume.
        val focusToken = focus.acquire(AudioFocus.Kind.SPEAK)
        // Natural reading (owner, A20e 2026-09-30): the reading's pause marks (SpokenText) become real silences between its
        // pieces — a breath after "From Ama, on WhatsApp", a longer one between messages — instead of one run-on line.
        // playSilentUtterance works on every engine; SSML breaks may be read aloud by some.
        val maxChars = com.tekyerema.core.config.AppConfig.int("speech.tts.chunkChars", 180)
        val pieces = ArrayList<Pair<String?, Long>>()             // text, or null + a silence in ms
        for (seg in SpokenText.segments(text, com.tekyerema.core.config.AppConfig.long("speech.tts.pauseMs", 450L),
                com.tekyerema.core.config.AppConfig.long("speech.tts.breakMs", 900L))) {
            SpeechChunks.split(seg.text, maxChars).forEach { pieces += it to 0L }
            if (seg.pauseAfterMs > 0) pieces += null to seg.pauseAfterMs
        }
        if (pieces.isEmpty()) return false
        pieces.filter { it.first == null }.takeIf { it.isNotEmpty() }?.let { s ->
            Log.i("Tekyerema", "[TTS] paced reading: ${pieces.size - s.size} spoken parts, pauses ${s.map { it.second }} ms")
        }
        val chunks = pieces
        val t0 = android.os.SystemClock.uptimeMillis()
        try {
            return suspendCancellableCoroutine { cont ->
                // B-06: queue every chunk now (the engine pipelines synthesis) and finish on the LAST one's onDone.
                val ids = chunks.indices.map { "tk-" + System.nanoTime() + "-" + it }
                val call = Utterances.Call(ids, onFirstAudio = { lastFirstAudioMs = android.os.SystemClock.uptimeMillis() - t0 }) { ok ->   // Q-01
                    if (cont.isActive) cont.resume(ok)
                }
                // QUEUE_FLUSH below drops whatever this engine was still saying. End those callers now — they used to stay
                // suspended forever once their listener was replaced (a progress line talked over by a confirmation never
                // returned, and its later cancellation stopped whatever was speaking by then).
                utterances.begin(call)
                engine.setOnUtteranceProgressListener(utterances.listener)
                for ((i, c) in chunks.withIndex()) {
                    val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val rc = c.first?.let { engine.speak(it, mode, null, ids[i]) } ?: engine.playSilentUtterance(c.second, mode, ids[i])
                    if (rc != TextToSpeech.SUCCESS) { utterances.finish(call, false); break }
                }
                cont.invokeOnCancellation { if (utterances.isCurrent(call)) engine.stop(); utterances.finish(call, false) }
            }
        } finally {
            focus.release(focusToken)
        }
    }

    private val utterances = Utterances()

    private val focus = AudioFocus(context, onLost = { stop() })   // a call rings → the reply stops

    /** Q-01: time from speak() to the first audible chunk of the last utterance (ms), or -1. */
    @Volatile var lastFirstAudioMs: Long = -1; private set

    /** 1.0 = the engine's normal pace; the Voice settings page offers slow/normal/fast. */
    fun setRate(rate: Float) { try { engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f)) } catch (_: Throwable) {} }

    /** B-13: 1.0 = the engine's normal pitch. */
    fun setPitch(pitch: Float) { try { engine.setPitch(pitch.coerceIn(0.5f, 2.0f)) } catch (_: Throwable) {} }

    override fun stop() { try { engine.stop() } catch (_: Throwable) {} }
    override fun shutdown() { try { engine.stop(); engine.shutdown() } catch (_: Throwable) {} }
}

/**
 * Who is waiting on which utterance of ONE TextToSpeech instance. A single listener serves every caller: the engine
 * keeps only one listener, and each speak() used to install its own, so the caller it replaced was never told its
 * line was flushed and stayed suspended. Pure (no Android) → unit-tested.
 */
internal class Utterances {
    class Call(val ids: List<String>, val onFirstAudio: () -> Unit = {}, val onEnd: (Boolean) -> Unit)

    private val byId = java.util.concurrent.ConcurrentHashMap<String, Call>()
    @Volatile private var current: Call? = null

    /** [call] is about to QUEUE_FLUSH: every earlier caller's line is being dropped — end them (not spoken fully). */
    fun begin(call: Call) {
        byId.values.toSet().forEach { if (it !== call) finish(it, false) }
        call.ids.forEach { byId[it] = call }
        current = call
    }

    fun isCurrent(call: Call) = current === call

    /** Ends [call] once (later callbacks for its ids find nothing). */
    fun finish(call: Call, ok: Boolean) {
        var mine = false
        call.ids.forEach { if (byId.remove(it, call)) mine = true }
        if (current === call) current = null
        if (mine) call.onEnd(ok)
    }

    private fun owner(id: String?): Call? = id?.let { byId[it] }   // ConcurrentHashMap rejects a null key

    val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { owner(utteranceId)?.let { c -> if (c.ids.first() == utteranceId) c.onFirstAudio() } }
        override fun onDone(utteranceId: String?) { owner(utteranceId)?.let { c -> if (c.ids.last() == utteranceId) finish(c, true) } }
        // Stopped, or flushed by a newer line: only the caller that owns this id ends.
        override fun onStop(utteranceId: String?, interrupted: Boolean) { owner(utteranceId)?.let { finish(it, false) } }
        @Deprecated("deprecated in API 21")
        override fun onError(utteranceId: String?) { owner(utteranceId)?.let { finish(it, false) } }
        override fun onError(utteranceId: String?, errorCode: Int) { owner(utteranceId)?.let { finish(it, false) } }
    }
}
