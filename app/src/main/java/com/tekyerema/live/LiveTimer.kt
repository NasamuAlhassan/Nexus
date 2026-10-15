package com.tekyerema.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tɛkyerɛma's own timer (owner, 15 Oct 2026: "the app itself could have its own timer … a preview at the corner, some
 * small one. So when it's time to tell you"). No Clock app is opened: the countdown lives in the island at the top of the
 * screen, and when it is done the phone says so, rings and vibrates until "stop", a tap or a volume key.
 *
 * The countdown runs in this process (the accessibility service keeps it alive); an AlarmManager alarm is the backstop
 * that fires even if the CPU slept or the process was restarted — its intent carries everything needed to ring.
 */
object LiveTimer {
    private const val TAG = "Tekyerema"
    const val ACTION_DONE = "com.tekyerema.live.TIMER_DONE"

    data class Running(val id: String, val label: String?, val totalMs: Long, val endsAt: Long /* elapsedRealtime */)

    @Volatile private var timers: List<Running> = emptyList()
    @Volatile private var done: Running? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null
    @Volatile private var app: Context? = null

    fun running(): List<Running> = timers
    fun isRinging(): Boolean = done != null

    /** Start a timer of [seconds]; returns what to say. */
    fun start(ctx: Context, seconds: Long, label: String? = null): String {
        val context = ctx.applicationContext.also { app = it }
        val r = Running("timer-" + SystemClock.elapsedRealtime(), label?.trim()?.ifEmpty { null }, seconds * 1000L,
            SystemClock.elapsedRealtime() + seconds * 1000L)
        timers = timers + r
        schedule(context, r)
        tick()
        ensureTicker()
        Log.i(TAG, "[TIMER] started ${seconds}s${r.label?.let { " ($it)" } ?: ""} — own timer, shown at the top")
        return "Timer set for ${TimerWords.duration(seconds)}" + (r.label?.let { " for $it" } ?: "") + ". It's at the top of the screen."
    }

    /** "stop the timer": the ringing one first, else the soonest running one. Returns what to say, or null with none. */
    fun stop(ctx: Context): String? {
        done?.let { d -> dismiss(ctx); return "Timer stopped." + (if (timers.isNotEmpty()) " " + leftWords() else "") }
        val soonest = timers.minByOrNull { it.endsAt } ?: return null
        cancel(ctx, soonest)
        return "Cancelled the timer for ${TimerWords.duration(soonest.totalMs / 1000)}."
    }

    /** "how long is left?" — null when no timer runs. */
    fun leftWords(): String? {
        val now = SystemClock.elapsedRealtime()
        val soonest = timers.minByOrNull { it.endsAt } ?: return null
        val left = ((soonest.endsAt - now).coerceAtLeast(0) + 999) / 1000
        return TimerWords.duration(left) + " left" + (soonest.label?.let { " on the $it timer" } ?: " on your timer") + "."
    }

    fun addMinute(ctx: Context) {
        val d = done
        if (d != null) { dismiss(ctx); start(ctx, 60, d.label); return }
        val soonest = timers.minByOrNull { it.endsAt } ?: return
        val longer = soonest.copy(endsAt = soonest.endsAt + 60_000L, totalMs = soonest.totalMs + 60_000L)
        cancelAlarm(ctx, soonest)
        timers = timers.map { if (it.id == soonest.id) longer else it }
        schedule(ctx, longer); tick()
    }

    /** The alarm (or the in-process countdown) reached zero. Idempotent per timer. */
    internal fun fire(ctx: Context, id: String, label: String?, totalMs: Long) {
        val context = ctx.applicationContext.also { app = it }
        val r = timers.firstOrNull { it.id == id } ?: Running(id, label, totalMs, SystemClock.elapsedRealtime())
        if (done?.id == id) return
        if (timers.none { it.id == id } && r.endsAt < SystemClock.elapsedRealtime() - 120_000L) return   // a stale backstop
        timers = timers.filterNot { it.id == id }
        LiveActivities.end(id)
        done = r
        Log.i(TAG, "[TIMER] done (${r.totalMs / 1000}s)")
        val what = TimerWords.duration(r.totalMs / 1000)
        val line = "Your timer for $what is done" + (r.label?.let { " — $it" } ?: "") + ". Say stop, or press a volume key."
        LiveActivities.upsert(LiveActivity(id = "timer-done", kind = LiveKind.TIMER, title = r.label?.let { "$it — time's up" } ?: "Time's up",
            compact = "Time's up", detail = "$what timer", glyph = LiveGlyph.BELL, urgent = true, priority = 10,
            actions = listOf(LiveAction("stop", "Stop", primary = true), LiveAction("plus1", "+1 min")),
            spoken = "Your timer for $what is done")) { a ->
            when (a) { "stop" -> dismiss(context); "plus1" -> addMinute(context) }
        }
        // [owner: verify the Twi]
        try { com.tekyerema.voice.VoiceEngine.announceAnswer(line, "Timer no aba awieɛ. Ka 'stop'.", urgent = true) } catch (_: Throwable) {}
        TimerAlert.start(context, delayMs = 3_200L)
        ensureTicker()
    }

