package com.tekyerema.tools

import com.tekyerema.memory.MemKind
import com.tekyerema.memory.MemoryCore
import com.tekyerema.memory.UserMemory
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * The planner's door to the user's memory for wordings the memory phrase parser doesn't know ("the meeting with Richmond is in N
 * Block, room 3", "Richmond is only on Telegram"). A20e 8 Nov 2026: with no such tool the brain either made a calendar event or
 * said "I've noted that" and stored nothing, so the fact was gone at the next request. It saves the fact in the user's own words
 * (MemoryCore's secret/number guard applies) and sends nothing.
 */
object RememberTool : Tool {
    override val name = "remember"
    override val description = "Save a fact the user asks you to remember or keep in mind, in their own words ('the meeting with Richmond " +
        "is in N Block, room 3 at half past ten', 'Richmond is only on Telegram'). Saved on the phone; sends nothing, opens nothing. " +
        "Use this — not the calendar or an alarm — unless they asked for an event or a reminder."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER0
    override val params = listOf(
        ToolParam("fact", "string", "The fact, in the user's own words."),
        ToolParam("about", "string", "Who or what it is about, if anyone.", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val fact = args.optString("fact").trim().trimEnd('.', '!')
        if (fact.length < 4) return ToolResult(false, "What should I remember? Ask the user.", recoverable = false)
        val key = MemoryCore.noteKey(fact)
        if (!MemoryCore.check(MemKind.FACT, key, fact))
            return ToolResult(false, "I can't keep that — I don't store numbers, codes, amounts or passwords.", recoverable = false)
        UserMemory.update(ctx.appContext) { MemoryCore.upsert(it, MemKind.FACT, key, fact, true, System.currentTimeMillis()) }
        return ToolResult(true, "Noted: $fact.", channel = "native")
    }
}
