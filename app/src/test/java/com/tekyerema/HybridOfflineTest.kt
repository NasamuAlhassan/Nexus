package com.tekyerema

import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AnswerCache
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.FallbackBrain
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.LocalModelBrain
import com.tekyerema.agent.OfflineQueueStore
import com.tekyerema.agent.ProviderCooling
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.core.LinkHealthTracker
import com.tekyerema.core.OfflineReason
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
 * Overnight hybrid pass (N-09 / N-10 / N-15 / H-11 / C-04): a stalled link is noticed and routed around, offline does
 * more (local multi-step plans, texts, "what can you do"), answers are cached, questions can wait for the internet.
 */
class HybridOfflineTest {

    private fun ctx(goal: String, history: List<String> = emptyList(), simple: Boolean = false, screen: JSONObject = JSONObject()) =
        BrainContext(goal, screen, history, JSONArray(), JSONArray(), simple = simple)

    private class FakeBrain(private val d: AgentDecision) : AgentBrain {
        var calls = 0
        override suspend fun decide(ctx: BrainContext): AgentDecision { calls++; return d }
    }

    private class Scripted(vararg ds: AgentDecision) : AgentBrain {
        private val q = ds.toList(); var calls = 0
        override suspend fun decide(ctx: BrainContext): AgentDecision = q[minOf(calls++, q.size - 1)]
    }

    private fun tool(d: AgentDecision): AgentDecision.CallTool {
        assertTrue("expected CallTool, got $d", d is AgentDecision.CallTool); return d as AgentDecision.CallTool
    }

    /** The engine's history line for a finished tool step. */
    private fun ok(call: String, message: String) = "$call → success [native]; verified=true (checked); $message"

    // ---------------------------------------------------------------- LinkHealth: the circuit breaker (N-10)

    private fun tracker() = LinkHealthTracker(openMs = { 60_000L }, slowFailMs = { 6_000L }, weakProbeMs = { 3_000L }, recentMs = { 120_000L })

    @Test fun link_a_slow_failure_opens_the_circuit_and_a_success_closes_it() {
        val t = tracker()
        assertEquals(LinkHealthTracker.Verdict.UNKNOWN, t.verdict(0))
        // Groq's 35 s timeout on the stalled campus Wi-Fi
        assertTrue("a timeout opens the circuit", t.failure(now = 35_000, elapsedMs = 35_000))
        assertTrue(t.stalled(36_000))
        assertEquals(LinkHealthTracker.Verdict.STALLED, t.verdict(36_000))
        assertTrue("the first success reports the link came back", t.success(now = 40_000, elapsedMs = 300))
        assertFalse(t.stalled(40_001))
        assertEquals(LinkHealthTracker.Verdict.GOOD, t.verdict(41_000))
    }

    @Test fun a_reply_that_just_got_through_proves_the_link() {
        // campus Wi-Fi 2026-09-29: four quick 429 replies, then a probe timeout called the working link dead
        val t = LinkHealthTracker()
        assertFalse(t.okWithin(10_000L, 8_000L))
        t.success(now = 10_000L, elapsedMs = 250L)
        assertTrue(t.okWithin(15_000L, 8_000L))
        assertFalse("old traffic proves nothing", t.okWithin(19_000L, 8_000L))
    }

    @Test fun probe_budget_follows_how_long_a_good_probe_takes_on_this_link() {
        val p = com.tekyerema.core.LinkProbe
        assertEquals(6_000L, p.budget(6_000L, weak = false, lastGoodMs = 0L))          // nothing measured yet
        assertEquals(12_000L, p.budget(6_000L, weak = true, lastGoodMs = 0L))          // a known-slow link: twice
        // campus Wi-Fi 2026-09-29: good probes take ~3.7 s, so the budget must not stop at 4–6 s
        assertEquals(9_250L, p.budget(6_000L, weak = false, lastGoodMs = 3_700L))
        assertEquals("capped: a dead link is still noticed", 12_000L, p.budget(6_000L, weak = false, lastGoodMs = 9_000L))
    }

