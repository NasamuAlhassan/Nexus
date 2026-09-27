package com.tekyerema.eval

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.voice.VoiceEngine
import com.tekyerema.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Phase II — `tekyerema-eval` v2: a reproducible, state-checked task harness (AndroidWorld's design, adapted to
 * run ON the phone). v1 raced (it matched the PREVIOUS run's ACTIVE state and read stale stats); v2 fixes that:
 *
 *  - **Real entry point.** Route `voice` (default) submits through `VoiceEngine.submitText` — the SAME path a
 *    user's typed/spoken command takes (LocalFastRouter first, then the agent) — so the fast path is measured as
 *    users experience it. Route `agent` submits straight to `AgentEngine` to measure agent-path competence alone.
 *  - **Known start state** (setup): quiesce any running turn, press HOME, run the task's own setup (e.g. put the
 *    media volume at a known level) — AndroidWorld's `initialize_task`.
 *  - **Bound to THIS run.** Completion waits on this task's own goal (agent route) or this turn's voice state
 *    transition (voice route); explicit timeouts are recorded as `timedOut`, never mis-scored.
 *  - **State-based success**: foreground package, the spoken answer, live system state (AudioManager), and the
 *    live accessibility tree — not trajectory matching.
 *  - **Timing**: wall-clock ms from submit to completion, the route actually taken (fast vs agent), and the
 *    agent's own RunStats when it ran.
 *
 *   adb shell am broadcast -p com.tekyerema -a com.tekyerema.RUN_GOAL --es eval_suite core [--es eval_route voice|agent]
 *
 * Built-in suites contain SAFE goals only (no money, messages or calls) so they can run repeatedly on a device.
 */
object EvalRunner {
    private const val TAG = "Tekyerema"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val TERMINAL = setOf(
        AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED,
        AgentStatus.CANNOT_DO, AgentStatus.FAILED, AgentStatus.STOPPED
    )
    private val AGENT_ACTIVE = setOf(AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.WAITING_CONFIRMATION)
    @Volatile private var running = false

    /** What the harness observed after a task finished (the ground truth a success check scores). */
    data class Observed(
        val route: String,            // "fast" | "agent" | "none" (nothing handled it)
        val agentStatus: AgentStatus?,
        val spoken: String,
        val foreground: String,
        val screenText: String,       // lowercase labels on the final screen (a11y tree)
        val timedOut: Boolean,
        val elapsedMs: Long,
        val answerMs: Long            // submit → Tɛkyerɛma starts SPEAKING the reply (-1: never / agent route)
    )

    data class EvalTask(
        val id: String,
        val goal: String,
        val category: String,         // fast | simple_agent | multi_step | negative
        val budgetMs: Long,
        val setup: (Context) -> Unit = {},
        val check: (Context, Observed) -> Float
    )

