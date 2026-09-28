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

/**
 * Product planner over any OpenAI-compatible Chat Completions endpoint (Groq by default; also
 * OpenRouter / Together / a local Ollama server). Same seam a future on-device model plugs into.
 */
class OpenAiCompatBrain(private val settings: Settings) : AgentBrain {

    // 200s: a shared/free-tier GPU (e.g. a Kaggle T4) processing the full real prompt — long
    // system message + full multi-tool schema, ~3k tokens — can take well over a minute on
    // prefill alone. Generic headroom for any OpenAI-compatible endpoint, not tuned to one model.
    // Bounded so a slow/unreachable brain can never leave the user in "thinking" for minutes (§5/§10/§20).
    // The on-device LocalBrain handles simple goals ("open whatsapp") with NO network at all, so the cloud
    // is only for genuinely complex requests; 35 s is ample for those and fails fast otherwise.
    // N-04: derived from the shared pool (a warm TLS connection is reused across steps and tasks).
    private val client = com.tekyerema.core.Http.client(
        AppConfig.long("models.planner.timeouts.connectSec", 8), AppConfig.long("models.planner.timeouts.callSec", 35))

    /** Groq limits are per MODEL per organization: when the primary model's DAILY quota is gone on every key, the
     *  same keys still have a full, separate budget on the fallback model (seen: gpt-oss-120b 429 "tokens per day"
     *  on every key while gpt-oss-20b answered fresh). The free Gemini tier is only 20 requests/day, so this is the
     *  fallback that actually carries a multi-step task. */
    override suspend fun decide(ctx: BrainContext): AgentDecision {
        var models = (listOf(settings.groqModel) + AppConfig.stringList("models.planner.fallbacks", FALLBACK_MODELS)).distinct()
        val fast = AppConfig.string("models.planner.fastModel", "openai/gpt-oss-20b")
        // C-08: a simple step (a direct knowledge answer) goes to the fast model first, keeping the deep model's own
        // per-model daily/minute quota for GUI tasks.
        if (ctx.simple && fast in models && com.tekyerema.core.config.FeatureFlags.isEnabled("C-08", true))
            models = listOf(fast) + (models - fast)
        // N-10: on a weak connection the smaller model answers sooner (less to generate over a slow link).
        else if (fast in models && com.tekyerema.core.config.FeatureFlags.isEnabled("N-10", true) &&
            AppConfig.bool("router.network.fastModelWhenWeak", true) && com.tekyerema.core.NetworkMonitor.isWeak()) {
            android.util.Log.i("Tekyerema", "[LLM] weak network → $fast first")
            models = listOf(fast) + (models - fast)
        }
        // N-15: Groq limits are per MODEL. If every key is cooling on the first model (per-minute cap) for longer than
        // a short wait, take this step on the next model whose bucket is free now, instead of stalling.
        val keys = settings.groqApiKeys
        if (models.size > 1 && keys.isNotEmpty() && com.tekyerema.core.config.FeatureFlags.isEnabled("N-15", true)) {
            val now = System.currentTimeMillis()
            fun waitFor(m: String) = keys.minOf { coolUntil["$m|$it"] ?: 0L } - now
            val maxWait = AppConfig.long("router.quota.maxWaitMs", 1500L)
            if (waitFor(models[0]) > maxWait) models.drop(1).firstOrNull { waitFor(it) <= 0 }?.let { free ->
                android.util.Log.i("Tekyerema", "[LLM] ${models[0]} is cooling on every key → this step on $free (separate quota)")
                models = listOf(free) + (models - free)
            }
        }
        var last: AgentDecision = AgentDecision.Fail("no model configured")
        for ((i, model) in models.withIndex()) {
            last = decideWith(model, ctx)
            if (!(last is AgentDecision.Fail && last.reason.contains(DAILY_MARKER))) return last
            if (i < models.lastIndex)
                android.util.Log.w("Tekyerema", "[LLM] $model: daily quota used up on every key → falling back to ${models[i + 1]}")
        }
        return last
    }

