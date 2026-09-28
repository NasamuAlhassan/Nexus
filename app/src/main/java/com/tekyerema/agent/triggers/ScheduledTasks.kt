package com.tekyerema.agent.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.tekyerema.core.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * A-13 — one scheduled goal. [days] holds java.util.Calendar day constants (SUNDAY=1 … SATURDAY=7); empty = every
 * day. [once] = a one-off ("at 9 pm remind me …"): it fires at [fireAt] (pinned when added) and is then removed.
 * [allowQuiet] lets it speak inside quiet hours even when its own time is outside them.
 */
data class Schedule(
    val id: String,
    val goal: String,
    val hour: Int,
    val minute: Int,
    val days: Set<Int>,
    val enabled: Boolean = true,
    val once: Boolean = false,
    val allowQuiet: Boolean = false,
    /** One-off only: the absolute time it is due (epoch ms); 0 = not pinned yet. */
    val fireAt: Long = 0L
) {
    val valid: Boolean get() = goal.isNotBlank() && hour in 0..23 && minute in 0..59 && days.all { it in 1..7 }
}

/** What a schedule alarm does when it goes off — decided purely so the policy is unit-tested. */
enum class FireDecision {
    /** Schedule disabled or flag A-13 off — nothing happens. */
    SKIP_DISABLED,
    /** Inside quiet hours without consent — skipped silently (not postponed to the morning). */
    SKIP_QUIET,
    /** The user is mid-task / mid-turn / on a call — try again in a couple of minutes. */
    RETRY_LATER,
    /** Still busy after every retry — leave the line in the shade silently, speak nothing. */
    GIVE_UP_BUSY,
    /** "remind me …" / "say …" — speak the line locally; the agent is never involved. */
    SPEAK_REMINDER,
    /** A read-only goal — run it through the agent unattended and speak the answer. */
    RUN_READ_ONLY,
    /** Anything else — speak "It's time: <goal>." and do NOTHING else. */
    SPEAK_OFFER
}

/**
 * A-13 — scheduled and recurring goals. Stored as a JSON array in SharedPreferences "tekyerema_triggers" (plus
 * optional read-only seeds from config `triggers.schedule`), armed with inexact `setAndAllowWhileIdle(RTC_WAKEUP)`
 * alarms (no SCHEDULE_EXACT_ALARM on API 30) that broadcast to [TriggerReceiver].
 */
object ScheduledTasks {
    private const val KEY = "schedules"
    private const val SCHEME = "tekyerema-trigger"
    const val FEATURE = "A-13"

    // ---------------------------------------------------------------- storage

    /** Every schedule: config seeds first (recurring only, can't be removed by voice), then the user's own. */
    fun list(ctx: Context): List<Schedule> = configSchedules() + userSchedules(ctx)

    @Synchronized
    fun userSchedules(ctx: Context): List<Schedule> = decode(TriggerGate.prefs(ctx).getString(KEY, null))

    fun configSchedules(): List<Schedule> =
        decode((AppConfig.node("triggers.schedule") as? JSONArray)?.toString())
            .map { it.copy(id = if (it.id.startsWith("cfg:")) it.id else "cfg:${it.id}", once = false, fireAt = 0L) }

    fun get(ctx: Context, id: String): Schedule? = list(ctx).firstOrNull { it.id == id }

