package com.tekyerema.language

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.tekyerema.voice.AsrException
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.AudioFocus
import com.tekyerema.voice.Lang
import com.tekyerema.voice.LanguagePipeline
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The three network roles built from an [HttpSpeechSpec]. Each call runs through a [ProviderGuard] (health/quota +
 * backstop timeout) and logs only ids, sizes and status codes — never the user's words (privacy.logcatContent).
 */

private fun <K, V> lru(max: Int): MutableMap<K, V> = java.util.Collections.synchronizedMap(
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    })

/**
 * Online recognition: audio up, raw transcript back, then the SAME on-device [LanguagePipeline] every recogniser
 * uses (LID, entity-first normalisation, entities) — so where the words came from never changes how they are read.
 */
class HttpAsrProvider(
    val spec: HttpSpeechSpec,
    private val secrets: () -> Map<String, String>,
    private val transport: SpeechTransport,
    private val guard: ProviderGuard,
    private val ledger: QuotaLedger = QuotaLedger()
) : AsrProvider, HealthAware {
    override val name = "http:${spec.id}"
    /** Worth trying now: not resting after a failure, and the plan has a request left ([QuotaLedger]). WS-C: never while
     *  the user has the cloud off. */
    override fun available() = com.tekyerema.operator.OperatorPolicy.cloudAllowed && guard.health.available() &&
        ledger.refusal(spec.id, spec.limits, QuotaLedger.Units()) == null

    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
        if (!com.tekyerema.operator.OperatorPolicy.cloudAllowed) throw AsrException("$name: the cloud is off")   // WS-C
        val lang = forceLanguage ?: spec.fixedLanguage
        val code = HttpSpeech.languageCode(spec, lang)
        if (lang != null && code == null) throw AsrException("$name doesn't recognise ${Lang.label(lang)}")
        val units = QuotaLedger.Units(audioSec = audioSeconds(wav))
        ledger.refusal(spec.id, spec.limits, units)?.let {
            Log.i("Tekyerema", "[ASR] $name skipped — $it; on-device instead")
            throw AsrException("$name: quota")
        }
        val t0 = SystemClock.elapsedRealtime()
        val reply = try {
            guard.run {
                val r = transport.send(HttpSpeech.asrRequest(spec, secrets(), wav, code), spec.connectMs, spec.callMs)
                // It reached the gateway's counter — except a rejected key (401), which is refused before any counting.
                if (r.code !in 500..599 && r.code != 401) ledger.record(spec.id, spec.limits, units)
                HttpSpeech.failureOf(spec, r)?.let { throw it }
                val text = HttpSpeech.parseText(spec, r)
                text to HttpSpeech.parseLanguage(spec, r)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            (e as? ProviderFailure)?.window?.let { ledger.exhaust(spec.id, spec.limits, it) }
            ProviderGuard.log("ASR", spec.id, e)
            // AsrException is what the controller labels as the ASR stage (§29); HybridAsr degrades on it.
            throw AsrException(e.message ?: "$name failed")
        }
        val (raw, reported) = reply
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i("Tekyerema", "[ASR] $name ok in ${ms}ms (${raw.length} chars, lang=${lang ?: reported ?: "auto"})")
        if (raw.isBlank()) throw AsrException("$name heard no words")
        // A forced/fixed language wins; else the API's own (audio-based) language report; else on-device text LID.
        val tr = LanguagePipeline.process(raw, sessionLanguage, lang ?: reported, backend = name, asrMs = ms)
        return if (lang == null && reported != null) tr.copy(reason = "provider-reported $reported") else tr
    }
}

/** Seconds of audio in a WAV (its header's byte rate), else 16 kHz 16-bit mono — what the quota counts. */
internal fun audioSeconds(wav: ByteArray): Double {
    if (wav.size > 44 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF") {
        val rate = (wav[28].toInt() and 0xff) or ((wav[29].toInt() and 0xff) shl 8) or
            ((wav[30].toInt() and 0xff) shl 16) or ((wav[31].toInt() and 0xff) shl 24)
        if (rate > 0) return (wav.size - 44).toDouble() / rate
    }
    return wav.size / 32_000.0
}