    private suspend fun decideWith(model: String, ctx: BrainContext): AgentDecision = withContext(Dispatchers.IO) {
        val keys = settings.groqApiKeys
        if (keys.isEmpty()) return@withContext AgentDecision.Fail("No Groq/OpenAI API key configured.")
        val url = settings.groqBaseUrl.trimEnd('/') + "/chat/completions"

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", BrainPrompts.SYSTEM))
            .put(JSONObject().put("role", "user").put("content", BrainPrompts.userText(ctx)))

        // PROACTIVE PACING (Groq TPM): the free-tier per-MINUTE token cap (~8k) — not a daily quota — is the
        // real limit on multi-step tasks. Each step is ~5–6k tokens, so a single key sustains barely one step
        // a minute; blindly firing until 429 produced 80-second back-off stalls. Instead we read each
        // response's x-ratelimit-* headers and, the moment a key can't serve another full request, mark it
        // "cooling until it refills". Selection always takes the FRESHEST key (sticky to the last good one);
        // when EVERY key is cooling we wait precisely until the soonest refills — so a long task glides under
        // the cap instead of erroring. Token refill is continuous (limit/60s), so a cool is the deficit only.
        var corrected = false
        var emptyRetried = false
        var pacedWaits = 0
        while (true) {
            val now = System.currentTimeMillis()
            // freshest usable key, preferring the last good one (sticky), skipping any that are still cooling
            var chosen = -1
            for (n in keys.indices) {
                val i = (lastGoodKey + n) % keys.size
                if ((coolUntil["$model|${keys[i]}"] ?: 0L) <= now) { chosen = i; break }
            }
            if (chosen < 0) {
                val soonest = keys.minOf { coolUntil["$model|$it"] ?: 0L }
                // Every key is parked on a DAILY cap (minutes-to-hours away): pacing can't help — fail now so the
                // fallback chain moves straight to the next provider instead of spinning 8 s waits.
                if (soonest - now > 90_000L) return@withContext AgentDecision.Fail(
                    "The AI service is rate-limited right now ($DAILY_MARKER). Please try again later."
                )
                val waitMs = (soonest - now).coerceIn(250L, 8000L)
                if (++pacedWaits > 8) return@withContext AgentDecision.Fail(
                    "The AI service is rate-limited right now. Please try again in a moment."
                )
                android.util.Log.w("Tekyerema", "[LLM] all ${keys.size} keys cooling — pacing ${waitMs}ms (wait $pacedWaits)")
                delay(waitMs)
                continue
            }

            val apiKey = keys[chosen]
            val coolKey = "$model|$apiKey"   // limits are per model per org
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", ctx.openAiTools)
                .put("tool_choice", "auto")
                .put("temperature", AppConfig.double("models.planner.temperature", 0.2))
            // C-21: a debug override wins; else this step's effort (raised by the engine after failures); else the default.
            val effort = settings.reasoningEffortOverride?.takeIf { it.isNotBlank() } ?: ctx.effort ?: settings.groqReasoningEffort
            if (effort.isNotBlank()) body.put("reasoning_effort", effort)

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // SEC-P1-4: the request body carries the full screen snapshot (private messages, contacts, notif
            // text). Log it ONLY in debug builds — never in the shared/release APK.
            if (com.tekyerema.BuildConfig.DEBUG) android.util.Log.d("Tekyerema", "[LLM-REQ] ${body.toString().take(4000)}")
            var remaining: Int? = null; var limitTok: Int? = null; var resetMs: Long? = null; var retryMs: Long? = null
            val (code, text) = try {
                client.newCall(request).execute().use { resp ->
                    remaining = resp.header("x-ratelimit-remaining-tokens")?.trim()?.toIntOrNull()
                    limitTok = resp.header("x-ratelimit-limit-tokens")?.trim()?.toIntOrNull()
                    resetMs = parseDurationMs(resp.header("x-ratelimit-reset-tokens"))
                    retryMs = resp.header("retry-after")?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                    resp.code to resp.body?.string().orEmpty()
                }
            } catch (t: Throwable) {
                com.tekyerema.core.telemetry.Telemetry.noteLlm("groq/$model", null)
                return@withContext AgentDecision.Fail("LLM request failed: ${t.message}")
            }
            // Q-03 / N-03 / Q-10: every call counts; tokens (incl. cached) and the key's remaining minute budget when given.
            com.tekyerema.core.telemetry.Telemetry.noteLlm("groq/$model",
                if (code in 200..299) com.tekyerema.core.telemetry.LlmUsage.fromOpenAi(text) else null, remaining)

            when {
                // Rate-limited: cool THIS key until its own reset (precise), then the loop picks another or waits.
                code == 429 -> {
                    // A tokens/requests-PER-DAY cap (Groq free tier: 200k tokens/day per organization) returns a
                    // retry-after of minutes to hours; honour it in full, or the key is retried every minute for nothing.
                    val daily = Regex("(?i)per day|\\(TPD\\)|\\(RPD\\)").containsMatchIn(text)
                    val cool = (retryMs ?: resetMs ?: parseRetrySeconds(text)?.times(1000L) ?: 4000L)
                        .coerceIn(500L, if (daily) 24 * 3_600_000L else 60_000L)
                    coolUntil[coolKey] = System.currentTimeMillis() + cool
                    android.util.Log.w("Tekyerema", "[LLM] 429 on key #${chosen + 1}/${keys.size} → cooling ${cool}ms${if (daily) " (daily quota)" else ""}")
                }
                code == 400 && text.contains("tool_use_failed") && !corrected -> {
                    // The model invented an invalid tool. Nudge it and retry once.
                    corrected = true
                    messages.put(
                        JSONObject().put("role", "user").put(
                            "content",
                            "Your previous response called a tool that does not exist. " +
                                "Call ONLY one of the provided tools, using its exact name."
                        )
                    )
                }
                code !in 200..299 -> return@withContext AgentDecision.Fail("LLM HTTP $code: ${text.take(220)}")
                else -> {
                    lastGoodKey = chosen   // sticky: next step starts from a key that just worked
                    // Proactively cool this key if it can't serve another full request, so the NEXT step skips
                    // it instead of eating a 429. Refill is continuous, so wait only for the token DEFICIT,
                    // never longer than the header's full-reset time.
                    val rem = remaining
                    if (rem != null && rem < ONE_REQUEST_TOKENS) {
                        val refillPerMs = (limitTok ?: 8000) / 60_000.0
                        val deficitMs = if (refillPerMs > 0) ((ONE_REQUEST_TOKENS - rem) / refillPerMs).toLong() else (resetMs ?: 3000L)
                        val cool = minOf(deficitMs, resetMs ?: deficitMs).coerceIn(0L, 60_000L)
                        if (cool > 0) coolUntil[coolKey] = System.currentTimeMillis() + cool else coolUntil.remove(coolKey)
                    } else coolUntil.remove(coolKey)
                    if (com.tekyerema.BuildConfig.DEBUG) android.util.Log.d("Tekyerema", "[LLM-RESP] ${text.take(4000)}")   // SEC-P1-4: debug-only
                    val d = parseResponse(text)
                    // gpt-oss (seen with the 20b) sometimes ends a turn with reasoning only — no tool call, no text.
                    // One nudged retry; otherwise the chain fell through to Gemini's 429 and the user heard
                    // "too many requests" for what was really an empty reply.
                    if (d is AgentDecision.Fail && d.reason.contains("neither a tool call nor text") && !emptyRetried) {
                        emptyRetried = true
                        messages.put(JSONObject().put("role", "user").put("content",
                            "Your last reply was empty. Call exactly one tool now, or reply with one short sentence."))
                        continue
                    }
                    return@withContext d
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        AgentDecision.Fail("unreachable")
    }

    companion object {
        /** Turn a chat-completions response into a decision. Pure (no instance state), so it is unit-tested. */
        fun parseResponse(text: String): AgentDecision {
            val json = try { JSONObject(text) } catch (t: Throwable) { return AgentDecision.Fail("Unparseable LLM response.") }
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return AgentDecision.Fail("No message in LLM response.")
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val fn = toolCalls.optJSONObject(0)?.optJSONObject("function")
                    ?: return AgentDecision.Fail("Malformed tool call.")
                val name = fn.optString("name")
                val rawArgs = fn.optString("arguments").ifBlank { "{}" }
                // D-06: never run a tool on silently-emptied arguments. Repair the common damage (code fences,
                // trailing prose) and otherwise hand the engine a rejection it can feed back to the model.
                val args = tryJson(rawArgs) ?: jsonObjectsIn(rawArgs).firstOrNull()
                    ?: return AgentDecision.fromToolCall(name, JSONObject(), null,
                        argError = "its arguments were not valid JSON (${rawArgs.take(80)})")
                return AgentDecision.fromToolCall(name, args, null)
            }
            // No structured tool_calls. gpt-oss-120b (and some other models) frequently emit the tool call as
            // prose/JSON in `content` instead of the tool_calls channel — which previously became Malformed and
            // looped the agent to MAX_STEPS. Recover it: parse a tool call out of the content and EXECUTE it,
            // so multi-step tasks actually progress. Falls back to plain text (Done/Malformed) only if none.
            val content = message.optString("content")
            recoverToolCallFromContent(content)?.let { return it }
            return AgentDecision.fromText(content)
        }

        /** Sticky index of the last Groq key that returned successfully — shared across tasks so a
         *  rate-limited key isn't re-tried first on every step. Guarded only by @Volatile (best-effort). */
        @Volatile private var lastGoodKey = 0

        /** Per-key "cooling until" epoch-ms, shared across steps/tasks: set from a 429's Retry-After/reset,
         *  or proactively when a key's remaining tokens drop below one request's worth. A cooling key is
         *  skipped by selection until then, so the pacer glides under the per-minute token cap. */
        private val coolUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** Tokens one full step costs (~prompt + completion). A key with fewer remaining is cooled so the
         *  next step doesn't fire a request that would 429. Sized to the ACTUAL step cost after the prompt
         *  trim (~4.4k typical); the old 6000 padded ~1.5k over reality and cooled each key ~18s instead of
         *  ~7s between steps. A too-low value only risks an occasional 429, which the 4-key rotation absorbs. */
        private val ONE_REQUEST_TOKENS get() = AppConfig.int("models.planner.requestTokens", 4800)

        /** Tried in order after the configured model when its daily quota is exhausted on every key. */
        private val FALLBACK_MODELS = listOf("openai/gpt-oss-20b")
        private const val DAILY_MARKER = "daily quota on every key"

        /** Parse a Groq rate-limit reset duration ("547ms", "24.652s", "4m19.2s", "1m0s") to milliseconds. */
        fun parseDurationMs(s: String?): Long? {
            val t = s?.trim()?.lowercase() ?: return null
            if (t.isEmpty()) return null
            Regex("^([0-9]+(?:\\.[0-9]+)?)ms$").find(t)?.let { return it.groupValues[1].toDouble().toLong() }
            var total = 0.0; var matched = false
            for (m in Regex("([0-9]+(?:\\.[0-9]+)?)(h|m|s)").findAll(t)) {
                matched = true
                total += m.groupValues[1].toDouble() * when (m.groupValues[2]) {
                    "h" -> 3_600_000.0; "m" -> 60_000.0; "s" -> 1_000.0; else -> 0.0
                }
            }
            return if (matched) total.toLong() else null
        }

        // The model's tool vocabulary — used to validate a content-embedded call so ordinary prose that
        // merely mentions a word isn't mistaken for a call. D-06: derived from the registry, never a stale copy.
        private val KNOWN_TOOLS: Set<String> get() = AgentDecision.knownTools

        /** Extract a tool call the model wrote into `content` (prose or JSON), or null if there isn't one.
         *  Handles three shapes: a JSON object with name+arguments (optionally ```fenced``` or nested under
         *  `function`/`tool_call`), and the prose form `name({...})`. Pure + testable. */
        fun recoverToolCallFromContent(content: String?): AgentDecision? {
            val c = content?.trim().orEmpty()
            if (c.isEmpty()) return null
            // (a) prose form: open_app({"app":"YouTube"})
            Regex("\\b([a-z_]+)\\s*\\(\\s*(\\{[\\s\\S]*?\\})\\s*\\)").findAll(c).forEach { m ->
                val name = m.groupValues[1]
                if (name in KNOWN_TOOLS) tryJson(m.groupValues[2])?.let { return AgentDecision.fromToolCall(name, it, null) }
            }
            // (b) JSON object(s) with a tool name (possibly fenced / nested under function|tool_call)
            for (obj in jsonObjectsIn(c)) {
                val call = obj.optJSONObject("function") ?: obj.optJSONObject("tool_call") ?: obj
                val name = call.optString("name")
                if (name in KNOWN_TOOLS) {
                    val args = call.optJSONObject("arguments") ?: call.optJSONObject("parameters")
                        ?: call.optString("arguments").takeIf { it.isNotBlank() }?.let { tryJson(it) }
                        ?: JSONObject()
                    return AgentDecision.fromToolCall(name, args, null)
                }
            }
            return null
        }

        private fun tryJson(s: String): JSONObject? = try { JSONObject(s) } catch (t: Throwable) { null }

        /** Every top-level {...} JSON object found in text (strips ``` fences first). Brace-balanced scan. */
        private fun jsonObjectsIn(text: String): List<JSONObject> {
            val s = text.replace(Regex("```[a-zA-Z]*"), "").replace("```", "")
            val out = ArrayList<JSONObject>()
            var i = 0
            while (i < s.length) {
                if (s[i] == '{') {
                    var depth = 0; var j = i; var inStr = false; var esc = false
                    while (j < s.length) {
                        val ch = s[j]
                        if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false }
                        else { if (ch == '"') inStr = true else if (ch == '{') depth++ else if (ch == '}') { depth--; if (depth == 0) break } }
                        j++
                    }
                    if (depth == 0 && j < s.length) { tryJson(s.substring(i, j + 1))?.let { out.add(it) }; i = j + 1; continue }
                }
                i++
            }
            return out
        }
    }

    /** Groq 429 bodies say e.g. "Please try again in 2.5s". */
    private fun parseRetrySeconds(body: String): Int? {
        val m = Regex("try again in ([0-9]+(?:\\.[0-9]+)?)s").find(body) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.let { Math.ceil(it).toInt() + 1 }
    }
}
