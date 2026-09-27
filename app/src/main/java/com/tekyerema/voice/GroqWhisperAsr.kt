package com.tekyerema.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Online ASR via Groq's hosted Whisper (`/audio/transcriptions`, whisper-large-v3-turbo). This is the
 * FAST + ACCURATE path — the old Tɛkyerɛma ran Whisper on a server for exactly this reason; the A20's CPU
 * is far too weak to run whisper-base at interactive speed (~24–44 s), so on-device ASR is the OFFLINE
 * fallback, not the online default. Groq returns raw text in ~1–2 s; the already-local [LanguagePipeline]
 * then does LID + normalization + entity extraction, so the canonical goal is produced identically to every
 * other ASR path. Bounded timeouts so a slow/unreachable endpoint can't hang the UI (§5/§24).
 */
class GroqWhisperAsr(
    private val baseUrl: () -> String,     // https://api.groq.com/openai/v1
    private val apiKey: () -> String,
    private val model: String = "whisper-large-v3-turbo"
) : AsrProvider {
    override val name = "groq-whisper($model, online)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript =
        withContext(Dispatchers.IO) {
            val key = apiKey()
            if (key.isBlank()) throw AsrException("no Groq API key for online ASR")
            val url = baseUrl().trimEnd('/') + "/audio/transcriptions"
            // Whisper's own language code: en for Ghanaian English; let it auto-detect for Twi/unknown.
            val lang = when (forceLanguage) { Lang.GH_EN -> "en"; else -> null }

            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .addFormDataPart("temperature", "0")
            if (lang != null) form.addFormDataPart("language", lang)

            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $key")
                .post(form.build()).build()

            val t0 = android.os.SystemClock.uptimeMillis()
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(txt).optJSONObject("error")?.optString("message") }.getOrNull()
                    throw AsrException(msg?.ifBlank { null } ?: "Groq ASR HTTP ${resp.code}")
                }
                val raw = JSONObject(txt).optString("text").trim()
                Log.i("Tekyerema", "[ASR] groq-whisper transcribed in ${android.os.SystemClock.uptimeMillis() - t0}ms")
                if (raw.isEmpty()) throw AsrException("Groq ASR returned empty transcript")
                // LID + normalization + entities are on-device and model-agnostic (same as every ASR path).
                LanguagePipeline.process(raw, sessionLanguage, forceLanguage, backend = name)
            }
        }
}
