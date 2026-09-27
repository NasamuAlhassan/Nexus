package com.tekyerema.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import org.json.JSONObject

object SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val description = "Set an alarm at a given hour and minute (24-hour clock)."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("hour", "integer", "Hour of day, 0-23."),
        ToolParam("minute", "integer", "Minute, 0-59.", required = false),
        ToolParam("message", "string", "Optional alarm label.", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val hour = args.intOrNull("hour") ?: return ToolResult(false, "No hour provided.")
        val minute = args.intOrNull("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) return ToolResult(false, "Invalid time $hour:$minute.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.str("message")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (intent.resolveActivity(ctx.appContext.packageManager) == null)
            return ToolResult(false, "No clock app can set alarms.")
        return try {
            ctx.appContext.startActivity(intent)   // SET_ALARM permission declared (§1.4); Clock adds the alarm
            ToolResult(true, "Asked the Clock to set an alarm for %02d:%02d.".format(hour, minute), channel = "intent")
        } catch (t: Throwable) {
            ToolResult(false, "Couldn't set the alarm: ${t.message}")
        }
    }
}

object SetTimerTool : Tool {
    override val name = "set_timer"
    override val description = "Start a countdown timer for a number of seconds."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("seconds", "integer", "Timer length in seconds."),
        ToolParam("message", "string", "Optional timer label.", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val seconds = args.intOrNull("seconds") ?: return ToolResult(false, "No duration provided.")
        if (seconds <= 0) return ToolResult(false, "Duration must be positive.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.str("message")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (intent.resolveActivity(ctx.appContext.packageManager) == null)
            return ToolResult(false, "No clock app can set timers.")
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult(true, "Started a ${seconds}s timer.", channel = "intent")
        } catch (t: Throwable) {
            ToolResult(false, "Failed to set timer: ${t.message}")
        }
    }
}

/**
 * Pure, Android-free normalization for the volume accelerators — the "semantic argument parsing"
 * of §7. Kept separate so it is unit-testable on the JVM (OperatorLogicTest). NOTE: this is NOT
 * phrase-matching on the user's raw utterance (§6); it only normalizes the tool ARGUMENT the LLM
 * already chose to pass (an int 0-100 or a compact word like "mute"/"half"/"max").
 */
object VolumeMath {
    /** An int 0..100, a Number, or a semantic word/"NN%"; null when nothing usable is present. */
    fun parseLevel(raw: Any?): Int? = when (raw) {
        is Boolean -> null
        is Int -> raw.coerceIn(0, 100)
        is Number -> raw.toInt().coerceIn(0, 100)
        is String -> {
            val s = raw.trim().lowercase()
            when {
                s.isEmpty() -> null
                s in setOf("mute", "muted", "min", "minimum", "zero", "silent", "off", "none") -> 0
                s in setOf("half", "halfway", "medium", "mid") -> 50
                s in setOf("max", "maximum", "full", "loudest", "highest", "top", "all the way up", "all the way") -> 100
                else -> Regex("-?\\d+").find(s)?.value?.toIntOrNull()?.coerceIn(0, 100)
            }
        }
        else -> null
    }

    /** Map a 0..100 percentage onto a stream's discrete index [0, max]. */
    fun pctToIndex(pct: Int, max: Int): Int {
        if (max <= 0) return 0
        return Math.round(pct.coerceIn(0, 100) / 100.0 * max).toInt().coerceIn(0, max)
    }

    /** Map a discrete index back to an approximate percentage, for reporting. */
    fun indexToPct(idx: Int, max: Int): Int {
        if (max <= 0) return 0
        return Math.round(idx * 100.0 / max).toInt().coerceIn(0, 100)
    }
}

/** Shared plumbing for the direct-AudioManager volume accelerators. */
private val VOLUME_STREAMS = mapOf(
    "music" to AudioManager.STREAM_MUSIC,
    "media" to AudioManager.STREAM_MUSIC,
    "ring" to AudioManager.STREAM_RING,
    "ringer" to AudioManager.STREAM_RING,
    "alarm" to AudioManager.STREAM_ALARM,
    "notification" to AudioManager.STREAM_NOTIFICATION,
    "call" to AudioManager.STREAM_VOICE_CALL,
    "system" to AudioManager.STREAM_SYSTEM
)
private val STREAM_PARAM = ToolParam(
    "stream", "string",
    "Which audio stream (default media/music).", required = false,
    enumValues = listOf("music", "ring", "alarm", "notification", "call", "system")
)
private fun audioManager(ctx: ToolContext): AudioManager? =
    ctx.appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
private fun streamFor(args: JSONObject): Int =
    VOLUME_STREAMS[args.str("stream")?.trim()?.lowercase()] ?: AudioManager.STREAM_MUSIC
private fun streamName(args: JSONObject): String =
    args.str("stream")?.trim()?.lowercase()?.takeIf { VOLUME_STREAMS.containsKey(it) } ?: "music"

/**
 * DIRECT SYSTEM-CONTROL ACCELERATOR (§1). Sets an ABSOLUTE volume 0-100% on an audio stream via
 * AudioManager — no Settings navigation, no slider drag. Verifies the resulting stream index
 * (§2) and never falsely reports success (§9). A failure is recoverable → General Operator UI.
 * Distinct from key_event(volume_up/down), which is an incremental hardware-key press (§14).
 */
