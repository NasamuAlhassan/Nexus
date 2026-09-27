package com.tekyerema.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Twi TTS via the Khaya TTS API through the proxy `/v1/tts` (permissive API/TOS). The system engine
 * has no Twi voice and the shippable on-device `nano-twi` (MIT) is not yet packaged, so this is the
 * best available Twi voice (§21). Only handles Twi — returns false for English so the router uses the
 * on-device engine; never speaks English audio as if it were Twi (§10/§22).
 */
class ProxyTtsProvider(
    private val appContext: Context,
    private val baseUrl: () -> String,
    private val authKey: () -> String = { "" }
) : TtsProvider {
    override val name = "khaya-tts (Twi, online)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    @Volatile private var player: MediaPlayer? = null

    override suspend fun speak(text: String, language: String): Boolean {
        if (language != Lang.TWI || text.isBlank()) return false
        val wav = try { fetch(text) } catch (e: Exception) {
            Log.w("Tekyerema", "[TTS] Khaya fetch failed: ${e.message}"); return false
        } ?: return false
        return play(wav)
    }

    private suspend fun fetch(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = baseUrl().trimEnd('/') + "/tts"
        val json = JSONObject().put("text", text).put("language", "twi").toString()
        val rb = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
        authKey().takeIf { it.isNotBlank() }?.let { rb.addHeader("Authorization", "Bearer $it") }
        client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w("Tekyerema", "[TTS] Khaya HTTP ${resp.code}"); return@withContext null }
            resp.body?.bytes()
        }
    }

    private suspend fun play(wav: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val f = File.createTempFile("tk_tts", ".wav", appContext.cacheDir)
        f.writeBytes(wav)
        val done = AtomicBoolean(false)
        try {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                player = mp
                fun finish(ok: Boolean) {
                    if (done.compareAndSet(false, true)) {
                        try { mp.release() } catch (_: Throwable) {}
                        player = null; f.delete()
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> finish(false); true }
                try {
                    mp.setDataSource(f.absolutePath); mp.prepare(); mp.start()
                } catch (e: Throwable) { Log.w("Tekyerema", "[TTS] play error: ${e.message}"); finish(false) }
                cont.invokeOnCancellation { try { mp.stop() } catch (_: Throwable) {}; finish(false) }
            }
        } finally { if (f.exists()) f.delete() }
    }

    override fun stop() { try { player?.stop() } catch (_: Throwable) {} }
    override fun shutdown() { stop(); try { player?.release() } catch (_: Throwable) {}; player = null }
}

/**
 * Routes TTS by language: English → the on-device system engine (offline-capable), Twi → the preferred
 * Twi voice (Khaya, online). Directive §28: if the preferred Twi voice is unavailable (e.g. OFFLINE, or
 * Khaya fails), the assistant must NOT go silent — it falls back to the on-device system engine to voice
 * the text. Pronunciation is imperfect (an English voice reading Twi), so this is a last resort and is
 * logged; for a blind user, hearing the response is more important than a perfect accent.
 */
class RoutingTts(
    private val english: TtsProvider,
    private val twi: TtsProvider,
    /** Optional English→Twi localizer (Khaya MT): so a Twi session is answered in Twi even when the
     *  response text is English (confirmations, errors, cloud-brain results). Returns null to leave text
     *  as-is (already Twi, or MT unavailable). App-authored Twi strings pass through untouched. */
    private val enToTw: (suspend (String) -> String?)? = null,
    /** Ghanaian-accented English voice (Khaya "eng"). Used for English replies when online; the phone's own
     *  engine has no Ghanaian voice, so this is what makes English sound Ghanaian. Null ⇒ system engine only. */
    private val ghEnglish: TtsProvider? = null,
    private val isOnline: () -> Boolean = { true }
) : TtsProvider {
    override val name = "routing(en=${english.name}; ghEn=${ghEnglish?.name ?: "-"}; twi=${twi.name})"
    override suspend fun speak(rawText: String, language: String): Boolean {
        // Strip markdown (**bold**, *, _, `, #) the cloud brain sometimes emits: it makes the Twi MT fail
        // (so the reply stays English) and makes a voice read "asterisk". Replies are spoken, so plain text.
        val text = rawText.replace(Regex("[*_`#]+"), "").replace(Regex("[ \\t]+"), " ").trim()
        if (language == Lang.TWI) {
            // Localize English response text to Twi so the Twi VOICE speaks Twi WORDS (not English read by
            // a Twi voice). Already-Twi text (e.g. LocalBrain "Mabue no.") is left as-is by enToTw.
            val twiText = enToTw?.invoke(text)?.takeIf { it.isNotBlank() } ?: text
            if (twi.speak(twiText, language)) { Log.i("Tekyerema", "[TTS] spoke Twi via ${twi.name}"); return true }
            Log.w("Tekyerema", "[TTS] Twi voice (${twi.name}) unavailable → English system-engine fallback (${english.name}) (§28)")
            return english.speak(text, Lang.GH_EN)   // fallback: ORIGINAL English text, English voice — never silent
        }
        // English: prefer the Ghanaian-accented Khaya voice when online; fall back to the system engine
        // (offline, no key, or Khaya failure) so we're never silent and never stall offline.
        if (ghEnglish != null && isOnline()) {
            if (ghEnglish.speak(text, Lang.GH_EN)) { Log.i("Tekyerema", "[TTS] spoke Gh-English via ${ghEnglish.name}"); return true }
            Log.w("Tekyerema", "[TTS] Gh-English voice unavailable → system-engine fallback (${english.name})")
        }
        Log.i("Tekyerema", "[TTS] spoke $language via ${english.name}")
        return english.speak(text, language)
    }
    override fun stop() { english.stop(); twi.stop(); ghEnglish?.stop() }
    override fun shutdown() { english.shutdown(); twi.shutdown(); ghEnglish?.shutdown() }
}
