package com.tekyerema.eval

import android.content.Context
import android.util.Log
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.accessibility.AccessibilityController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Phase II P0 — `tekyerema-eval`: a lightweight, state-checked, reproducible task harness (AndroidWorld's
 * design, adapted to run ON the phone). It submits each task's goal through the SAME agent entry the debug
 * `RUN_GOAL` path uses (`AgentEngine.submitGoal`), waits for a terminal status within a per-task step budget,
 * then scores success by inspecting **ground truth we can actually read** — the terminal status, the
 * foreground package, and (later) the a11y tree / system state — NOT trajectory-matching. Results are logged
 * to logcat (`[EVAL]`) and written as JSON lines under `filesDir/eval/`.
 *
 * Fire it from the existing debug receiver:
 *   adb shell am broadcast -a com.tekyerema.RUN_GOAL --es eval_suite core [--ei seed 42]
 *
 * Only SAFE goals are in the built-in suite (device ops, open-app, navigation) — nothing consequential
 * (no money / messages / calls), so it can be run repeatedly on a real device without side effects.
 * NOTE: this runs the AGENT path; fast-path (LocalFastRouter) coverage is a follow-up via the routing entry.
 */
object EvalRunner {
    private const val TAG = "Tekyerema"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val TERMINAL = setOf(
        AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED,
        AgentStatus.CANNOT_DO, AgentStatus.FAILED, AgentStatus.STOPPED
    )
    private val ACTIVE = setOf(AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.WAITING_CONFIRMATION)

    /** A reproducible task: `check` returns success in [0,1] from the terminal state + live controller. */
    data class EvalTask(
        val id: String,
        val goal: String,
        val complexity: Int,
        val check: (AgentUiState, AccessibilityController?) -> Float
    )

    private fun fgContains(c: AccessibilityController?, vararg needles: String): Boolean {
        val pkg = (c?.foregroundPackage() ?: "").lowercase()
        return needles.any { pkg.contains(it) }
    }

    /** The built-in "core" suite — SAFE, side-effect-free goals only. Extend per Tɛkyerɛma's mission. */
    private fun coreSuite(): List<EvalTask> = listOf(
        EvalTask("answer_time", "what time is it", 1) { s, _ -> if (s.status == AgentStatus.COMPLETED) 1f else 0f },
        EvalTask("answer_battery", "what's my battery level", 1) { s, _ -> if (s.status == AgentStatus.COMPLETED) 1f else 0f },
        EvalTask("open_settings", "open settings", 2) { _, c -> if (fgContains(c, "settings")) 1f else 0f },
        EvalTask("open_clock", "open the clock app", 2) { _, c -> if (fgContains(c, "clock", "deskclock")) 1f else 0f },
        EvalTask("go_home", "go home", 1) { _, c -> if (fgContains(c, "launcher", "nexuslauncher", "home")) 1f else 0f },
        EvalTask("volume_down", "turn the volume down", 1) { s, _ -> if (s.status == AgentStatus.COMPLETED) 1f else 0f },
        EvalTask("read_screen", "read what's on my screen", 1) { s, _ -> if (s.status == AgentStatus.COMPLETED) 1f else 0f }
    )

    fun run(ctx: Context, suiteName: String, seed: Int) {
        val suite = when (suiteName.lowercase()) {
            else -> coreSuite()   // only "core" for now
        }
        Log.i(TAG, "[EVAL] starting suite '$suiteName' (${suite.size} tasks, seed=$seed)")
        scope.launch {
            val results = ArrayList<JSONObject>()
            for (t in suite) {
                // Wait for any in-flight run to quiet down, then submit.
                try { AgentEngine.stop() } catch (_: Throwable) {}
                delay(600)
                val budgetMs = 12_000L + t.complexity * 8_000L   // ~ per-task budget (2× expected human steps)
                AgentEngine.submitGoal(t.goal)
                val terminal: AgentUiState = withTimeoutOrNull(budgetMs) {
                    AgentEngine.state.first { it.status in ACTIVE }     // it actually started
                    AgentEngine.state.first { it.status in TERMINAL }   // …then finished
                } ?: AgentEngine.state.value
                val ctrl = AgentEngine.currentController()
                val score = try { t.check(terminal, ctrl) } catch (e: Throwable) { Log.w(TAG, "[EVAL] check ${t.id} threw: ${e.message}"); 0f }
                val row = JSONObject()
                    .put("id", t.id).put("goal", t.goal).put("complexity", t.complexity)
                    .put("success", score.toDouble()).put("status", terminal.status.name)
                    .put("stats", terminal.lastStats ?: "")
                results.add(row)
                Log.i(TAG, "[EVAL] ${t.id}: success=$score status=${terminal.status} | ${terminal.lastStats ?: ""}")
                delay(1500)   // settle between tasks
            }
            val passed = results.count { it.getDouble("success") >= 1.0 }
            val outDir = File(ctx.filesDir, "eval").apply { mkdirs() }
            val out = File(outDir, "eval_${System.currentTimeMillis()}.jsonl")
            try { out.writeText(results.joinToString("\n") { it.toString() } + "\n") } catch (_: Throwable) {}
            Log.i(TAG, "[EVAL] SUITE DONE: $passed/${results.size} passed → ${out.absolutePath}")
        }
    }
}
