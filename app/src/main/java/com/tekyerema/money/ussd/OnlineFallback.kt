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
    companion object {
        /** The chat-completions endpoint for an OpenAI-compatible base URL, whether or not it already ends in `/v1`:
         *  `models.json` carries `…/openai/v1`, and this used to append `/v1/chat/completions` to it (B-14, found 7 October
         *  2026) — every call went to `…/openai/v1/v1/chat/completions` and the fallback could never answer. Pure, tested. */
        fun chatCompletionsUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return (if (base.endsWith("/v1")) base else "$base/v1") + "/chat/completions"
        }
    }


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
                    .url(chatCompletionsUrl(AppConfig.string("providers.groq.baseUrl", "https://api.groq.com/openai/v1")))
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

    /** The brain's move toward a user's goal on one menu screen (TxIntent.GOAL). */
    sealed class GoalPick {
        data class Row(val option: String) : GoalPick()
        data class Ask(val question: String) : GoalPick()
        object None : GoalPick()
    }

    /**
     * Which row moves toward [goal] (the user's words), given the rows already chosen ([path]) — or a short question when the
     * menu is ambiguous for the goal (two plans, two wallets), or none. Never a confirmation or PIN screen (those don't reach
     * here). The answer may only NAME a row that exists on this screen; anything else is None (owner, 22 Oct 2026: the brain
     * understands the network's menus — a midnight bundle, Allow Cash Out — instead of a fixed list of jobs).
     */
    suspend fun chooseForGoal(goal: String, rows: List<Pair<String, String>>, path: List<String>, amount: String?): GoalPick? {
        if (goal.isBlank() || rows.isEmpty() || keys.isEmpty()) return null
        if (!com.tekyerema.core.NetworkMonitor.isOnline()) return null
        val menu = rows.joinToString("\n") { (n, w) -> "$n. $w" }
        val body = JSONObject()
            .put("model", AppConfig.string("ussd.goal.model", "openai/gpt-oss-120b"))
            .put("temperature", 0)
            // gpt-oss reasons before it answers, and its reasoning counts against this cap (the app's own logs: 88–249 reasoning tokens
            // a step). At its default ("medium") effort 300 can run out before any JSON — the walk then can't choose (audit, 22 Oct 2026).
            .put("max_tokens", AppConfig.int("ussd.goal.maxTokens", 1024))
            .apply { AppConfig.string("ussd.goal.reasoning", "low").takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) } }
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "You walk a Ghanaian mobile network's USSD menu for a blind user, one screen at a time, toward what they asked. " +
                        "Answer ONLY JSON: {\"option\": \"<row number>\"} for the row that moves toward the goal; " +
                        "{\"ask\": \"<one short question>\"} when two or more rows could both be what they want (say what differs); " +
                        "{\"option\": null} when nothing on this screen leads there (a 'Next'/'More' row counts as leading there only " +
                        "if the goal is clearly not on this page). Never pick a row that buys, pays or sends something different from the " +
                        "goal. A price the user named must match the row's price."))
                .put(JSONObject().put("role", "user").put("content",
                    "Goal: $goal" + (amount?.let { "\nAmount the user named: $it cedis" } ?: "") +
                        (if (path.isEmpty()) "" else "\nChosen so far: " + path.joinToString(" > ")) + "\nMenu:\n$menu")))
        for (key in keys.take(3)) {
            try {
                val req = Request.Builder()
                    .url(chatCompletionsUrl(AppConfig.string("providers.groq.baseUrl", "https://api.groq.com/openai/v1")))
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val (code, text, _) = com.tekyerema.core.Http.execute(com.tekyerema.core.Http.client(5, 15).newCall(req))
                if (code == 429 || code == 401 || code == 403) continue   // next key
                if (code != 200) return null
                val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content") ?: return null
                val o = JSONObject(content)
                o.optString("ask").trim().takeIf { it.isNotEmpty() && it != "null" }?.let { return GoalPick.Ask(it.take(160)) }
                val opt = o.opt("option")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return GoalPick.None
                // The model may only NAME a row that is really on the screen.
                return rows.firstOrNull { it.first == opt }?.first?.let { GoalPick.Row(it) } ?: GoalPick.None
            } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { return null }
        }
        return null
    }
}
