package com.tekyerema.agent.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import com.tekyerema.voice.VoiceEngine
import com.tekyerema.voice.VoiceSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A-13 — receives schedule alarms ([ACTION_FIRE], explicit intents from [ScheduledTasks]) and re-arms everything
 * after BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED. Declare it `exported="false"`:
 * system broadcasts still arrive, and no other app can fire a schedule.
 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_FIRE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
                val pending = goAsync()
                TriggerRunner.fire(app, id, attempt) { try { pending.finish() } catch (_: Throwable) {} }
            }
            Intent.ACTION_BOOT_COMPLETED -> ScheduledTasks.rescheduleAll(app, afterBoot = true)
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                ScheduledTasks.rescheduleAll(app)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.tekyerema.action.TRIGGER_SCHEDULE"
        const val EXTRA_ID = "schedule_id"
        const val EXTRA_ATTEMPT = "attempt"
    }
}

/**
 * Runs one firing of a schedule under the unattended-safety rules:
 *  - flag A-13 on, not in quiet hours (unless the schedule consents), user not busy (else retry in
 *    `triggers.retryDelayMs`, up to `triggers.maxRetries`);
 *  - "remind me …"/"say …" → spoken locally; a read-only goal → the agent runs it and the answer is spoken + posted;
 *    anything else → "It's time: <goal>." and NOTHING else;
 *  - an unattended run that reaches a confirmation or a secure (PIN) screen is STOPPED — never approved.
 */
