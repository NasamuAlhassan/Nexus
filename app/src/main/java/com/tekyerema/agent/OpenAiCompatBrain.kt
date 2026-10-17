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

/** One HTTP reply: status code, body text, headers. */
internal typealias Reply = Triple<Int, String, okhttp3.Headers>

/**
 * Product planner over any OpenAI-compatible Chat Completions endpoint (Groq by default; also
 * OpenRouter / Together / a local Ollama server). Same seam a future on-device model plugs into.
 *
 * B (quota-aware routing): with a [route], a COMPLEX step stays on the capable model(s) and waits out a per-minute
 * limit up to `router.quota.complexMaxWaitMs` (~20 s) instead of dropping to gpt-oss-20b; the weaker model is used only
 * through [decideWeak], which [FallbackBrain] calls per the operator's weak-model policy. Without a route (tests, old
 * callers) nothing changes.
 */
class OpenAiCompatBrain(
    private val settings: Settings,
    private val route: com.tekyerema.agent.routing.TaskRoute? = null,
    /** P1-15: which OpenAI-compatible endpoint this brain talks to — Groq from Settings unless an extra rung is given. */
    private val provider: PlannerProvider = PlannerProvider.groq(settings)
) : AgentBrain, com.tekyerema.agent.routing.QuotaAware {

    /** Models that must not carry a complex task on their own (C-08's fast model is one). */
    private fun weakModels(): List<String> = AppConfig.stringList("models.planner.weakModels", listOf("openai/gpt-oss-20b"))

    /** The primary model plus any configured fallback that isn't a weak one. */
    internal fun capableModels(): List<String> =
        capableOf(provider.model, provider.fallbacks, weakModels())

    override val weakModel: String?
        get() = weakOf(provider.model, provider.fallbacks,
            AppConfig.string("models.planner.fastModel", "openai/gpt-oss-20b"), weakModels())

    override fun capableReadyInMs(now: Long): Long? {
        val keys = provider.keys
        if (keys.isEmpty()) return null
        val soonest = capableModels().minOf { m -> keys.minOf { coolUntil["$m|$it"] ?: 0L } } - now
        return when { soonest <= 0L -> 0L; soonest > 90_000L -> null; else -> soonest }
    }

    override suspend fun decideWeak(ctx: BrainContext): AgentDecision {
        val m = weakModel ?: return AgentDecision.Fail("no weaker model configured ($DAILY_MARKER)")
        return decideWith(m, ctx, canFallBack = true, maxWaitMs = route?.policy?.complexMaxWaitMs ?: 1500L)
    }

    /** B: a complex step on a pinned task — capable models only, a per-minute limit waited out up to ~20 s. */
    private suspend fun decideCapable(ctx: BrainContext, r: com.tekyerema.agent.routing.TaskRoute): AgentDecision {
        var last: AgentDecision = AgentDecision.Fail("no model configured")
        for (model in capableModels()) {
            last = decideWith(model, ctx, canFallBack = true, maxWaitMs = r.policy.complexMaxWaitMs)
            val daily = last is AgentDecision.Fail && last.reason.contains(DAILY_MARKER)
            val paced = last is AgentDecision.Fail && last.reason.contains(PACE_MARKER)
            if (!daily && !paced) return last
        }
        return last
    }

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
        // B: a complex step of a routed task never slides onto the weak model here (FallbackBrain decides that, per policy).
        if (route != null && com.tekyerema.agent.routing.ModelRouting.complex(ctx)) return decideCapable(ctx, route)
        var models = (listOf(provider.model) + provider.fallbacks).distinct()
        val fast = AppConfig.string("models.planner.fastModel", "openai/gpt-oss-20b")
        // C-08: a simple step (a direct knowledge answer) goes to the fast model first, keeping the deep model's own
        // per-model daily/minute quota for GUI tasks.
        if (ctx.simple && fast in models && com.tekyerema.core.config.FeatureFlags.isEnabled("C-08", true))
            models = listOf(fast) + (models - fast)
        // N-10: on a weak connection the smaller model answers sooner (less to generate over a slow link) — but not for a
        // step the engine raised to "high" effort because the task is failing: that is exactly when the deeper model
        // earns its extra second (cascade: escalate on a failed verification, not by default — arXiv 2603.04445).
        else if (fast in models && ctx.effort != "high" && com.tekyerema.core.config.FeatureFlags.isEnabled("N-10", true) &&
            AppConfig.bool("router.network.fastModelWhenWeak", true) && com.tekyerema.core.NetworkMonitor.isWeak()) {
            android.util.Log.i("Tekyerema", "[LLM] weak network → $fast first")
            models = listOf(fast) + (models - fast)
        }
        // N-15: Groq limits are per MODEL. If every key is cooling on the first model (per-minute cap) for longer than
        // a short wait, take this step on the next model whose bucket is free now, instead of stalling.
        val keys = provider.keys
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
            last = decideWith(model, ctx, canFallBack = i < models.lastIndex)
            val daily = last is AgentDecision.Fail && last.reason.contains(DAILY_MARKER)
            val paced = last is AgentDecision.Fail && last.reason.contains(PACE_MARKER)
            if (!daily && !paced) return last
            if (i < models.lastIndex)
                android.util.Log.w("Tekyerema", "[LLM] $model: " + (if (daily) "daily quota used up on every key" else
                    "every key is cooling") + " → this step on ${models[i + 1]}")
        }
        return last
    }

    private suspend fun decideWith(model: String, ctx: BrainContext, canFallBack: Boolean = false,
                                   maxWaitMs: Long = AppConfig.long("router.quota.maxWaitMs", 1500L)): AgentDecision = withContext(Dispatchers.IO) {
        val keys = provider.keys
        if (keys.isEmpty()) return@withContext AgentDecision.Fail("No ${provider.id} API key configured.")
        val url = provider.chatUrl

        val messages = messagesFor(ctx)

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
        // The reply allowance counts toward Groq's per-request TPM check (prompt + max completion ≤ 8k on the free tier):
        // with no cap its default reserve pushed a 6.3k-token step to "Requested 8106, Limit 8000" (HTTP 413, A20e
        // 2026-09-29). A planner reply, reasoning included, is ~100–300 tokens; "high" effort gets more room.
        var completionCap = AppConfig.int(if (ctx.effort == "high") "models.planner.maxCompletionHigh" else "models.planner.maxCompletion",
            if (ctx.effort == "high") 2048 else 1024)
        var shrunk = false
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
                // A2 (device 2026-09-29): three keys on the daily cap and the fourth per-minute limited paced 8 s at a time
                // for a minute while the next model's separate quota sat unused. Move on instead of waiting long.
                if (canFallBack && soonest - now > maxWaitMs)
                    return@withContext AgentDecision.Fail("The AI service is busy right now ($PACE_MARKER).")
                val waitMs = (soonest - now).coerceIn(250L, 8000L)
                if (++pacedWaits > 8) return@withContext AgentDecision.Fail(
                    "The AI service is rate-limited right now. Please try again in a moment."
                )
                android.util.Log.w("Tekyerema", "[LLM] all ${keys.size} keys cooling — pacing ${waitMs}ms (wait $pacedWaits)")
                // A deliberate wait, not a silent network: the FallbackBrain watchdog must not take it for a dead link.
                BrainActivity.pausingFor(waitMs)
                delay(waitMs)
                continue
            }

            var apiKey = keys[chosen]
            var coolKey = "$model|$apiKey"   // limits are per model per org
            // C-21: a debug override wins; else this step's effort (raised by the engine after failures); else the default.
            val effort = settings.reasoningEffortOverride?.takeIf { it.isNotBlank() } ?: ctx.effort ?: settings.groqReasoningEffort
            val body = requestBody(model, messages, ctx.openAiTools, AppConfig.double("models.planner.temperature", 0.2), effort, completionCap, provider)

            val payload = body.toString()
            fun requestFor(key: String) = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            // SEC-P1-4: the request body carries the full screen snapshot (private messages, contacts, notif
            // text). Log it ONLY in debug builds — never in the shared/release APK.
            if (com.tekyerema.BuildConfig.DEBUG) android.util.Log.d("Tekyerema", "[LLM-REQ] ${payload.take(4000)}")
            // Prompt-cache forensics (13 October 2026): a fingerprint of the parts Groq's prefix cache sees before the step's
            // own message — the same two hashes on consecutive steps with cached=0 means the miss is on the provider's side
            // (per-organisation caching), a changed hash means our prefix moved. Debug builds only; hashes, never content.
            if (com.tekyerema.BuildConfig.DEBUG) try {
                fun md5(s: String) = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
                android.util.Log.d("Tekyerema", "[LLM-FP] key#${chosen + 1} model=$model sys=${md5(messages.optJSONObject(0)?.toString().orEmpty())} " +
                    "tools=${md5(ctx.openAiTools.toString())} ntools=${ctx.openAiTools.length()} body=${payload.length} effort=${effort ?: "default"}")
            } catch (_: Throwable) {}
            // D-02: the request log is cut before the tool list, so name the tools this step offered (names only).
            if (com.tekyerema.BuildConfig.DEBUG) android.util.Log.d("Tekyerema", "[LLM-TOOLS] " + (0 until ctx.openAiTools.length())
                .joinToString(",") { ctx.openAiTools.optJSONObject(it)?.optJSONObject("function")?.optString("name").orEmpty() })
            var remaining: Int? = null; var limitTok: Int? = null; var resetMs: Long? = null; var retryMs: Long? = null
            // N-15 hedge: the pooled connection is the usual suspect when a call hangs (A20e, 2026-09-28: seven Groq calls in a
            // row ran into the 35 s timeout while Gemini answered in 4 s). If this call hasn't answered after a few seconds
            // — far past the normal 1–2 s — the same request also goes out on a FRESH connection with the next free key;
            // the first response wins and the other is cancelled. Cancellable: stopping the task aborts both.
            val callMs = AppConfig.long("models.planner.timeouts.callSec", 35) * 1000L
            val backupIdx = hedgeKeyIndex(keys.size, chosen) { i -> (coolUntil["$model|${keys[i]}"] ?: 0L) <= System.currentTimeMillis() }
            val hedgeMs = hedgeAfterMs(effort)
            val t0 = System.currentTimeMillis()
            val attempts = buildList<suspend () -> Result<Reply>> {
                add { callOnce(client, requestFor(keys[chosen]), callMs) }
                if (hedgeMs > 0 && com.tekyerema.core.config.FeatureFlags.isEnabled("N-15", true))
                    add { callOnce(freshClient, requestFor(keys[backupIdx]), (callMs - hedgeMs).coerceAtLeast(5_000L)) }
            }
            val race = Hedge.race(attempts, hedgeMs.coerceAtLeast(1L)) { it.isSuccess }
            // The first request was slow (cancelled as the loser) or failed: drop the pooled connections, so a hung one can't
            // serve the next step too (seen: seven consecutive timeouts on one stalled connection).
            val primary = race.results.firstOrNull()
            if (primary == null || primary.isFailure) evictPooled(client)
            val reply = race.winner?.getOrNull()
            if (reply == null) {
                com.tekyerema.core.telemetry.Telemetry.noteLlm("groq/$model", null)
                val err = race.results.firstNotNullOfOrNull { it?.exceptionOrNull() }
                android.util.Log.w("Tekyerema", "[LLM] $model: no answer after ${System.currentTimeMillis() - t0} ms " +
                    "(${race.started} attempt${if (race.started > 1) "s, one on a fresh connection" else ""}): ${err?.javaClass?.simpleName}")
                return@withContext AgentDecision.Fail("LLM request failed: ${err?.message ?: "timeout"}")
            }
            if (race.winnerIndex == 1) {
                chosen = backupIdx; apiKey = keys[chosen]; coolKey = "$model|$apiKey"
                android.util.Log.w("Tekyerema", "[LLM] hedged: the fresh-connection request (key #${chosen + 1}) answered first " +
                    "after ${System.currentTimeMillis() - t0} ms — the pooled connection was stalled")
            }
            val (code, text, headers) = reply
            remaining = headers["x-ratelimit-remaining-tokens"]?.trim()?.toIntOrNull()
            limitTok = headers["x-ratelimit-limit-tokens"]?.trim()?.toIntOrNull()
            resetMs = parseDurationMs(headers["x-ratelimit-reset-tokens"])
            retryMs = headers["retry-after"]?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            // Q-03 / N-03 / Q-10: every call counts; tokens (incl. cached) and the key's remaining minute budget when given.
            val usage = if (code in 200..299) com.tekyerema.core.telemetry.LlmUsage.fromOpenAi(text) else null
            com.tekyerema.core.telemetry.Telemetry.noteLlm("groq/$model", usage, remaining)
            // B / sovereignty: every answered cloud call counts toward the operator's daily budget (OperatorPolicy).
            // N-03: one line per call, so the phone run shows latency and how much of each prompt Groq served from its
            // prefix cache (cached tokens are faster and don't count toward the per-minute cap).
            android.util.Log.i("Tekyerema", "[LLM] ${usageLine(model, chosen, keys.size, code, System.currentTimeMillis() - t0, usage, effort, provider.id)}")

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
                // A key the provider REJECTS (401 "Invalid API Key"): skip it for hours and take the next key — it used to end
                // the whole Groq attempt, so every step went on to Gemini (3–8 s slower) while three good keys sat unused
                // (A20e, 17 Oct 2026: GROQ_API_KEY_2, seven times). Only when no key is left does the step fail.
                code == 401 -> {
                    coolUntil[coolKey] = System.currentTimeMillis() + 6 * 3_600_000L
                    val now = System.currentTimeMillis()
                    val live = keys.indices.count { (coolUntil["$model|${keys[it]}"] ?: 0L) <= now }
                    android.util.Log.w("Tekyerema", "[LLM] key #${chosen + 1}/${keys.size} was rejected (401) — skipping it " +
                        "for 6 h ($live usable key(s) left); check GROQ_API_KEY_${chosen + 1} in local.properties")
                    if (live == 0) return@withContext AgentDecision.Fail("LLM HTTP 401: ${text.take(220)}")
                    continue
                }
                // Still over the per-request limit: lower the reply allowance by the overshoot once and resend.
                code == 413 && !shrunk && TOO_LARGE.containsMatchIn(text) -> {
                    shrunk = true
                    val m = TOO_LARGE.find(text)!!
                    val over = m.groupValues[2].toInt() - m.groupValues[1].toInt()
                    val next = completionCap - over - 64
                    if (next < 384) return@withContext AgentDecision.Fail("LLM HTTP 413: this step's screen is too large for the model's limit")
                    android.util.Log.w("Tekyerema", "[LLM] request ${over} tokens over the per-request limit — reply allowance $completionCap → $next, resending")
                    completionCap = next
                    continue
                }
                code == 400 && text.contains("tool_use_failed") && !corrected -> {
                    // The model invented an invalid tool — or (B) its arguments failed the schema check, e.g. a `plan` sent
                    // as a list. Nudge it with the real reason and retry once.
                    corrected = true
                    messages.put(JSONObject().put("role", "user").put("content", toolUseFailedNudge(text)))
                }
                code !in 200..299 -> return@withContext AgentDecision.Fail("LLM HTTP $code: ${text.take(220)}")
                else -> {
                    lastGoodKey = chosen   // sticky: next step starts from a key that just worked
                    // Proactively cool this key if it can't serve another full request, so the NEXT step skips
                    // it instead of eating a 429. Refill is continuous, so wait only for the token DEFICIT,
                    // never longer than the header's full-reset time.
                    val rem = remaining
                    val need = nextRequestTokens(usage, ONE_REQUEST_TOKENS)
                    if (rem != null && rem < need) {
                        val refillPerMs = (limitTok ?: 8000) / 60_000.0
                        val deficitMs = if (refillPerMs > 0) ((need - rem) / refillPerMs).toLong() else (resetMs ?: 3000L)
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
                        // It may have been cut off by the reply allowance: give the retry twice the room, within the limit.
                        usage?.let { u -> completionCap = maxOf(completionCap, minOf(completionCap * 2, 7_900 - u.prompt)) }
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
        /** B: the models that may carry a complex step — the primary, then fallbacks that aren't weak. Pure. */
        fun capableOf(primary: String, fallbacks: List<String>, weak: Collection<String>): List<String> =
            (listOf(primary) + fallbacks.filter { it !in weak }).distinct()

        /** B: the weaker model FallbackBrain may hand a complex step to (per policy), or null. Pure. */
        fun weakOf(primary: String, fallbacks: List<String>, fast: String, weak: Collection<String>): String? =
            (fallbacks + fast).distinct().firstOrNull { it in weak && it != primary }

        /**
         * N-03: a step's messages — the fixed system prompt, then this step's message (task-fixed parts first, the screen
         * last: [BrainPrompts.userText]). With the tool list ([ToolSelector.select]'s cache-stable order) this is what
         * Groq's prefix cache sees; nothing in front of the step's own message varies between the steps of a task.
         */
        fun messagesFor(ctx: BrainContext, nonce: String = BrainPrompts.nonce()): JSONArray {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", BrainPrompts.SYSTEM))
            // B / injection hardening (A/B flag `llm.injection.screenInOwnMessage`, default OFF): the screen — third-party
            // text — leaves the user's message for a message of its own behind a per-request random delimiter; by default
            // as the result of a synthetic read_state call (the tool role is the lowest rung of gpt-oss's instruction
            // hierarchy), else as a second user message. Still last, so the prompt-cache prefix is unchanged.
            if (!BrainPrompts.screenInOwnMessage()) return msgs.put(JSONObject().put("role", "user").put("content", BrainPrompts.userText(ctx)))
            msgs.put(JSONObject().put("role", "user").put("content", BrainPrompts.userText(ctx, screenSeparate = true, nonce = nonce)))
            val offered = (0 until ctx.openAiTools.length()).any { ctx.openAiTools.optJSONObject(it)?.optJSONObject("function")?.optString("name") == "read_state" }
            val screen = BrainPrompts.screenBlock(ctx, nonce)
            if (BrainPrompts.screenRole() == "tool" && offered) {
                val id = "obs_$nonce"
                msgs.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", id).put("type", "function").put("function", JSONObject().put("name", "read_state").put("arguments", "{}")))))
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", screen))
            } else msgs.put(JSONObject().put("role", "user").put("content", screen))
            return msgs
        }

        /** The chat-completions body. Pure, so the prefix a step shares with the one before it is unit-tested. gpt-oss puts
         *  the reasoning effort at the very top of its prompt, so a step at a different effort starts a cache of its own. */
        fun requestBody(model: String, messages: JSONArray, tools: JSONArray, temperature: Double, effort: String?,
                        maxCompletion: Int? = null, shape: PlannerProvider? = null): JSONObject {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", shape?.toolChoice ?: "auto")
                .put("temperature", temperature)
            // P1-15 (R-A6): one tool call per reply where the provider's default is several; no reasoning_effort for a
            // provider that rejects the field; the reply cap under the field name the provider knows.
            shape?.parallelToolCalls?.let { body.put("parallel_tool_calls", it) }
            if (!effort.isNullOrBlank() && (shape == null || shape.reasoningEffort)) body.put("reasoning_effort", effort)
            // After the fields the prompt cache sees, so a cap change never breaks a shared prefix.
            val capField = if (shape == null) "max_completion_tokens" else shape.maxTokensField
            if (maxCompletion != null && maxCompletion > 0 && capField != null) body.put(capField, maxCompletion)
            return body
        }

        /**
         * The retry nudge for Groq's 400 `tool_use_failed`: a schema mismatch ("parameters for tool tap did not match schema:
         * … `/plan`: expected string") says which argument was wrong; otherwise the model named a tool that doesn't exist.
         */
        fun toolUseFailedNudge(body: String): String {
            val schema = Regex("(?i)did not match schema[^\\[]*\\[([^\\]]{1,160})").find(body)?.groupValues?.get(1)
                ?: if (Regex("(?i)did not match schema|expected (?:string|integer|boolean)").containsMatchIn(body)) "" else null
            return if (schema != null) "Your previous tool call's arguments didn't match the tool's schema" +
                (if (schema.isNotBlank()) " (${schema.replace("\\\"", "\"").trim()})" else "") +
                ". Call it again with valid arguments — each argument ONE value of its declared type; `plan` is ONE string " +
                "'subgoal => check; subgoal => check'."
            else "Your previous response called a tool that does not exist. Call ONLY one of the provided tools, using its exact name."
        }

        /** Groq's "Request too large … (TPM): Limit 8000, Requested 8106". */
        internal val TOO_LARGE = Regex("(?i)limit (\\d+), requested (\\d+)")

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

        /**
         * Phone care ("check my phone", A20e 2026-09-29: the judge's session ran on the backup model with nobody told why):
         * ms until the first of [keys] may call [model] again — 0 when one is free now. Over ~90 s means the daily
         * allowance. A read of the in-memory map only: no network call, and no key leaves this class.
         */
        fun coolingLeftMs(model: String, keys: List<String>, now: Long = System.currentTimeMillis()): Long =
            if (keys.isEmpty()) 0L else (keys.minOf { coolUntil["$model|$it"] ?: 0L } - now).coerceAtLeast(0L)

        /** Tokens one full step costs (~prompt + completion). A key with fewer remaining is cooled so the
         *  next step doesn't fire a request that would 429. Sized to the ACTUAL step cost after the prompt
         *  trim (~4.4k typical); the old 6000 padded ~1.5k over reality and cooled each key ~18s instead of
         *  ~7s between steps. A too-low value only risks an occasional 429, which the 4-key rotation absorbs.
         *  N-03: now only the estimate when a reply carried no usage — [nextRequestTokens] measures the real step. */
        private val ONE_REQUEST_TOKENS get() = AppConfig.int("models.planner.requestTokens", 4800)

        /**
         * N-03: what the next step on this key will cost, from what this one just did (prompt + completion, +10% for the
         * step record growing) — [fallback] when the reply carried no usage. Groq's prompt cache is per ORGANIZATION (one
         * per key here) and lasts 2 h, so the step after this one only reuses this task's cached prefix if it goes out on
         * the same key. A fixed 4.8k threshold rotated keys after every ~3.5k step (8k minus a step leaves ~4.5k), so
         * each step of a short task landed on an org that had never seen it. Measuring keeps the key while it can take
         * one more step like this one; a wrong guess costs one quick 429, after which the key cools as before.
         */
        fun nextRequestTokens(u: com.tekyerema.core.telemetry.LlmUsage?, fallback: Int): Int =
            if (u == null || u.prompt <= 0) fallback else ((u.prompt + u.completion) * 11L / 10).toInt().coerceIn(500, 64_000)

        /** N-15 hedge: the backup request's client — same dispatcher, but its OWN connection pool, so it never inherits the
         *  pooled connection the first request is hung on (it opens a new DNS + TCP + TLS path). */
        private val freshClient: OkHttpClient by lazy {
            com.tekyerema.core.Http.shared.newBuilder()
                .connectionPool(okhttp3.ConnectionPool(2, 2, TimeUnit.MINUTES))
                .connectTimeout(AppConfig.long("models.planner.timeouts.connectSec", 8), TimeUnit.SECONDS)
                .readTimeout(AppConfig.long("models.planner.timeouts.callSec", 35), TimeUnit.SECONDS)
                .callTimeout(AppConfig.long("models.planner.timeouts.callSec", 35), TimeUnit.SECONDS)
                .build()
        }

        /** One request with a hard deadline; a network failure or the deadline is a failed [Result], never a throw. */
        private suspend fun callOnce(c: OkHttpClient, req: Request, ms: Long): Result<Reply> = try {
            Hedge.within(ms) { com.tekyerema.core.Http.execute(c.newCall(req)) }?.let { Result.success(it) }
                ?: Result.failure(java.io.InterruptedIOException("timeout"))
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce
        } catch (t: Throwable) { Result.failure(t) }

        private fun evictPooled(c: OkHttpClient) {
            try { c.connectionPool.evictAll(); android.util.Log.i("Tekyerema", "[NET] dropped pooled connections after a stalled model call") }
            catch (_: Throwable) {}
        }

        /**
         * N-15 hedge: how long the first request may take before the backup goes out. The day's traces put a normal step at
         * 1–2 s (p95 ≈ 5 s); "high" reasoning thinks longer, so it gets more room. 0 (config) switches hedging off.
         */
        fun hedgeAfterMs(effort: String?): Long {
            val base = AppConfig.long("models.planner.hedgeAfterMs", 8_000L)
            if (base <= 0L) return 0L
            return if (effort == "high") (base * 7) / 4 else base
        }

        /** The key for the backup request: the next one after [chosen] that isn't cooling, else [chosen] itself (a fresh
         *  connection alone is what helps when the pooled one is hung). Pure. */
        fun hedgeKeyIndex(size: Int, chosen: Int, free: (Int) -> Boolean): Int {
            for (n in 1 until size) { val i = (chosen + n) % size; if (free(i)) return i }
            return chosen
        }

        /** "[LLM] groq/openai/gpt-oss-120b key#2/4 http=200 ms=1834 prompt=4630 cached=3584(77%) completion=212 effort=default". */
        fun usageLine(model: String, keyIdx: Int, keys: Int, code: Int, ms: Long, u: com.tekyerema.core.telemetry.LlmUsage?, effort: String?,
                      providerId: String = "groq"): String {
            val tok = if (u == null) "" else " prompt=${u.prompt} cached=${u.cached}" +
                (if (u.prompt > 0) "(${u.cached * 100 / u.prompt}%)" else "") + " completion=${u.completion}"
            return "$providerId/$model key#${keyIdx + 1}/$keys http=$code ms=$ms$tok effort=${effort?.ifBlank { null } ?: "default"}"
        }

        /** Tried in order after the configured model when its daily quota is exhausted on every key. */
        private val FALLBACK_MODELS = listOf("openai/gpt-oss-20b")
        private const val DAILY_MARKER = "daily quota on every key"
        private const val PACE_MARKER = "every key cooling on this model"

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
                // (c) a whole PLAN as JSON — {"plan":[{"action":"open_app","app":"Settings","expects":"…"}, …]} — which
                // gpt-oss-120b wrote in `content` instead of calling a tool (13 October 2026: it was SPOKEN as the answer
                // and the task marked done). The first step becomes the tool call; the rest ride along as the plan.
                planStep(obj)?.let { return it }
            }
            return null
        }

        /** The first action of a `plan` / `steps` array as a tool call, the whole plan as its `plan` argument; null if none. */
        internal fun planStep(obj: JSONObject): AgentDecision? {
            val steps = obj.optJSONArray("plan") ?: obj.optJSONArray("steps") ?: return null
            val first = (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }.firstOrNull() ?: return null
            val name = first.optString("action").ifBlank { first.optString("tool").ifBlank { first.optString("name") } }
            if (name !in KNOWN_TOOLS) return null
            val args = JSONObject()
            for (k in first.keys()) if (k != "action" && k != "tool" && k != "name") args.put(k, first.get(k))
            val plan = (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }.joinToString("; ") { s ->
                val a = s.optString("action").ifBlank { s.optString("tool").ifBlank { s.optString("name") } }
                val what = listOf("app", "label", "text", "key", "direction").mapNotNull { s.optString(it).takeIf { v -> v.isNotBlank() } }.joinToString(" ")
                val check = s.optString("expects").takeIf { it.isNotBlank() }
                (if (what.isBlank()) a else "$a $what") + (check?.let { " => $it" } ?: "")
            }
            if (plan.isNotBlank() && !args.has("plan")) args.put("plan", plan)
            return AgentDecision.fromToolCall(name, args, null)
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
