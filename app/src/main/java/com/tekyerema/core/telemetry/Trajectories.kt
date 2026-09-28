package com.tekyerema.core.telemetry

import android.content.Context
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Q-07 — opt-in trajectory capture for improving the agent (fine-tuning and synthesis data, OpenMobile-style).
 * Runs ONLY when the user switched "Help improve Tɛkyerɛma" on in Settings ([com.tekyerema.settings.Settings.trajectoryConsent]).
 * Each agent task becomes one encrypted file under `filesDir/trajectories/`: the goal, then per step the screen the
 * model saw (already sanitised; credential screens are never observed, denied apps are blanked), the action, and
 * whether it verified. Nothing is uploaded. Kept `privacy.retention.trajectoryDays` (30), at most
 * `telemetry.datasets.max` (100) files; switching consent off deletes them all.
 */
class TrajectoryRecorder(private val goal: String) {
    private val steps = JSONArray()

    fun step(screen: JSONObject, tool: String, args: JSONObject, result: String, verified: Boolean) {
        if (steps.length() >= AppConfig.int("telemetry.datasets.maxSteps", 40)) return
        steps.put(JSONObject()
            .put("screen", screen)
            .put("action", JSONObject().put("tool", tool).put("args", JSONObject(args.toString())))
            .put("result", com.tekyerema.core.Redact.secrets(result.take(300)))
            .put("verified", verified))
    }

    fun toJson(status: String, answer: String?): JSONObject = JSONObject()
        .put("schema", "tekyerema.trajectory/1")
        .put("t", System.currentTimeMillis())
        .put("goal", goal)
        .put("status", status)
        .put("answer", answer ?: JSONObject.NULL)
        .put("steps", steps)

    val isEmpty: Boolean get() = steps.length() == 0
}

object Trajectories {
    private fun dir(ctx: Context) = File(ctx.filesDir, "trajectories")

    fun enabled(ctx: Context): Boolean =
        com.tekyerema.core.config.FeatureFlags.isEnabled("Q-07", true) && com.tekyerema.settings.Settings(ctx).trajectoryConsent

    fun save(ctx: Context, rec: TrajectoryRecorder, status: String, answer: String?) {
        if (!enabled(ctx) || rec.isEmpty) return
        try {
            val d = dir(ctx).apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date())
            SecureStore.write(File(d, "traj-$stamp.enc"), rec.toJson(status, answer).toString())
            val max = AppConfig.int("telemetry.datasets.max", 100)
            d.listFiles()?.sortedBy { it.lastModified() }?.dropLast(max)?.forEach { it.delete() }
        } catch (_: Throwable) {}
    }

    fun count(ctx: Context): Int = dir(ctx).listFiles()?.size ?: 0

    fun clear(ctx: Context) { try { dir(ctx).deleteRecursively() } catch (_: Throwable) {} }

    /** DEBUG export: decrypt everything into one JSONL file in app-private storage (pulled with `adb run-as`). */
    fun exportTo(ctx: Context, out: File): Int {
        val files = dir(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return 0
        var n = 0
        out.parentFile?.mkdirs()
        out.bufferedWriter().use { w ->
            for (f in files) SecureStore.read(f)?.let { w.write(JSONObject(it).toString()); w.newLine(); n++ }
        }
        return n
    }
}