internal object TriggerRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val TERMINAL = setOf(
        AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED, AgentStatus.CANNOT_DO, AgentStatus.FAILED, AgentStatus.STOPPED
    )
    private val NEEDS_USER = setOf(AgentStatus.WAITING_CONFIRMATION, AgentStatus.SECURE_INPUT)

    private fun maxRetries() = AppConfig.int("triggers.maxRetries", 5)
    private fun retryDelayMs() = AppConfig.long("triggers.retryDelayMs", 120_000L)
    private fun runTimeoutMs() = AppConfig.long("triggers.runTimeoutMs", 120_000L)

    fun fire(app: Context, id: String, attempt: Int, release: () -> Unit) {
        val released = AtomicBoolean(false)
        val done = { if (released.compareAndSet(false, true)) release() }
        scope.launch {
            try { handle(app, id, attempt, done) }
            catch (t: Throwable) { Log.w(TriggerGate.TAG, "[TRIGGER] A-13 $id failed: ${t.message}") }
            finally { done() }
        }
    }

    private suspend fun handle(app: Context, id: String, attempt: Int, release: () -> Unit) {
        val s = ScheduledTasks.get(app, id) ?: run { Log.i(TriggerGate.TAG, "[TRIGGER] A-13 $id no longer exists"); return }
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        // Arm the NEXT occurrence first, so nothing below (a crash, a kill) can lose a recurring schedule.
        if (attempt == 0) ScheduledTasks.armNext(app, s, now, zone)
        val busy = withContext(Dispatchers.Main) { TriggerGate.userBusy(app) }
        val decision = ScheduledTasks.decide(
            s, FeatureFlags.isEnabled(ScheduledTasks.FEATURE, true), QuietHours.blocksSchedule(s, now, zone), busy, attempt, maxRetries()
        )
        Log.i(TriggerGate.TAG, "[TRIGGER] A-13 ${s.id} attempt=$attempt → $decision")
        when (decision) {
            FireDecision.SKIP_DISABLED, FireDecision.SKIP_QUIET -> finish(app, s)
            FireDecision.RETRY_LATER -> retry(app, s, attempt, now)
            FireDecision.GIVE_UP_BUSY -> {
                // Speak nothing over the user's task; leave the line in the shade for later.
                val line = TriggerSafety.reminderLine(s.goal) ?: TriggerSafety.itsTime(s.goal)
                try { VoiceSessionService.postResult(app, line) } catch (_: Throwable) {}
                finish(app, s)
            }
            FireDecision.SPEAK_REMINDER -> { TriggerGate.speak(app, TriggerSafety.reminderLine(s.goal) ?: TriggerSafety.itsTime(s.goal)); finish(app, s) }
            FireDecision.SPEAK_OFFER -> { TriggerGate.speak(app, TriggerSafety.itsTime(s.goal)); finish(app, s) }
            FireDecision.RUN_READ_ONLY -> {
                val ran = runReadOnly(app, s, release)
                if (!ran && attempt < maxRetries()) retry(app, s, attempt, now) else finish(app, s)
            }
        }
    }

    private fun retry(app: Context, s: Schedule, attempt: Int, now: Long) =
        ScheduledTasks.armRetry(app, s.id, attempt + 1, now + retryDelayMs())

    /** A one-off is used up once it has been handled (spoken, run, skipped or given up). */
    private fun finish(app: Context, s: Schedule) { if (s.once) ScheduledTasks.remove(app, s.id) }

    /**
     * Submit a READ-ONLY goal to the agent and speak what it found. Returns false when the agent could not take it
     * (the user became busy in the meantime) so the caller retries. Re-checks [TriggerSafety.isReadOnlyGoal] right
     * at the gate — defence in depth for a schedule stored by an older build or edited by hand.
     */
    private suspend fun runReadOnly(app: Context, s: Schedule, release: () -> Unit): Boolean {
        val goal = s.goal.trim()
        if (!TriggerSafety.isReadOnlyGoal(goal)) { TriggerGate.speak(app, TriggerSafety.itsTime(goal)); return true }
        // Busy-check and submit in ONE main-thread step: voice/UI submit on the main thread too, so nothing can slip in.
        val accepted = withContext(Dispatchers.Main) {
            if (TriggerGate.userBusy(app)) false
            // unattended = true: D-08 refuses any tool that doesn't allow the "trigger" caller (sends, calls, money).
            else { AgentEngine.submitGoal(goal, unattended = true); AgentEngine.state.value.goal == goal }
        }
        if (!accepted) return false
        // The agent's foreground service pins the process from here; the broadcast can be released.
        release()
        val end = withTimeoutOrNull(runTimeoutMs()) {
            AgentEngine.state.first { it.goal == goal && (it.status in TERMINAL || it.status in NEEDS_USER) }
        }
        if (end == null) {
            Log.i(TriggerGate.TAG, "[TRIGGER] A-13 ${s.id} timed out — stopping the unattended run")
            stopIfOurs(goal)
            TriggerGate.speak(app, TriggerSafety.itsTime(goal))
            return true
        }
        when {
            end.status in NEEDS_USER -> {
                // The agent wanted an OK (or reached a PIN screen). Unattended ⇒ NEVER approve: stop, then tell the user.
                Log.i(TriggerGate.TAG, "[TRIGGER] A-13 ${s.id} reached ${end.status} — stopped, nothing approved")
                stopIfOurs(goal)
                TriggerGate.speak(app, AppConfig.phrase(
                    "triggers.phrases.needsYou", "It's time: {goal}. It needs your OK, so I didn't do it by myself.",
                    "goal" to TriggerSafety.clean(goal)
                ))
            }
            end.status == AgentStatus.COMPLETED || end.status == AgentStatus.COMPLETED_UNVERIFIED -> {
                val result = end.log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text?.takeIf { it.isNotBlank() }
                if (result == null) TriggerGate.speak(app, TriggerSafety.itsTime(goal))
                else {
                    try { VoiceEngine.announce(result) } catch (_: Throwable) {}
                    // Same wording VoiceEngine's history collector posts, so the shade doesn't flip between two texts.
                    val posted = if (end.status == AgentStatus.COMPLETED_UNVERIFIED) "$result (not confirmed)" else result
                    try { VoiceSessionService.postResult(app, posted) } catch (_: Throwable) {}
                }
            }
            end.status == AgentStatus.STOPPED -> Unit   // the user stopped it themselves — say nothing
            // CANNOT_DO / FAILED: the agent's honest result is already in the shade; speak the reminder only.
            else -> TriggerGate.speak(app, TriggerSafety.itsTime(goal), post = false)
        }
        return true
    }

    /** Stop the agent only if the run in flight is still OURS — never a task the user started meanwhile. */
    private suspend fun stopIfOurs(goal: String) {
        withContext(Dispatchers.Main) {
            val st = AgentEngine.state.value
            if (st.goal == goal && TriggerGate.isAgentBusy(st.status)) AgentEngine.stop()
        }
    }
}
