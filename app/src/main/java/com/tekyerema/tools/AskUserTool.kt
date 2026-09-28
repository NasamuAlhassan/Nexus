package com.tekyerema.tools

import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * C-13 — ask the user a question and wait for the answer WITHOUT ending the task ("Which Ama — Ama Mensah or Ama
 * Boateng?", "How much should I send?"). Intercepted by the engine: it speaks the question, listens, and hands the
 * answer back into the same run. [execute] is never reached in the agent loop.
 */
object AskUserTool : Tool {
    override val name = "ask_user"
    override val description = "Ask the user for information only they have (which contact, how much, which option) and wait for the answer."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = listOf(
        ToolParam("question", "string", "One short question."),
        ToolParam("options", "string", "Optional choices to offer, comma-separated.", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult =
        ToolResult(true, "Questions are asked by the engine.")
}
