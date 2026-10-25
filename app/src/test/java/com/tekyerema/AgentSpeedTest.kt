package com.tekyerema

import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.FallbackBrain
import com.tekyerema.agent.Hedge
import com.tekyerema.agent.OpenAiCompatBrain
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Overnight [A2] speed work: hedged model calls (N-15), per-call usage/cache telemetry (N-03). Evidence for each is the
 * A20e run of 2026-09-28 night (build/device_reports/20260928-233636): seven Groq calls in a row hung for their full
 * 35 s timeout while Gemini — asked only afterwards — answered in 4 s.
 */
class AgentSpeedTest {

    private fun ctx(goal: String = "x") = BrainContext(goal, JSONObject(), emptyList(), JSONArray(), JSONArray())

    // ---------------------------------------------------------------- Hedge.race

    @Test fun a_fast_first_attempt_never_starts_the_backup() = runBlocking {
        var backupRan = false
        val out = Hedge.race(listOf<suspend () -> String>({ delay(5); "first" }, { backupRan = true; "second" }), afterMs = 500) { true }
        assertEquals("first", out.winner); assertEquals(0, out.winnerIndex); assertEquals(1, out.started)
        assertFalse(backupRan)
    }

    @Test fun a_stalled_first_attempt_is_hedged_and_the_backup_wins() = runBlocking {
        var firstCancelled = false
        val t0 = System.currentTimeMillis()
        val out = Hedge.race(listOf<suspend () -> String>(
            { try { delay(5_000); "slow" } catch (e: kotlinx.coroutines.CancellationException) { firstCancelled = true; throw e } },
            { delay(10); "backup" }
        ), afterMs = 50) { true }
        val took = System.currentTimeMillis() - t0
        assertEquals("backup", out.winner); assertEquals(1, out.winnerIndex); assertEquals(2, out.started)
        assertTrue("the slow attempt must be cancelled", firstCancelled)
        assertTrue("took $took ms — the hedge must not wait out the slow attempt", took < 2_000)
    }

    @Test fun a_quick_failure_starts_the_next_attempt_at_once() = runBlocking {
        val t0 = System.currentTimeMillis()
        val out = Hedge.race(listOf<suspend () -> String>({ "FAIL" }, { "ok" }), afterMs = 5_000) { it != "FAIL" }
        assertEquals("ok", out.winner)
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
    }

    @Test fun when_every_attempt_fails_all_results_come_back_in_order() = runBlocking {
        val out = Hedge.race(listOf<suspend () -> String>({ "F1" }, { "F2" }), afterMs = 10) { false }
        assertNull(out.winner); assertEquals(-1, out.winnerIndex)
        assertEquals(listOf("F1", "F2"), out.results)
    }

    @Test fun an_unusable_early_answer_still_lets_a_later_usable_one_win() = runBlocking {
        // the backup fails fast while the first is still running: the first's late success is still taken
        val out = Hedge.race(listOf<suspend () -> String>({ delay(120); "late ok" }, { "FAIL" }), afterMs = 20) { it != "FAIL" }
        assertEquals("late ok", out.winner); assertEquals(0, out.winnerIndex)
    }

    @Test fun within_gives_up_on_time() = runBlocking {
        assertNull(Hedge.within(30) { delay(2_000); "never" })
        assertEquals("ok", Hedge.within(1_000) { "ok" })
    }

    // ---------------------------------------------------------------- FallbackBrain hedging

    private class SlowBrain(private val ms: Long, private val d: AgentDecision) : AgentBrain {
        var calls = 0; var cancelled = false
        override suspend fun decide(ctx: BrainContext): AgentDecision {
            calls++
            try { delay(ms) } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            return d
        }
    }

    // Merge note (overnight, 2026-09-29): the fallback chain kept the hybrid branch's design — a watchdog that checks the
    // link after [watchdogMs] of silence and gives up at once when the internet isn't answering, plus cooling of a provider
    // that just timed out — instead of a parallel race (a stall on a slow-but-working link is hedged inside the Groq brain).

