package com.tekyerema.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import com.tekyerema.accessibility.UiElement
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
        ToolParam("message", "string", "Optional alarm label.", required = false),
        ToolParam("repeat", "string", "Optional repeat: every day | weekdays | weekends | e.g. 'mon,wed,fri'.", required = false)
    )

    /** E-05: "weekdays" / "every day" / "mon,wed" → java.util.Calendar day numbers for AlarmClock.EXTRA_DAYS. */
    internal fun repeatDays(spec: String?): ArrayList<Int>? {
        val s = spec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val all = listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY,
            java.util.Calendar.THURSDAY, java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)
        return when {
            Regex("every ?day|daily|all days").containsMatchIn(s) -> ArrayList(all)
            s.contains("weekday") -> ArrayList(all.take(5))
            s.contains("weekend") -> arrayListOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)
            else -> {
                val names = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
                ArrayList(names.indices.filter { i -> Regex("\\b" + names[i]).containsMatchIn(s) }.map { all[it] }).takeIf { it.isNotEmpty() }
            }
        }
    }

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
        repeatDays(args.str("repeat"))?.let { intent.putExtra(AlarmClock.EXTRA_DAYS, it) }
        if (intent.resolveActivity(ctx.appContext.packageManager) == null)
            return ToolResult(false, "No clock app can set alarms.")
        return try {
            ctx.appContext.startActivity(intent)   // SET_ALARM permission declared (§1.4); Clock adds the alarm
            ToolResult(true, "Asked the Clock to set an alarm for %02d:%02d.".format(hour, minute),
                JSONObject().put("hour", hour).put("minute", minute), channel = "intent")
        } catch (t: Throwable) {
            ToolResult(false, "Couldn't set the alarm: ${t.message}")
        }
    }

    // The Clock adds the alarm itself when it receives the standard request; what can be SEEN is the Clock coming to
    // the front with that time in its list. With neither visible, the Clock having accepted the request is still the
    // evidence (as for set_timer) — a working alarm used to end "unverified" and cost two cloud calls (A20e 2026-10-01).
    override val verifiesOutcome = true

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        if (!result.success) return VerificationResult(false, "the alarm wasn't set")
        val hour = result.data?.optInt("hour", -1) ?: -1
        val minute = result.data?.optInt("minute", 0) ?: 0
        var screen = after
        repeat(4) { i ->
            val s = screen
            if (s != null && CLOCK_APP.containsMatchIn(s.appPackage.orEmpty())) {
                val shown = hour >= 0 && s.elements.any { e -> e.label?.let { showsTime(it, hour, minute) } == true }
                return VerificationResult(true, if (shown) "the Clock app shows the alarm for %02d:%02d".format(hour, minute)
                    else "the Clock app opened with the alarm")
            }
            if (i < 3) {
                kotlinx.coroutines.delay(400L)
                screen = try { ctx.controller?.buildSnapshot() }
                    catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { null } ?: screen
            }
        }
        return VerificationResult(true, "the Clock app accepted the alarm request")
    }

    private val CLOCK_APP = Regex("clock|deskclock|alarm", RegexOption.IGNORE_CASE)

    /** Does a Clock row's text carry this time? "06:30", "6:30 am", Samsung's "06 30 , Friday…" — 24 h or 12 h. Pure. */
    internal fun showsTime(label: String, hour: Int, minute: Int): Boolean {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val mm = "%02d".format(minute)
        if (Regex("(?<!\\d)0?(?:$hour|$h12)\\s*[:. ]\\s*$mm(?!\\d)").containsMatchIn(label)) return true
        // Samsung's alarm list drops the minutes of a full hour: "06 , Sunday, 4 October, On" (A20e, 3 October 2026) — the
        // 06:00 row was missed, the tool tapped the "06:00" text instead and opened the editor (QA-39).
        return minute == 0 && Regex("(?<!\\d)0?(?:$hour|$h12)\\s*,").containsMatchIn(label)
    }
}

/**
 * Cancel (dismiss) an alarm at a given time through the standard [AlarmClock.ACTION_DISMISS_ALARM] request, which the
 * Clock apps answer themselves (Samsung Clock's AlarmCTSHandleActivity declares it — checked on the A20e, 3 October
 * 2026). Before this, "cancel the 6:30 alarm" had no path of its own: the cloud brain drove Samsung Clock's list by hand,
 * where tapping the row toggles the alarm, and switched it on and off five times in 76 s over 11 model calls (QA D08).
 */
