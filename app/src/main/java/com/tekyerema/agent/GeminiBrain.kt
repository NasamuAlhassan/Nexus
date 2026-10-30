package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig
import com.tekyerema.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Product planner: Gemini via the Generative Language REST API with function calling. */
class GeminiBrain(private val settings: Settings) : AgentBrain {

    // N-04: derived from the shared pool.
    private val client = com.tekyerema.core.Http.client(
        AppConfig.long("models.gemini.timeouts.connectSec", 15), AppConfig.long("models.gemini.timeouts.callSec", 45))

    override suspend fun decide(ctx: BrainContext): AgentDecision = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey
        if (apiKey.isBlank()) return@withContext AgentDecision.Fail("No Gemini API key configured.")
        // The key travels in a header, never the URL (URLs end up in proxy and error logs).
        val base = AppConfig.string("providers.gemini.baseUrl", "https://generativelanguage.googleapis.com/v1beta").trimEnd('/')
        val url = "$base/models/${settings.model}:generateContent"

        // The privacy gate (core/PrivacyGate): phone numbers, emails and account numbers leave the phone as tags only.
        val raw = buildRequest(ctx).toString()
        val gate = if (com.tekyerema.core.PrivacyGate.enabled()) com.tekyerema.core.PrivacyGate.seal(raw)
            else com.tekyerema.core.PrivacyGate.Sealed(raw, emptyMap())
        if (gate.count > 0) android.util.Log.i("Tekyerema", "[PRIVACY] gate: ${gate.count} detail(s) sent to the cloud as tags (${gate.kinds()})")
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(gate.text.toRequestBody("application/json".toMediaType()))
            .build()

        // Bounded 429 retry, mirroring OpenAiCompatBrain — a rate-limited PLANNING call is retried;
        // the Android action itself (tool.execute()) only ever runs once this returns a decision,
        // so retrying here can never cause a duplicate tap/call/message.
        var rateAttempts = 0
        // §4.5 — cap the TOTAL 429 wait (default 60 s; was 6×30 s ≈ 180 s, which blew past the 180 s agent
        // ceiling); then fail honestly.
        var totalWaitMs = 0L
        val maxWaitMs = AppConfig.long("models.gemini.maxRateLimitWaitMs", 60_000L)
        while (true) {
            val (code, text) = try {
                // N-15: a hard deadline on top of OkHttp's — its callTimeout can't interrupt a thread stuck in DNS.
                (Hedge.within(AppConfig.long("models.gemini.timeouts.callSec", 45) * 1000L) {
                    com.tekyerema.core.Http.execute(client.newCall(request))   // cancellable
                } ?: throw java.io.InterruptedIOException("timeout")).let { (c, body, _) -> c to body }
            } catch (ce: kotlinx.coroutines.CancellationException) { throw ce
            } catch (t: Throwable) {
                com.tekyerema.core.telemetry.Telemetry.noteLlm("gemini", null)
                return@withContext AgentDecision.Fail("Gemini request failed: ${t.message}")
            }
            val usage = if (code in 200..299) com.tekyerema.core.telemetry.LlmUsage.fromGemini(text) else null
            com.tekyerema.core.telemetry.Telemetry.noteLlm("gemini", usage)   // Q-03 / N-03
            // B / sovereignty: an answered call counts toward the operator's daily budget.
            // One line per call, like Groq's: a Gemini failure used to be invisible in the log (A20e 2026-09-29).
            android.util.Log.i("Tekyerema", "[LLM] gemini/${settings.model} http=$code" +
                (if (code !in 200..299) " ${text.replace(Regex("\\s+"), " ").take(120)}" else ""))
            // The free tier's DAILY request cap: waiting can't help — let the chain report it at once.
            val dailyCap = code == 429 && Regex("(?i)per ?day|PerDay").containsMatchIn(text)
            when {
                dailyCap -> return@withContext AgentDecision.Fail("Gemini HTTP 429: daily quota used up")
                code == 429 && rateAttempts < 6 && totalWaitMs < maxWaitMs -> {
                    rateAttempts++
                    val waitMs = minOf(((parseRetrySeconds(text) ?: 8).coerceIn(2, 30)) * 1000L, maxWaitMs - totalWaitMs)
                    totalWaitMs += waitMs
                    delay(waitMs)
                }
                !(code in 200..299) -> return@withContext AgentDecision.Fail("Gemini HTTP $code: ${text.take(200)}")
                else -> return@withContext parseResponse(gate.open(text))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        AgentDecision.Fail("unreachable")
    }

    private fun buildRequest(ctx: BrainContext): JSONObject {
        val system = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", BrainPrompts.SYSTEM)))
        // B / injection hardening (A/B flag, default OFF): the screen as a part of its own behind a random delimiter.
        val parts = if (!BrainPrompts.screenInOwnMessage()) JSONArray().put(JSONObject().put("text", BrainPrompts.userText(ctx)))
            else BrainPrompts.nonce().let { n ->
                JSONArray().put(JSONObject().put("text", BrainPrompts.userText(ctx, screenSeparate = true, nonce = n)))
                    .put(JSONObject().put("text", BrainPrompts.screenBlock(ctx, n)))
            }
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", ctx.geminiTools))
        return JSONObject()
            .put("systemInstruction", system)
            .put("contents", contents)
            .put("tools", tools)
            .put("generationConfig", JSONObject().put("temperature", AppConfig.double("models.gemini.temperature", 0.2)))
    }

    private fun parseResponse(text: String): AgentDecision {
        val json = try { JSONObject(text) } catch (t: Throwable) { return AgentDecision.Fail("Unparseable Gemini response.") }
        val content = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?: return AgentDecision.Fail("No content in Gemini response.")
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val textBuf = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val fc = p.optJSONObject("functionCall")
            if (fc != null) {
                return AgentDecision.fromToolCall(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject(), null)
            }
            p.optString("text").takeIf { it.isNotBlank() }?.let { textBuf.append(it) }
        }
        return AgentDecision.fromText(textBuf.toString())
    }

    /** Gemini 429 bodies carry `"retryDelay": "13s"` inside error.details[]. */
    private fun parseRetrySeconds(body: String): Int? {
        val m = Regex("\"retryDelay\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+)?)s\"").find(body) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.let { Math.ceil(it).toInt() + 1 }
    }
}
