package com.tekyerema

import com.tekyerema.agent.BrainPrompts
import com.tekyerema.tools.ToolRegistry
import com.tekyerema.tools.defaultTools
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every agent step re-sends the system prompt + the full tool schema, and Groq's free tier is limited in
 * tokens PER MINUTE — so these fixed bytes directly cap how many steps per minute the agent can take
 * (on-device eval: 429s across all 4 keys mid-task). This guards against silent prompt bloat.
 * ~4 chars ≈ 1 token for this English/JSON mix.
 */
class PromptBudgetTest {
    @Test fun fixed_prompt_stays_within_budget() {
        val tools = ToolRegistry(defaultTools()).openAiTools().toString().length
        val system = BrainPrompts.SYSTEM.length
        println("[PROMPT-BUDGET] tools=$tools chars (~${tools / 4} tok), system=$system chars (~${system / 4} tok), " +
            "fixed total ~${(tools + system) / 4} tok/step")
        assertTrue("tool schema grew to $tools chars", tools <= 16_000)
        assertTrue("system prompt grew to $system chars", system <= 5_600)
    }
}
