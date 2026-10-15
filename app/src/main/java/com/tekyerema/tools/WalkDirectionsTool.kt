package com.tekyerema.tools

import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * Walking directions by Tɛkyerɛma itself (live/nav/WalkGuide): finds the place ("the nearest mall", "Accra Mall") and guides
 * from the phone's location — the next turn and how far in the island, each turn said aloud. No map app is opened (it hands
 * over to Google Maps only when the free map services can't be reached). Reversible → TIER1.
 */
object WalkDirectionsTool : Tool {
    override val name = "walk_directions"
    override val description = "Walking directions, guided live by Tɛkyerɛma (never open a maps app)."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("destination", "string", "A place or kind ('nearest pharmacy')."),
        ToolParam("action", "string", "Default start.", required = false, enumValues = listOf("start", "stop", "repeat", "how_far"))
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val g = com.tekyerema.live.nav.WalkGuide
        return when (args.str("action")?.lowercase()) {
            "stop" -> ToolResult(true, g.stop(), channel = "native")
            "repeat" -> ToolResult(true, g.repeat(), channel = "native")
            "how_far" -> ToolResult(true, g.howFar(), channel = "native")
            else -> {
                val dest = args.str("destination")?.trim().orEmpty()
                if (dest.isEmpty()) return ToolResult(false, "Where would you like to walk to?", recoverable = true)
                val said = g.start(ctx.appContext, dest)
                ToolResult(g.active || said.contains("Google Maps"), said, channel = "native")
            }
        }
    }
}
