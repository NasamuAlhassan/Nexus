package com.tekyerema.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Online ASR (Path B): POSTs a 16 kHz mono WAV to the proxy's /v1/voice, which runs the verified
 * phase2 ASR + LID + normalization and returns {raw, canonical, language, entities}. The 2.8 GB
 * test phone cannot host whisper-large-v3 / w2v-bert; this keeps the benchmark models in play while
 * Android stays thin. [AsrProvider] is the seam a future on-device tiny model would implement.
 */
class ProxyAsrProvider(
    private val baseUrl: () -> String,   // e.g. Settings.voiceBaseUrl (…/v1)
    private val authKey: () -> String = { "" }
) : AsrProvider {

    override val name = "proxy-voice(whisper-large-v3 / w2v-bert)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // Twi w2v-bert on CPU can be slow
        .build()

    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript =
        withContext(Dispatchers.IO) {
            val lang = forceLanguage ?: Lang.AUTO
            val url = baseUrl().trimEnd('/') + "/voice?lang=$lang&session=$sessionLanguage"
            val reqBuilder = Request.Builder()
                .url(url)
                .post(wav.toRequestBody("audio/wav".toMediaType()))
            authKey().takeIf { it.isNotBlank() }?.let { reqBuilder.addHeader("Authorization", "Bearer $it") }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(txt).optJSONObject("error")?.optString("message") }
                        .getOrNull() ?: "HTTP ${resp.code}"
                    throw AsrException(msg.ifBlank { "HTTP ${resp.code}" })
                }
                val o = JSONObject(txt)
                val entities = mutableListOf<Pair<String, String>>()
                o.optJSONArray("entities")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val pair = arr.optJSONArray(i) ?: continue
                        entities.add(pair.optString(0) to pair.optString(1))
                    }
                }
                Transcript(
                    raw = o.optString("raw"),
                    canonical = o.optString("canonical"),
                    language = o.optString("language", sessionLanguage),
                    reason = o.optString("reason"),
                    entities = entities,
                    backend = o.optString("backend"),
                    fallbackUsed = o.optBoolean("fallback_used", false),
                    asrMs = o.optLong("ms", 0)
                )
            }
        }
}

/** Distinguishes an ASR-layer failure so the controller can label the failed stage honestly (§29). */
class AsrException(message: String) : Exception(message)
