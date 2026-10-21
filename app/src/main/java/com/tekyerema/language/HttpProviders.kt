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
/**
 * The owner's overnight mission (14 Oct 2026, the UG HCI Lab answering 502 "upstream_service_error"): a speech request is
 * retried with exponential backoff inside a time limit — only after a server error (5xx) that came back with time left for
 * another try; never after a timeout (that already used the wait) and never after a 4xx (a refusal is a refusal). 5xx replies
 * are not counted against the daily quota (the callers' rule), so a retry costs no quota. [attempts] includes the first.
 */
internal object LabRetry {
    suspend fun send(budgetMs: Long, attempts: Int = 3, baseDelayMs: Long = 800L,
                     now: () -> Long = { SystemClock.elapsedRealtime() }, request: suspend () -> HttpReply): HttpReply {
        val start = now()
        var wait = baseDelayMs
        var tries = 0
        while (true) {
            val t0 = now()
            val r = request(); tries++
            val took = now() - t0
            if (r.code !in 500..599 || tries >= attempts) return r
            if (budgetMs - (now() - start) < wait + took) return r          // another try would not finish in time
            try { Log.i("Tekyerema", "[LANG] the lab answered ${r.code} in ${took} ms — try ${tries + 1} after ${wait} ms") } catch (_: Throwable) {}
            kotlinx.coroutines.delay(wait); wait *= 2
        }
    }
}

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
                if (spec.dialect == "gradio") {
                    // Our own Twi hearing model on the language Space (asr_b64: base64 WAV + language → text).
                    // Less to send: the words only, as μ-law (SpaceAudio) — `speech.asr.compactUpload` = false sends the WAV as recorded.
                    val audio = if (com.tekyerema.core.config.AppConfig.bool("speech.asr.compactUpload", true)) SpaceAudio.compact(wav) else wav
                    val call = transport.send(HttpSpeech.gradioCallRequest(spec, secrets(),
                        org.json.JSONArray().put(java.util.Base64.getEncoder().encodeToString(audio)).put(code ?: "twi")),
                        spec.connectMs, spec.callMs)
                    HttpSpeech.failureOf(spec, call)?.let { throw it }
                    val tSent = SystemClock.elapsedRealtime()
                    val result = transport.send(HttpSpeech.gradioResultRequest(spec, secrets(), HttpSpeech.gradioEventId(spec, call)),
                        spec.connectMs, spec.callMs)
                    HttpSpeech.failureOf(spec, result)?.let { throw it }
                    // Where the time goes — sending the audio, or the Space's queue and model (judge's latency audit, 21 Oct 2026).
                    Log.i("Tekyerema", "[ASR] $name: audio ${audio.size / 1024} KB (recorded ${wav.size / 1024} KB) sent in ${tSent - t0} ms, answer ${SystemClock.elapsedRealtime() - tSent} ms later")
                    return@run (HttpSpeech.parseGradio(spec, result.text()) ?: "") to null
                }
                val r = LabRetry.send(com.tekyerema.core.config.AppConfig.long("speech.lab.retryBudgetMs.asr", 14_000L)) {
                    transport.send(HttpSpeech.asrRequest(spec, secrets(), wav, code), spec.connectMs, spec.callMs) }
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
 * no network wait the second time. Twi clips are also kept in app-private storage across restarts ([TtsDiskCache];
 * never one holding a digit); the clip being played is a temp file, deleted after.
 */
