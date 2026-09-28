package com.tekyerema.policy

import android.util.Log
import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import com.tekyerema.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * J-19 / O-14 — an ISOLATED guard. Before a consequential action (send / call / pay) whose payload came from
 * third-party text, that text is classified by a separate injection model (Llama Prompt Guard 2 on Groq's free tier,
 * its own quota bucket — `models.guard.model`), not by the planner that might already be influenced by it. It only
 * ever ADDS a stop: a guard that is off, offline, rate-limited or unsure never blocks and never approves anything
 * (the deterministic J-06 checks and the user's confirmation still apply). Flag J-19 ships OFF until verified on device.
 */
object Sentinel {
    data class Verdict(val malicious: Boolean, val score: Double?, val detail: String)

    /** Pure: read a guard response's content — a label ("MALICIOUS"/"BENIGN"/"INJECTION"/"JAILBREAK") or a score. */
    fun parse(content: String): Verdict {
        val c = content.trim()
        val score = Regex("([01](?:\\.\\d+)?)").find(c)?.value?.toDoubleOrNull()
        val labelBad = Regex("(?i)\\b(malicious|injection|jailbreak|unsafe)\\b").containsMatchIn(c)
        val labelOk = Regex("(?i)\\b(benign|safe)\\b").containsMatchIn(c)
        val threshold = AppConfig.double("policy.sentinel.threshold", 0.8)
        val bad = labelBad || (!labelOk && score != null && score >= threshold)
        return Verdict(bad, score, c.take(60))
    }

    suspend fun check(texts: List<String>, settings: Settings): Verdict? {
        if (!FeatureFlags.isEnabled("J-19", false)) return null
        val keys = settings.groqApiKeys
        val body = texts.joinToString("\n").take(2000)
        if (keys.isEmpty() || body.isBlank()) return null
        val model = AppConfig.string("models.guard.model", "meta-llama/llama-prompt-guard-2-86m")
        return withContext(Dispatchers.IO) {
            try {
                val req = JSONObject().put("model", model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", body)))
                val call = com.tekyerema.core.Http.client(5, AppConfig.long("policy.sentinel.timeoutSec", 8)).newCall(
                    Request.Builder().url(settings.groqBaseUrl.trimEnd('/') + "/chat/completions")
                        .header("Authorization", "Bearer ${keys.last()}")   // the last key: keep the planner's first key free
                        .post(req.toString().toRequestBody("application/json".toMediaType())).build())
                call.execute().use { resp ->
                    if (!resp.isSuccessful) { Log.w("Tekyerema", "[SENTINEL] HTTP ${resp.code} — not blocking"); return@use null }
                    val content = JSONObject(resp.body?.string().orEmpty()).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty()
                    parse(content).also { Log.i("Tekyerema", "[SENTINEL] ${if (it.malicious) "MALICIOUS" else "ok"} (${it.detail})") }
                }
            } catch (t: Throwable) { Log.w("Tekyerema", "[SENTINEL] unavailable: ${t.message} — not blocking"); null }
        }
    }
}