    @Test fun a_stalled_link_no_longer_holds_the_step_for_its_whole_timeout() = runBlocking {
        val groq = SlowBrain(5_000, AgentDecision.Done("groq"))
        val gemini = SlowBrain(10, AgentDecision.Done("gemini"))
        val t0 = System.currentTimeMillis()
        val d = FallbackBrain(listOf(groq, gemini), networkRetryDelayMs = 1, linkCheck = { false }, watchdogMs = 50,
            health = com.tekyerema.agent.ProviderCooling { 90_000L }).decide(ctx())
        assertTrue("got $d", d is AgentDecision.CannotDo)
        assertEquals(FallbackBrain.NETWORK_LOST, (d as AgentDecision.CannotDo).reasonClass)
        assertTrue("the stalled call is abandoned", groq.cancelled)
        assertEquals("no point asking the next provider on a dead link", 0, gemini.calls)
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
    }

    @Test fun a_healthy_first_brain_never_touches_the_backup_quota() = runBlocking {
        val groq = SlowBrain(5, AgentDecision.Done("groq"))
        val gemini = SlowBrain(5, AgentDecision.Done("gemini"))
        val d = FallbackBrain(listOf(groq, gemini), networkRetryDelayMs = 1, linkCheck = { null },
            health = com.tekyerema.agent.ProviderCooling { 90_000L }).decide(ctx())
        assertEquals(AgentDecision.Done("groq"), d)
        assertEquals(0, gemini.calls)   // Gemini's free tier is 20 requests a day
    }

    @Test fun the_chain_still_reports_the_informative_failure() = runBlocking {
        val groq = SlowBrain(60, AgentDecision.Fail("LLM request failed: timeout"))
        val gemini = SlowBrain(5, AgentDecision.Fail("Gemini HTTP 429: quota"))
        val d = FallbackBrain(listOf(groq, gemini), networkRetryDelayMs = 1, retryWithinMs = 10_000, linkCheck = { null },
            health = com.tekyerema.agent.ProviderCooling { 90_000L }).decide(ctx())
        // groq's timeout (a network error) is the real problem, not Gemini's quota → the honest "internet" message.
        assertTrue("got $d", d is AgentDecision.CannotDo)
        assertEquals(FallbackBrain.NETWORK_LOST, (d as AgentDecision.CannotDo).reasonClass)
    }

    @Test fun a_throwing_brain_is_a_fail_not_a_crash() = runBlocking {
        val boom = object : AgentBrain { override suspend fun decide(ctx: BrainContext): AgentDecision = throw IllegalStateException("boom") }
        val d = try {
            FallbackBrain(listOf(boom, SlowBrain(1, AgentDecision.Done("ok"))), networkRetryDelayMs = 1, linkCheck = { null },
                health = com.tekyerema.agent.ProviderCooling { 90_000L }).decide(ctx())
        } catch (e: IllegalStateException) { AgentDecision.Fail("threw: ${e.message}") }
        assertEquals(AgentDecision.Done("ok"), d)
    }

    // ---------------------------------------------------------------- I-03 web-first

    private fun single(q: String) = com.tekyerema.agent.RetrievalNeed.classify(q)

    @Test fun a_self_contained_fresh_fact_goes_straight_to_the_web() {
        val w = com.tekyerema.agent.WebFirst
        for (q in listOf("what's the weather in Accra today", "what is the cedi to dollar rate today",
                "who won the Black Stars match yesterday", "is it going to rain in Kumasi tomorrow")) {
            assertEquals(q, com.tekyerema.agent.RetrievalNeed.Need.SINGLE, single(q))
            assertTrue(q, w.eligible(q, single(q)))
        }
    }

    @Test fun web_first_stays_out_of_what_the_planner_must_resolve() {
        val w = com.tekyerema.agent.WebFirst
        // needs the conversation ("it", "there"), several lookups, no lookup, too short, or Twi
        assertFalse(w.eligible("what's the weather there today", single("what's the weather there today")))
        assertFalse(w.eligible("how much does it cost today", single("how much does it cost today")))
        assertFalse(w.eligible("compare MTN and Telecel data prices today", single("compare MTN and Telecel data prices today")))
        assertFalse(w.eligible("what is the capital of Ghana", single("what is the capital of Ghana")))
        assertFalse(w.eligible("weather today", com.tekyerema.agent.RetrievalNeed.Need.SINGLE))
        assertFalse(w.eligible("ewiem tebea bɛyɛ dɛn nnɛ", com.tekyerema.agent.RetrievalNeed.Need.SINGLE))
    }