    @Test fun link_one_quick_blip_is_only_suspect_two_in_a_row_stall() {
        val t = tracker()
        t.success(now = 1_000, elapsedMs = 200)
        assertFalse("one quick DNS blip doesn't stall", t.failure(now = 2_000, elapsedMs = 150))
        assertFalse(t.stalled(2_001))
        assertTrue("…but it is worth a probe before the next cloud call", t.suspect(2_001))
        assertTrue("a second failure in a row stalls", t.failure(now = 3_000, elapsedMs = 150))
        assertTrue(t.stalled(3_001))
    }

    @Test fun link_old_failures_dont_count_and_the_circuit_half_opens() {
        val t = tracker()
        t.success(now = 10_000, elapsedMs = 100)
        // a call that started at 0 and failed at 12 s is older news than the success at 10 s
        assertFalse(t.failure(now = 12_000, elapsedMs = 12_000))
        assertFalse(t.stalled(12_001))
        assertTrue(t.failure(now = 20_000, elapsedMs = 8_000))
        assertTrue(t.stalled(79_999))
        assertFalse("after openMs the next request is tried again", t.stalled(80_001))
        assertTrue("a failed probe re-opens at once", t.failure(now = 81_000, elapsedMs = 100, kind = LinkHealthTracker.Kind.PROBE))
    }

    @Test fun link_a_hanging_prewarm_makes_the_first_request_probe_first() {
        val t = LinkHealthTracker(warmSuspectMs = { 2_500L })
        t.warmStarted(1_000)
        assertFalse("a TLS handshake in progress is normal", t.suspect(2_000))
        assertTrue("still silent after 2.5 s on the stalled Wi-Fi", t.suspect(4_000))
        t.warmEnded()
        assertFalse(t.suspect(4_001))
    }

    @Test fun link_a_slow_probe_marks_the_link_weak_and_reset_forgets() {
        val t = tracker()
        t.success(now = 1_000, elapsedMs = 3_500, kind = LinkHealthTracker.Kind.PROBE)
        assertTrue(t.slowProbe())
        t.success(now = 2_000, elapsedMs = 400, kind = LinkHealthTracker.Kind.PROBE)
        assertFalse(t.slowProbe())
        t.failure(now = 3_000, elapsedMs = 9_000)
        t.reset()
        assertEquals(LinkHealthTracker.Verdict.UNKNOWN, t.verdict(3_001))
    }

    // ---------------------------------------------------------------- FallbackBrain: watchdog, link check, cooling

    @Test fun fallback_watchdog_abandons_a_silent_call_when_the_link_is_dead() = runBlocking {
        val silent = object : AgentBrain {
            override suspend fun decide(ctx: BrainContext): AgentDecision { delay(30_000); return AgentDecision.Done("late") }
        }
        val gemini = FakeBrain(AgentDecision.Done("gemini"))
        val t0 = System.currentTimeMillis()
        val d = FallbackBrain(listOf(silent, gemini), networkRetryDelayMs = 1, linkCheck = { false }, watchdogMs = 50,
            health = ProviderCooling()).decide(ctx("x"))
        assertTrue("gave up in well under the 35 s timeout", System.currentTimeMillis() - t0 < 5_000)
        assertEquals(FallbackBrain.NETWORK_LOST, (d as AgentDecision.CannotDo).reasonClass)
        assertEquals("over a dead link the next provider isn't tried either", 0, gemini.calls)
    }

    @Test fun fallback_watchdog_waits_for_a_slow_model_when_the_link_works() = runBlocking {
        val slow = object : AgentBrain {
            override suspend fun decide(ctx: BrainContext): AgentDecision { delay(300); return AgentDecision.Done("thought hard") }
        }
        var probes = 0
        val d = FallbackBrain(listOf(slow), linkCheck = { probes++; true }, watchdogMs = 50, health = ProviderCooling()).decide(ctx("x"))
        assertEquals(AgentDecision.Done("thought hard"), d)
        assertTrue("the link was checked while waiting", probes >= 1)
    }

    @Test fun a_brain_pacing_on_purpose_is_not_abandoned_as_a_dead_link() = runBlocking {
        // A20e 2026-09-29: every key at its per-minute cap → an 8 s pacing wait → the watchdog probed, and abandoned it
        val pacing = object : AgentBrain {
            override suspend fun decide(ctx: BrainContext): AgentDecision {
                com.tekyerema.agent.BrainActivity.pausingFor(300); delay(300); return AgentDecision.Done("after the wait")
            }
        }
        val d = FallbackBrain(listOf(pacing), linkCheck = { false }, watchdogMs = 50, health = ProviderCooling()).decide(ctx("x"))
        assertEquals(AgentDecision.Done("after the wait"), d)
    }

