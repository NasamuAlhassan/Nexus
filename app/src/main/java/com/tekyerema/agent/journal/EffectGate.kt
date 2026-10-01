package com.tekyerema.agent.journal

import com.tekyerema.accessibility.UiSnapshot

/**
 * The effect ledger inside one run of the engine loop — what replaced the per-task D-09 ("never repeat a send that
 * succeeded") and G-21 ("don't retry an uncertain send until checked") sets in AgentEngine (audit §3 gaps 1–4).
 *
 * [check] runs AFTER the policy gate has decided the step (so a GUI send counts), BEFORE any confirmation:
 *  - the same consequence already CONFIRMED in THIS task → refused; the model is told it happened (old D-09);
 *  - …in an EARLIER task (or before a restart), within the dedupe window → the step becomes a confirmation even if it
 *    would have been automatic, and the question says so: "I already sent this to Ama at 10:02 — send it again?";
 *  - UNKNOWN (or cut off mid-flight) → reconciled with the world first ([WorldCheck]): done → as above; not done → the
 *    row is FAILED and the step proceeds normally; can't tell → in this task the model is first told to look (once),
 *    after that — and in any other task — the user decides: "I may already have sent this to Ama at 10:02…".
 * A generic screen read no longer counts as "checked" (audit: `noteChecked` cleared on any read): only a world check that
 * actually recognises the outcome does.
 */
