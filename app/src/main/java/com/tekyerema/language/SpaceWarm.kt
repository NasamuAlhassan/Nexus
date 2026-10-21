package com.tekyerema.language

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wake our language Space's models in the background, the way scripts/warm_space.py does from the laptop (owner, 21 Oct 2026:
 * "warm the phone up … after restart to be ready for use"): the ZeroGPU Space sleeps when idle and its first call after a
 * pause took ~28 s. One tiny call per model, all at once — Twi hearing (a quarter second of silence), the Twi voice and the
 * English (Kafui) voice; nothing is played. Gradio's two-step API: POST /gradio_api/call/<api> → event id, then GET the
 * event stream until it completes. Off when `speech.warm.space` is false or the Space isn't configured.
 */
object SpaceWarm {
    private val JSON = "application/json".toMediaType()

    suspend fun wake(twi: Boolean): String = withContext(Dispatchers.IO) {
        val base = LanguageLayer.secrets()["SPEECH_SPACE_URL"]?.trimEnd('/') ?: return@withContext "no Space configured"
        val calls = buildList {
            add("eng voice" to ("tts" to JSONArray().put("Okay.").put("eng")))
            if (twi) {
                add("twi voice" to ("tts" to JSONArray().put("Aane").put("twi")))
                add("twi hearing" to ("asr_b64" to JSONArray().put(silenceWavB64()).put("twi")))
            }
        }
        coroutineScope {
            calls.map { (name, c) -> async { "$name ${call(base, c.first, c.second)}" } }.awaitAll()
        }.joinToString(", ")
    }

    private fun call(base: String, api: String, data: JSONArray): String {
        val t0 = System.currentTimeMillis()
        return try {
            val client = com.tekyerema.core.Http.client(10L, com.tekyerema.core.config.AppConfig.long("speech.warm.callSec", 90L))
            val post = Request.Builder().url("$base/gradio_api/call/$api")
                .post(JSONObject().put("data", data).toString().toRequestBody(JSON)).build()
            val id = client.newCall(post).execute().use { r -> JSONObject(r.body?.string().orEmpty()).optString("event_id") }
            if (id.isBlank()) return "no event"
            val done = client.newCall(Request.Builder().url("$base/gradio_api/call/$api/$id").build()).execute()
                .use { r -> r.body?.string().orEmpty().contains("event: complete") }
            "${if (done) "ok" else "not ready"} ${System.currentTimeMillis() - t0} ms"
        } catch (t: Throwable) {
            "failed ${t.javaClass.simpleName} ${System.currentTimeMillis() - t0} ms"
        }
    }

    /** 0.25 s of 16 kHz mono silence as a WAV, base64 — enough to make the Space load its Twi hearing model. */
    private fun silenceWavB64(): String {
        val samples = 4000
        val data = samples * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(data)
        return android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP)
    }

    internal fun log(line: String) = Log.i("Tekyerema", "[READY] $line")
}
