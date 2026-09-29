package com.tekyerema.core.telemetry

import android.content.Context
import com.tekyerema.core.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local, on-device telemetry for one request ("turn"), written as one JSON line per turn to
 * `filesDir/telemetry/turns.jsonl`. It never leaves the phone and holds NO words — only timings, counts and grades —
 * unless the user opted into the field log (Q-08), which is stored encrypted and separately ([FieldLog]).
 *
 *  - Q-01 stage timestamps: trigger → end_of_turn → asr_final → route → first_decision → action_done → reply_start.
 *  - N-02 stage budgets (`pipeline.budgets.*`): a stage over its budget is logged and listed in the record.
 *  - Q-02 the tier that resolved it (fast_path, direct_answer, retrieval, local_brain, cloud_operator).
 *  - Q-03 / N-03 model calls, prompt / completion / cached tokens, and the provider's remaining quota.
 *  - Q-04 a graded outcome: full (verified), partial (done but unverified), failed, stopped.
 *  - Q-10 resources: CPU time, network bytes, battery charge used, free RAM, low-memory / trim events,
 *    screenshots and OCR runs.
 */
object Telemetry {
    private const val TAG = "Tekyerema"

    /** One turn in flight. Mutated from the voice layer and the engine; guarded by [lock]. */
    class Turn(val trigger: String, val ownedByVoice: Boolean) {
        val startMs: Long = System.currentTimeMillis()
        val t0: Long = android.os.SystemClock.uptimeMillis()
        val marks = LinkedHashMap<String, Long>()
        var tier: String? = null
        var grade: String = "none"
        var steps = 0
        var llmCalls = 0
        var promptTokens = 0
        var completionTokens = 0
        var cachedTokens = 0
        var provider: String? = null
        var quotaRemaining: Int? = null
        var screenshots = 0
        var ocrRuns = 0
        var goal: String? = null
        val resources: Resources.Sample? = Resources.sample()
        /** B-19: per-stage voice numbers (capture ms, speech ms, end reason, ASR audio/decode ms, route, name fixes).
         *  Numbers and labels only — never words. */
        val voice = LinkedHashMap<String, Any>()
    }

    private val lock = Any()
    @Volatile private var current: Turn? = null
    @Volatile private var appContext: Context? = null
    @Volatile var lastTrimLevel: Int = -1; private set

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    fun enabled(): Boolean = AppConfig.bool("telemetry.enabled", true)

    /** Start a turn (the voice layer at the trigger, or the engine for a typed / unattended task). */
    fun beginTurn(trigger: String, ownedByVoice: Boolean): Turn? {
        if (!enabled()) return null
        return synchronized(lock) { Turn(trigger, ownedByVoice).also { current = it } }
    }

    /** The open turn, or a new one owned by the engine. */
    fun ensureTurn(trigger: String): Turn? = synchronized(lock) { current } ?: beginTurn(trigger, ownedByVoice = false)

    /** The open turn, or a new one owned by the voice layer (typed input, a barge-in). */
    fun ensureVoiceTurn(trigger: String): Turn? = synchronized(lock) { current } ?: beginTurn(trigger, ownedByVoice = true)

    /** Drop an unfinished turn owned by that side (no speech, cancelled) — it measured nothing. */
    fun discard(byVoice: Boolean) { synchronized(lock) { if (current?.ownedByVoice == byVoice) current = null } }

    fun mark(stage: String) {
        val t = current ?: return
        synchronized(lock) { if (stage !in t.marks) t.marks[stage] = android.os.SystemClock.uptimeMillis() - t.t0 }
    }

    fun update(block: Turn.() -> Unit) { val t = current ?: return; synchronized(lock) { t.block() } }

    /** Q-03 / N-03: one model call (success or not); usage when the provider returned it. */
    fun noteLlm(provider: String, usage: LlmUsage?, quotaRemaining: Int? = null) = update {
        llmCalls++
        this.provider = provider
        usage?.let { promptTokens += it.prompt; completionTokens += it.completion; cachedTokens += it.cached }
        if (quotaRemaining != null) this.quotaRemaining = quotaRemaining
    }

