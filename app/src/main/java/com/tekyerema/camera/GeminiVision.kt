package com.tekyerema.camera

import android.util.Base64
import android.util.Log
import com.tekyerema.BuildConfig
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Outcome of one describe request. [Failed.reason] is for logs only, never spoken. */
sealed class VisionResult {
    data class Described(val text: String) : VisionResult()
    data class Failed(val reason: String) : VisionResult()
}

/**
 * P-01 — one photo in, two short spoken sentences out, via Gemini vision (same REST endpoint, key header and model
 * setting as GeminiBrain). This is the only camera path that sends pixels off the phone, which is why the caller
 * checks the user's "Gemini backup" privacy switch first and sends exactly one frame per request.
 *
 * Never logs the image, and logs the description only in debug builds.
 */
object GeminiVision {
    private const val TAG = "Tekyerema"

    const val PROMPT = "Describe what is in front of a blind person's phone camera in two short sentences: " +
        "the main thing first, then any text or important detail. No markdown."

    /** Shared connection pool; per-call timeouts come from config via newBuilder(). */
    private val shared: OkHttpClient by lazy { OkHttpClient() }

    /** [prompt]: the camera's default, or F-09's screen/picture prompt (DescribeScreenTool). */
    suspend fun describe(jpeg: ByteArray, apiKey: String, model: String, prompt: String = PROMPT): VisionResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext VisionResult.Failed("no Gemini key")
        val timeoutSec = AppConfig.long("camera.describe.timeoutSec", 30L).coerceAtLeast(5L)
        val client = shared.newBuilder()
            .connectTimeout(minOf(timeoutSec, 15L), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        // The key travels in a header, never the URL (URLs end up in proxy and error logs).
        val base = AppConfig.string("providers.gemini.baseUrl", "https://generativelanguage.googleapis.com/v1beta").trimEnd('/')
        val body = requestBody(
            prompt,
            Base64.encodeToString(jpeg, Base64.NO_WRAP),
            AppConfig.double("camera.describe.temperature", 0.2)
        )
        val request = Request.Builder()
            .url("$base/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val (code, text) = try {
            await(client.newCall(request))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "[CAMERA] describe request failed: ${t.javaClass.simpleName}")
            return@withContext VisionResult.Failed("network: ${t.javaClass.simpleName}")
        }
        if (code !in 200..299) {
            Log.w(TAG, "[CAMERA] describe HTTP $code")
            if (BuildConfig.DEBUG) Log.d(TAG, "[CAMERA] describe error body: ${text.take(300)}")
            return@withContext VisionResult.Failed("HTTP $code")
        }
        val description = parse(text) ?: run {
            Log.w(TAG, "[CAMERA] describe: no text in response")
            return@withContext VisionResult.Failed("empty")
        }
        Log.i(TAG, "[CAMERA] described (${description.length} chars, ${jpeg.size / 1024} KB image)")
        if (BuildConfig.DEBUG) Log.d(TAG, "[CAMERA] description: $description")
        VisionResult.Described(description)
    }

    /** The generateContent body: one user turn holding the prompt and the inline JPEG. */
    fun requestBody(prompt: String, imageBase64: String, temperature: Double): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64)))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", temperature))
    }

    /** candidates[0].content.parts → the joined text (thought parts skipped), or null when there is none. */
    fun parse(body: String): String? {
        val json = try { JSONObject(body) } catch (_: Throwable) { return null }
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            val t = p.optString("text", "").trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
        return sb.toString().ifBlank { null }
    }

    /** Enqueue + suspend, so leaving the screen (scope cancelled) cancels the HTTP call instead of orphaning it. */
    private suspend fun await(call: Call): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { try { call.cancel() } catch (_: Throwable) {} }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body?.string().orEmpty() }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                    return
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }
}
