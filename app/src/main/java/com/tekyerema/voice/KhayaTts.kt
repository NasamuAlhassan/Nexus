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
 * Ghana NLP Khaya TTS (/tts/v1/synthesize) — REAL Ghanaian voices. Handles **Twi** (so Twi sessions are
 * answered in Twi, §21/§28) AND **Ghanaian-accented English** (Khaya supports language "eng"): the phone's
 * system engine has no Ghanaian English voice, so English replies get a Ghanaian accent from here when
 * online. Returns false for any other language or on any failure, so [RoutingTts] falls back to the system
 * engine rather than going silent. Bounded timeouts. Same Khaya key as [KhayaAsr].
 */
class KhayaTts(
    private val appContext: Context,
    private val baseUrl: () -> String,
    private val apiKey: () -> String,
    private val speaker: () -> String = { "female" }   // Khaya speakers (multilingual): male_low | male_high | female
) : TtsProvider {
    override val name = "khaya-tts (Twi + Gh-English, online)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    @Volatile private var player: MediaPlayer? = null

    // Khaya language code for our internal Lang. Asante Twi = "tw", Ghanaian English = "eng".
    private fun khayaLang(language: String): String? = when (language) {
        Lang.TWI -> "tw"; Lang.GH_EN -> "eng"; else -> null
    }

    // §quota — cache synthesized audio for repeated FIXED strings (confirmations, errors, "Okay", capability
    // lines) so we don't re-hit Khaya every time. Bounded LRU keyed by lang+speaker+text.
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(e: Map.Entry<String, ByteArray>): Boolean = size > 48
    })

    override suspend fun speak(text: String, language: String): Boolean {
        val code = khayaLang(language) ?: return false
        if (text.isBlank()) return false
        val key = apiKey(); if (key.isBlank()) return false
        val ck = "$code|${speaker()}|$text"
        val wav = cache[ck] ?: try { fetch(text, code, key)?.also { cache[ck] = it } } catch (e: Exception) {
            Log.w("Tekyerema", "[TTS] Khaya fetch failed: ${e.message}"); return false
        } ?: return false
        return play(wav)
    }

    private suspend fun fetch(text: String, code: String, key: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = baseUrl().trimEnd('/') + "/tts/v1/synthesize"
        val json = JSONObject().put("text", text).put("language", code).put("speaker_id", speaker()).toString()
        val req = Request.Builder().url(url)
            .addHeader("Ocp-Apim-Subscription-Key", key)
            .post(json.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w("Tekyerema", "[TTS] Khaya HTTP ${resp.code} ($code)"); return@withContext null }
            resp.body?.bytes()
        }
    }

    private suspend fun play(audio: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val f = File.createTempFile("tk_khaya", ".wav", appContext.cacheDir)
        f.writeBytes(audio)
        val done = AtomicBoolean(false)
        try {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer(); player = mp
                fun finish(ok: Boolean) {
                    if (done.compareAndSet(false, true)) {
                        try { mp.release() } catch (_: Throwable) {}
                        player = null; f.delete(); if (cont.isActive) cont.resume(ok)
                    }
                }
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> finish(false); true }
                try { mp.setDataSource(f.absolutePath); mp.prepare(); mp.start() }
                catch (e: Throwable) { Log.w("Tekyerema", "[TTS] play error: ${e.message}"); finish(false) }
                cont.invokeOnCancellation { try { mp.stop() } catch (_: Throwable) {}; finish(false) }
            }
        } finally { if (f.exists()) f.delete() }
    }

    override fun stop() { try { player?.stop() } catch (_: Throwable) {} }
    override fun shutdown() { stop(); try { player?.release() } catch (_: Throwable) {}; player = null }
}