    fun noteScreenshot() = update { screenshots++ }
    fun noteOcr() = update { ocrRuns++ }
    fun noteTrim(level: Int) { lastTrimLevel = level }

    /** Close the turn: grade it, check the budgets, write the record. Only the owner ends it. */
    fun endTurn(grade: String, byVoice: Boolean) {
        val t = synchronized(lock) {
            val c = current ?: return
            if (c.ownedByVoice != byVoice) return
            current = null
            c.grade = grade
            c
        }
        write(t)
    }

    // ---------------------------------------------------------------- budgets (N-02), pure

    /** Stage durations from the marks: each stage measured from the one that feeds it. */
    fun durations(marks: Map<String, Long>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        fun span(name: String, from: String, to: String) {
            val a = marks[from]; val b = marks[to]
            if (a != null && b != null && b >= a) out[name] = b - a
        }
        span("speech", "speech_start", "end_of_turn")       // B-19: the user talking + the end-of-turn silence
        span("asr", "end_of_turn", "asr_final")
        span("route", "asr_final", "route")
        span("first_decision", "route", "first_decision")
        span("task", "route", "action_done")
        span("tts_start", marks.keys.lastOrNull { it == "action_done" } ?: "route", "reply_start")
        span("response", "end_of_turn", "reply_start")
        return out
    }

    val DEFAULT_BUDGETS = mapOf("asr" to 1500L, "route" to 200L, "first_decision" to 3500L, "tts_start" to 800L, "response" to 4000L)

    fun overBudget(durations: Map<String, Long>, budgets: Map<String, Long> = budgets()): List<String> =
        durations.filter { (k, v) -> budgets[k]?.let { v > it } == true }.keys.toList()

    /** `pipeline.budgets.asrMs`, `routeMs`, `firstDecisionMs`, `ttsStartMs`, `responseMs`. */
    private fun budgets(): Map<String, Long> = DEFAULT_BUDGETS.mapValues { (k, v) ->
        val camel = k.split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")
        AppConfig.long("pipeline.budgets.${camel}Ms", v)
    }

    // ---------------------------------------------------------------- storage

    fun toJson(t: Turn, end: Resources.Sample? = Resources.sample(), includeGoal: Boolean = false): JSONObject {
        val d = durations(t.marks)
        val over = overBudget(d)
        val o = JSONObject()
            .put("t", t.startMs).put("trigger", t.trigger).put("tier", t.tier ?: JSONObject.NULL).put("grade", t.grade)
            .put("steps", t.steps)
            .put("stages", JSONObject(t.marks as Map<*, *>)).put("durations", JSONObject(d as Map<*, *>))
            .put("overBudget", JSONArray(over))
            .put("llm", JSONObject().put("calls", t.llmCalls).put("prompt", t.promptTokens).put("completion", t.completionTokens)
                .put("cached", t.cachedTokens).put("provider", t.provider ?: JSONObject.NULL)
                .put("quotaRemaining", t.quotaRemaining ?: JSONObject.NULL))
            .put("vision", JSONObject().put("screenshots", t.screenshots).put("ocr", t.ocrRuns))
        if (t.voice.isNotEmpty()) o.put("voice", JSONObject(t.voice as Map<*, *>))
        Resources.delta(t.resources, end)?.let { o.put("res", it.put("trim", lastTrimLevel)) }
        if (includeGoal && t.goal != null) o.put("goal", t.goal)
        return o
    }

    private fun file(): File? = appContext?.let { File(it.filesDir, "telemetry/turns.jsonl") }

    private fun write(t: Turn) {
        val ctx = appContext ?: return
        try {
            val rec = toJson(t)
            val over = rec.optJSONArray("overBudget")
            if (over != null && over.length() > 0) android.util.Log.i(TAG, "[BUDGET] over: $over ${rec.optJSONObject("durations")}")
            val f = file() ?: return
            f.parentFile?.mkdirs()
            f.appendText(rec.toString() + "\n")
            prune(f)
            if (com.tekyerema.settings.Settings(ctx).fieldLog && t.goal != null) FieldLog.add(ctx, toJson(t, includeGoal = true))
        } catch (_: Throwable) {}
    }