    @Test fun web_first_query_is_the_question_itself() {
        val w = com.tekyerema.agent.WebFirst
        assertEquals("what's the weather in Accra today", w.query("what's the weather in Accra today?"))
        assertEquals("the cedi rate today", w.query("Can you please tell me the cedi rate today?"))
        assertNull(w.spoken("  "))
        assertEquals("Sunny, 31 °C.", w.spoken(" Sunny, 31 °C. "))
    }

    // ---------------------------------------------------------------- G-10 launch settle

    @Test fun a_just_opened_app_is_polled_while_it_is_still_drawing() {
        val p = com.tekyerema.agent.Progress
        assertTrue(p.keepWaitingForLaunch(1_300, actionable = 0, grew = false, maxMs = 3_500, maxSparse = 5))   // Chrome: empty
        assertTrue(p.keepWaitingForLaunch(1_700, actionable = 3, grew = true, maxMs = 3_500, maxSparse = 5))    // filling in
        assertFalse(p.keepWaitingForLaunch(1_700, actionable = 3, grew = false, maxMs = 3_500, maxSparse = 5))  // small but settled
        assertFalse(p.keepWaitingForLaunch(1_700, actionable = 30, grew = true, maxMs = 3_500, maxSparse = 5))  // loaded
        assertFalse(p.keepWaitingForLaunch(3_600, actionable = 0, grew = false, maxMs = 3_500, maxSparse = 5))  // bounded
    }

    // ---------------------------------------------------------------- G-11 tap_sequence

    private fun steps(args: JSONObject, expects: String? = null) =
        (com.tekyerema.agent.ActionChain.expand(args, expects) as com.tekyerema.agent.ActionChain.Expansion.Steps).steps

    @Test fun a_keypad_entry_becomes_checked_taps_by_label() {
        val s = steps(JSONObject().put("labels", "1|2| × |1|3|="), "element_present: 156")
        assertEquals(listOf("1", "2", "×", "1", "3", "="), s.map { it.args.getString("label") })
        assertTrue(s.all { it.name == "tap" && !it.args.has("element_id") })   // labels, never per-snapshot ids
        assertEquals("element_present: 2", s[0].expects)                      // each tap claims the next key is there
        assertEquals("element_present: 156", s.last().expects)                // the model's own claim on the last step
        assertEquals("chain:1/6", s[0].rationale); assertEquals("chain:6/6", s.last().rationale)
    }

    @Test fun a_search_button_then_its_text_is_tap_then_type() {
        val s = steps(JSONObject().put("labels", "Search").put("then_type", "Kwame Nkrumah").put("submit", true), "element_present: Kwame Nkrumah")
        assertEquals(listOf("tap", "type"), s.map { it.name })
        assertNull("the tap before typing makes no claim — the field it opens is found at run time", s[0].expects)
        assertEquals("Kwame Nkrumah", s[1].args.getString("text")); assertTrue(s[1].args.getBoolean("submit"))
        assertFalse(s[1].args.has("element_id"))
        assertEquals("element_present: Kwame Nkrumah", s[1].expects)
    }

    @Test fun a_sequence_without_labels_or_too_long_is_rejected() {
        val c = com.tekyerema.agent.ActionChain
        assertTrue(c.expand(JSONObject().put("labels", " | "), null) is com.tekyerema.agent.ActionChain.Expansion.Rejected)
        assertTrue(c.expand(JSONObject().put("labels", (1..20).joinToString("|")), null) is com.tekyerema.agent.ActionChain.Expansion.Rejected)
    }

    // ---- P3-B3: local continuation (agent.plan.localContinue, default off)

    private fun btn(id: Int, label: String, clickable: Boolean = true, editable: Boolean = false) =
        com.tekyerema.accessibility.UiElement(id, "Button", label, com.tekyerema.accessibility.Box(0, 0, 10, 10), clickable = clickable, editable = editable)