    @Test fun fallback_checks_the_link_before_trying_the_next_provider() = runBlocking {
        val timeout = AgentDecision.Fail("LLM request failed: timeout")
        val gemini = FakeBrain(AgentDecision.Done("gemini"))
        val dead = FallbackBrain(listOf(FakeBrain(timeout), gemini), networkRetryDelayMs = 1, linkCheck = { false },
            watchdogMs = 0, health = ProviderCooling()).decide(ctx("x"))
        assertEquals(FallbackBrain.NETWORK_LOST, (dead as AgentDecision.CannotDo).reasonClass)
        assertEquals(0, gemini.calls)
        // the link answers → it was Groq's path, not the internet: Gemini gets the step (the A20e Chrome log: 4 s)
        val alive = FallbackBrain(listOf(FakeBrain(timeout), gemini), networkRetryDelayMs = 1, linkCheck = { true },
            watchdogMs = 0, health = ProviderCooling()).decide(ctx("x"))
        assertEquals(AgentDecision.Done("gemini"), alive)
    }

    @Test fun fallback_starts_with_the_provider_that_answers_while_the_other_cools() = runBlocking {
        val groq = Scripted(AgentDecision.Done("groq"))
        val gemini = Scripted(AgentDecision.Done("gemini"))
        val health = ProviderCooling(coolMs = { 90_000L })
        health.networkFail("0:" + groq.javaClass.simpleName, System.currentTimeMillis())
        val d = FallbackBrain(listOf(groq, gemini), linkCheck = { null }, watchdogMs = 0, health = health).decide(ctx("x"))
        assertEquals(AgentDecision.Done("gemini"), d)
        assertEquals(0, groq.calls)
        assertTrue(FallbackBrain.isNetworkError("stream was reset: PROTOCOL_ERROR"))   // an HTTP/2 ping failure
    }

    // ---------------------------------------------------------------- AnswerCache (H-11)

    @Test fun cache_keys_only_self_contained_questions() {
        assertEquals("what is the capital of ghana", AnswerCache.key("What is the capital of Ghana?"))
        assertNull("leans on the conversation", AnswerCache.key("what about him"))
        assertNull("asked for variety", AnswerCache.key("tell me a joke"))
        assertNull(AnswerCache.key("hi"))
    }

    @Test fun clock_relative_answers_are_short_lived() {
        // review #13: the time of day is never kept; "days until …" / "how old …" are kept only as fresh answers
        var now = 0L
        val c = AnswerCache(maxEntries = 8, clock = { now })
        c.put("what time is it in London", "It's 3 PM in London.")
        c.put("how many days until Christmas", "87 days.")
        c.put("how old is the president of Ghana", "He is 65.")
        assertNull(c.fresh("what time is it in London"))
        assertEquals("87 days.", c.fresh("how many days until Christmas"))
        now = 2 * 3_600_000L
        assertNull("no longer fresh after 2 h", c.fresh("how many days until Christmas"))
        now = 3 * 24 * 3_600_000L
        assertNull("not offered offline days later", c.stale("how old is the president of Ghana"))
    }

    @Test fun date_questions_the_phone_leaves_to_the_model_are_short_lived_too() {
        // The phone answers most date questions itself now (LocalDates); the rest must not be repeated a week later.
        var now = 0L
        val c = AnswerCache(maxEntries = 8, clock = { now })
        c.put("when is Eid al-Fitr", "Eid al-Fitr is expected on 9 March 2027.")
        c.put("how many years since Ghana's independence", "69 years.")
        c.put("what's the time difference between Ghana and Brazil", "Brazil is 3 hours behind.")
        assertNull("never kept", c.fresh("what's the time difference between Ghana and Brazil"))
        now = 2 * 3_600_000L
        assertNull(c.fresh("when is Eid al-Fitr"))
        assertNull(c.fresh("how many years since Ghana's independence"))
        now = 3 * 24 * 3_600_000L
        assertNull("not offered offline days later", c.stale("when is Eid al-Fitr"))
    }

