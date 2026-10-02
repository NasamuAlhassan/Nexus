package com.tekyerema.money

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LocalBrain

/**
 * Money safety round 2's three hooks into the agent (each one line in a shared file):
 *  - [decide] — RoutingBrain, before any brain: "try again" after a *170# timeout ([MoMoRetry]), "I sent money to the
 *    wrong number" ([MoMoAfterCare]), and the guided bank menu ([BankGuide]). All decided on the phone, online or offline.
 *  - [end] — RoutingBrain.moneyEnd: a Mobile Money send refused by the user's spending limit, or a bank step that failed
 *    or reached a payment confirmation / PIN, ENDS the task with its line — no planner works round a limit or improvises
 *    inside a bank's menu.
 *  - [rewrite] — References.rewrite (where "call her back" becomes "call Ama back"): "try again" → the send it retries,
 *    and "yes" to "Should I call MTN on 100?" → "call 100".
 */
object MoneyPlan {
    fun decide(goal: String, history: List<String>): AgentDecision? =
        MoMoRetry.decide(goal, history) ?: MoMoAfterCare.decide(goal, history) ?: BankGuide.decide(goal, history)
            ?: TelcoGuide.decide(goal, history)
            // Finance audit (2026-10-01): a spoken send / airtime / balance / "did it go through" is planned on the phone.
            ?: MoneyFlow.decide(goal, history)

    fun end(history: List<String>): Pair<AgentStatus, String>? {
        BankGuide.end(history)?.let { return it }
        TelcoGuide.end(history)?.let { return it }
        navigatorEnd(history)?.let { return it }
        return limitEnd(history)
    }

    /** The navigator's walk ends the task at its boundary or its honest failure — never a recovery inside a money
     *  menu. A hand-over to the guide (data ask=true → a question line) does NOT end: TelcoGuide continues it. */
    fun navigatorEnd(history: List<String>): Pair<AgentStatus, String>? {
        val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return null
        if (!last.startsWith("${com.tekyerema.money.ussd.UssdNavigatorTool.NAME}(")) return null
        // "Which Prince?" was asked and the same call is queued again with the pick — not an end (the engine's note).
        if (last.contains("→ several contacts matched")) return null
        val msg = LocalBrain.resultMessage(last)
        if (!last.contains("→ success")) return AgentStatus.CANNOT_DO to
            msg.ifBlank { "The Mobile Money walk didn't finish. Nothing was sent." }
        if (msg.trim().endsWith("?")) return null   // the hand-over question: the guide takes it from here
        return AgentStatus.COMPLETED to msg
    }

    /** A momo_send / mobile_money preflight refused by a spending limit: the refusal is the answer. Pure. */
    fun limitEnd(history: List<String>): Pair<AgentStatus, String>? {
        val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return null
        if (!(last.startsWith("momo_send(") || last.startsWith("mobile_money(")) || !last.contains("[preflight]")) return null
        val msg = LocalBrain.resultMessage(last)
        return if (MoneyLimits.isRefusal(msg)) AgentStatus.CANNOT_DO to msg else null
    }

    fun rewrite(goal: String): String? = MoMoRetry.rewrite(goal) ?: MoMoAfterCare.rewrite(goal)

    private val STEP_LINE = Regex("^[a-z_]+\\(")
}
