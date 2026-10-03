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
        // The FULL compiled schema (every tool available), independent of flags/API gates.
        val tools = ToolRegistry(defaultTools()) { true }.openAiTools().toString().length
        assertTrue("schema measured empty — the registry hid every tool", tools > 1_000)
        val system = BrainPrompts.SYSTEM.length
        println("[PROMPT-BUDGET] tools=$tools chars (~${tools / 4} tok), system=$system chars (~${system / 4} tok), " +
            "fixed total ~${(tools + system) / 4} tok/step")
        // D-02: the model never receives the whole schema any more (per-step subsets, below); this caps the compiled set.
        // 27.5k: +4 app-task accelerators (install_app, take_photo, ask_app, google_sign_in — [D] 2026-09-28), which
        // load only with the "apps" tool-search group; the per-step subset guard below is unchanged.
        // 28k (overnight 2026-09-29): + tap_sequence (several predictable taps per LLM call) and describe_screen (online
        // vision on one screenshot). The model only ever gets a per-step subset (guarded below at 9k).
        // 28.3k (Workstream B, 2026-09-29): + an optional `plan` argument ({"type":"string"}, ~25 chars) on the ~14 acting
        // tools, so a multi-step goal's plan rides in the first action's call instead of costing a planning call of its own.
        // Measured: compiled set 27,799 → 28,099 chars; the 'open chrome' subset 8,182 → 8,382 (guard below, 9k, unchanged).
        // Workstream D (2026-09-29): + read_shared (text shared / selected into Tɛkyerɛma), which loads only with the
        // "shared" tool-search group; the per-step subset guard below is unchanged.
        // Integration A+E+B+D (2026-09-29): measured 28,577 (B's plan arg + E's open_url + D's read_shared) — cap = that + ~120.
        // Audit fixes (2026-10-01): measured 29,109 — + share_screenshot (the screen as a picture handed to a chat app; loads
        // only with the "comms" group), the Mobile Money balance kind / navigator intent, and reply-to-last-sender on
        // send_sms. The 'open chrome' per-step subset moved 8,382 → 8,409 (guard below, 9k, unchanged). Cap = that + ~140.
        // QA run (2026-10-03): measured 29,441 — + cancel_alarm (the Clock's own DISMISS_ALARM request; "device" group, so it
        // loads per step only with alarm words). Cap = that + ~140.
        assertTrue("compiled tool schema grew to $tools chars", tools <= 29_580)
        assertTrue("system prompt grew to $system chars", system <= 5_600)
    }

    /** D-02: a typical request carries a fraction of the schema; money/comms tools load only when asked for. */
    @Test fun per_step_tool_subset_is_small_and_relevant() {
        val reg = ToolRegistry(defaultTools()) { true }
        val all = reg.all.map { it.name }
        fun sel(goal: String) = com.tekyerema.agent.ToolSelector.select(goal, emptySet(), emptySet(), all)
        val open = sel("open chrome")
        val openChars = reg.openAiTools(open).toString().length
        val fullChars = reg.openAiTools().toString().length
        println("[PROMPT-BUDGET] subset 'open chrome' = ${open.size} tools, $openChars chars (full $fullChars)")
        assertTrue("per-step core subset grew to $openChars chars", openChars <= 9_000)
        assertTrue("subset should be well under the full schema", openChars < fullChars * 0.65)
        assertTrue("momo_send" !in open && "make_call" !in open)
        assertTrue("find_tool" in open && "tap" in open)
        // The prompt says "not sure of a fact? web_search it" — so it is there even when no keyword names the web.
        assertTrue("web_search" in sel("I meant mimics, mimics export max"))
        assertTrue("momo_send" in sel("send 5 cedis to 0241234567 on momo"))
        assertTrue("make_call" in sel("call Ama"))
        assertTrue("set_alarm" in sel("wake me at 6"))
        // Used and discovered tools stay loaded.
        assertTrue("set_timer" in com.tekyerema.agent.ToolSelector.select("open chrome", setOf("set_timer"), emptySet(), all))
        assertTrue("pinch" in com.tekyerema.agent.ToolSelector.select("open chrome", emptySet(), setOf("pinch"), all))
        // find_tool search finds by description words.
        val hits = com.tekyerema.agent.ToolSelector.search("alarm clock", defaultTools())
        assertTrue(hits.any { it.name == "set_alarm" })
    }
}
