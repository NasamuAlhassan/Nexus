package com.tekyerema.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.tekyerema.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * §2 quota discipline — Abena's free tier is tiny (25 ASR anon / 50 signed-in / ~20 other). This gate:
 *  - blocks LIVE Abena calls from automated ADB batteries (they set liveAllowed=false unless --es use_live_api 1),
 *  - tracks the last-seen ASR credit balance and refuses ASR below a reserve, so a test run can't drain the demo.
 * The real voice UI path leaves liveAllowed=true.
 */
object AbenaBudget {
    @Volatile var liveAllowed: Boolean = true       // set false by the debug goal path unless explicitly overridden
    /** Enough ASR credit to serve a live turn? (credits are only reported by the ASR endpoint.) */
    fun asrAllowed(s: Settings): Boolean {
        if (!liveAllowed) { Log.w("Tekyerema", "[ABENA] live API disabled for this (test) path → skip"); return false }
        val c = s.abenaCredits
        if (c in 0 until s.abenaCreditReserve) { Log.w("Tekyerema", "[ABENA] ASR credits low ($c < ${s.abenaCreditReserve}) → skip, use fallback"); return false }
        return true
    }
    fun ttsAllowed(): Boolean {
        if (!liveAllowed) { Log.w("Tekyerema", "[ABENA] live API disabled for this (test) path → TTS skip"); return false }
        return true
    }
}

/** §2.1 persistent on-disk cache of synthesized WAVs, keyed by (text, voice, speed). Fixed strings are
 *  synthesized ONCE and reused forever, so confirmations/errors/prompts never re-hit the API. */