object CancelAlarmTool : Tool {
    override val name = "cancel_alarm"
    override val description = "Cancel (dismiss) the alarm set for a given hour and minute (24-hour clock)."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("hour", "integer", "Hour of day, 0-23."),
        ToolParam("minute", "integer", "Minute, 0-59.", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val hour = args.intOrNull("hour") ?: return ToolResult(false, "No hour provided.")
        val minute = args.intOrNull("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) return ToolResult(false, "Invalid time $hour:$minute.")
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.appContext.packageManager) == null)
            return ToolResult(false, "This phone's Clock app can't cancel alarms on request.", recoverable = true)
        try { ctx.appContext.startActivity(intent) } catch (t: Throwable) { return ToolResult(false, "Couldn't cancel the alarm: ${t.message}") }
        // Samsung Clock answers the request by opening its alarm list with the alarm still ON (A20e, 3 October 2026): the
        // row's own switch is then flipped here — one tap on the element the list exposes, no model, nothing else touched.
        // The list takes a moment to draw and the alarm may sit below the fold (A20e 8 Nov 2026: one look at 1.2 s saw nothing,
        // "Asked the Clock…" was reported, and the 10:25 alarm stayed on; the brain then long-pressed rows for a Delete that
        // isn't there). So: wait for the list, scroll to the row if needed, tap its switch, and re-check that it went off.
        val ctrl = ctx.controller
        val data = JSONObject().put("hour", hour).put("minute", minute)
        suspend fun snap(): UiSnapshot? = try { ctrl?.takeIf { it.ready }?.buildSnapshot() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { null }
        var s: UiSnapshot? = null
        for (i in 0 until 8) {
            kotlinx.coroutines.delay(if (i == 0) 1_200L else 400L)
            s = snap()
            if (s != null && CLOCK_APP.containsMatchIn(s.appPackage.orEmpty()) && listShowing(s)) break
        }
        if (ctrl != null && s != null && CLOCK_APP.containsMatchIn(s.appPackage.orEmpty()) && listShowing(s)) {
            var shown: UiSnapshot = s
            // The alarm's row may sit below the fold, and the list's HEADER names the next alarm's time too ("Alarm in 23 hours…
            // Sun, 9 Nov, 10:45") without a switch — so look for a row WITH a switch, scrolling down until one shows.
            var on = switchesFor(shown, hour, minute).filter { it.checked }.ifEmpty { switchesByRow(shown, hour, minute) }
            for (i in 0 until 5) {
                if (on.isNotEmpty()) break
                val list = shown.elements.firstOrNull { it.scrollable } ?: break
                try { ctrl.scrollElement(list.id, "down") } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { break }
                kotlinx.coroutines.delay(700L)
                shown = snap() ?: break
                on = switchesFor(shown, hour, minute).filter { it.checked }.ifEmpty { switchesByRow(shown, hour, minute) }
            }
            // Every switch at that time that is ON (two "6 am" alarms are both "the 6 am alarm" to the user) — one tap each,
            // on the switch itself: tapping the row opens the editor (A20e, 3 October 2026, QA-39). A switch that doesn't move
            // on ACTION_CLICK gets one real tap at its centre.
            if (on.isNotEmpty()) {
                var switched = 0
                for (sw in on) {
                    try { ctrl.clickElement(sw.id) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) {}
                    kotlinx.coroutines.delay(700L)
                    val again = snap()
                    val still = again?.elements?.firstOrNull { it.id == sw.id || (it.checkable && it.bounds == sw.bounds) }
                    if (still != null && still.checked) {
                        try { ctrl.tap((sw.bounds.left + sw.bounds.right) / 2, (sw.bounds.top + sw.bounds.bottom) / 2) }
                        catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) {}
                        kotlinx.coroutines.delay(700L)
                    }
                    switched++
                }
                val after = snap()
                if (after != null && listShowing(after) && alarmState(after, hour, minute) == AlarmState.ON)
                    return ToolResult(false, "The %02d:%02d alarm is still on in the Clock's list — tap the switch in its row (not the row itself).".format(hour, minute),
                        data, recoverable = true, channel = "node")
                val said = if (switched == 1) "Switched off the %02d:%02d alarm in the Clock.".format(hour, minute)
                    else "Switched off both %02d:%02d alarms in the Clock.".format(hour, minute).let { if (switched > 2) it.replace("both", "all $switched") else it }
                return ToolResult(true, said, data.put("switched", switched), channel = "node")
            }
            if (alarmState(shown, hour, minute) == AlarmState.ON)
                return ToolResult(false, "The %02d:%02d alarm is still on in the Clock's list — tap the switch in its row (not the row itself).".format(hour, minute),
                    data, recoverable = true, channel = "node")
        }
        return ToolResult(true, "Asked the Clock to cancel the alarm for %02d:%02d.".format(hour, minute), data, channel = "intent")
    }

    /** When the switch itself carries no time (some One UI builds): the checkable element whose centre lies within the bounds of a
     *  row that shows the time. Pure. */
    internal fun switchesByRow(s: UiSnapshot, hour: Int, minute: Int): List<UiElement> {
        val rows = s.elements.filter { e -> !e.checkable && e.label?.let { SetAlarmTool.showsTime(it, hour, minute) } == true }
        if (rows.isEmpty()) return emptyList()
        return s.elements.filter { sw ->
            sw.checkable && sw.checked && rows.any { r ->
                val cy = (sw.bounds.top + sw.bounds.bottom) / 2
                cy >= r.bounds.top && cy <= r.bounds.bottom
            }
        }
    }

    internal enum class AlarmState { ON, OFF, GONE }

    /** The alarm's state on the Clock's list: ON if any switch carrying the time is checked (or its row says "On" — Samsung:
     *  "06 45 , Sunday, 4 October, On, Double tap to edit."), OFF when every one is off; GONE when no element carries the time. Pure. */
    internal fun alarmState(s: UiSnapshot, hour: Int, minute: Int): AlarmState {
        val rows = s.elements.filter { e -> e.label?.let { SetAlarmTool.showsTime(it, hour, minute) } == true }
        if (rows.isEmpty()) return AlarmState.GONE
        val switches = rows.filter { it.checkable }
        if (switches.isNotEmpty()) return if (switches.any { it.checked }) AlarmState.ON else AlarmState.OFF
        val worded = rows.filter { Regex("(?i)\\b(?:on|off)\\b").containsMatchIn(it.label.orEmpty()) }
        return when {
            worded.isEmpty() -> AlarmState.ON
            worded.all { Regex("(?i)\\boff\\b").containsMatchIn(it.label.orEmpty()) } -> AlarmState.OFF
            else -> AlarmState.ON
        }
    }

    /** The switches (checkable elements) carrying the time — never the row or a text, which open the editor. Pure. */
    internal fun switchesFor(s: UiSnapshot, hour: Int, minute: Int): List<UiElement> =
        s.elements.filter { e -> e.checkable && e.label?.let { SetAlarmTool.showsTime(it, hour, minute) } == true }

    /** The first switch at that time, or null (kept for callers and tests). */
    internal fun switchFor(s: UiSnapshot, hour: Int, minute: Int): UiElement? = switchesFor(s, hour, minute).firstOrNull()

    /** The Clock's alarm LIST is what's showing (rows with switches / "Add alarm"), not its editor or another tab. Pure. */
    internal fun listShowing(s: UiSnapshot): Boolean = s.elements.any { e ->
        e.checkable || e.label?.contains("Double tap to edit", ignoreCase = true) == true || e.label.equals("Add alarm", ignoreCase = true)
    }

    override val verifiesOutcome = true

    /** If the Clock's list is in front, the alarm's row must be off or gone; otherwise the Clock having accepted the
     *  request is the evidence, as for set_alarm. */
    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        if (!result.success) return VerificationResult(false, "the alarm wasn't cancelled")
        val hour = result.data?.optInt("hour", -1) ?: -1
        val minute = result.data?.optInt("minute", 0) ?: 0
        kotlinx.coroutines.delay(600L)
        val s = try { ctx.controller?.buildSnapshot() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { null } ?: after
        if (s != null && CLOCK_APP.containsMatchIn(s.appPackage.orEmpty()) && hour >= 0) {
            // Only the alarm LIST is evidence: the editor (which a tap on the row opens) shows no "06:00" row either, and
            // that was read as "no longer lists an alarm" while the alarm stayed on (A20e, 3 October 2026, QA-39).
            if (!listShowing(s)) return VerificationResult(false, "the Clock isn't showing its alarm list, so the %02d:%02d alarm may still be on".format(hour, minute))
            return when (alarmState(s, hour, minute)) {
                AlarmState.GONE -> VerificationResult(true, "the Clock no longer lists an alarm for %02d:%02d".format(hour, minute))
                AlarmState.OFF -> VerificationResult(true, "the Clock shows the %02d:%02d alarm off".format(hour, minute))
                AlarmState.ON -> VerificationResult(false, "the Clock still shows the %02d:%02d alarm on".format(hour, minute))
            }
        }
        return VerificationResult(true, "the Clock app accepted the cancel request")
    }

    private val CLOCK_APP = Regex("clock|deskclock|alarm", RegexOption.IGNORE_CASE)
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
        // Our own timer (owner, 15 Oct 2026): no Clock app, the countdown in the island at the top (live/LiveTimer).
        if (com.tekyerema.core.config.AppConfig.bool("live.timer.own", true)) {
            val said = com.tekyerema.live.LiveTimer.start(ctx.appContext, seconds.toLong(), args.str("message"))
            return ToolResult(true, said, channel = "native", data = JSONObject().put("ownTimer", true))
        }
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

    // The timer runs in the Clock app with no screen (EXTRA_SKIP_UI), so nothing on screen can confirm it: an on-screen
    // check read it as "no effect" and sent a working timer to the cloud (A20e 2026-09-29, "set a timer for 3 minutes").
    // The Clock's own running-timer notification confirms it; without notification access, the Clock accepting the
    // standard request is the evidence.
    override val verifiesOutcome = true

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        if (!result.success) return VerificationResult(false, "the timer wasn't started")
        if (result.data?.optBoolean("ownTimer") == true)
            return VerificationResult(com.tekyerema.live.LiveTimer.running().isNotEmpty(), "Nexus's own timer is counting down")
        val listener = com.tekyerema.notifications.TekyeremaNotificationListener.instance
            ?: return VerificationResult(true, "the Clock app accepted the timer (it runs without a screen)")
        repeat(6) {
            val running = try {
                listener.activeNotifications?.any { sbn ->
                    CLOCK.containsMatchIn(sbn.packageName) && (sbn.notification.extras.getCharSequence("android.title")?.toString()
                        .orEmpty() + " " + sbn.notification.extras.getCharSequence("android.text")?.toString().orEmpty()).contains("timer", true)
                } == true
            } catch (_: Throwable) { false }
            if (running) return VerificationResult(true, "the Clock app shows the timer running")
            kotlinx.coroutines.delay(500L)
        }
        return VerificationResult(true, "the Clock app accepted the timer (no timer notification seen yet)")
    }

    private val CLOCK = Regex("clock|deskclock", RegexOption.IGNORE_CASE)
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
    "system" to AudioManager.STREAM_SYSTEM,
    // The phone's "Assistant" slider (AudioSystem.STREAM_ASSISTANT = 11, API 29+) is what the assistant's own voice plays on
    // when it is not muted; the accessibility stream (10) is what it falls back to. Owner, A20e, 25 Oct 2026: "the normal
    // volume is at max, but it can't set the assistant volume".
    "assistant" to 11,
    "accessibility" to AudioManager.STREAM_ACCESSIBILITY
)
private val STREAM_PARAM = ToolParam(
    "stream", "string",
    "Which audio stream (default media/music).", required = false,
    enumValues = listOf("music", "ring", "alarm", "notification", "call", "system", "assistant")
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
            // the assistant's voice also falls back to the accessibility stream: keep the two together
            if (streamName(args) == "assistant") try {
                val acc = AudioManager.STREAM_ACCESSIBILITY
                am.setStreamVolume(acc, VolumeMath.pctToIndex(pct, am.getStreamMaxVolume(acc)), 0)
            } catch (_: Throwable) {}
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