    @Test fun cache_fresh_then_stale_with_its_age() {
        var now = 0L
        val c = AnswerCache(maxEntries = 8, clock = { now })
        c.put("what is the capital of Burkina Faso", "Ouagadougou.")
        c.put("what's the weather in Accra today", "Sunny, 31 degrees.")
        c.put("who won the match", "Which match do you mean?")          // a question back is never stored
        c.put("what is the dollar rate", "Sorry, I couldn't find that.")   // nor a non-answer
        assertEquals("Ouagadougou.", c.fresh("What is the capital of Burkina Faso?"))
        assertEquals("Sunny, 31 degrees.", c.fresh("what's the weather in Accra today"))
        assertNull(c.fresh("who won the match")); assertNull(c.fresh("what is the dollar rate"))
        now = 2 * 3_600_000L   // 2 h later: weather is no longer fresh; the capital still is
        assertNull(c.fresh("what's the weather in Accra today"))
        assertEquals("Ouagadougou.", c.fresh("what is the capital of Burkina Faso"))
        val (answer, age) = c.stale("what's the weather in Accra today")!!
        assertEquals("Sunny, 31 degrees.", answer)
        assertEquals("about 2 hours ago", AnswerCache.spokenAge(age))
        now = 3 * 24 * 3_600_000L   // 3 days: too old even to offer the weather
        assertNull(c.stale("what's the weather in Accra today"))
    }

    // ---------------------------------------------------------------- RoutingBrain: online/offline behaviour

    private fun router(
        cloud: AgentBrain?, online: Boolean, cache: AnswerCache = AnswerCache(clock = { 0L }),
        reason: OfflineReason = OfflineReason.NO_NETWORK, kept: MutableList<String> = mutableListOf(),
        linkCheck: (suspend () -> Boolean?)? = null, app: ((String) -> Pair<String, String>?)? = null, model: AgentBrain? = null
    ) = RoutingBrain(LocalBrain(), cloud, appForGoal = app, linkCheck = linkCheck, offlineReason = { reason }, answers = cache,
        enqueue = { kept.add(it); true }, queued = { false }, localModel = model) { online }

    @Test fun router_answers_a_repeated_question_from_the_cache_without_the_cloud() = runBlocking {
        val cache = AnswerCache(clock = { 0L })
        val cloud = FakeBrain(AgentDecision.Done("Ouagadougou."))
        val q = "what is the capital of Burkina Faso"
        assertEquals(AgentDecision.Done("Ouagadougou."), router(cloud, true, cache).decide(ctx(q, simple = true)))
        assertEquals(1, cloud.calls)
        val again = FakeBrain(AgentDecision.Done("should not be asked"))
        assertEquals(AgentDecision.Done("Ouagadougou."), router(again, true, cache).decide(ctx(q, simple = true)))
        assertEquals(0, again.calls)
    }

    @Test fun router_offline_offers_an_older_answer_with_its_age() = runBlocking {
        var now = 0L
        val cache = AnswerCache(clock = { now })
        cache.put("what is the capital of Burkina Faso", "Ouagadougou.")
        now = 8 * 24 * 3_600_000L   // past the week a settled fact is repeated as-is
        val d = router(FakeBrain(AgentDecision.Done("x")), false, cache).decide(ctx("what is the capital of Burkina Faso", simple = true))
        assertEquals(AgentDecision.Done("I'm offline, but when I checked 8 days ago: Ouagadougou."), d)
    }

    @Test fun router_offline_question_is_kept_for_later_only_when_the_user_says_yes() = runBlocking {
        val kept = mutableListOf<String>()
        val q = "what is the capital of Burkina Faso"
        val r = router(FakeBrain(AgentDecision.Done("x")), false, kept = kept)
        val offer = tool(r.decide(ctx(q, simple = true)))
        assertEquals("ask_user", offer.name)
        assertTrue(offer.args.getString("question").contains("when the internet is back"))
        val yes = r.decide(ctx(q, listOf("ask_user(\"…\") → the user answered: \"yes please\""), simple = true))
        assertTrue((yes as AgentDecision.Done).summary.contains("I'll find out"))
        assertEquals(listOf(q), kept)

        val kept2 = mutableListOf<String>()
        val r2 = router(FakeBrain(AgentDecision.Done("x")), false, kept = kept2)
        tool(r2.decide(ctx(q, simple = true)))
        val no = r2.decide(ctx(q, listOf("ask_user(\"…\") → the user answered: \"no\""), simple = true))
        assertEquals(AgentDecision.Done("Okay, I won't."), no)
        assertTrue(kept2.isEmpty())
    }

