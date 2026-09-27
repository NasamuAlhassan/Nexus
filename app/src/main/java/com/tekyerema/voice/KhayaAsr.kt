package com.tekyerema.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Online Twi ASR via Ghana NLP's Khaya API — purpose-built for Twi, so it handles NATURAL, conversational
 * speech and code-switching that the tiny on-device command-register model cannot (proven on-device:
 * "Mepɛ sɛ wubue tiktok ma me" → garbage on tiny; Khaya is trained for exactly this). Used only for Twi,
 * only when a key is set AND we're online; offline/Twi still falls back to the on-device tiny model via
 * [HybridAsr]. Raw WAV body + Ocp-Apim-Subscription-Key, per the Khaya ASR contract. Bounded timeouts so a
 * slow endpoint can never hang the UI (§5/§24). The raw text then flows through the same [LanguagePipeline]
 * (LID/normalize/entities) as every other ASR path — Khaya is a transcriber, not the agent brain.
 */
class KhayaAsr(
    private val baseUrl: () -> String,   // https://translation-api.ghananlp.org
    private val apiKey: () -> String
) : AsrProvider {
    override val name = "khaya-asr(twi, online)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript =
        withContext(Dispatchers.IO) {
            val key = apiKey()
            if (key.isBlank()) throw AsrException("no Khaya API key for online Twi ASR")
            val url = baseUrl().trimEnd('/') + "/asr/v1/transcribe?language=tw"
            val req = Request.Builder().url(url)
                .addHeader("Ocp-Apim-Subscription-Key", key)
                .post(wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            val t0 = android.os.SystemClock.uptimeMillis()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw AsrException("Khaya ASR HTTP ${resp.code}")
                // Khaya returns either a bare JSON string ("Sɛ twerɛma.") or an object {text|transcription}.
                val raw = parseKhaya(body)
                Log.i("Tekyerema", "[ASR] khaya transcribed in ${android.os.SystemClock.uptimeMillis() - t0}ms -> '${raw.take(60)}'")
                if (raw.isBlank()) throw AsrException("Khaya ASR returned empty transcript")
                // Twi is explicit here — Khaya only does Twi in this wiring.
                LanguagePipeline.process(raw, sessionLanguage, Lang.TWI, backend = name)
            }
        }

    private fun parseKhaya(body: String): String {
        val s = body.trim()
        return try {
            when {
                s.startsWith("{") -> org.json.JSONObject(s).let { j ->
                    j.optString("text").ifBlank { j.optString("transcription").ifBlank { j.optString("result") } }
                }.trim()
                s.startsWith("\"") -> org.json.JSONTokener(s).nextValue().toString().trim()  // bare JSON string
                else -> s
            }
        } catch (_: Throwable) { s }
    }
}