class EffectGate(
    private val taskId: String?,
    private val world: () -> WorldCheck,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val clock: (Long) -> String = EffectLines::clock
) {
    sealed class Verdict {
        object Proceed : Verdict()
        /** Don't run it; [note] goes into the model's history, [log] into the trace. */
        data class Refuse(val log: String, val note: String) : Verdict()
        /** Ask the user, even if the policy would have allowed it; [line] leads the confirmation. */
        data class AskAgain(val line: String, val prior: Effect) : Verdict()
    }

    private companion object { val GUI_TOOLS = setOf("type", "tap", "long_press", "node_action", "tap_sequence") }

    /** UNKNOWN effects of this task the model was already told to check (it is told once; then the user decides). */
    private val warned = HashSet<String>()
    /** This run's payloads by effect id, in memory only — the chat check needs the words, the ledger keeps a hash. */
    private val payloads = HashMap<String, String>()

    fun check(spec: EffectKeys.Spec, screen: UiSnapshot?): Verdict {
        val target = EffectLedger.normalizeTarget(spec.target)
        val hash = EffectLedger.hash(spec.payload)
        repeat(4) {
            val prior = EffectLedger.recentSameHash(spec.kind, target, hash, EffectLedger.dedupeWindowMs(), now()) ?: return Verdict.Proceed
            val sameTask = prior.taskId != null && prior.taskId == taskId
            when (prior.state) {
                EffectState.CONFIRMED -> return confirmed(prior, sameTask)
                EffectState.FAILED -> return Verdict.Proceed   // (never returned by recentSame)
                EffectState.INTENDED, EffectState.DISPATCHED, EffectState.UNKNOWN -> {
                    val r = try { world().check(prior, spec.payload, screen) } catch (_: Throwable) { WorldResult.UNDETERMINED }
                    when (r.verdict) {
                        WorldVerdict.DONE -> {
                            EffectLedger.mark(prior.id, EffectState.CONFIRMED, "checked: ${r.evidence}")
                            return confirmed(EffectLedger.get(prior.id) ?: prior.copy(state = EffectState.CONFIRMED), sameTask)
                        }
                        // Didn't happen: that row is closed, and an older matching one (if any) is looked at next.
                        WorldVerdict.NOT_DONE -> EffectLedger.mark(prior.id, EffectState.FAILED, "checked: ${r.evidence}")
                        WorldVerdict.UNDETERMINED -> {
                            if (prior.state != EffectState.UNKNOWN) EffectLedger.mark(prior.id, EffectState.UNKNOWN, prior.evidence)
                            if (sameTask && warned.add(prior.id)) return Verdict.Refuse(
                                "not retried yet: the last attempt may have gone through (${r.evidence})",
                                "NOT retried yet: the earlier attempt may have gone through and I couldn't confirm it (${r.evidence}). " +
                                    "LOOK first — open that chat, or read_sms / call_log — and only try again if it clearly didn't happen. " +
                                    "If you can't tell, propose it again and the user will decide.")
                            return Verdict.AskAgain(EffectLines.maybe(prior, clock), prior)
                        }
                    }
                }
            }
        }
        return Verdict.Proceed
    }

    private fun confirmed(prior: Effect, sameTask: Boolean): Verdict =
        if (sameTask) Verdict.Refuse("not repeating: the identical consequence already happened in this task",
            "NOT repeated: this already happened earlier in this task (${prior.evidence ?: "confirmed"}). Don't do it again — " +
                "if the user wants a second one they will say so; otherwise go on with the rest of the task or finish.")
        else Verdict.AskAgain(EffectLines.already(prior, clock), prior)

    /** Written and synced BEFORE the tool runs. */
    fun intend(spec: EffectKeys.Spec, tool: String): Effect =
        EffectLedger.intend(spec.kind, spec.target, spec.payload, taskId, now(), label = spec.label, tool = tool)
            .also { payloads[it.id] = spec.payload }

    /** The tool returned. A failure may still have happened (a timeout after dispatch): UNKNOWN, never FAILED blind. */
    fun dispatched(e: Effect, success: Boolean, message: String) {
        EffectLedger.mark(e.id, if (success) EffectState.DISPATCHED else EffectState.UNKNOWN,
            if (success) "done: ${message.take(80)}" else "the tool reported a failure: ${message.take(80)}")
    }

    /**
     * Verification settled: verified → CONFIRMED; otherwise the chat on screen may still tell, else UNKNOWN.
     *
     * A message is recorded as sent only when the chat agrees. For a chat message the screen is asked FIRST: words still
     * sitting in the message box did NOT go, whatever a looser claim concluded — the row is FAILED, so the app's real
     * Send button may still be pressed. (A20e 2026-10-01, Telegram: a `type` + submit that only typed was "verified" by a
     * claim matching the box, the row became CONFIRMED, and the Send tap was then refused as "already happened".)
     */
    fun settled(e: Effect, verified: Boolean, detail: String, after: UiSnapshot?) {
        val row = EffectLedger.get(e.id) ?: e
        val chat = if (EffectLedger.family(row.kind) == EffectKind.CHAT_MESSAGE) ChatCheck.check(row, payloads[e.id], after) else null
        // Only for a send done through the screen (type + submit, a tap): a send tool has its own evidence (the radio's
        // answer, the bubble it watched appear) and is not second-guessed by whatever chat happens to be in front.
        val viaScreen = row.tool == null || row.tool in GUI_TOOLS
        if (viaScreen && chat != null && chat.verdict == WorldVerdict.NOT_DONE) {
            EffectLedger.mark(e.id, EffectState.FAILED, "checked: ${chat.evidence}"); return
        }
        if (verified) { EffectLedger.mark(e.id, EffectState.CONFIRMED, "verified: ${detail.take(100)}"); return }
        when {
            chat != null && chat.verdict == WorldVerdict.DONE -> EffectLedger.mark(e.id, EffectState.CONFIRMED, "checked: ${chat.evidence}")
            chat != null && chat.verdict == WorldVerdict.NOT_DONE -> EffectLedger.mark(e.id, EffectState.FAILED, "checked: ${chat.evidence}")
            else -> EffectLedger.mark(e.id, EffectState.UNKNOWN, "not verified: ${detail.take(100)}")
        }
    }

    /**
     * The user already said yes to exactly this consequence in THIS task and it then demonstrably did not happen (the
     * words stayed in the box) — the row, or null. The engine may carry that approval over to the next attempt (the real
     * Send button) instead of asking the same question twice.
     */
    fun approvedButNotDone(spec: EffectKeys.Spec): Effect? {
        val id = taskId ?: return null
        val target = EffectLedger.normalizeTarget(spec.target)
        val hash = EffectLedger.hash(spec.payload)
        return EffectLedger.forTask(id).lastOrNull {
            EffectLedger.family(it.kind) == EffectLedger.family(spec.kind) && it.target == target && it.payloadHash == hash &&
                it.state == EffectState.FAILED && it.evidence?.startsWith("checked:") == true
        }
    }

    /**
     * On every fresh screen: an UNKNOWN chat message of this task is settled as soon as its chat is in front (the model
     * navigating there to look is exactly the "check" G-21 wanted — but only a recognised outcome counts).
     */
    fun observe(screen: UiSnapshot?) {
        val id = taskId ?: return
        for (e in EffectLedger.forTask(id)) {
            if (e.state != EffectState.UNKNOWN) continue
            val p = payloads[e.id] ?: continue
            val r = ChatCheck.check(e, p, screen)
            if (r.verdict == WorldVerdict.DONE) EffectLedger.mark(e.id, EffectState.CONFIRMED, "checked: ${r.evidence}")
            else if (r.verdict == WorldVerdict.NOT_DONE) EffectLedger.mark(e.id, EffectState.FAILED, "checked: ${r.evidence}")
        }
    }
}

