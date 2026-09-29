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
 *
 * WS-A: the exit-reason labels were swapped (research: agenticness_reliability_state §2.1, AOSP android11):
 * `REASON_USER_REQUESTED` (10) is "Force stop" in Settings or the app swiped away from Recents; `REASON_USER_STOPPED`
 * (11) is a stopped multi-user profile. After a force-stop Android 11 REMOVES the app's accessibility service from the
 * enabled list (AccessibilityManagerService.onHandleForceStop), so the next start says exactly that, with the fix.
 * The one-time notices also carry the offer to carry on a paused or interrupted task (made once, never acted on).
 */
object Health {
    data class Issue(val key: String, val spoken: String)

    private val announced = HashSet<String>()

    /** ApplicationExitInfo reason → words, for the log and the journal. */
    fun exitReasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> "force-stopped or swiped away by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "its user profile was stopped"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by the system"
        ApplicationExitInfo.REASON_OTHER -> "closed by the system"
        else -> "reason $reason"
    }

    /** True for a force-stop / swipe from Recents — the exit after which Android 11 turns screen control off. */
    fun isForceStop(reason: Int?): Boolean = reason == ApplicationExitInfo.REASON_USER_REQUESTED

    const val SCREEN_CONTROL_OFF = "Heads up: screen control is off, so I can only do simple things like the time, calls " +
        "and settings. To turn it on, go to Settings, Accessibility, Installed apps, Tɛkyerɛma."
    const val SCREEN_CONTROL_OFF_AFTER_FORCE_STOP = "Heads up: when Tɛkyerɛma was force-stopped or swiped away, Android " +
        "switched screen control off, so I can only do simple things like the time, calls and settings. To turn it back " +
        "on, go to Settings, Accessibility, Installed apps, Tɛkyerɛma."

    /** The screen-control notice, told why when the last exit was a force-stop. Pure. */
    fun screenControlNotice(a11yEnabled: Boolean, lastExit: Int?): String? = when {
        a11yEnabled -> null
        isForceStop(lastExit) -> SCREEN_CONTROL_OFF_AFTER_FORCE_STOP
        else -> SCREEN_CONTROL_OFF
    }

    fun issues(ctx: Context): List<Issue> {
        val out = ArrayList<Issue>()
        screenControlNotice(SystemChecks.accessibilityEnabled(ctx), lastExitCode(ctx))?.let { out.add(Issue("screen_control", it)) }
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true)
            out.add(Issue("background", "Heads up: Android is restricting me in the background, so “Hey Tɛkyerɛma” may stop working. " +
                "You can allow it in Settings, under Battery."))
        return out
    }

    /**
     * The first not-yet-spoken notice, or null. Marks it spoken. After the health issues, a paused or interrupted task
     * is offered here once (the next time the user opens Tɛkyerɛma) — WS-A, [com.tekyerema.agent.journal.ResumeOffer].
     */
    @Synchronized
    fun newNotice(ctx: Context): String? =
        issues(ctx).firstOrNull { it.key !in announced }?.also { announced.add(it.key) }?.spoken
            ?: try { com.tekyerema.agent.journal.ResumeOffer.take()?.second } catch (_: Throwable) { null }

    @Volatile private var exitCode: Int? = null
    @Volatile private var exitRead = false

    /** The ApplicationExitInfo reason the process last ended with (API 30+), read once per process. */
    fun lastExitCode(ctx: Context): Int? {
        if (exitRead) return exitCode
        exitRead = true
        if (Build.VERSION.SDK_INT < 30) return null
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        exitCode = try { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()?.reason } catch (_: Throwable) { null }
        return exitCode
    }

    /** Why the process ended last time (API 30+), for logs: "force-stopped or swiped away by the user", "low memory"… */
    fun lastExitReason(ctx: Context): String? = lastExitCode(ctx)?.let { exitReasonLabel(it) }

    fun logStartup(ctx: Context) {
        lastExitReason(ctx)?.let { Log.i("Tekyerema", "[HEALTH] last exit: $it") }
        issues(ctx).forEach { Log.i("Tekyerema", "[HEALTH] issue: ${it.key}") }
    }
}