    private fun settledPlan(raw: String, firstCheckScreen: com.tekyerema.accessibility.UiSnapshot): com.tekyerema.agent.plan.PlanTracker {
        val t = com.tekyerema.agent.plan.PlanTracker()
        assertNotNull(t.offer(raw))
        t.onStep(com.tekyerema.agent.plan.PlanTracker.StepEvidence(verified = true, read = false, failed = false, before = null, after = firstCheckScreen))
        return t
    }

    private val twoSteps = "open Display => element_present: Font size; open Font size => element_present: Preview"

    @Test fun local_continuation_is_off_by_default() {
        com.tekyerema.core.config.AppConfig.loadFrom(emptyList())
        assertFalse(com.tekyerema.agent.plan.LocalContinuation.enabled())
    }

    @Test fun the_next_plain_tap_on_a_label_that_is_on_screen_is_taken_locally() {
        val screen = snap(btn(1, "Font size"), btn(2, "Brightness"))
        val t = settledPlan(twoSteps, screen)
        assertTrue(t.justSettled)
        val d = com.tekyerema.agent.plan.LocalContinuation.next(t, screen)!!
        assertEquals("tap", d.name); assertEquals("Font size", d.args.getString("label")); assertEquals("element_present: Preview", d.expects)
    }

    @Test fun it_asks_the_planner_when_the_label_is_missing_ambiguous_or_the_step_did_not_settle() {
        val lc = com.tekyerema.agent.plan.LocalContinuation
        val here = snap(btn(1, "Font size"))
        assertNull(lc.next(settledPlan(twoSteps, here), snap(btn(2, "Brightness"))))                       // not on screen
        assertNull(lc.next(settledPlan(twoSteps, here), snap(btn(1, "Font size"), btn(2, "Font size"))))     // ambiguous
        assertNull(lc.next(settledPlan(twoSteps, here), snap(btn(1, "Font size", clickable = false))))       // not tappable
        val t = com.tekyerema.agent.plan.PlanTracker(); t.offer(twoSteps)
        assertNull(lc.next(t, snap(btn(1, "Font size"))))                                                    // nothing just settled
    }

    @Test fun it_never_continues_money_sends_calls_or_compound_subgoals() {
        val lc = com.tekyerema.agent.plan.LocalContinuation
        val here = snap(btn(1, "Contacts"))
        for (next in listOf("tap Send", "tap Call", "tap Pay now", "open Transfer money", "open Contacts and call Ama", "type hello"))
            assertNull(next, lc.next(settledPlan("open Contacts => element_present: Contacts; $next => element_present: Done", here),
                snap(btn(1, "Send"), btn(2, "Call"), btn(3, "Pay now"), btn(4, "Transfer money"), btn(5, "Contacts"), btn(6, "hello"))))
        assertEquals("Contacts", lc.targetLabel("open the Contacts page"))
        assertNull(lc.targetLabel("search for Ama"))
    }

    @Test fun it_never_taps_a_field_or_a_password() {
        val lc = com.tekyerema.agent.plan.LocalContinuation
        val here = snap(btn(1, "Font size"))
        assertNull(lc.next(settledPlan(twoSteps, here), snap(btn(1, "Font size", editable = true))))
    }

    // ---- P3-B1: a chain may open an app first and press Enter last (agent.chain.verbs)

    private fun withVerbs(v: String?) = com.tekyerema.core.config.AppConfig.loadFrom(emptyList(),
        v?.let { """{"agent":{"chain":{"verbs":"$it"}}}""" })

    private fun expansion(args: JSONObject, expects: String? = null) = com.tekyerema.agent.ActionChain.expand(args, expects)

    @org.junit.After fun resetChainConfig() = com.tekyerema.core.config.AppConfig.loadFrom(emptyList())

    @Test fun open_app_and_enter_expand_in_order_when_the_verbs_allow_them() {
        withVerbs("tap,type,open_app,enter")
        val s = (expansion(JSONObject().put("open_app", "Chrome").put("labels", "Search or type URL").put("then_type", "cats").put("then_enter", true),
            "element_present: cats") as com.tekyerema.agent.ActionChain.Expansion.Steps).steps
        assertEquals(listOf("open_app", "tap", "type"), s.map { it.name })
        assertEquals("Chrome", s[0].args.getString("app"))
        assertEquals("element_present: Search or type URL", s[0].expects)
        assertTrue(s[2].args.getBoolean("submit"))
        assertEquals(listOf("chain:1/3", "chain:2/3", "chain:3/3"), s.map { it.rationale })
    }