/** The spoken and planner-facing words about effects. */
object EffectLines {
    fun clock(ms: Long): String = java.text.SimpleDateFormat("H:mm", java.util.Locale.US).format(java.util.Date(ms))

    private fun who(e: Effect) = e.label?.takeIf { it.isNotBlank() } ?: "them"

    /** "sent this to Ama" — what happened, in the past tense. */
    fun did(e: Effect): String = when (EffectLedger.family(e.kind)) {
        EffectKind.CHAT_MESSAGE -> "sent this to ${who(e)}"
        EffectKind.CALL -> "called ${who(e)}"
        EffectKind.PAYMENT -> "started this payment to ${who(e)}"
        EffectKind.INSTALL -> "started installing ${who(e)}"
        EffectKind.DELETE -> "deleted this"
        EffectKind.ACCOUNT -> "did this in ${who(e)}"
        else -> "did this"
    }

    private fun again(e: Effect): String = when (EffectLedger.family(e.kind)) {
        EffectKind.CHAT_MESSAGE -> "send it again"
        EffectKind.CALL -> "call again"
        EffectKind.PAYMENT -> "start it again"
        EffectKind.INSTALL -> "install it again"
        else -> "do it again"
    }

    /** "I already sent this to Ama at 10:02 — send it again?" */
    fun already(e: Effect, fmt: (Long) -> String = { clock(it) }): String = "I already ${did(e)} at ${fmt(e.at)} — ${again(e)}?"

    /** "I may already have sent this to Ama at 10:02 — I couldn't confirm it went. Send it again?" */
    fun maybe(e: Effect, fmt: (Long) -> String = { clock(it) }): String =
        "I may already have ${perfect(e)} at ${fmt(e.at)} — I couldn't confirm it went. ${again(e).replaceFirstChar { it.uppercaseChar() }}?"

    /** "did this" reads "have done this" after "may already have". */
    private fun perfect(e: Effect): String = did(e).let { if (it.startsWith("did ")) "done " + it.removePrefix("did ") else it }

    /** The planner-facing facts about this task's effects, for the resume note. */
    fun facts(effects: List<Effect>, fmt: (Long) -> String = { clock(it) }): List<String> = effects.mapNotNull { e ->
        when (e.state) {
            EffectState.CONFIRMED -> "ALREADY DONE: ${did(e)} at ${fmt(e.at)} — do not do it again."
            EffectState.UNKNOWN, EffectState.INTENDED, EffectState.DISPATCHED ->
                "MAYBE DONE: ${did(e)} at ${fmt(e.at)} could not be confirmed — look before doing it again."
            else -> null
        }
    }
}