/** Plays synthesized audio. Abstracted so the TTS client is testable without a speaker. */
interface AudioSink {
    /** Suspends until playback ends. True if it played to the end. */
    suspend fun play(audio: ByteArray): Boolean
    fun stop()
}

/**
 * Online synthesis. Twi text is sent in its standard spelling ([TwiText.canonical]); unsupported languages return
 * false at once so [com.tekyerema.voice.RoutingTts] / the fallback chain moves on to the phone's own voice. Fixed
 * lines (confirmations, "Okay") recur every session, so audio is cached in memory (bounded) — fewer quota calls and
 * no network wait the second time. Nothing is written to disk except the clip being played, deleted after.
 */
class HttpTtsProvider(
    val spec: HttpSpeechSpec,
    private val secrets: () -> Map<String, String>,
    private val transport: SpeechTransport,
    private val guard: ProviderGuard,
    private val sink: AudioSink,
    private val ledger: QuotaLedger = QuotaLedger()
) : TtsProvider, HealthAware, com.tekyerema.voice.VoiceAvailability {
    override val name = "http:${spec.id}"
    override fun available() = com.tekyerema.operator.OperatorPolicy.cloudAllowed && guard.health.available() &&   // WS-C
        ledger.refusal(spec.id, spec.limits, QuotaLedger.Units(chars = 1)) == null
    private val cache: MutableMap<String, ByteArray> = lru(spec.cacheEntries.coerceIn(0, 256))

    /** Why this voice can't be used right now, in words for the user and the phone check; null when it can. */
    override fun whyUnavailable(): String? = unavailableReason(
        cloudAllowed = com.tekyerema.operator.OperatorPolicy.cloudAllowed,
        resting = !guard.health.available(), lastFailure = guard.health.lastFailure,
        quotaSpent = ledger.refusal(spec.id, spec.limits, QuotaLedger.Units(chars = 1)) != null)

    init {
        // Synthesized clips are the one thing here worth megabytes: give them back when the offline recogniser needs
        // room to load (voice/AsrMemory). They are fetched again on next use. The hook holds only the map (not this
        // provider), and the registry holds this provider weakly.
        val clips = cache
        com.tekyerema.voice.AsrMemory.onRelease(this) { clips.clear() }
    }

    override suspend fun speak(text: String, language: String): Boolean {
        val code = HttpSpeech.languageCode(spec, language) ?: return false
        // Pause marks (voice/SpokenText) are silences on the phone's own voice; a network voice gets plain text.
        val flat = com.tekyerema.voice.SpokenText.plain(text)
        val say = if (language == Lang.TWI) TwiText.canonical(flat).trim() else flat.trim()
        if (say.isEmpty() || !available()) return false
        // The gateway takes at most spec.maxChars a request (HCI Lab: 250) — split, and leave a long reply to the phone's
        // own voice rather than spend several of a small day's requests on one answer.
        val parts = HttpSpeech.parts(say, spec.maxChars)
        if (parts.size > spec.maxParts) {
            Log.i("Tekyerema", "[TTS] $name: ${parts.size} parts (> ${spec.maxParts}) — the phone's voice says this one")
            return false
        }
        val clips = ArrayList<ByteArray>(parts.size)
        for (p in parts) {
            val key = "$code|$p"
            clips += cache[key] ?: run {
                val units = QuotaLedger.Units(chars = p.length)
                ledger.refusal(spec.id, spec.limits, units)?.let { Log.i("Tekyerema", "[TTS] $name skipped — $it"); return false }
                try {
                    guard.run {
                        val r = transport.send(HttpSpeech.ttsRequest(spec, secrets(), p, code), spec.connectMs, spec.callMs)
                        // A rejected key (401) never reached the plan's counter: don't spend the small day on it.
                        if (r.code !in 500..599 && r.code != 401) ledger.record(spec.id, spec.limits, units)
                        HttpSpeech.failureOf(spec, r)?.let { throw it }
                        HttpSpeech.parseAudio(spec, r)
                    }.also { if (spec.cacheEntries > 0) cache[key] = it }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    (e as? ProviderFailure)?.window?.let { ledger.exhaust(spec.id, spec.limits, it) }
                    ProviderGuard.log("TTS", spec.id, e); return false
                }
            }
        }
        for (c in clips) if (!sink.play(c)) return false
        return true
    }

    override fun stop() = sink.stop()
    override fun shutdown() = sink.stop()
}

