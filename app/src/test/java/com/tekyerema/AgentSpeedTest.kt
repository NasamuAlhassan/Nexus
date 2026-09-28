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

    @Test fun a_stalled_groq_no_longer_holds_the_step_for_its_whole_timeout() = runBlocking {
        val groq = SlowBrain(5_000, AgentDecision.Done("groq"))
        val gemini = SlowBrain(10, AgentDecision.Done("gemini"))
        val t0 = System.currentTimeMillis()
        val d = FallbackBrain(listOf(groq, gemini), networkRetryDelayMs = 1, hedgeAfterMs = 50).decide(ctx())
        assertEquals(AgentDecision.Done("gemini"), d)
        assertTrue("the stalled brain is cancelled", groq.cancelled)
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
    }

    @Test fun a_healthy_first_brain_never_touches_the_backup_quota() = runBlocking {
        val groq = SlowBrain(5, AgentDecision.Done("groq"))
        val gemini = SlowBrain(5, AgentDecision.Done("gemini"))
        val d = FallbackBrain(listOf(groq, gemini), networkRetryDelayMs = 1, hedgeAfterMs = 1_000).decide(ctx())
        assertEquals(AgentDecision.Done("groq"), d)
        assertEquals(0, gemini.calls)   // Gemini's free tier is 20 requests a day
    }

    @Test fun a_hedged_chain_still_reports_the_informative_failure() = runBlocking {
        val groq = SlowBrain(60, AgentDecision.Fail("LLM request failed: timeout"))
        val gemini = SlowBrain(5, AgentDecision.Fail("Gemini HTTP 429: quota"))
        val d = FallbackBrain(listOf(groq, gemini), networkRetryDelayMs = 1, retryWithinMs = 10_000, hedgeAfterMs = 20).decide(ctx())
        // groq's timeout (a network error) is the real problem, not Gemini's quota; a quick chain gets its one retry,
        // and the retry fails the same way → the honest "internet" message.
        assertTrue("got $d", d is AgentDecision.CannotDo)
        assertEquals(FallbackBrain.NETWORK_LOST, (d as AgentDecision.CannotDo).reasonClass)
    }

    @Test fun a_throwing_brain_is_a_fail_not_a_crash() = runBlocking {
        val boom = object : AgentBrain { override suspend fun decide(ctx: BrainContext): AgentDecision = throw IllegalStateException("boom") }
        val d = FallbackBrain(listOf(boom, SlowBrain(1, AgentDecision.Done("ok"))), networkRetryDelayMs = 1, hedgeAfterMs = 1_000).decide(ctx())
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