    private fun spokeDigit(o: Observed) = o.spoken.any { it.isDigit() }
    private fun fg(o: Observed, vararg n: String) = n.any { o.foreground.lowercase().contains(it) }
    private fun musicVol(ctx: Context) =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
    /** The phone's default launcher package — "home" on any OEM (nexuslauncher, sec.android.app.launcher, miui.home…). */
    private fun homePkg(ctx: Context): String? = try {
        ctx.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName?.takeIf { it != "android" }
    } catch (_: Throwable) { null }
    private fun setMusicVol(ctx: Context, v: Int) =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)

    private fun coreSuite(): List<EvalTask> = listOf(
        // FAST PATH — should be handled deterministically, no reasoning call.
        EvalTask("time", "what time is it", "fast", 20_000) { _, o -> if (spokeDigit(o)) 1f else 0f },
        EvalTask("battery", "what's my battery level", "fast", 20_000) { _, o ->
            if (spokeDigit(o) && (o.spoken.contains("%") || o.spoken.lowercase().contains("percent"))) 1f else 0f },
        EvalTask("open_settings", "open settings", "fast", 25_000) { _, o -> if (fg(o, "settings")) 1f else 0f },
        EvalTask("open_chrome", "open chrome", "fast", 25_000) { _, o -> if (fg(o, "chrome")) 1f else 0f },
        EvalTask("go_home", "go home", "fast", 20_000) { ctx, o -> if (fg(o, homePkg(ctx) ?: "launcher")) 1f else 0f },
        EvalTask("volume_down", "turn the volume down", "fast", 20_000,
            setup = { setMusicVol(it, 8) }) { ctx, _ -> if (musicVol(ctx) < 8) 1f else 0f },
        // SIMPLE AGENT — needs the reasoning loop (not a single deterministic op).
        EvalTask("settings_battery", "open settings and go to the battery page", "simple_agent", 90_000) { _, o ->
            if (fg(o, "settings") && o.screenText.contains("battery")) 1f else 0f },
        EvalTask("read_screen", "read what's on my screen", "simple_agent", 60_000) { _, o ->
            if (!o.timedOut && o.spoken.length > 10) 1f else 0f },
        // NEGATIVE — must fail honestly, quickly, without looping.
        EvalTask("absent_app", "open the Zorblax app", "negative", 60_000) { _, o ->
            val s = o.spoken.lowercase()
            if (!o.timedOut && (s.contains("couldn't") || s.contains("can't") || s.contains("cannot") ||
                    s.contains("not find") || s.contains("isn't installed") || s.contains("not installed") ||
                    s.contains("don't see") || s.contains("no app"))) 1f else 0f }
    )

    fun run(ctx: Context, suiteName: String, seed: Int, route: String) {
        if (running) { Log.w(TAG, "[EVAL] a suite is already running — ignoring"); return }
        running = true
        val suite = coreSuite()   // only "core" for now; seed reserved for parameterised tasks
        Log.i(TAG, "[EVAL] start suite='$suiteName' route=$route tasks=${suite.size} seed=$seed")
        scope.launch {
            val rows = ArrayList<JSONObject>()
            try {
                for (t in suite) rows.add(runOne(ctx, t, route))
            } finally { running = false }
            val passed = rows.count { it.getDouble("success") >= 1.0 }
            val byCat = rows.groupBy { it.getString("category") }.map { (c, r) ->
                "$c ${r.count { it.getDouble("success") >= 1.0 }}/${r.size}" }.joinToString(", ")
            val fastMs = rows.filter { it.getString("route") == "fast" }.map { it.getLong("elapsedMs") }.sorted()
            val agentMs = rows.filter { it.getString("route") == "agent" }.map { it.getLong("elapsedMs") }.sorted()
            fun med(l: List<Long>) = if (l.isEmpty()) -1 else l[l.size / 2]
            fun medAns(r: String) = med(rows.filter { it.getString("route") == r && it.getLong("answerMs") >= 0 }
                .map { it.getLong("answerMs") }.sorted())
            val out = File(File(ctx.filesDir, "eval").apply { mkdirs() }, "eval_${route}_${System.currentTimeMillis()}.jsonl")
            try { out.writeText(rows.joinToString("\n") { it.toString() } + "\n") } catch (_: Throwable) {}
            Log.i(TAG, "[EVAL] SUITE DONE route=$route: $passed/${rows.size} passed [$byCat] " +
                "medianMs fast=${med(fastMs)} (n=${fastMs.size}) agent=${med(agentMs)} (n=${agentMs.size}) " +
                "medianAnswerMs fast=${medAns("fast")} agent=${medAns("agent")} → ${out.absolutePath}")
        }
    }

    private suspend fun runOne(ctx: Context, t: EvalTask, route: String): JSONObject {
        // ---- setup: quiesce, known start state ----
        try { withContext(Dispatchers.Main) { VoiceEngine.cancel(); AgentEngine.stop() } } catch (_: Throwable) {}
        withTimeoutOrNull(10_000) { AgentEngine.state.first { it.status !in AGENT_ACTIVE } }
        withTimeoutOrNull(10_000) { VoiceEngine.state.first { it.state == VoiceState.IDLE || it.state == VoiceState.CANCELLED || it.state == VoiceState.ERROR } }
        AgentEngine.currentController()?.globalAction("home")
        delay(1_200)
        try { t.setup(ctx) } catch (e: Throwable) { Log.w(TAG, "[EVAL] setup ${t.id} failed: ${e.message}") }
        delay(300)

        // ---- submit through the chosen entry, measure until THIS turn completes ----
        val t0 = SystemClock.elapsedRealtime()
        var timedOut = false
        var accepted = true
        val answerAt = java.util.concurrent.atomic.AtomicLong(-1L)
        var watcher: kotlinx.coroutines.Job? = null
        when (route) {
            "agent" -> {
                withContext(Dispatchers.Main) { AgentEngine.submitGoal(t.goal) }
                // ONE goal-bound predicate: StateFlow conflates fast transitions and the on-device LocalBrain can
                // finish in ~1 ms, so "wait for ACTIVE, then TERMINAL" could miss ACTIVE and hang. submitGoal sets
                // goal+THINKING synchronously, so a terminal state carrying THIS goal can only be this run's.
                val done = withTimeoutOrNull(t.budgetMs) {
                    AgentEngine.state.first { it.goal == t.goal && it.status in TERMINAL }
                }
                timedOut = done == null
            }
            else -> {
                // Time-to-answer: the moment the reply starts playing (what a blind user actually waits for) —
                // distinct from elapsed, which also includes the length of the spoken sentence.
                watcher = scope.launch {
                    VoiceEngine.state.first { it.lastCanonical == t.goal && it.state == VoiceState.SPEAKING }
                    answerAt.set(SystemClock.elapsedRealtime() - t0)
                }
                accepted = withContext(Dispatchers.Main) { VoiceEngine.submitText(t.goal) }
                if (accepted) {
                    // ONE turn-bound predicate: runTurn stamps lastCanonical=goal on entry and the value persists through
                    // the resting state, so "lastCanonical==goal AND resting" can only be THIS turn finishing (a stale
                    // resting state from the previous task carries a different lastCanonical).
                    val resting = setOf(VoiceState.IDLE, VoiceState.ERROR, VoiceState.CANCELLED, VoiceState.CONFIRMATION_REQUIRED)
                    val done = withTimeoutOrNull(t.budgetMs) {
                        VoiceEngine.state.first { it.lastCanonical == t.goal && it.state in resting }
                    }
                    timedOut = done == null
                }
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - t0
        watcher?.cancel()
        val answerMs = answerAt.get()
        delay(500)   // let the final screen settle before we read ground truth

        // ---- observe ground truth ----
        val ag = AgentEngine.state.value
        val agentRan = ag.goal == t.goal
        val ctrl = AgentEngine.currentController()
        val screenText = try {
            ctrl?.buildSnapshot()?.elements?.mapNotNull { it.label }?.joinToString(" | ")?.lowercase() ?: ""
        } catch (_: Throwable) { "" }
        val spoken = if (route == "agent") (ag.log.lastOrNull { it.kind == com.tekyerema.agent.LogEntry.Kind.RESULT }?.text ?: "")
            else VoiceEngine.state.value.spoken
        val obs = Observed(
            route = when { !accepted -> "none"; agentRan -> "agent"; else -> "fast" },
            agentStatus = if (agentRan) ag.status else null,
            spoken = spoken, foreground = ctrl?.foregroundPackage() ?: "",
            screenText = screenText, timedOut = timedOut, elapsedMs = elapsed, answerMs = answerMs
        )
        val score = try { if (timedOut) 0f else t.check(ctx, obs) } catch (e: Throwable) { 0f }
        val row = JSONObject()
            .put("id", t.id).put("goal", t.goal).put("category", t.category).put("route", obs.route)
            .put("success", score.toDouble()).put("elapsedMs", elapsed).put("answerMs", answerMs).put("timedOut", timedOut)
            .put("agentStatus", obs.agentStatus?.name ?: "").put("foreground", obs.foreground)
            .put("spoken", spoken.take(160)).put("stats", if (agentRan) (ag.lastStats ?: "") else "")
        Log.i(TAG, "[EVAL] ${t.id} [${t.category}] success=$score route=${obs.route} ${elapsed}ms" +
            (if (answerMs >= 0) " answer=${answerMs}ms" else "") +
            (if (timedOut) " TIMEOUT" else "") + (obs.agentStatus?.let { " status=$it" } ?: "") +
            " fg=${obs.foreground.substringAfterLast('.')} said='${spoken.take(70)}'" +
            (if (agentRan) " | ${ag.lastStats ?: ""}" else ""))
        return row
    }
}