    private fun prune(f: File) {
        val max = AppConfig.int("telemetry.maxRecords", 1000)
        val lines = f.readLines()
        val cutoff = System.currentTimeMillis() - com.tekyerema.core.Privacy.telemetryDays() * 86_400_000L
        if (lines.size <= max * 6 / 5 && (lines.firstOrNull()?.let { tOf(it) >= cutoff } != false)) return
        val keep = lines.filter { tOf(it) >= cutoff }.takeLast(max)
        f.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
    }

    private fun tOf(line: String): Long = try { JSONObject(line).optLong("t", 0L) } catch (_: Throwable) { 0L }

    fun records(): List<JSONObject> = try {
        file()?.takeIf { it.exists() }?.readLines()?.mapNotNull { l -> try { JSONObject(l) } catch (_: Throwable) { null } }.orEmpty()
    } catch (_: Throwable) { emptyList() }

    fun clear() { try { file()?.delete() } catch (_: Throwable) {}; appContext?.let { FieldLog.clear(it) } }

    // ---------------------------------------------------------------- summary (Q-02 / Q-03 / Q-04), pure

    /** A plain-text roll-up of [records] for the debug hook and the run report. */
    fun summary(records: List<JSONObject>): String {
        if (records.isEmpty()) return "No turns recorded yet."
        val n = records.size
        val tiers = records.groupingBy { it.optString("tier").ifBlank { "none" }.let { t -> if (t == "null") "none" else t } }.eachCount()
        val graded = records.filter { it.optString("grade") in setOf("full", "partial", "failed") }
        val full = graded.count { it.optString("grade") == "full" }
        val partial = graded.count { it.optString("grade") == "partial" }
        val calls = records.map { it.optJSONObject("llm")?.optInt("calls") ?: 0 }
        val tokens = records.map { (it.optJSONObject("llm")?.optInt("prompt") ?: 0) + (it.optJSONObject("llm")?.optInt("completion") ?: 0) }
        val cached = records.sumOf { it.optJSONObject("llm")?.optInt("cached") ?: 0 }
        val prompt = records.sumOf { it.optJSONObject("llm")?.optInt("prompt") ?: 0 }
        // Q-02 v1.1: fast-path coverage — no planner call, no screenshot, at most one model turn.
        val cheap = records.count { r ->
            val c = r.optJSONObject("llm")?.optInt("calls") ?: 0
            val shots = r.optJSONObject("vision")?.optInt("screenshots") ?: 0
            c <= 1 && shots == 0 && r.optString("tier") in setOf("fast_path", "direct_answer", "local_brain")
        }
        fun median(k: String): Long? = records.mapNotNull { r -> r.optJSONObject("durations")?.let { if (it.has(k)) it.optLong(k) else null } }
            .sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        fun pct(a: Int, b: Int) = if (b == 0) "—" else "${Math.round(a * 100.0 / b)}%"
        return buildString {
            append("turns=$n tiers=").append(tiers.entries.joinToString(",") { "${it.key}:${pct(it.value, n)}" })
            append(" | cheap(no planner, no screenshot, ≤1 model turn)=${pct(cheap, n)}")
            append(" | verified success=${pct(full, graded.size)} partial=${pct(partial, graded.size)} of ${graded.size} graded")
            append(" | llm calls/turn avg=${"%.1f".format(java.util.Locale.US, calls.average())}")
            append(" tokens/turn avg=${tokens.average().toInt()} cached=${pct(cached, prompt)}")
            append(" | median ms:")
            for (k in listOf("asr", "route", "first_decision", "tts_start", "response")) median(k)?.let { append(" $k=$it") }
            append(" | over budget=").append(records.count { (it.optJSONArray("overBudget")?.length() ?: 0) > 0 })
        }
    }
}

