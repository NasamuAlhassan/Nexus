package com.tekyerema.agent

import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolParam
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.boolOrDefault
import com.tekyerema.tools.str
import org.json.JSONObject

/**
 * G-11 — several actions from ONE planning call. `tap_sequence` lets the model do the next few taps it can already
 * predict — a keypad entry ("1|2|×|1|3|="), a menu path ("Sound and vibration|Ringtone"), a search button then its
 * text — without a cloud round-trip between them. The engine EXPANDS it into ordinary `tap` / `type` steps and runs
 * each through the unchanged pipeline: target resolution, the policy gate and any confirmation, the stale-screen
 * check, execution and verification, the Secure Input boundary at the top of every step. The chain stops at the first
 * step that is refused, fails, or changes nothing, and the planner is told exactly how far it got.
 *
 * Why: every step costs a full cloud call (1–2 s on a good network, ~40 s on tonight's stalled one, ~4.5k tokens of an
 * 8k-per-minute budget). The calculator sum needs six key presses — six calls; "open Chrome and search" spent separate
 * calls on tapping the box and typing. Research: AppAgentX shortcuts cut steps 9.1→5.7 and tokens −47% (arXiv
 * 2503.02268); Mobile-Agent-E's `Tap_Type_and_Enter` shortcut carries a precondition and is checked afterwards (arXiv
 * 2501.11733); AutoDroid-V2 multi-step scripts cut LLM latency 5.7–13.4× (arXiv 2412.18116). Labels, never element ids:
 * ids are numbered per snapshot and don't survive the screen changing under the chain.
 */
object TapSequenceTool : Tool {
    override val name = "tap_sequence"
    // Kept short: the compiled schema is budgeted (PromptBudgetTest) and this tool is offered on every step.
    override val description = "Several taps in order in ONE step when you can predict them: keypad '1|2|×|1|3|=', menu path " +
        "'Sound and vibration|Ringtone', or labels='Search' then_type='…'. Each is checked; stops at the first that fails."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = listOf(
        ToolParam("labels", "string", "Labels to tap in order, '|'-separated."),
        ToolParam("then_type", "string", "Text to type into the field open after the taps.", required = false),
        ToolParam("submit", "boolean", "Also press Enter/Search.", required = false),
        ToolParam("expects", "string", "What the LAST step shows.", required = false)
    )

    /** Never executed: the engine expands it into its steps first. */
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult =
        ToolResult(false, "tap_sequence is expanded by the engine into separate steps.", recoverable = true)
}

object ActionChain {
    /** The rationale prefix that marks a decision as a chain step ("chain:2/5"). */
    const val TAG = "chain:"

    sealed class Expansion {
        data class Steps(val steps: List<AgentDecision.CallTool>) : Expansion()
        data class Rejected(val why: String) : Expansion()
    }

    fun enabled(): Boolean = com.tekyerema.core.config.FeatureFlags.isEnabled("G-11", true) && AppConfig.bool("agent.chain.enabled", true)

    private fun maxSteps() = AppConfig.int("agent.chain.maxSteps", 8).coerceIn(2, 12)

    /**
     * Turn a tap_sequence call into `tap` steps (+ one `type`). Each tap but the last claims the NEXT label is on screen
     * (a menu path's next row — its verification also waits for that screen to load); the last step carries the
     * model's own claim. The tap right before a `type` makes no claim: the field it opens is found at run time.
     */
    fun expand(args: JSONObject, expects: String?): Expansion {
        val labels = args.str("labels").orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val text = args.str("then_type")?.takeIf { it.isNotEmpty() }
        val submit = args.boolOrDefault("submit", false)
        if (labels.isEmpty() && text == null) return Expansion.Rejected("give the labels to tap, separated by '|'")
        val total = labels.size + (if (text != null) 1 else 0)
        if (total > maxSteps()) return Expansion.Rejected("at most ${maxSteps()} steps in one sequence — do the rest next step")
        val out = ArrayList<AgentDecision.CallTool>()
        labels.forEachIndexed { i, label ->
            val last = i == labels.lastIndex
            val claim = when {
                !last -> "element_present: ${labels[i + 1]}"
                text != null -> null
                else -> expects
            }
            out.add(AgentDecision.CallTool("tap", JSONObject().put("label", label), "$TAG${out.size + 1}/$total", claim))
        }
        if (text != null) {
            val a = JSONObject().put("text", text)
            if (submit) a.put("submit", true)
            out.add(AgentDecision.CallTool("type", a, "$TAG${out.size + 1}/$total", expects))
        }
        return Expansion.Steps(out)
    }

    /**
     * The text field a chained `type` goes into (its target is unknown when the chain is planned): the focused editable,
     * else the only editable on screen; null when it's ambiguous or absent (the chain then stops and the planner looks).
     * Password fields are never picked here — and the policy gate would block typing into one anyway.
     * [focusedOnly]: only the focused field (after a vision tap aimed at a field, the field it focused — B, 2026-09-29).
     */
    fun typeTarget(screen: UiSnapshot, focusedOnly: Boolean = false): Int? {
        val fields = screen.elements.filter { it.editable && it.source == ElementSource.TREE && !it.password }
        return (fields.firstOrNull { it.focused } ?: if (focusedOnly) null else fields.singleOrNull())?.id
    }

    /**
     * May the chain go on after a step? Only if it ran and did something: verified, or at least changed the screen.
     * A refused, blocked, failed or dead step stops it.
     */
    fun stepPassed(success: Boolean, verified: Boolean, changed: Boolean): Boolean = success && (verified || changed)

    /** The planner's note when a chain stops early — what ran, and what did NOT. */
    fun stoppedNote(done: Int, total: Int, remaining: List<AgentDecision.CallTool>, why: String): String {
        val rest = remaining.joinToString(", ") { d -> d.args.optString("label").ifBlank { "type '" + d.args.optString("text").take(30) + "'" } }
        return "tap_sequence stopped after step $done of $total ($why) — NOT done: $rest. Look at the screen and decide the next step."
    }
}
