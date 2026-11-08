package com.tekyerema.tools

import com.tekyerema.agent.triggers.StandingOrderWords
import com.tekyerema.agent.triggers.StandingOrders
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * The planner's door to standing orders, for wordings the phrase parser doesn't know. It only REMEMBERS the order; the limits on what
 * an order may do later are in StandingOrderWords.build and StandingGrant, the same for both doors.
 */
object StandingOrderTool : Tool {
    private val APPS = listOf("Telegram", "WhatsApp", "Messenger", "Signal")

    override val name = "set_standing_order"
    override val description = "Remember an instruction to act LATER, every time something arrives: 'when Lydia sends a voice note, " +
        "forward it to my Saved Messages on Telegram'. Use this instead of acting now. It saves the order and sends nothing."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER0
    override val params = listOf(
        ToolParam("who", "string", "The person whose messages to wait for, as the user named them."),
        ToolParam("kind", "string", "What they must send.", enumValues = listOf("voice_note", "photo", "video", "document", "message")),
        ToolParam("destination", "string", "Where it goes, e.g. 'Saved Messages' or a person's name."),
        ToolParam("from_app", "string", "The app it must arrive in, if the user said.", required = false, enumValues = APPS),
        ToolParam("to_app", "string", "The app the destination is in, if the user said.", required = false, enumValues = APPS)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val order = StandingOrderWords.build(args.optString("who"), args.optString("kind"), args.str("from_app"), args.optString("destination"),
            args.str("to_app"), System.currentTimeMillis())
            ?: return ToolResult(false, "That needs one named person and a destination. Ask the user who, or call cannot_do.", recoverable = false)
        StandingOrders.add(ctx.appContext, order)
        return ToolResult(true, "Saved: " + StandingOrderWords.describe(order) + ". Nothing was sent now.", channel = "native")
    }
}