    fun dismiss(ctx: Context) {
        done = null
        TimerAlert.stop()
        LiveActivities.end("timer-done")
        Log.i(TAG, "[TIMER] dismissed")
    }

    private fun cancel(ctx: Context, r: Running) {
        timers = timers.filterNot { it.id == r.id }
        cancelAlarm(ctx, r)
        LiveActivities.end(r.id)
        Log.i(TAG, "[TIMER] cancelled")
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (timers.isNotEmpty()) {
                tick()
                val now = SystemClock.elapsedRealtime()
                timers.filter { it.endsAt <= now }.forEach { r -> app?.let { fire(it, r.id, r.label, r.totalMs) } }
                delay(1000L - (SystemClock.elapsedRealtime() % 1000L).coerceIn(0, 999))
            }
        }
    }

    /** The island's view of every running timer. */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        timers.forEach { r ->
            val leftMs = (r.endsAt - now).coerceAtLeast(0)
            LiveActivities.upsert(LiveActivity(id = r.id, kind = LiveKind.TIMER, title = r.label ?: "Timer",
                compact = TimerWords.clock((leftMs + 999) / 1000), detail = TimerWords.duration(r.totalMs / 1000) + " timer",
                progress = if (r.totalMs > 0) (1f - leftMs.toFloat() / r.totalMs).coerceIn(0f, 1f) else null,
                glyph = LiveGlyph.TIMER, priority = 2, updatedAt = r.endsAt * -1,   // the soonest leads among timers
                actions = listOf(LiveAction("cancel", "Cancel"), LiveAction("plus1", "+1 min")),
                spoken = "Timer, ${TimerWords.duration((leftMs + 999) / 1000)} left")) { a ->
                app?.let { c -> when (a) { "cancel" -> cancel(c, r); "plus1" -> addMinute(c) } }
            }
        }
    }

    private fun pending(ctx: Context, r: Running): PendingIntent =
        PendingIntent.getBroadcast(ctx, r.id.hashCode(), Intent(ctx, TimerReceiver::class.java).setAction(ACTION_DONE)
            .putExtra("id", r.id).putExtra("label", r.label).putExtra("total", r.totalMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(ctx: Context, r: Running) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, r)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, r.endsAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, r.endsAt, pi)   // in-process countdown is exact
        } catch (t: Throwable) { Log.w(TAG, "[TIMER] alarm backstop not set: ${t.javaClass.simpleName}") }
    }

    private fun cancelAlarm(ctx: Context, r: Running) {
        try { ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, r)) } catch (_: Throwable) {}
    }
}

/** The backstop alarm. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveTimer.ACTION_DONE) return
        val id = intent.getStringExtra("id") ?: return
        LiveTimer.fire(context, id, intent.getStringExtra("label"), intent.getLongExtra("total", 0L))
    }
}

/** Words for timers. Pure (unit-tested). */
object TimerWords {
    /** 300 → "5 minutes", 90 → "1 minute 30 seconds", 3600 → "1 hour", 45 → "45 seconds". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        val parts = buildList {
            if (h > 0) add(plural(h, "hour"))
            if (m > 0) add(plural(m, "minute"))
            if (sec > 0 && h == 0L) add(plural(sec, "second"))
        }
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
    }
    /** 272 → "4:32", 3872 → "1:04:32". */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
    private fun plural(n: Long, w: String) = "$n $w" + if (n == 1L) "" else "s"
}