class HttpTtsProvider(
    val spec: HttpSpeechSpec,
    private val secrets: () -> Map<String, String>,
    private val transport: SpeechTransport,
    private val guard: ProviderGuard,
    private val sink: AudioSink,
    private val ledger: QuotaLedger = QuotaLedger()
) : com.tekyerema.voice.PrefetchTts, HealthAware, com.tekyerema.voice.VoiceAvailability {
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

    override suspend fun speak(text: String, language: String): Boolean = fetch(text, language)?.let { play(it) } ?: false

    override suspend fun play(clips: List<ByteArray>): Boolean {
        // The moment the first word is heard — what the user waits for (judge's per-turn latency, 21 Oct 2026).
        Log.i("Tekyerema", "[TTS] $name audio starts (${clips.size} clip(s))")
        for (c in clips) if (!sink.play(c)) return false
        return true
    }

    /** The clips for [text] — on the phone already, or fetched — without playing them; null when this voice can't say it
     *  now (so [com.tekyerema.voice.FallbackTts] can ask two voices at once and play only the first to answer). */
    override suspend fun fetch(text: String, language: String): List<ByteArray>? {
        val code = HttpSpeech.languageCode(spec, language) ?: return null
        // Pause marks (voice/SpokenText) are silences on the phone's own voice; a network voice gets plain text.
        val flat = com.tekyerema.voice.SpokenText.plain(text)
        val say = if (language == Lang.TWI) TwiText.canonical(flat).trim() else flat.trim()
        if (say.isEmpty()) return null
        // A line already on the phone — a lab clip recorded in advance and shipped in the app (LabClips), or one cached
        // earlier — plays at once: no network, no daily quota, and none of the lab's ~14 s wait (3 October 2026).
        val ready = HttpSpeech.parts(say, spec.maxChars).map { p ->
            val key = "$code|$p"
            cache[key] ?: LabClips.get(spec.id, key) ?: TtsDiskCache.get(spec.id, key)
        }
        if (ready.isNotEmpty() && ready.all { it != null }) { Log.i("Tekyerema", "[TTS] $name: from the phone's cache"); return ready.map { it!! } }
        if (!available()) return null
        // The gateway takes at most spec.maxChars a request (HCI Lab: 250) — split, and leave a long reply to the phone's
        // own voice rather than spend several of a small day's requests on one answer.
        val parts = HttpSpeech.parts(say, spec.maxChars)
        if (parts.size > spec.maxParts) {
            Log.i("Tekyerema", "[TTS] $name: ${parts.size} parts (> ${spec.maxParts}) — the phone's voice says this one")
            return null
        }
        val clips = ArrayList<ByteArray>(parts.size)
        for (p in parts) {
            val key = "$code|$p"
            clips += cache[key] ?: LabClips.get(spec.id, key) ?: TtsDiskCache.get(spec.id, key)?.also { if (spec.cacheEntries > 0) cache[key] = it } ?: run {
                val units = QuotaLedger.Units(chars = p.length)
                // DEBUG builds: the line that has no recorded clip yet, so a rehearsal can list what to pre-record when the
                // lab is up (owner's overnight mission, 14 Oct 2026). Never in a release build.
                if (com.tekyerema.BuildConfig.DEV_HOOKS) Log.d("Tekyerema", "[TTS-UNCACHED] key=$key")
                ledger.refusal(spec.id, spec.limits, units)?.let { Log.i("Tekyerema", "[TTS] $name skipped — $it"); return null }
                try {
                    guard.run {
                        if (spec.dialect == "gradio") {
                            // Our own Twi voice on the language Space (api tts: text + language → a WAV file to download).
                            val call = transport.send(HttpSpeech.gradioCallRequest(spec, secrets(),
                                // Our Space voice reads Twi letters only: English words respelled first (voice/TwiRespell). The
                                // cache key stays the line itself.
                                org.json.JSONArray().put(if (language == Lang.TWI) com.tekyerema.voice.TwiRespell.apply(p) else p).put(code)), spec.connectMs, spec.callMs)
                            HttpSpeech.failureOf(spec, call)?.let { throw it }
                            val result = transport.send(HttpSpeech.gradioResultRequest(spec, secrets(), HttpSpeech.gradioEventId(spec, call)),
                                spec.connectMs, spec.callMs)
                            HttpSpeech.failureOf(spec, result)?.let { throw it }
                            val file = transport.send(okhttp3.Request.Builder().url(HttpSpeech.gradioFileUrl(spec, result.text())).get().build(),
                                spec.connectMs, spec.callMs)
                            HttpSpeech.failureOf(spec, file)?.let { throw it }
                            if (file.body.size < 64) throw ProviderFailure(FailureKind.BAD_RESPONSE, "${spec.id}: audio file too short")
                            return@run file.body
                        }
                        val r = LabRetry.send(com.tekyerema.core.config.AppConfig.long("speech.lab.retryBudgetMs.tts", 10_000L)) {
                            transport.send(HttpSpeech.ttsRequest(spec, secrets(), p, code), spec.connectMs, spec.callMs) }
                        // A rejected key (401) never reached the plan's counter: don't spend the small day on it.
                        if (r.code !in 500..599 && r.code != 401) ledger.record(spec.id, spec.limits, units)
                        HttpSpeech.failureOf(spec, r)?.let { throw it }
                        HttpSpeech.parseAudio(spec, r)
                    }.also { if (spec.cacheEntries > 0) cache[key] = it; if (language == Lang.TWI || (language == Lang.GH_EN && TtsDiskCache.keepsEnglish(key))) TtsDiskCache.put(spec.id, key, it) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    (e as? ProviderFailure)?.window?.let { ledger.exhaust(spec.id, spec.limits, it) }
                    ProviderGuard.log("TTS", spec.id, e); return null
                }
            }
        }
        return clips
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
        if (text.isBlank()) return null
        val key = "$f>$t|$text"
        cache[key]?.let { Log.i("Tekyerema", "[MT] ${spec.id} $from→$to cached (${text.length} chars)"); return it }
        // Kept across restarts (MtDiskCache): a line translated in the morning's rehearsal is there on stage, at once, even
        // with the translator slow or down (owner's overnight mission, 15 Oct 2026).
        MtDiskCache.get(spec.id, key)?.let { if (spec.cacheEntries > 0) cache[key] = it
            Log.i("Tekyerema", "[MT] ${spec.id} $from→$to from the phone's cache (${text.length} chars)"); return it }
        if (!available()) return null
        val t0 = System.currentTimeMillis()
        return try {
            guard.run {
                val r = transport.send(HttpSpeech.mtRequest(spec, secrets(), text, f, t), spec.connectMs, spec.callMs)
                HttpSpeech.failureOf(spec, r)?.let { throw it }
                HttpSpeech.parseText(spec, r)
            }.ifBlank { null }?.also {
                if (spec.cacheEntries > 0) cache[key] = it
                MtDiskCache.put(spec.id, key, it)
                // Measured for the QA run (no content): the translator's own latency was invisible in the log before 3 Oct 2026.
                Log.i("Tekyerema", "[MT] ${spec.id} $from→$to ok in ${System.currentTimeMillis() - t0}ms (${text.length} → ${it.length} chars)")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ProviderGuard.log("MT", spec.id, e); null }
    }
}

/**
 * The HCI Lab's own voice, recorded IN ADVANCE for the app's fixed Twi lines (confirmation frames, questions, "Mante. San
 * ka bio."…) and shipped in the APK under assets/voice/twi_lab/ — named like [TtsDiskCache] (the SHA-256 of "twi|<text>").
 * Owner, 3 October 2026: the lab voice is "very, very good" but takes ~14 s a new line and 15 lines a day; names, amounts
 * and numbers are the English voice's (voice/ConfirmTemplates), so these frames never change and play at once.
 * Recorded with campaign/twi_lab_clips.py from the list TwiLabLinesTest writes. Only for the lab voice ([ID]).
 */
object LabClips {
    const val ID = "hcilab_tts"
    const val DIR = "voice/twi_lab"
    @Volatile private var names: Set<String>? = null

    private fun index(): Set<String> = names ?: try {
        com.tekyerema.agent.AgentEngine.appContext()?.assets?.list(DIR)?.toSet().orEmpty()
    } catch (_: Throwable) { emptySet() }.also { names = it }

    fun get(id: String, key: String): ByteArray? {
        if (id != ID) return null
        val file = TtsDiskCache.name(key) + ".wav"
        if (file !in index()) return null
        return try { com.tekyerema.agent.AgentEngine.appContext()?.assets?.open("$DIR/$file")?.use { it.readBytes() } } catch (_: Throwable) { null }
    }
}

/**
 * Twi voice clips kept across restarts (owner, 2 October 2026: the HCI Lab voice allows 15 requests a day, and a Twi line
 * is now spoken in pieces — "Wopɛ sɛ mesoma | five Ghana cedis | ma Ama?" — whose fixed Twi words recur on every
 * confirmation). App-private storage only, at most [MAX] clips, oldest dropped first; a line holding a digit (an amount, a
 * number) is never kept — those pieces are the English voice's anyway. Cleared with the app's data.
 */
object TtsDiskCache {
    private const val MAX = 300
    private fun dir(id: String): java.io.File? = try {
        com.tekyerema.agent.AgentEngine.appContext()?.let { java.io.File(it.filesDir, "tts_cache/$id").apply { mkdirs() } }
    } catch (_: Throwable) { null }

    internal fun name(key: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

    fun get(id: String, key: String): ByteArray? = try {
        dir(id)?.let { java.io.File(it, name(key)) }?.takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }?.readBytes()
    } catch (_: Throwable) { null }

    /**
     * The Kafui English voice is slow when the Space's GPU is busy (a CPU line took 24 s on 19 Oct 2026), so the app's own FIXED
     * lines — a question, a read-back, a call announcement — are kept across restarts and play at once next time. Only those
     * frames: a message, a balance or anything the user said is never written to disk. `speech.ghEnglish.keepPrefixes` overrides.
     */
    internal fun keepsEnglish(key: String): Boolean {
        val text = key.substringAfter('|').trim()
        val prefixes = com.tekyerema.core.config.AppConfig.stringList("speech.ghEnglish.keepPrefixes", DEFAULT_ENGLISH_FRAMES)
        return prefixes.any { text.startsWith(it, ignoreCase = true) }
    }
    private val DEFAULT_ENGLISH_FRAMES = listOf("What should the reference", "How much do you want", "Which one is", "Send ", "Calling ",
        "Do you want me to", "Okay", "Yes", "No,", "I'll stop at the PIN", "MTN shows", "I'll wait", "Say yes or no", "Done", "Sent")

    fun put(id: String, key: String, clip: ByteArray) {
        if (key.substringAfter('|').any { it.isDigit() }) return
        try {
            val d = dir(id) ?: return
            java.io.File(d, name(key)).writeBytes(clip)
            val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size > MAX) files.take(files.size - MAX).forEach { it.delete() }
        } catch (_: Throwable) {}
    }
}

/**
 * Translations kept across restarts, app-private, at most [MAX] (oldest dropped first). A line about a PIN or a balance is
 * never kept. Cleared with the app's data.
 */
object MtDiskCache {
    private const val MAX = 400
    private val SECRET = Regex("(?i)\\b(pin|balance|password|otp|code)\\b")
    private fun dir(id: String): java.io.File? = try {
        com.tekyerema.agent.AgentEngine.appContext()?.let { java.io.File(it.filesDir, "mt_cache/$id").apply { mkdirs() } }
    } catch (_: Throwable) { null }

    fun get(id: String, key: String): String? = try {
        dir(id)?.let { java.io.File(it, TtsDiskCache.name(key)) }?.takeIf { it.isFile }?.readText()?.ifBlank { null }
    } catch (_: Throwable) { null }

    fun put(id: String, key: String, value: String) {
        if (SECRET.containsMatchIn(key) || SECRET.containsMatchIn(value)) return
        try {
            val d = dir(id) ?: return
            java.io.File(d, TtsDiskCache.name(key)).writeText(value)
            val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size > MAX) files.take(files.size - MAX).forEach { it.delete() }
        } catch (_: Throwable) {}
    }
}