    @Test fun router_a_lost_connection_before_any_action_is_answered_the_offline_way() = runBlocking {
        val lost = FakeBrain(AgentDecision.CannotDo(FallbackBrain.NETWORK_LOST, "The internet isn't answering right now."))
        val d = router(lost, true, reason = OfflineReason.NONE).decide(ctx("what is the capital of Burkina Faso", simple = true))
        val offer = tool(d)
        assertEquals("ask_user", offer.name)
        assertTrue(offer.args.getString("question").startsWith("The internet isn't working right now"))
    }

    @Test fun router_does_not_wait_on_the_cloud_when_the_link_check_fails() = runBlocking {
        val cloud = FakeBrain(AgentDecision.Done("should not be asked"))
        val d = router(cloud, true, reason = OfflineReason.STALLED, linkCheck = { false }).decide(ctx("reconcile my quarterly ledgers"))
        assertEquals(0, cloud.calls)
        d as AgentDecision.CannotDo
        assertEquals(RoutingBrain.OFFLINE_UNKNOWN, d.reasonClass)
        assertTrue(d.detail.startsWith("The internet isn't working right now"))
        assertTrue(d.detail.contains("open apps"))
    }

    @Test fun router_offline_says_it_can_open_the_app_the_request_names() = runBlocking {
        val app: (String) -> Pair<String, String>? = { g -> if (g.contains("whatsapp", true)) "WhatsApp" to "com.whatsapp" else null }
        val d = router(FakeBrain(AgentDecision.Done("x")), false, app = app).decide(ctx("open whatsapp and send prince a message"))
        d as AgentDecision.CannotDo
        assertTrue(d.detail, d.detail.contains("I can open WhatsApp"))
    }

    @Test fun router_offline_tries_the_optional_on_device_model_and_keeps_it_for_the_task() = runBlocking {
        val model = Scripted(AgentDecision.CallTool("open_app", JSONObject().put("app", "Ledger"), "local-model:open_app"),
            AgentDecision.Done("Ledger is open."))
        val r = router(null, false, model = model)
        assertEquals("open_app", tool(r.decide(ctx("reconcile my quarterly ledgers"))).name)
        assertEquals(AgentDecision.Done("Ledger is open."), r.decide(ctx("reconcile my quarterly ledgers", listOf(ok("open_app({})", "Opened")))))
    }

    // ---------------------------------------------------------------- LocalBrain: wider offline coverage (N-09)

    @Test fun local_answers_what_can_you_do_on_the_phone() = runBlocking {
        val cloud = FakeBrain(AgentDecision.Done("should not be asked"))
        val d = router(cloud, true).decide(ctx("What can you do?", simple = true))
        assertTrue((d as AgentDecision.Done).summary.contains("Even without internet"))
        assertEquals(0, cloud.calls)
    }

    @Test fun local_parses_texts_in_everyday_phrasings() {
        fun p(s: String) = LocalBrain.parseSms(s)
        assertEquals(LocalBrain.Sms("Prince", null, "I'm on my way", false), p("text Prince saying I'm on my way"))
        assertEquals(LocalBrain.Sms("Prince", null, "hello", true), p("send a WhatsApp message to Prince saying hello"))
        assertEquals(LocalBrain.Sms("Prince", null, "hi", true), p("send a message to Prince on WhatsApp saying hi"))
        assertEquals(LocalBrain.Sms("Ama", null, "the meeting is at 3", false), p("please send Ama a text that says the meeting is at 3"))
        assertEquals(LocalBrain.Sms(null, "0244123456", "I'm late", false), p("send a message to 0244 123 456 that I'm late"))
        assertEquals("a clear 'saying' wins; its 'that' isn't part of the message",
            LocalBrain.Sms("Ama", null, "I'm late", false), p("text Ama saying that I'm late"))
        assertEquals("the message may itself say 'and' or 'weather'",
            "the weather is bad and I'm staying home", p("message Kofi: the weather is bad and I'm staying home")?.message)
        assertNull("no message", p("send a message to Prince"))
        assertNull("not a send", p("read the text that says hello"))
    }