    @Test fun open_app_and_enter_are_opt_in_not_default() {
        withVerbs(null)
        assertEquals(setOf("tap", "type"), com.tekyerema.agent.ActionChain.verbs())
        assertFalse(com.tekyerema.agent.BrainPrompts.SYSTEM.contains("then_enter"))   // the prompt does not offer what the default refuses
    }

    @Test fun open_app_and_enter_are_refused_unless_the_verbs_list_them() {
        withVerbs("tap,type")
        assertTrue(expansion(JSONObject().put("open_app", "Chrome").put("labels", "x")) is com.tekyerema.agent.ActionChain.Expansion.Rejected)
        assertTrue(expansion(JSONObject().put("labels", "x").put("then_type", "y").put("then_enter", true)) is com.tekyerema.agent.ActionChain.Expansion.Rejected)
        assertTrue(expansion(JSONObject().put("labels", "x")) is com.tekyerema.agent.ActionChain.Expansion.Steps)
    }

    @Test fun money_sends_and_calls_are_never_chained() {
        withVerbs("tap,type,open_app,enter")
        for (l in listOf("Send", "Pay now", "Call", "Transfer money", "Confirm"))
            assertTrue("$l", expansion(JSONObject().put("labels", "Contacts|$l")) is com.tekyerema.agent.ActionChain.Expansion.Rejected)
        assertTrue(expansion(JSONObject().put("open_app", "MTN MoMo").put("labels", "Home")) is com.tekyerema.agent.ActionChain.Expansion.Rejected)
        assertTrue(expansion(JSONObject().put("labels", "Sound and vibration|Ringtone")) is com.tekyerema.agent.ActionChain.Expansion.Steps)
    }

    @Test fun the_open_app_step_counts_against_max_steps() {
        withVerbs("tap,type,open_app,enter")
        val eight = (1..8).joinToString("|") { "k$it" }
        assertTrue(expansion(JSONObject().put("labels", eight)) is com.tekyerema.agent.ActionChain.Expansion.Steps)
        assertTrue(expansion(JSONObject().put("open_app", "Calculator").put("labels", eight)) is com.tekyerema.agent.ActionChain.Expansion.Rejected)
    }

    @Test fun only_tap_type_and_open_app_ever_come_out_of_a_chain() {
        withVerbs("tap,type,open_app,enter")
        val s = (expansion(JSONObject().put("open_app", "Settings").put("labels", "Display").put("then_type", "a").put("then_enter", true))
            as com.tekyerema.agent.ActionChain.Expansion.Steps).steps
        assertTrue(s.all { it.name in setOf("open_app", "tap", "type") })   // never a TIER2+ tool
        val tools = com.tekyerema.tools.defaultTools().associateBy { it.name }
        assertTrue(s.all { tools.getValue(it.name).riskTier == com.tekyerema.policy.RiskTier.TIER1 || tools.getValue(it.name).riskTier == com.tekyerema.policy.RiskTier.TIER0 })
    }

    @Test fun a_chain_goes_on_only_after_a_step_that_did_something() {
        val c = com.tekyerema.agent.ActionChain
        assertTrue(c.stepPassed(success = true, verified = true, changed = false))
        assertTrue(c.stepPassed(success = true, verified = false, changed = true))
        assertFalse(c.stepPassed(success = true, verified = false, changed = false))   // a dead tap stops it
        assertFalse(c.stepPassed(success = false, verified = false, changed = true))
        val rest = steps(JSONObject().put("labels", "a|b|c"))
        assertTrue(c.stoppedNote(1, 3, rest.drop(1), "x").contains("NOT done: b, c"))
    }

    private fun el(id: Int, editable: Boolean = false, focused: Boolean = false, password: Boolean = false) =
        com.tekyerema.accessibility.UiElement(id, "EditText", "f$id", com.tekyerema.accessibility.Box(0, 0, 10, 10),
            editable = editable, focused = focused, password = password)

