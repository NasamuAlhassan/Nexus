package com.tekyerema.agent.helper

import android.content.Context
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.FastOutcome
import com.tekyerema.agent.LogEntry
import com.tekyerema.notifications.PrivateReading
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolResult

/**
 * The trust commands on the Local Fast Path (2026-09-29) — one hook in LocalFastRouter.tryHandle, before the classifier:
 *  - private reading by voice: "read my messages privately" / "read my messages out loud", and "read them" for the
 *    messages private reading held back ([PrivateReading]);
 *  - the trusted helper's PIN rule: "call my helper", "tell my helper what you did" or "text my helper …" are refused at a
 *    PIN ([HelperGate]); with no helper chosen, or nothing to tell, the user hears why. Otherwise null: the agent runs it
 *    ([HelperPlan]) and its gate confirms the call or the text like any other.
 */
object TrustFast {
    suspend fun handle(ctx: Context, text: String, ctrl: AccessibilityController?): FastOutcome? {
        PrivateReading.toggleOf(text)?.let { on -> return privateToggle(ctx, on) }
        if (PrivateReading.isReadThem(text)) PrivateReading.take()?.let { return FastOutcome("READ_PRIVATE", it, systemVoice = false, ok = true) }
        if (!HelperCommands.involvesHelper(text)) return null
        if (blockedNow(ctrl)) return FastOutcome("HELPER_AT_PIN", HelperGate.PIN_LINE, systemVoice = true, ok = false)
        val cmd = HelperCommands.parse(text) ?: return null   // "text my helper saying …": the agent sends it, confirmed
        if (HelperPlan.currentHelper() == null) return FastOutcome("HELPER", HelperPlan.NO_HELPER, systemVoice = false, ok = false)
        if (cmd is HelperCommands.Cmd.Summary && LastTask.recent() == null)
            return FastOutcome("HELPER", HelperPlan.NOTHING_YET, systemVoice = false, ok = false)
        return null
    }

    private fun privateToggle(ctx: Context, on: Boolean): FastOutcome {
        com.tekyerema.settings.Settings(ctx).privateReading = on
        val line = if (on) "Okay. On the loudspeaker I'll only say who your messages are from — say “read them” to hear them. " +
            "With headphones I'll read them in full."
        else "Okay, I'll read your messages out loud." + (PrivateReading.take()?.let { " $it" } ?: "")
        return FastOutcome("PRIVATE_READING_${if (on) "ON" else "OFF"}", line, systemVoice = on, ok = true)
    }

    /** Secure Input on, a credential screen in front (when we can see it — our own voice screen may cover it), or the last
     *  task stopped at a PIN. */
    private suspend fun blockedNow(ctrl: AccessibilityController?): Boolean {
        val secure = AgentEngine.state.value.status == AgentStatus.SECURE_INPUT || com.tekyerema.core.Privacy.secureStep
        if (secure || HelperGate.pinPending()) return true
        val screen = try { ctrl?.takeIf { it.ready }?.buildSnapshot() } catch (_: Throwable) { null }
        return screen != null && com.tekyerema.policy.TargetSemantics.isCredentialScreen(screen)
    }

    /**
     * make_call / send_sms preflight: the helper (by name or number) is not reached while the PIN rule holds — whatever
     * words or brain got there ("call Kofi" when Kofi is the helper, a paired computer's request). Null = go ahead.
     */
    fun toolRefusal(ctx: ToolContext, resolvedName: String?, number: String?): ToolResult? {
        if (!HelperGate.pinPending() && !com.tekyerema.core.Privacy.secureStep) return null
        val helper = HelperPlan.currentHelper() ?: return null
        val byName = resolvedName?.trim()?.equals(helper.trim(), ignoreCase = true) == true
        val byNumber = !byName && number != null && number.count(Char::isDigit) >= 9 && try {
            com.tekyerema.android.ContactResolver.resolve(ctx.appContext, helper)
                .any { it.number.filter(Char::isDigit).takeLast(9) == number.filter(Char::isDigit).takeLast(9) }
        } catch (_: Throwable) { false }
        return if (byName || byNumber) ToolResult(false, HelperGate.PIN_LINE, recoverable = false) else null
    }
}

/** The engine's task-end hook (AgentEngine.finish): the helper summary's "last task", the PIN mark, the one-time offer. */
object HelperHooks {
    /**
     * @param log the run's log; @return the log with the helper offer added to its spoken RESULT, or null (unchanged).
     */
    fun taskEnded(ctx: Context?, goal: String?, status: AgentStatus, paused: Boolean, auditTask: Long?, log: List<LogEntry>): List<LogEntry>? {
        return try {
            val atPin = HelperGate.pinPending()   // before runEnded: this task may be the one that stopped at the PIN
            HelperGate.runEnded()
            val result = log.lastOrNull { it.kind == LogEntry.Kind.RESULT }
            val g = goal?.trim().orEmpty()
            if (g.isNotEmpty() && HelperCommands.parse(g) == null && !paused) {
                val entries = if (auditTask == null) emptyList() else com.tekyerema.audit.AuditLog.all().filter { it.task == auditTask }
                LastTask.note(LastTask.from(g, status, result?.text, entries, System.currentTimeMillis()))
            }
            if (status != AgentStatus.CANNOT_DO && status != AgentStatus.FAILED) { HelperOffer.afterTask(status, paused, false, atPin); return null }
            com.tekyerema.android.Relations.ensureLoaded(ctx)
            val offer = HelperOffer.afterTask(status, paused, HelperPlan.currentHelper() != null, atPin || HelperGate.pinPending())
                ?: return null
            if (result == null || result.text.contains(offer)) return null
            log.map { if (it === result) it.copy(text = it.text.trimEnd() + " " + offer) else it }
        } catch (_: Throwable) { null }
    }
}