object TtsCache {
    private fun dir(ctx: Context) = File(ctx.filesDir, "tts_cache").apply { mkdirs() }
    private fun key(text: String, voice: String, speed: Double): String {
        val md = MessageDigest.getInstance("SHA-256").digest("$voice|$speed|$text".toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }
    fun get(ctx: Context, text: String, voice: String, speed: Double): ByteArray? {
        val f = File(dir(ctx), key(text, voice, speed) + ".wav")
        return if (f.exists() && f.length() > 44) f.readBytes() else null
    }
    fun put(ctx: Context, text: String, voice: String, speed: Double, wav: ByteArray) {
        try { File(dir(ctx), key(text, voice, speed) + ".wav").writeBytes(wav) } catch (_: Throwable) {}
    }
}

/**
 * Abena AI (Mobobi) TTS — PRIMARY voice for BOTH Ghanaian-accented English (akua_eng/kwabena_eng) and Twi
 * (abena_twi_high/lite). `POST /tts/synthesize/` returns base64 WAV. Cache-first (§2.1); >500-char text is
 * split into sentence chunks (§4.3); honours Retry-After on 503 (§4.2). Returns false on any failure so the
 * router falls back to Khaya, then the system engine — never silent.
 */
class AbenaTts(
    private val appContext: Context,
    private val settings: Settings,
    private val twiVoice: () -> String = { "abena_twi_high" }
) : TtsProvider {
    override val name = "abena-tts (Gh-English + Twi, online)"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    @Volatile private var player: MediaPlayer? = null

    private fun voiceFor(language: String): String? = when (language) {
        Lang.TWI -> twiVoice()
        Lang.GH_EN -> settings.abenaEnglishVoice
        else -> null
    }

    override suspend fun speak(text: String, language: String): Boolean {
        val voice = voiceFor(language) ?: return false
        if (text.isBlank() || !settings.hasAbena) return false
        val speed = speedFor()
        val chunks = chunk(text, 500)   // §4.3
        for (part in chunks) if (!speakOne(part, voice, speed)) return false
        return true
    }

    /** A slightly slower default than Abena's native 1.0 (owner asked for a calmer pace); the user's
     *  Slow/Normal/Fast setting still shifts it. speed<1 = slower (verified against the Abena API). */
    private fun speedFor(): Double = when (settings.speechRate) { 0 -> 0.75; 2 -> 1.0; else -> 0.85 }

    private suspend fun speakOne(text: String, voice: String, speed: Double): Boolean {
        // cache-first (never spends quota on a repeat); the cache key includes speed, so a rate change re-synths.
        TtsCache.get(appContext, text, voice, speed)?.let {
            Log.i("Tekyerema", "[TTS] provider=abena voice=$voice speed=$speed cached=true"); return play(it)
        }
        if (!AbenaBudget.ttsAllowed()) return false
        val wav = try { fetch(text, voice, speed) } catch (e: Exception) {
            Log.w("Tekyerema", "[TTS] Abena fetch failed: ${e.message}"); return false
        } ?: return false
        TtsCache.put(appContext, text, voice, speed, wav)
        Log.i("Tekyerema", "[TTS] provider=abena voice=$voice speed=$speed cached=false")
        return play(wav)
    }

    private suspend fun fetch(text: String, voice: String, speed: Double): ByteArray? = withContext(Dispatchers.IO) {
        val url = settings.abenaBaseUrl.trimEnd('/') + "/tts/synthesize/"
        val body = JSONObject().put("text", text).put("voice", voice).put("speed", speed).toString()
        var attempt = 0
        while (attempt < 2) {
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer ${settings.abenaApiKey}")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 503) {                          // §4.2 at capacity → honour Retry-After once
                    val wait = resp.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 8) ?: 3
                    Log.w("Tekyerema", "[TTS] Abena 503, retry after ${wait}s"); kotlinx.coroutines.delay(wait * 1000); attempt++; return@use
                }
                if (!resp.isSuccessful) { Log.w("Tekyerema", "[TTS] Abena HTTP ${resp.code}"); return@withContext null }
                val b64 = JSONObject(resp.body?.string().orEmpty()).optString("audio_base64")
                return@withContext if (b64.isBlank()) null else android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
            attempt++
        }
        null
    }

    private suspend fun play(audio: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val f = File.createTempFile("tk_abena", ".wav", appContext.cacheDir); f.writeBytes(audio)
        val done = AtomicBoolean(false)
        try {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer(); player = mp
                fun finish(ok: Boolean) { if (done.compareAndSet(false, true)) { try { mp.release() } catch (_: Throwable) {}; player = null; f.delete(); if (cont.isActive) cont.resume(ok) } }
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> finish(false); true }
                try { mp.setDataSource(f.absolutePath); mp.prepare(); mp.start() } catch (e: Throwable) { Log.w("Tekyerema", "[TTS] play error: ${e.message}"); finish(false) }
                cont.invokeOnCancellation { try { mp.stop() } catch (_: Throwable) {}; finish(false) }
            }
        } finally { if (f.exists()) f.delete() }
    }

    override fun stop() { try { player?.stop() } catch (_: Throwable) {} }
    override fun shutdown() { stop(); try { player?.release() } catch (_: Throwable) {}; player = null }

    companion object {
        /** Split text into ≤max-char pieces at sentence/space boundaries (§4.3). */
        fun chunk(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = ArrayList<String>(); val sb = StringBuilder()
            for (sentence in text.split(Regex("(?<=[.!?。])\\s+"))) {
                if (sb.isNotEmpty() && sb.length + sentence.length + 1 > max) { out.add(sb.toString().trim()); sb.setLength(0) }
                if (sentence.length > max) { sentence.chunked(max).forEach { out.add(it) } } else { if (sb.isNotEmpty()) sb.append(' '); sb.append(sentence) }
            }
            if (sb.isNotEmpty()) out.add(sb.toString().trim())
            return out.filter { it.isNotBlank() }
        }
    }
}

/**
 * Abena AI ASR — PRIMARY recognizer. `POST /asr/transcribe/` (multipart). Language: forced Twi → "twi-only",
 * Gh-English → "en", auto/code-switched → "twi-en" (recommended). Logs credits + updates the budget guard.
 * Throws AsrException on failure/low-credit so the router falls back (Khaya for Twi, Groq for English).
 */