    private fun snap(vararg e: com.tekyerema.accessibility.UiElement) = com.tekyerema.accessibility.UiSnapshot("app", "App", e.toList(),
        0L, com.tekyerema.accessibility.PerceptionRoute("tree", "test"))

    @Test fun the_typed_text_goes_into_the_focused_or_only_field_never_a_password() {
        val c = com.tekyerema.agent.ActionChain
        assertEquals(2, c.typeTarget(snap(el(1, editable = true), el(2, editable = true, focused = true))))
        assertEquals(1, c.typeTarget(snap(el(0), el(1, editable = true))))
        assertNull(c.typeTarget(snap(el(1, editable = true), el(2, editable = true))))   // ambiguous: the planner decides
        assertNull(c.typeTarget(snap(el(1, editable = true, focused = true, password = true))))
    }

    @Test fun tap_sequence_is_offered_every_step() {
        val all = com.tekyerema.tools.ToolRegistry(com.tekyerema.tools.defaultTools()) { true }.all.map { it.name }
        assertTrue("tap_sequence" in all)
        assertTrue("tap_sequence" in com.tekyerema.agent.ToolSelector.select("open chrome", emptySet(), emptySet(), all))
        assertTrue(com.tekyerema.agent.BrainPrompts.SYSTEM.contains("tap_sequence"))
    }

    // ---------------------------------------------------------------- N-03 cache-stable tool order

    @Test fun a_tool_loaded_mid_task_goes_on_the_end_so_the_cached_prefix_survives() {
        val all = com.tekyerema.tools.ToolRegistry(com.tekyerema.tools.defaultTools()) { true }.all.map { it.name }
        val sel = com.tekyerema.agent.ToolSelector
        val before = sel.select("open chrome", emptySet(), emptySet(), all)
        val after = sel.select("open chrome", setOf("tap"), linkedSetOf("set_alarm", "pinch"), all)
        assertEquals(before, after.take(before.size))           // the step-1 list is an exact prefix of the step-2 list
        assertEquals(listOf("set_alarm", "pinch"), after.drop(before.size))
        val reg = com.tekyerema.tools.ToolRegistry(com.tekyerema.tools.defaultTools()) { true }
        val arr = sel.inOrder(reg.openAiTools(after), after) { it.optJSONObject("function")?.optString("name") }
        assertEquals(after, (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function").getString("name") })
    }

    // ---------------------------------------------------------------- OpenAiCompatBrain helpers

    @Test fun the_backup_request_uses_the_next_free_key() {
        assertEquals(2, OpenAiCompatBrain.hedgeKeyIndex(4, 1) { it != 1 })
        assertEquals(0, OpenAiCompatBrain.hedgeKeyIndex(4, 3) { it == 0 })
        assertEquals(3, OpenAiCompatBrain.hedgeKeyIndex(4, 1) { it == 3 })
        // every other key cooling: the same key on a fresh connection
        assertEquals(1, OpenAiCompatBrain.hedgeKeyIndex(4, 1) { false })
        assertEquals(0, OpenAiCompatBrain.hedgeKeyIndex(1, 0) { true })
    }

    @Test fun high_effort_gets_more_room_before_hedging() {
        val base = OpenAiCompatBrain.hedgeAfterMs(null)
        assertTrue(base in 3_000L..15_000L)
        assertTrue(OpenAiCompatBrain.hedgeAfterMs("high") > base)
        assertEquals(base, OpenAiCompatBrain.hedgeAfterMs("low"))
    }

    @Test fun usage_line_shows_the_cache_share() {
        val u = com.tekyerema.core.telemetry.LlmUsage.fromOpenAi(
            """{"usage":{"prompt_tokens":4000,"completion_tokens":150,"prompt_tokens_details":{"cached_tokens":3000}}}""")
        val line = OpenAiCompatBrain.usageLine("openai/gpt-oss-120b", 1, 4, 200, 1834, u, "")
        assertEquals("groq/openai/gpt-oss-120b key#2/4 http=200 ms=1834 prompt=4000 cached=3000(75%) completion=150 effort=default", line)
        assertTrue(OpenAiCompatBrain.usageLine("m", 0, 1, 429, 12, null, "high").endsWith("http=429 ms=12 effort=high"))
    }
}
