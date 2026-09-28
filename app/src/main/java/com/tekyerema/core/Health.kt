package com.tekyerema.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.tekyerema.core.config.SystemChecks

/**
 * N-16 — capability health. Samsung battery management, a force-stop or a reinstall silently switch off the
 * accessibility service ("screen control") or restrict the app in the background; the agent then fails in ways a
 * blind user can't diagnose. Each problem is spoken ONCE per app run, at the start of a voice turn, in plain words
 * with the fix. The reason the process last died (API 30) is logged for the run report.
 */
object Health {
    data class Issue(val key: String, val spoken: String)

    private val announced = HashSet<String>()

    fun issues(ctx: Context): List<Issue> {
        val out = ArrayList<Issue>()
        if (!SystemChecks.accessibilityEnabled(ctx))
            out.add(Issue("screen_control", "Heads up: screen control is off, so I can only do simple things like the time, calls " +
                "and settings. To turn it on, go to Settings, Accessibility, Installed apps, Tɛkyerɛma."))
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true)
            out.add(Issue("background", "Heads up: Android is restricting me in the background, so “Hey Tɛkyerɛma” may stop working. " +
                "You can allow it in Settings, under Battery."))
        return out
    }

    /** The first not-yet-spoken issue, or null. Marks it spoken. */
    @Synchronized
    fun newNotice(ctx: Context): String? = issues(ctx).firstOrNull { it.key !in announced }?.also { announced.add(it.key) }?.spoken

    /** Why the process ended last time (API 30+), for logs: "force-stopped by the user", "low memory", "crash"… */
    fun lastExitReason(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = try { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() } catch (_: Throwable) { null } ?: return null
        val why = when (info.reason) {
            ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped by the user"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
            else -> "reason ${info.reason}"
        }
        return why
    }

    fun logStartup(ctx: Context) {
        lastExitReason(ctx)?.let { Log.i("Tekyerema", "[HEALTH] last exit: $it") }
        issues(ctx).forEach { Log.i("Tekyerema", "[HEALTH] issue: ${it.key}") }
    }
}