    @Test fun local_sends_a_text_without_the_cloud_even_when_the_message_says_weather() = runBlocking {
        val t = tool(router(null, false).decide(ctx("text Prince saying the weather is bad and I'm staying home")))
        assertEquals("send_sms", t.name)
        assertEquals("Prince", t.args.getString("contact"))
        assertEquals("the weather is bad and I'm staying home", t.args.getString("message"))
    }

    @Test fun install_requests_go_straight_to_install_app() {
        val t = LocalBrain::installTarget
        assertEquals("Claude", t("install the Claude app from the Play Store"))
        assertEquals("WhatsApp", t("install WhatsApp"))
        assertEquals("Jiji", t("please download the Jiji app"))
        assertEquals("Telegram", t("get Telegram from the Play Store"))
        assertNull("not an install", t("download this song"))
        assertNull(t("get me a taxi"))
        assertNull("which app?", t("install the app"))
        assertNull(t("install WhatsApp and message Ama"))
    }

    @Test fun a_text_never_swallows_a_second_request() = runBlocking {
        // review #2: "…then call Kofi" is a second request, not part of the message
        assertTrue(LocalBrain.hasFollowOnCommand("I'm late then call Kofi"))
        assertTrue(LocalBrain.hasFollowOnCommand("I'm late and open WhatsApp"))
        assertFalse(LocalBrain.hasFollowOnCommand("the weather is bad and I'm staying home"))
        assertFalse(LocalBrain.hasFollowOnCommand("call me when you land"))                 // the FIRST clause is the message
        val d = router(null, false).decide(ctx("text Ama saying I'm late then call Kofi"))
        if (d is AgentDecision.CallTool) assertTrue("the whole compound became one text: ${d.args}",
            !(d.name == "send_sms" && d.args.optString("message").contains("call Kofi")))
    }

    @Test fun local_reads_only_the_named_apps_messages() = runBlocking {
        val t = tool(LocalBrain().decide(ctx("read my WhatsApp messages")))
        assertEquals("read_notifications", t.name)
        assertEquals("whatsapp", t.args.getString("filter"))
    }

    @Test fun local_answers_what_did_kwame_say_from_the_notifications() = runBlocking {
        val t = tool(router(null, false).decide(ctx("What did Kwame say?", simple = true)))
        assertEquals("read_notifications", t.name); assertEquals("Kwame", t.args.getString("filter"))
        assertEquals("Ama", tool(LocalBrain().decide(ctx("any messages from Ama"))).args.getString("filter"))
        // "he" needs the conversation — not a local read
        assertFalse(LocalBrain().decide(ctx("what did he say")) is AgentDecision.CallTool)
    }

    @Test fun local_runs_open_whatsapp_and_read_my_messages_offline_step_by_step() = runBlocking {
        val r = router(null, false)
        val g = "open WhatsApp and read my messages"
        val s1 = tool(r.decide(ctx(g)))
        assertEquals("open_app", s1.name); assertEquals("WhatsApp", s1.args.getString("app"))
        val h1 = listOf(ok("open_app({\"app\":\"WhatsApp\"})", "Opened WhatsApp"))
        val s2 = tool(r.decide(ctx(g, h1)))
        assertEquals("inside the app, 'read my messages' reads its screen", "read_screen", s2.name)
        val h2 = h1 + ok("read_screen({})", "Chats. Prince, 10:32, I'm coming.")
        assertEquals(AgentDecision.Done("WhatsApp is open. Chats. Prince, 10:32, I'm coming."), r.decide(ctx(g, h2)))
    }

    @Test fun local_plan_covers_independent_steps_and_stops_honestly_on_a_failure() = runBlocking {
        val b = LocalBrain()
        val g = "go home and set a timer for 5 minutes"
        assertEquals("key_event", tool(b.decide(ctx(g))).name)
        val h1 = listOf(ok("key_event({\"key\":\"home\"})", "Pressed home"))
        val t = tool(b.decide(ctx(g, h1)))
        assertEquals("set_timer", t.name); assertEquals(300, t.args.getInt("seconds"))
        val failed = h1 + "set_timer({\"seconds\":300}) → fail [intent]; verified=false (no clock app); No clock app can set timers."
        val end = b.decide(ctx(g, failed))
        end as AgentDecision.CannotDo
        assertEquals(LocalBrain.ACTION_FAILED, end.reasonClass)
        assertEquals("Home screen. No clock app can set timers.", end.detail)
    }

