package com.tekyerema.tools

import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * D-02 — tool search. Each step only carries the tools the request plainly needs; when none of them fits, the model
 * describes the capability it wants and the engine loads the best matches for the next step. Intercepted by the
 * engine (like cannot_do) — [execute] is never reached in the agent loop.
 */
object FindTool : Tool {
    override val name = "find_tool"
    override val description = "Load more tools. If none of the listed tools fits the step, describe the capability you need " +
        "(e.g. 'send an SMS', 'alarm', 'mobile money', 'calendar event') and the matching tools become available next step."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = listOf(ToolParam("query", "string", "The capability you need, in a few words."))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult =
        ToolResult(true, "Tool search is handled by the engine.")
}
