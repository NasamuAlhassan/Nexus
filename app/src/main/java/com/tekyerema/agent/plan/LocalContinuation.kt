package com.tekyerema.agent.plan

import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.ActionChain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.core.TextDistance
import com.tekyerema.core.config.AppConfig
import org.json.JSONObject

/**
 * P3-B3 — local continuation. When a step has just verified and settled a plan subgoal, and the NEXT open subgoal is a
 * plain "open/tap/select <label>" whose label is on the fresh screen exactly once, the engine taps it without a planner
 * call. The tap goes through the same target resolution, policy gate, stale-screen check and verification as any step,
 * and carries the subgoal's own VERIFY check as its claim (only a checkable subgoal is continued).
 *
 * Never for money, sends, calls, anything TIER2+ (only `tap` is produced), a password field, or the secure step (the
 * engine asks only after the Secure Input boundary). Off by default: `agent.plan.localContinue`.
 */
object LocalContinuation {
    const val TAG = "local-continue"

    fun enabled(): Boolean = AppConfig.bool("agent.plan.localContinue", false)
    fun maxPerTask(): Int = AppConfig.int("agent.plan.localContinue.max", 4).coerceIn(1, 8)

    private val VERB = Regex("(?i)^\\s*(?:open|tap|select|choose|click|press|pick|go to)\\s+(?:the\\s+)?(.+?)\\s*$")
    private val TAIL = Regex("(?i)\\s+(?:page|screen|option|menu|button|tab|section|row|item|setting|settings page)$")
    // A subgoal that does more than tap one thing is the planner's job.
    private val COMPOUND = Regex("(?i)\\b(?:and|then|after|before|type|enter|write|search|send|call|pay|read|say|tell|toggle|turn|set)\\b|[,;]")

    /** The label a subgoal asks to tap, or null when it isn't a plain single tap. */
    fun targetLabel(subgoal: String): String? {
        val m = VERB.matchEntire(subgoal.trim().trimEnd('.')) ?: return null
        val raw = m.groupValues[1].trim().trim('\'', '"', '‘', '’', '“', '”')
        if (COMPOUND.containsMatchIn(raw)) return null
        val label = TAIL.replace(raw, "").trim()
        return label.takeIf { it.length >= 3 }
    }

    /** The decision to run locally, or null (ask the planner). Pure: the plan state and the fresh [screen] decide. */
    fun next(plan: PlanTracker, screen: UiSnapshot): AgentDecision.CallTool? {
        if (!plan.justSettled) return null
        val sub = plan.active ?: return null
        if (!sub.checkable) return null
        val label = targetLabel(sub.text) ?: return null
        if (ActionChain.consequentialLabel(label) || ActionChain.consequentialLabel(sub.text)) return null
        val cands = screen.elements.filter {
            it.source == ElementSource.TREE && it.enabled && it.clickable && !it.editable && !it.password && !it.label.isNullOrBlank()
        }
        fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
        val want = norm(label)
        val exact = cands.filter { norm(it.label!!) == want }
        val hit = when {
            exact.size == 1 -> exact[0]
            exact.isNotEmpty() -> return null   // ambiguous: the planner picks
            else -> cands.filter { TextDistance.similarity(norm(it.label!!), want) >= 0.9 }.singleOrNull() ?: return null
        }
        return AgentDecision.CallTool("tap", JSONObject().put("label", hit.label!!), TAG, sub.check)
    }
}