    /**
     * Store (replacing the same id) and arm. A one-off gets its [Schedule.fireAt] pinned to the next occurrence now.
     * Returns what was stored, or null when the schedule is invalid.
     */
    @Synchronized
    fun add(ctx: Context, s: Schedule, nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Schedule? {
        if (!s.valid || s.id.isBlank() || s.id.startsWith("cfg:")) return null
        val stored = if (s.once && s.fireAt <= 0L) s.copy(fireAt = nextFire(s, nowMillis, zone)) else s
        save(ctx, userSchedules(ctx).filter { it.id != s.id } + stored)
        if (stored.enabled) arm(ctx, stored.id, if (stored.once) stored.fireAt else nextFire(stored, nowMillis, zone))
        else cancel(ctx, stored.id)
        Log.i(TriggerGate.TAG, "[TRIGGER] A-13 added ${stored.id} ${whenPhrase(stored)}")
        return stored
    }

    /** Remove a user schedule and cancel its alarms. False when there was no such schedule. */
    @Synchronized
    fun remove(ctx: Context, id: String): Boolean {
        val all = userSchedules(ctx)
        cancel(ctx, id)
        if (all.none { it.id == id }) return false
        save(ctx, all.filter { it.id != id })
        return true
    }

    @Synchronized
    fun setEnabled(ctx: Context, id: String, enabled: Boolean): Boolean {
        val s = userSchedules(ctx).firstOrNull { it.id == id } ?: return false
        add(ctx, s.copy(enabled = enabled))
        return true
    }

    private fun save(ctx: Context, list: List<Schedule>) {
        TriggerGate.prefs(ctx).edit().putString(KEY, encode(list)).apply()
    }

    fun encode(list: List<Schedule>): String = JSONArray().apply {
        for (s in list) put(
            JSONObject()
                .put("id", s.id).put("goal", s.goal).put("hour", s.hour).put("minute", s.minute)
                .put("days", JSONArray().apply { s.days.sorted().forEach { put(it) } })
                .put("enabled", s.enabled).put("once", s.once).put("allowQuiet", s.allowQuiet).put("fireAt", s.fireAt)
        )
    }.toString()

    /** Tolerant: bad entries are skipped, a bad document is an empty list. */
    fun decode(text: String?): List<Schedule> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(text) } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<Schedule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) }.toSet() }.orEmpty()
            val s = Schedule(
                id = o.optString("id", ""), goal = o.optString("goal", ""),
                hour = o.optInt("hour", -1), minute = o.optInt("minute", 0), days = days,
                enabled = o.optBoolean("enabled", true), once = o.optBoolean("once", false),
                allowQuiet = o.optBoolean("allowQuiet", false), fireAt = o.optLong("fireAt", 0L)
            )
            if (s.id.isNotBlank() && s.valid) out.add(s)
        }
        return out
    }

    // ---------------------------------------------------------------- time (pure)

    /** The next time [s] is due strictly AFTER [nowMillis], in [zone]'s wall clock (DST-safe: fields, not +24 h). */
    fun nextFire(s: Schedule, nowMillis: Long, zone: TimeZone): Long {
        val days = s.days.filter { it in 1..7 }.toSet()
        for (offset in 0..7) {
            val c = Calendar.getInstance(zone).apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, s.hour)
                set(Calendar.MINUTE, s.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > nowMillis && (days.isEmpty() || c.get(Calendar.DAY_OF_WEEK) in days)) return c.timeInMillis
        }
        return nowMillis + 24L * 60 * 60 * 1000   // unreachable for a valid schedule
    }

    /** The policy for a firing alarm. Pure. */
    fun decide(s: Schedule, flagOn: Boolean, quietBlocks: Boolean, busy: Boolean, attempt: Int, maxRetries: Int): FireDecision = when {
        !s.enabled || !flagOn -> FireDecision.SKIP_DISABLED
        quietBlocks -> FireDecision.SKIP_QUIET
        busy -> if (attempt < maxRetries) FireDecision.RETRY_LATER else FireDecision.GIVE_UP_BUSY
        TriggerSafety.reminderLine(s.goal) != null -> FireDecision.SPEAK_REMINDER
        TriggerSafety.isReadOnlyGoal(s.goal) -> FireDecision.RUN_READ_ONLY
        else -> FireDecision.SPEAK_OFFER
    }

    private val DAY_NAMES = mapOf(
        Calendar.MONDAY to "Monday", Calendar.TUESDAY to "Tuesday", Calendar.WEDNESDAY to "Wednesday",
        Calendar.THURSDAY to "Thursday", Calendar.FRIDAY to "Friday", Calendar.SATURDAY to "Saturday", Calendar.SUNDAY to "Sunday"
    )

    fun timePhrase(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val ampm = if (hour < 12) "AM" else "PM"
        return if (minute == 0) "$h12 $ampm" else "$h12:${minute.toString().padStart(2, '0')} $ampm"
    }

    /** "every weekday at 6:30 AM", "every Monday and Friday at 5 PM", "at 9 PM" (one-off). */
    fun whenPhrase(s: Schedule): String {
        val t = timePhrase(s.hour, s.minute)
        if (s.once) return "at $t"
        val order = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val d = when {
            s.days.isEmpty() || s.days.size == 7 -> "every day"
            s.days == ScheduleParser.WEEKDAYS -> "every weekday"
            s.days == ScheduleParser.WEEKEND -> "every weekend"
            else -> {
                val names = order.filter { it in s.days }.mapNotNull { DAY_NAMES[it] }
                "every " + if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
            }
        }
        return "$d at $t"
    }

    /** A spoken summary for confirming a new schedule: "Every weekday at 6:30 AM: tell me the weather." */
    fun describe(s: Schedule): String = whenPhrase(s).replaceFirstChar { it.uppercase() } + ": " + TriggerSafety.clean(s.goal) + "."

    // ---------------------------------------------------------------- alarms

    private fun intent(ctx: Context, id: String, attempt: Int, retry: Boolean): Intent =
        Intent(ctx, TriggerReceiver::class.java)
            .setAction(TriggerReceiver.ACTION_FIRE)
            // The data URI makes each schedule's (and its retry's) PendingIntent distinct — extras don't.
            .setData(Uri.parse("$SCHEME://schedule/${Uri.encode(id)}" + if (retry) "/retry" else ""))
            .putExtra(TriggerReceiver.EXTRA_ID, id)
            .putExtra(TriggerReceiver.EXTRA_ATTEMPT, attempt)

    private fun pending(ctx: Context, id: String, attempt: Int, retry: Boolean, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(ctx.applicationContext, 0, intent(ctx.applicationContext, id, attempt, retry), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun alarms(ctx: Context): AlarmManager? = ctx.getSystemService(AlarmManager::class.java)

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent?) {
        if (pi == null) return
        try { alarms(ctx)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        catch (t: Throwable) { Log.w(TriggerGate.TAG, "[TRIGGER] A-13 can't arm alarm: ${t.message}") }
    }

    internal fun arm(ctx: Context, id: String, at: Long) = setAlarm(ctx, at, pending(ctx, id, 0, false, PendingIntent.FLAG_UPDATE_CURRENT))

    /** Arm the occurrence AFTER the one firing now (a minute of margin so an early delivery can't re-arm the same one). */
    internal fun armNext(ctx: Context, s: Schedule, nowMillis: Long, zone: TimeZone = TimeZone.getDefault()) {
        if (s.enabled && !s.once) arm(ctx, s.id, nextFire(s, nowMillis + 60_000L, zone))
    }

    internal fun armRetry(ctx: Context, id: String, attempt: Int, at: Long) =
        setAlarm(ctx, at, pending(ctx, id, attempt, true, PendingIntent.FLAG_UPDATE_CURRENT))

    internal fun cancel(ctx: Context, id: String) {
        for (retry in listOf(false, true)) {
            val pi = pending(ctx, id, 0, retry, PendingIntent.FLAG_NO_CREATE) ?: continue
            try { alarms(ctx)?.cancel(pi) } catch (_: Throwable) {}
            pi.cancel()
        }
    }

    /**
     * Re-arm everything — after boot ([afterBoot] = true: every alarm was wiped), an app update, a clock/time-zone
     * change, or on app start as a safety net. Recurring schedules are simply re-armed (re-arming replaces, never
     * duplicates). A past-due one-off is re-fired shortly ONLY after a boot and only if it is less than
     * `triggers.lateGraceMs` (default 1 h) late — otherwise its own alarm/retry is still pending, so it is left alone;
     * one more than the grace late is dropped rather than spoken hours late.
     */
    @Synchronized
    fun rescheduleAll(
        ctx: Context,
        afterBoot: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ) {
        val grace = AppConfig.long("triggers.lateGraceMs", 60 * 60 * 1000L)
        val keep = ArrayList<Schedule>()
        var changed = false
        for (s in userSchedules(ctx)) {
            if (!s.enabled) { cancel(ctx, s.id); keep.add(s); continue }
            if (!s.once) { arm(ctx, s.id, nextFire(s, nowMillis, zone)); keep.add(s); continue }
            val at = if (s.fireAt > 0L) s.fireAt else nextFire(s, nowMillis, zone).also { changed = true }
            when {
                at > nowMillis -> { arm(ctx, s.id, at); keep.add(s.copy(fireAt = at)) }
                nowMillis - at <= grace -> { if (afterBoot) arm(ctx, s.id, nowMillis + 5_000L); keep.add(s.copy(fireAt = at)) }
                else -> { cancel(ctx, s.id); changed = true; Log.i(TriggerGate.TAG, "[TRIGGER] A-13 one-off ${s.id} expired — dropped") }
            }
        }
        if (changed) save(ctx, keep)
        for (s in configSchedules()) if (s.enabled) arm(ctx, s.id, nextFire(s, nowMillis, zone)) else cancel(ctx, s.id)
        Log.i(TriggerGate.TAG, "[TRIGGER] A-13 rescheduled ${keep.count { it.enabled }} schedule(s)${if (afterBoot) " after boot" else ""}")
    }
}