class AbenaAsr(
    private val appContext: Context,
    private val settings: Settings
) : AsrProvider {
    override val name = "abena-asr(online)"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun langCode(forceLanguage: String?): String = when (forceLanguage) {
        Lang.TWI -> "twi-only"; Lang.GH_EN -> "en"; else -> "twi-en"
    }

    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript =
        withContext(Dispatchers.IO) {
            if (!settings.hasAbena) throw AsrException("no Abena key")
            if (!AbenaBudget.asrAllowed(settings)) throw AsrException("Abena ASR skipped (budget/guard)")
            val code = langCode(forceLanguage)
            val url = settings.abenaBaseUrl.trimEnd('/') + "/asr/transcribe/"
            // §4.2 — Abena's playground ASR is transiently "at capacity" (HTTP 503) for a few seconds at a
            // time. Without Khaya and with on-device Twi disqualified, a single 503 would DROP the user's Twi
            // command entirely. Mirror the TTS path: honour Retry-After and retry a few times before failing.
            var attempt = 0
            while (attempt < 3) {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("audio_file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("language", code).build()
                val req = Request.Builder().url(url).addHeader("Authorization", "Bearer ${settings.abenaApiKey}").post(form).build()
                val t0 = android.os.SystemClock.uptimeMillis()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 503) {
                        val wait = resp.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 5) ?: 2
                        Log.w("Tekyerema", "[ASR] Abena 503 (at capacity), retry ${attempt + 1}/3 after ${wait}s")
                        attempt++
                        kotlinx.coroutines.delay(wait * 1000)
                        return@use   // fall out of use{} and loop to retry
                    }
                    val txt = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw AsrException("Abena ASR HTTP ${resp.code}")
                    val j = JSONObject(txt)
                    val credits = j.optInt("credits_remaining", -1)
                    if (credits >= 0) settings.abenaCredits = credits
                    val raw = j.optString("text").ifBlank { j.optString("transcription") }.trim()
                    Log.i("Tekyerema", "[ASR] provider=abena lang=$code credits=$credits/${j.optInt("credit_limit", -1)} ${android.os.SystemClock.uptimeMillis() - t0}ms -> '${raw.take(60)}'")
                    if (raw.isEmpty()) throw AsrException("Abena ASR empty transcript")
                    return@withContext LanguagePipeline.process(raw, sessionLanguage, forceLanguage, backend = name)
                }
            }
            throw AsrException("Abena ASR at capacity (503) after retries")
        }
}

/** Try providers in order; the first that speaks wins. Used to make Abena primary + Khaya fallback (§3.2). */
class FallbackTts(private val chain: List<TtsProvider>) : TtsProvider {
    override val name = "fallback(" + chain.joinToString(",") { it.name } + ")"
    override suspend fun speak(text: String, language: String): Boolean {
        for (p in chain) if (p.speak(text, language)) return true
        return false
    }
    override fun stop() { chain.forEach { it.stop() } }
    override fun shutdown() { chain.forEach { it.shutdown() } }
}

/** Try ASR providers in order; the first that returns a transcript wins (Abena → Khaya/Groq). §3.1 */
class FallbackAsr(private val chain: List<AsrProvider>) : AsrProvider {
    override val name = "fallback(" + chain.joinToString(",") { it.name } + ")"
    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
        var last: Exception? = null
        for (p in chain) {
            try { return p.transcribe(wav, sessionLanguage, forceLanguage) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }   // user stop must propagate, never fall through
            // Catch ANY provider failure — including a raw network IOException/SocketTimeout, not just AsrException — so a
            // timeout on the primary provider still falls through to the next tier instead of silently killing the turn.
            catch (e: Exception) { Log.w("Tekyerema", "[ASR] ${p.name} failed → next: ${e.message}"); last = e }
        }
        throw AsrException(last?.message ?: "no ASR provider available")
    }
}
