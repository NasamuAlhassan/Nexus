package com.tekyerema.money

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.LocalBrain
import org.json.JSONObject

/**
 * "Try again" after the *170# walk timed out (money safety round 2, 2026-09-29; secure_payment_auth.md recommendation 5:
 * "redial and replay on timeout"). The research: *170# "will time you out", and a blind user loses the whole send to it.
 *
 * Only a TIMEOUT is remembered — the menu didn't answer, or MTN's confirmation screen never came — never a cancel or an
 * amount/number mismatch (those are MoMoSendTool's own Cancel verdicts, which a retry must not paper over). Within
 * [WINDOW_MS], "try again" becomes the SAME send (number, amount, reference) proposed as a new task: it goes through the
 * preflight (limits, first-time recipient) and the full spoken read-back and waits for a yes, like any send. Never
 * automatic: nothing is retried unless the user says so, and each "try again" is used once.
 *
 * How: [rewrite] (called where the engine resolves "call her back", References.rewrite) turns "try again" into a goal that
 * names the send ("try again: send 5 cedis to 0553238011 with Mobile Money, reference Payment") — so the read-back never
 * calls the number "a number you didn't say" — and [decide] (RoutingBrain, before any brain) proposes exactly that
 * momo_send.
 */
object MoMoRetry {
    const val WINDOW_MS = 5 * 60_000L
    const val RATIONALE = "local:try the Mobile Money send again"

    data class Spec(val number: String, val amount: String, val reference: String?, val at: Long)

    @Volatile private var failed: Spec? = null
    /** The spec a "try again" is running right now (its goal is [goalFor] of it). */
    @Volatile private var retrying: Spec? = null

    /** The *170# walk timed out: keep the send so "try again" can propose it again. */
    fun remember(number: String, amount: String, reference: String?, now: Long = System.currentTimeMillis()) {
        failed = Spec(number, amount, reference?.takeIf { it.isNotBlank() }, now)
    }

    /** A send reached the PIN or was cancelled: nothing to retry. */
    fun clear() { failed = null; retrying = null }

    /** The send waiting for "try again", or null (none, or older than [WINDOW_MS]). */
    fun pending(now: Long = System.currentTimeMillis()): Spec? = failed?.takeIf { now - it.at in 0..WINDOW_MS }

    private val TRY_AGAIN = Regex("^(?:(?:yes|ok|okay|please)[, ]+)?(?:please )?(?:try (?:it |that |this |the send |sending )?(?:again|once more)|" +
        "retry(?: it| that| the send)?|try sending (?:it )?again|send it again|have another go)(?: please| now)?$")

    fun isTryAgain(text: String): Boolean =
        TRY_AGAIN.matches(text.trim().lowercase().replace('’', '\'').replace(Regex("[.!?]+$"), "").replace(Regex("\\s+"), " ").trim())

    /** The goal a retry of [s] runs as — it names the number, so the read-back reads it as the user's. */
    fun goalFor(s: Spec): String =
        "try again: send ${s.amount} cedis to ${s.number} with Mobile Money" + (s.reference?.let { ", reference $it" } ?: "")

    /** "try again" with a timed-out send waiting → the goal to run instead (used once); else null. */
    fun rewrite(goal: String, now: Long = System.currentTimeMillis()): String? {
        if (!isTryAgain(goal)) return null
        val s = pending(now) ?: return null
        failed = null
        retrying = s
        return goalFor(s)
    }

    /**
     * The retry task's steps: first the same momo_send (the engine's gate reads it back and waits for a yes); when its
     * preflight refused it (a limit, the network), that reason ends the task. Everything else about how the send ends is
     * RoutingBrain.moneyEnd's. Null = not a retry task.
     */
    fun decide(goal: String, history: List<String>, spec: Spec? = retrying): AgentDecision? {
        val s = spec ?: return null
        if (goal != goalFor(s)) return null
        val last = LocalBrain.lastStep(history)
        if (!last.startsWith("momo_send(")) {
            if (history.any { it.startsWith("momo_send(") }) return null
            // The user answered the read-back with a change ("make it 10 cedis"): a retry repeats a send exactly, so it
            // stops here rather than proposing the same thing again.
            if (history.any { it.startsWith("You proposed:") && !it.contains("corrected the number") })
                return AgentDecision.Done(CHANGED)
            val args = JSONObject().put("number", s.number).put("amount", s.amount)
            s.reference?.let { args.put("reference", it) }
            return AgentDecision.CallTool("momo_send", args, RATIONALE)
        }
        if (last.contains("[preflight]")) return AgentDecision.CannotDo("money", LocalBrain.resultMessage(last)
            .ifBlank { "I couldn't start the send again. Nothing was sent." })
        return null
    }

    const val CHANGED = "Okay — I didn't send it. Say the whole send again the way you want it."

    /** What the tool says when the walk timed out ([MoMoSendTool]). Short: it rides in the step's history line. */
    const val MENU_TIMEOUT = "The Mobile Money menu didn't respond in time, so nothing was sent. Say “try again” to start again."
    const val CONFIRM_TIMEOUT = "MTN's confirmation screen didn't come in time, so I stopped and nothing was sent. " +
        "If a menu is still open, press Cancel. Say “try again” to start again."
}
