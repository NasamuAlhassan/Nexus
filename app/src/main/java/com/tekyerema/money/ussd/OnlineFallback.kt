package com.tekyerema.money.ussd

import com.tekyerema.core.config.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Level 4 (navigator brief §18): ONE tiny structured question to the small cloud model when the LOCAL matcher is
 * genuinely unsure — "which of these rows means SEND_MONEY?" — nothing else: no conversation, no history, no screen
 * beyond the menu rows, never a confirmation or PIN screen (the navigator refuses to call it there by construction:
 * those kinds never reach the matcher). The answer may only NAME a row that exists; anything else is a null, and the
 * navigator then asks the USER (§ LEVEL 5). Offline → null immediately.
 */
class OnlineFallback(private val keys: List<String>) {

    suspend fun choose(intent: TxIntent, rows: List<Pair<String, String>>): String? {
        if (rows.isEmpty() || keys.isEmpty()) return null
        if (!com.tekyerema.core.NetworkMonitor.isOnline()) return null
        val menu = rows.joinToString("\n") { (n, w) -> "$n. $w" }
        val body = JSONObject()
            .put("model", AppConfig.string("ussd.navigator.fallbackModel", "openai/gpt-oss-20b"))
            .put("temperature", 0)
            .put("max_tokens", 60)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "You map a Ghanaian telecom USSD menu row to a transaction intent. Answer ONLY JSON: " +
                        "{\"option\": \"<row number>\"} or {\"option\": null} if no row clearly matches. Never guess."))
                .put(JSONObject().put("role", "user").put("content",
                    "Intent: ${intent.name}\nMenu:\n$menu\nWhich row number most clearly corresponds to the intent?")))
        for (key in keys.take(2)) {
            try {
                val req = Request.Builder()
                    .url(AppConfig.string("providers.groq.baseUrl", "https://api.groq.com/openai") + "/v1/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val (code, text, _) = com.tekyerema.core.Http.execute(com.tekyerema.core.Http.client(4, 8).newCall(req))
                if (code == 429 || code == 401 || code == 403) continue   // next key
                if (code != 200) return null
                val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content") ?: return null
                val opt = JSONObject(content).opt("option")?.toString()?.trim()?.takeIf { it != "null" } ?: return null
                // The model may only NAME a row that is really on the screen.
                return rows.firstOrNull { it.first == opt }?.first
            } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { return null }
        }
        return null
    }
}