object SetVolumeTool : Tool {
    override val name = "set_volume"
    override val description =
        "Set the device volume to an ABSOLUTE level 0-100 (0=mute, 100=maximum) directly via the system audio service. " +
        "Use this for any 'set/change/mute the volume to N' request — do NOT open Settings or drag a slider. Optional stream (default media)."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1          // reversible system control (§8) — no confirm gate
    override val isAccelerator = true
    override val verifiesOutcome = true
    override val params = listOf(
        ToolParam("level", "integer", "Target volume 0-100 (also accepts mute/half/max)."),
        STREAM_PARAM
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val raw: Any? = if (args.has("level") && !args.isNull("level")) args.get("level") else null
        val pct = VolumeMath.parseLevel(raw)
            ?: return ToolResult(false, "No usable volume level (expected 0-100, or mute/half/max).")
        val am = audioManager(ctx) ?: return ToolResult(false, "Audio service unavailable.")
        val stream = streamFor(args)
        return try {
            val max = am.getStreamMaxVolume(stream)
            val targetIdx = VolumeMath.pctToIndex(pct, max)
            am.setStreamVolume(stream, targetIdx, AudioManager.FLAG_SHOW_UI)
            val actual = am.getStreamVolume(stream)
            val ok = actual == targetIdx
            val data = JSONObject()
                .put("direct", true).put("mechanism", "AudioManager.setStreamVolume")
                .put("stream", streamName(args)).put("requestedPct", pct)
                .put("targetIndex", targetIdx).put("actualIndex", actual).put("max", max)
                .put("actualPct", VolumeMath.indexToPct(actual, max))
            ToolResult(
                ok,
                if (ok) "Set ${streamName(args)} volume to $pct% ($actual/$max)."
                else "Volume set to $actual/$max but expected $targetIdx (${pct}%) — will fall back.",
                data = data, channel = "system", recoverable = true
            )
        } catch (t: Throwable) {
            // e.g. SecurityException adjusting a DND-guarded stream → recoverable, try the UI.
            ToolResult(false, "Direct volume set failed: ${t.message}", channel = "system", recoverable = true)
        }
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        val am = audioManager(ctx) ?: return VerificationResult(false, "no audio service")
        val stream = streamFor(args)
        val pct = VolumeMath.parseLevel(if (args.has("level") && !args.isNull("level")) args.get("level") else null)
            ?: return VerificationResult(false, "no target")
        val max = am.getStreamMaxVolume(stream)
        val expected = VolumeMath.pctToIndex(pct, max)
        val current = am.getStreamVolume(stream)
        return VerificationResult(result.success && current == expected, "volume now=$current/$max target=$expected (${pct}%)")
    }
}

/** Base for the two semantic increment accelerators (§1). Adjusts the media stream one step and
 *  verifies the change direction against the pre-adjust index (§2/§9). */
abstract class VolumeStepTool(
    private val raise: Boolean
) : Tool {
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val verifiesOutcome = true
    override val params = listOf(STREAM_PARAM)

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val am = audioManager(ctx) ?: return ToolResult(false, "Audio service unavailable.")
        val stream = streamFor(args)
        return try {
            val max = am.getStreamMaxVolume(stream)
            val beforeIdx = am.getStreamVolume(stream)
            am.adjustStreamVolume(stream, if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            val afterIdx = am.getStreamVolume(stream)
            val changed = if (raise) afterIdx > beforeIdx else afterIdx < beforeIdx
            val atBoundary = if (raise) beforeIdx >= max else beforeIdx <= 0
            val ok = changed || atBoundary
            val data = JSONObject()
                .put("direct", true).put("mechanism", "AudioManager.adjustStreamVolume")
                .put("stream", streamName(args)).put("before", beforeIdx).put("after", afterIdx).put("max", max)
            val word = if (raise) "up" else "down"
            ToolResult(
                ok,
                when {
                    changed -> "Volume $word: $beforeIdx→$afterIdx/$max."
                    atBoundary -> "Volume already at ${if (raise) "maximum" else "minimum"} ($afterIdx/$max)."
                    else -> "Volume did not change ($afterIdx/$max)."
                },
                data = data, channel = "system", recoverable = true
            )
        } catch (t: Throwable) {
            ToolResult(false, "Direct volume ${if (raise) "up" else "down"} failed: ${t.message}", channel = "system", recoverable = true)
        }
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        val am = audioManager(ctx) ?: return VerificationResult(false, "no audio service")
        val stream = streamFor(args)
        val max = am.getStreamMaxVolume(stream)
        val beforeIdx = result.data?.optInt("before", -1) ?: -1
        val current = am.getStreamVolume(stream)
        val moved = if (raise) current > beforeIdx else current < beforeIdx
        val atBoundary = if (raise) current >= max else current <= 0
        return VerificationResult(result.success && (moved || atBoundary), "volume before=$beforeIdx now=$current/$max")
    }
}

/** DIRECT increment accelerator (§1) — "turn the volume up". */
object VolumeUpTool : VolumeStepTool(raise = true) {
    override val name = "volume_up"
    override val description =
        "Increase the volume by ONE step directly (do NOT open Settings). Use for a relative 'turn the volume up' / 'louder' / 'a bit higher'. For an exact target level use set_volume instead."
}

/** DIRECT decrement accelerator (§1) — "turn the volume down". */
object VolumeDownTool : VolumeStepTool(raise = false) {
    override val name = "volume_down"
    override val description =
        "Decrease the volume by ONE step directly (do NOT open Settings). Use for a relative 'turn the volume down' / 'quieter' / 'a bit lower'. For an exact target level use set_volume instead."
}