/** Online translation, cached (bounded) because the same app lines recur. Null on any failure. */
class HttpTranslationProvider(
    val spec: HttpSpeechSpec,
    private val secrets: () -> Map<String, String>,
    private val transport: SpeechTransport,
    private val guard: ProviderGuard
) : TranslationProvider, HealthAware {
    override val name = "http:${spec.id}"
    override fun available() = com.tekyerema.operator.OperatorPolicy.cloudAllowed && guard.health.available()   // WS-C
    private val cache: MutableMap<String, String> = lru(spec.cacheEntries.coerceIn(0, 512))

    override suspend fun translate(text: String, from: String, to: String): String? {
        val f = HttpSpeech.languageCode(spec, from) ?: return null
        val t = HttpSpeech.languageCode(spec, to) ?: return null
        if (text.isBlank() || !available()) return null
        val key = "$f>$t|$text"
        cache[key]?.let { return it }
        return try {
            guard.run {
                val r = transport.send(HttpSpeech.mtRequest(spec, secrets(), text, f, t), spec.connectMs, spec.callMs)
                HttpSpeech.failureOf(spec, r)?.let { throw it }
                HttpSpeech.parseText(spec, r)
            }.ifBlank { null }?.also { if (spec.cacheEntries > 0) cache[key] = it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ProviderGuard.log("MT", spec.id, e); null }
    }
}

/**
 * The real transport: the app's shared OkHttp pool (N-04 — no fresh TLS handshake per call) with this client's own
 * connect/call timeouts, cancelled with the coroutine so "stop" aborts an upload in flight.
 */
object OkHttpSpeechTransport : SpeechTransport {
    override suspend fun send(request: Request, connectMs: Long, callMs: Long): HttpReply {
        val client = com.tekyerema.core.Http.shared.newBuilder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(callMs, TimeUnit.MILLISECONDS)
            .writeTimeout(callMs, TimeUnit.MILLISECONDS)
            .callTimeout(callMs, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { try { call.cancel() } catch (_: Throwable) {} }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    val r = try {
                        response.use {
                            Result.success(HttpReply(it.code, it.body?.bytes() ?: ByteArray(0),
                                it.header("Content-Type"), it.header("Retry-After")))
                        }
                    } catch (e: IOException) { Result.failure(e) }
                    if (cont.isActive) cont.resumeWith(r)
                }
            })
        }
    }
}

/**
 * Plays a synthesized clip through MediaPlayer on the assistant (or, under TalkBack, the accessibility) stream with
 * transient ducking focus (B-15), from a temp file in the cache dir that is deleted as soon as playback ends.
 */
class MediaAudioSink(private val appContext: Context) : AudioSink {
    private val focus = AudioFocus(appContext)
    /** Ends the clip in progress (releases the player, resumes [play] with false). MediaPlayer fires no callback after
     *  stop()/release(), so without this a "stop" would leave the voice turn waiting forever. */
    @Volatile private var finisher: ((Boolean) -> Unit)? = null

    override suspend fun play(audio: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val f = File.createTempFile("tk_tts", ".audio", appContext.cacheDir)
        val done = AtomicBoolean(false)
        try {
            f.writeBytes(audio)
            focus.acquire(AudioFocus.Kind.SPEAK)
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                fun finish(ok: Boolean) {
                    if (done.compareAndSet(false, true)) {
                        try { mp.stop() } catch (_: Throwable) {}
                        try { mp.release() } catch (_: Throwable) {}
                        finisher = null
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                finisher = ::finish
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> finish(false); true }
                try {
                    mp.setAudioAttributes(focus.attributes(AudioFocus.Kind.SPEAK))
                    mp.setDataSource(f.absolutePath); mp.prepare(); mp.start()
                } catch (e: Throwable) { Log.w("Tekyerema", "[TTS] clip playback failed: ${e.message}"); finish(false) }
                cont.invokeOnCancellation { finish(false) }
            }
        } finally {
            focus.release()
            f.delete()
        }
    }

    override fun stop() { finisher?.invoke(false) }
}
