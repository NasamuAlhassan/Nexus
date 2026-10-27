package com.tekyerema.camera

import android.util.Base64
import android.util.Log
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The camera's fast rung (vision_features.md §4): the same OpenAI-style chat endpoint the planner brains speak
 * (OpenAiCompatBrain's request shape), with the photo as a base64 data-URL content part. It answers first and is hedged
 * with GeminiVision, the better eye for questions (VisionLadder.race, 23 Oct 2026). Groq counts every image as ~2k input
 * tokens against the same daily budget the agent's brain runs on.
 *
 * Key rotation is the brains' idea at its simplest: limits are per key per organization, so a limited or refused
 * key just means the next one. One photo is one request — no pacing loop here.
 *
 * Never logs the image or the reply's words; lengths only.
 */
object GroqVision {
    private const val TAG = "Tekyerema"

    private val rest = KeyRest()

    /** Groq's current vision-capable model (the llama-4 scout/maverick pair left their list, 30 Sep 2026). */
    fun model(): String = AppConfig.string("providers.groq.visionModel", "qwen/qwen3.8-27b")

    /** Same contract as [GeminiVision.describe]: one photo in, spoken text or a log-only failure out. */
    suspend fun describe(jpeg: ByteArray, apiKeys: List<String>, baseUrl: String, prompt: String): VisionResult = withContext(Dispatchers.IO) {
        val all = apiKeys.filter { it.isNotBlank() }
        if (all.isEmpty()) return@withContext VisionResult.Failed("no Groq key")
        // A key that was refused (401/403) is skipped for hours and one that was rate-limited for a short while — the camera
        // used to try #1 (429) and #2 (401) on every photo, about a second lost each time (A20e log, 27 Oct 2026).
        val keys = rest.usable(all)
        if (keys.isEmpty()) return@withContext VisionResult.Failed("Groq keys resting")
        val timeoutSec = AppConfig.long("camera.describe.timeoutSec", 30L).coerceAtLeast(5L)
        val client = com.tekyerema.core.Http.client(minOf(timeoutSec, 15L), timeoutSec)
        val payload = requestBody(
            model(), prompt,
            Base64.encodeToString(jpeg, Base64.NO_WRAP),
            AppConfig.double("camera.describe.temperature", 0.2)
        ).toString()
        var lastReason = "no usable key"
        val t0 = System.nanoTime()
        for ((i, key) in keys.withIndex()) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val (code, text) = try {
                com.tekyerema.core.Http.execute(client.newCall(request)).let { it.first to it.second }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "[CAMERA] groq describe request failed: ${t.javaClass.simpleName}")
                return@withContext VisionResult.Failed("network: ${t.javaClass.simpleName}")
            }
            when {
                code in 200..299 -> {
                    val description = parse(text) ?: run {
                        Log.w(TAG, "[CAMERA] groq describe: no text in response")
                        return@withContext VisionResult.Failed("empty")
                    }
                    // P5-07: tokens and latency per picture (the response's usage block), never the words.
                    val usage = try { JSONObject(text).optJSONObject("usage") } catch (_: Throwable) { null }
                    Log.i(TAG, "[CAMERA] groq described (${description.length} chars, ${jpeg.size / 1024} KB image, ${(System.nanoTime() - t0) / 1_000_000} ms" +
                        (usage?.let { ", prompt=${it.optInt("prompt_tokens")} out=${it.optInt("completion_tokens")}" } ?: "") + ")")
                    return@withContext VisionResult.Described(description)
                }
                // This key is spent or refused; the next one has a budget of its own. Never tried again in this call, and
                // not on the next photo until its rest is over.
                code == 429 || code == 401 || code == 403 -> {
                    val ms = KeyRest.restFor(code, text,
                        AppConfig.long("camera.vision.badKeyMs", 6 * 3_600_000L), AppConfig.long("camera.vision.rateRestMs", 30_000L))
                    rest.rest(key, ms)
                    Log.w(TAG, "[CAMERA] groq describe HTTP $code on key #${i + 1}/${keys.size} — resting it for ${ms / 1000} s")
                    lastReason = "HTTP $code"
                }
                else -> {
                    Log.w(TAG, "[CAMERA] groq describe HTTP $code")
                    return@withContext VisionResult.Failed("HTTP $code")
                }
            }
        }
        VisionResult.Failed(lastReason)
    }

    /** One user turn: the prompt as a text part, the photo as a data-URL image part (Groq's vision request shape). */
    fun requestBody(model: String, prompt: String, imageBase64: String, temperature: Double): JSONObject {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")))
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("temperature", temperature)
            // A spoken answer is short; capping the reply also keeps the request under Groq's per-request token check.
            .put("max_completion_tokens", AppConfig.int("camera.describe.maxCompletion", 512))
            // Instruct mode, no thinking: a camera answer must come back in a second or two (Groq: qwen3.8 reasoning_effort
            // "none"; research, 20 Oct 2026). `providers.groq.visionReasoning` "" = leave it to the model's default.
            .apply { AppConfig.string("providers.groq.visionReasoning", "none").takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) } }
    }

    /** choices[0].message.content → trimmed text, or null when there is none. */
    fun parse(body: String): String? {
        val json = try { JSONObject(body) } catch (_: Throwable) { return null }
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        val content = message.opt("content") as? String ?: return null
        return content.trim().ifBlank { null }
    }
}

/**
 * Which of the camera's Groq keys are worth a request right now (the text planner's 6-hour skip of a rejected key, OpenAiCompatBrain,
 * for the camera too). Keys are held in memory only, by hash, and never logged. Pure over [clock] → KeyRestTest.
 */
class KeyRest(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val until = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** [keys] without the ones still resting, in order. */
    fun usable(keys: List<String>): List<String> {
        val now = clock()
        return keys.filter { (until[it.hashCode()] ?: 0L) <= now }
    }

    fun rest(key: String, ms: Long) { until[key.hashCode()] = clock() + ms }

    companion object {
        private val DAILY = Regex("(?i)per day|\\(TPD\\)|\\(RPD\\)")

        /** How long a key rests after [code]: refused (401/403) = [badMs]; rate-limited (429) = [rateMs], an hour when the body says
         *  its DAILY cap is spent. */
        fun restFor(code: Int, body: String, badMs: Long, rateMs: Long): Long = when {
            code == 401 || code == 403 -> badMs
            DAILY.containsMatchIn(body) -> 3_600_000L
            else -> rateMs
        }
    }
}