/** Q-03 / N-03 — token usage from a provider response (pure; unit-tested). */
data class LlmUsage(val prompt: Int, val completion: Int, val cached: Int) {
    companion object {
        /** OpenAI-compatible (Groq): `usage.prompt_tokens`, `usage.completion_tokens`, `usage.prompt_tokens_details.cached_tokens`. */
        fun fromOpenAi(body: String): LlmUsage? = try {
            val u = JSONObject(body).optJSONObject("usage")
            if (u == null) null else LlmUsage(u.optInt("prompt_tokens"), u.optInt("completion_tokens"),
                u.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: u.optInt("cached_tokens"))
        } catch (_: Throwable) { null }

        /** Gemini: `usageMetadata.promptTokenCount`, `candidatesTokenCount` (+ `thoughtsTokenCount`), `cachedContentTokenCount`. */
        fun fromGemini(body: String): LlmUsage? = try {
            val u = JSONObject(body).optJSONObject("usageMetadata")
            if (u == null) null else LlmUsage(u.optInt("promptTokenCount"),
                u.optInt("candidatesTokenCount") + u.optInt("thoughtsTokenCount"), u.optInt("cachedContentTokenCount"))
        } catch (_: Throwable) { null }
    }
}

/**
 * Q-10 — device resources around a turn: CPU time of this process, network bytes of this app, battery charge counter,
 * free RAM and the system's low-memory flag. Samples are best-effort (any field may be missing on a given phone).
 */
object Resources {
    data class Sample(val cpuMs: Long, val rx: Long, val tx: Long, val chargeUah: Long?, val availRamMb: Long?, val lowMemory: Boolean?)

    @Volatile private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }

    fun sample(): Sample? = try {
        val c = ctx
        val uid = android.os.Process.myUid()
        val bm = c?.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val am = c?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val mi = am?.let { android.app.ActivityManager.MemoryInfo().also { m -> it.getMemoryInfo(m) } }
        Sample(android.os.Process.getElapsedCpuTime(), android.net.TrafficStats.getUidRxBytes(uid), android.net.TrafficStats.getUidTxBytes(uid),
            bm?.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 },
            mi?.availMem?.div(1_048_576L), mi?.lowMemory)
    } catch (_: Throwable) { null }

    /** Pure: the change between two samples as JSON (null if either is missing). */
    fun delta(a: Sample?, b: Sample?): JSONObject? {
        if (a == null || b == null) return null
        val o = JSONObject().put("cpuMs", b.cpuMs - a.cpuMs)
            .put("rxKb", (b.rx - a.rx).coerceAtLeast(0) / 1024).put("txKb", (b.tx - a.tx).coerceAtLeast(0) / 1024)
        if (a.chargeUah != null && b.chargeUah != null) o.put("batteryUah", a.chargeUah - b.chargeUah)
        b.availRamMb?.let { o.put("availRamMb", it) }
        b.lowMemory?.let { o.put("lowMemory", it) }
        return o
    }
}

/** Q-08 — opt-in field log: what was asked and how it ended, encrypted at rest, same retention as telemetry. */
object FieldLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "telemetry/field.enc")

    @Synchronized
    fun add(ctx: Context, rec: JSONObject) {
        val arr = try { JSONArray(com.tekyerema.core.SecureStore.read(file(ctx)) ?: "[]") } catch (_: Throwable) { JSONArray() }
        arr.put(rec)
        val cutoff = System.currentTimeMillis() - com.tekyerema.core.Privacy.telemetryDays() * 86_400_000L
        val kept = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.takeIf { it.optLong("t") >= cutoff }?.let { kept.put(it) }
        val max = AppConfig.int("telemetry.field.max", 500)
        val out = JSONArray()
        for (i in maxOf(0, kept.length() - max) until kept.length()) out.put(kept.get(i))
        com.tekyerema.core.SecureStore.write(file(ctx), out.toString())
    }

    fun read(ctx: Context): JSONArray = try { JSONArray(com.tekyerema.core.SecureStore.read(file(ctx)) ?: "[]") } catch (_: Throwable) { JSONArray() }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) {} }
}