/**
 * Translation through a Gradio Space (dialect "gradio"): our own language Space, `PrinceAlhassanNasamu/tekyerema-lang`,
 * serving the owner's NLLB fine-tune (tekyerema-nllb600m-v1) — translation was switched on there on 2 October 2026. Two
 * calls (the event id, then its result), each under this client's timeouts; cached; null on any failure or when the Space
 * says it did not translate (`"covered": false`).
 */
class GradioTranslationProvider(
    val spec: HttpSpeechSpec,
    private val secrets: () -> Map<String, String>,
    private val transport: SpeechTransport,
    private val guard: ProviderGuard
) : TranslationProvider, HealthAware {
    override val name = "gradio:${spec.id}"
    override fun available() = com.tekyerema.operator.OperatorPolicy.cloudAllowed && guard.health.available()
    private val cache: MutableMap<String, String> = lru(spec.cacheEntries.coerceIn(0, 512))

    override suspend fun translate(text: String, from: String, to: String): String? {
        val f = HttpSpeech.languageCode(spec, from) ?: return null
        val t = HttpSpeech.languageCode(spec, to) ?: return null
        if (text.isBlank()) return null
        val key = "$f>$t|$text"
        cache[key]?.let { Log.i("Tekyerema", "[MT] ${spec.id} $from→$to cached (${text.length} chars)"); return it }
        // Kept across restarts (MtDiskCache): a line translated in the morning's rehearsal is there on stage, at once, even
        // with the translator slow or down (owner's overnight mission, 15 Oct 2026).
        MtDiskCache.get(spec.id, key)?.let { if (spec.cacheEntries > 0) cache[key] = it
            Log.i("Tekyerema", "[MT] ${spec.id} $from→$to from the phone's cache (${text.length} chars)"); return it }
        if (!available()) return null
        val t0 = System.currentTimeMillis()
        return try {
            guard.run {
                val call = transport.send(HttpSpeech.gradioCallRequest(spec, secrets(), org.json.JSONArray().put(text).put(f).put(t)),
                    spec.connectMs, spec.callMs)
                HttpSpeech.failureOf(spec, call)?.let { throw it }
                val id = HttpSpeech.gradioEventId(spec, call)
                val result = transport.send(HttpSpeech.gradioResultRequest(spec, secrets(), id), spec.connectMs, spec.callMs)
                HttpSpeech.failureOf(spec, result)?.let { throw it }
                HttpSpeech.parseGradio(spec, result.text()) ?: ""
            }.ifBlank { null }?.also {
                if (spec.cacheEntries > 0) cache[key] = it
                MtDiskCache.put(spec.id, key, it)
                Log.i("Tekyerema", "[MT] ${spec.id} $from→$to ok in ${System.currentTimeMillis() - t0}ms (${text.length} → ${it.length} chars)")
            }
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