    @Test fun local_plan_leaves_in_app_actions_to_the_planner() = runBlocking {
        // "call Ama" right after opening WhatsApp means a WhatsApp call — not the phone dialer.
        val d = LocalBrain().decide(ctx("open whatsapp and call Ama"))
        assertEquals(LocalBrain.DEFER, (d as AgentDecision.CannotDo).reasonClass)
        assertTrue(LocalBrain().decide(ctx("open whatsapp and send prince a message")) is AgentDecision.CannotDo)
    }

    @Test fun local_clause_split_handles_twi_na_but_not_a_question() {
        assertEquals(listOf("bue WhatsApp", "kenkan me nkra"), LocalBrain.splitClauses("bue WhatsApp na kenkan me nkra"))
        assertNull("'dɛn na' is a question, not two steps", LocalBrain.splitClauses("dɛn na wopɛ"))
        assertEquals(listOf("open camera", "take a photo"), LocalBrain.splitClauses("open camera, then take a photo"))
        assertNull(LocalBrain.splitClauses("open whatsapp"))
    }

    // ---------------------------------------------------------------- Offline queue (N-09)

    @Test fun queue_keeps_a_few_recent_questions_and_forgets_old_ones() {
        var now = 0L
        val q = OfflineQueueStore(maxItems = { 2 }, maxAgeMs = { 1_000L }, maxAttempts = { 2 }, clock = { now })
        assertTrue(q.add("what is the cedi rate"))
        assertTrue("a repeat is not a second item", q.add("What is the cedi rate"))
        assertTrue(q.add("who is the president of Togo"))
        assertFalse("full", q.add("what is the capital of Mali"))
        assertTrue(q.contains("what is the cedi rate"))
        val first = q.next()!!
        assertTrue(q.failed(first)); assertFalse("dropped after its last attempt", q.failed(first))
        assertEquals(1, q.size())
        now = 5_000L
        assertEquals("expired", 0, q.size())
    }

    @Test fun queue_only_offers_self_contained_questions_and_reads_yes_no() {
        assertTrue(OfflineQueueStore.queueable("what is the exchange rate of the cedi today"))
        assertFalse("an action on the phone is never run later by itself", OfflineQueueStore.queueable("send the news to Ama"))
        assertFalse("depends on the conversation", OfflineQueueStore.queueable("who is he"))
        assertEquals(true, OfflineQueueStore.yesNo("yes please"))
        assertEquals(false, OfflineQueueStore.yesNo("no"))
        assertNull(OfflineQueueStore.yesNo("maybe"))
    }

    // ---------------------------------------------------------------- Optional on-device model seam (C-04)

    @Test fun local_model_reply_is_checked_before_it_becomes_a_tool_call() {
        val t = LocalModelBrain.parse("""sure: {"tool":"open_app","args":{"app":"WhatsApp"}}""")!!
        assertEquals("open_app", t.name); assertEquals("WhatsApp", t.args.getString("app"))
        assertNull("not an allowed tool", LocalModelBrain.parse("""{"tool":"momo_send","args":{"number":"024","amount":"5"}}"""))
        assertNull("missing its argument", LocalModelBrain.parse("""{"tool":"open_app","args":{}}"""))
        assertNull("a text needs a recipient", LocalModelBrain.parse("""{"tool":"send_sms","args":{"message":"hi"}}"""))
        assertNull(LocalModelBrain.parse("""{"tool":"none"}"""))
        assertNull(LocalModelBrain.parse("I think you should open WhatsApp"))
        val p = LocalModelBrain.prompt("open whatsapp", setOf("open_app", "make_call"))
        assertTrue(p.contains("open_app(app)") && p.contains("make_call(") && !p.contains("send_sms"))
        assertNull("off by default: nothing changes", LocalModelBrain.ifEnabled())
        assertNotNull(p)
    }
}
