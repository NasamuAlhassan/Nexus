package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.ModelRoles
import com.tekyerema.agent.llm.SpaceLlm
import com.tekyerema.core.config.AppConfig
import com.tekyerema.memory.PlannerMemory
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Our own agent model on the Space as the cloud planner (2 Nov 2026): the `/agent/chat` request shape, the session that resends
 * its messages, the cool-down after a 429 / 503 / timeout, https only, the headers, and no memory leaving the phone by default.
 * Fake HTTP layer, as SpaceSearchTest.
 */
class SpaceLlmTest {
    private val url = "https://example-space.hf.space/agent/chat"
    private var now = 1_000_000L
    private val sent = mutableListOf<Triple<String, Map<String, String>, JSONObject>>()
    private val okReply = """{"content":"{\"tool\":\"set_alarm\",\"args\":{\"hour\":6}}","ms":812}"""

    private var savedShare = PlannerMemory.shareWithCloud
    private var savedSource = PlannerMemory.source

    @Before fun setUp() {
        SpaceLlm.resetCooling(); sent.clear(); now = 1_000_000L
        savedShare = PlannerMemory.shareWithCloud; savedSource = PlannerMemory.source
    }
    @After fun tearDown() {
        SpaceLlm.resetCooling(); PlannerMemory.shareWithCloud = savedShare; PlannerMemory.source = savedSource
        AppConfig.loadFrom(emptyList())
    }

    private fun llm(code: Int? = 200, body: String = okReply, retryMs: Long? = null, u: String = url, headers: Map<String, String> = emptyMap()) =
        SpaceLlm(u, { headers }, { reqUrl, h, j ->
            sent += Triple(reqUrl, h, j)
            if (code == null) null else SpaceLlm.Reply(code, body, retryMs)
        }, { now })

    private fun roles(j: JSONObject) = (0 until j.getJSONArray("messages").length()).map { j.getJSONArray("messages").getJSONObject(it).getString("role") }
    private fun contents(j: JSONObject) = (0 until j.getJSONArray("messages").length()).map { j.getJSONArray("messages").getJSONObject(it).getString("content") }

    // ── request shape ──

    @Test fun a_single_turn_posts_messages_and_max_tokens_and_nothing_else() = runBlocking {
        val out = llm().chat("SYSTEM", "USER", null, 200, 25_000L)
        assertEquals("""{"tool":"set_alarm","args":{"hour":6}}""", out)
        val (reqUrl, _, body) = sent.single()
        assertEquals(url, reqUrl)
        assertEquals(setOf("messages", "max_tokens"), body.keys().asSequence().toSet())
        assertEquals(200, body.getInt("max_tokens"))
        assertEquals(listOf("system", "user"), roles(body))
        assertEquals(listOf("SYSTEM", "USER"), contents(body))
        for (i in 0 until body.getJSONArray("messages").length())
            assertEquals(setOf("role", "content"), body.getJSONArray("messages").getJSONObject(i).keys().asSequence().toSet())
    }

    @Test fun a_prompt_without_a_system_part_is_one_user_message() = runBlocking {
        llm().complete("hello", 50, 25_000L)
        assertEquals(listOf("user"), roles(sent.single().third))
    }

    @Test fun the_endpoint_is_the_space_host_plus_agent_chat_and_https_only() {
        assertEquals("https://x.hf.space/agent/chat", SpaceLlm.endpoint("https://x.hf.space"))
        assertEquals("https://x.hf.space/agent/chat", SpaceLlm.endpoint("https://x.hf.space/web/ask/"))
        assertEquals("https://x.hf.space/agent/chat", SpaceLlm.endpoint("https://x.hf.space/agent/chat"))
        assertNull(SpaceLlm.endpoint("http://x.hf.space"))
        assertNull(SpaceLlm.endpoint("ftp://x.hf.space"))
        assertNull(SpaceLlm.endpoint(""))
        assertNull(SpaceLlm.endpoint(null))
    }

    @Test fun an_http_address_is_never_called() = runBlocking {
        val l = llm(u = "http://example-space.hf.space/agent/chat")
        assertNull(l.chat("s", "u", null, 200, 25_000L))
        assertTrue("no request went out", sent.isEmpty())
        assertNotNull(l.failure)
    }

    @Test fun the_headers_are_exactly_what_the_phone_supplies() = runBlocking {
        llm(headers = mapOf("X-Nexus-Key" to "k", "X-Nexus-Device" to "d")).chat("s", "u", null, 200, 25_000L)
        assertEquals(mapOf("X-Nexus-Key" to "k", "X-Nexus-Device" to "d"), sent.single().second)
        sent.clear()
        llm().chat("s", "u", null, 200, 25_000L)
        assertTrue("no key = no header", sent.single().second.isEmpty())
        assertEquals("X-Nexus-Key", com.tekyerema.language.SpaceVoice.KEY_HEADER)
        assertEquals("X-Nexus-Device", com.tekyerema.language.SpaceVoice.DEVICE_HEADER)
    }

    // ── sessions ──

    @Test fun a_session_resends_the_whole_conversation_each_turn() = runBlocking {
        val l = llm()
        val s = l.openSession("SYSTEM", 200)
        assertEquals("""{"tool":"set_alarm","args":{"hour":6}}""", s.send("turn one", null, 25_000L))
        assertEquals(listOf("system", "user"), roles(sent[0].third))
        s.send("turn two", null, 25_000L)
        val second = sent[1].third
        assertEquals(listOf("system", "user", "assistant", "user"), roles(second))
        assertEquals("SYSTEM", contents(second)[0])
        assertEquals("turn one", contents(second)[1])
        assertEquals("""{"tool":"set_alarm","args":{"hour":6}}""", contents(second)[2])
        assertEquals("turn two", contents(second)[3])
        assertEquals(200, second.getInt("max_tokens"))
        assertTrue("the held size grows with the conversation", s.tokens > 0)
        s.close()
        assertEquals(0, s.tokens)
    }

    @Test fun a_failed_turn_leaves_nothing_in_the_conversation() = runBlocking {
        var code = 503
        val l = SpaceLlm(url, { emptyMap() }, { u, h, j -> sent += Triple(u, h, j); SpaceLlm.Reply(code, if (code == 200) okReply else """{"error":"loading"}""") }, { now })
        val s = l.openSession("SYSTEM", 200)
        assertNull(s.send("lost turn", null, 25_000L))
        code = 200; now += 61_000L   // past the cool-down
        assertNotNull(s.send("next turn", null, 25_000L))
        assertEquals(listOf("system", "user"), roles(sent.last().third))
        assertEquals("next turn", contents(sent.last().third)[1])
    }

    // ── failures and the cool-down ──

    @Test fun a_429_returns_null_and_the_space_is_skipped_for_the_time_it_says() = runBlocking {
        val l = llm(code = 429, body = """{"error":"slow down"}""", retryMs = 30_000L)
        assertNull(l.chat("s", "u", null, 200, 25_000L))
        assertTrue(l.failure!!.contains("429"))
        assertEquals(1, sent.size)
        now += 10_000L
        assertNull("cooling: no request", l.chat("s", "u", null, 200, 25_000L))
        assertEquals(1, sent.size)
        assertTrue("a rate limit is classed as a quota by the cloud chain", com.tekyerema.agent.routing.ModelRouting.isQuota(l.failure!!))
        now += 21_000L   // 31 s after the 429
        assertNull(l.chat("s", "u", null, 200, 25_000L))   // asked again (the fake still says 429)
        assertEquals(2, sent.size)
    }

    @Test fun a_429_without_a_retry_after_cools_for_two_minutes() = runBlocking {
        val l = llm(code = 429)
        l.chat("s", "u", null, 200, 25_000L)
        now += 100_000L
        l.chat("s", "u", null, 200, 25_000L)
        assertEquals(1, sent.size)
        now += 30_000L
        l.chat("s", "u", null, 200, 25_000L)
        assertEquals(2, sent.size)
    }

    @Test fun a_503_returns_null_and_cools_for_a_minute() = runBlocking {
        val l = llm(code = 503, body = """{"error":"loading"}""")
        assertNull(l.chat("s", "u", null, 200, 25_000L))
        assertTrue(com.tekyerema.agent.routing.ModelRouting.isQuota(l.failure!!))
        now += 30_000L
        assertNull(l.chat("s", "u", null, 200, 25_000L))
        assertEquals(1, sent.size)
        now += 31_000L
        l.chat("s", "u", null, 200, 25_000L)
        assertEquals(2, sent.size)
    }

    @Test fun a_400_is_our_request_so_no_cool_down() = runBlocking {
        val l = llm(code = 400, body = """{"error":"bad messages"}""")
        assertNull(l.chat("s", "u", null, 200, 25_000L))
        assertNull(l.chat("s", "u", null, 200, 25_000L))
        assertEquals(2, sent.size)
    }

    @Test fun no_reply_is_a_timeout_failure_and_cools() = runBlocking {
        val l = llm(code = null)
        assertNull(l.chat("s", "u", null, 200, 25_000L))
        assertTrue(com.tekyerema.agent.FallbackBrain.isNetworkError(l.failure!!))
        assertNull(l.chat("s", "u", null, 200, 25_000L))
        assertEquals("cooling after a timeout", 1, sent.size)
    }

    @Test fun a_slow_space_times_out_inside_the_budget() = runBlocking {
        val l = SpaceLlm(url, { emptyMap() }, { _, _, _ -> kotlinx.coroutines.delay(60_000L); SpaceLlm.Reply(200, okReply) }, { now })
        val t0 = System.currentTimeMillis()
        assertNull(l.chat("s", "u", null, 200, 2_000L))
        assertTrue("gave up near the 2 s budget, not 60 s", System.currentTimeMillis() - t0 < 10_000L)
        assertTrue(com.tekyerema.agent.FallbackBrain.isNetworkError(l.failure!!))
    }

    @Test fun an_unreadable_or_empty_reply_is_null() = runBlocking {
        assertNull(llm(body = "<html>oops</html>").chat("s", "u", null, 200, 25_000L))
        SpaceLlm.resetCooling()
        assertNull(llm(body = """{"content":"   ","ms":3}""").chat("s", "u", null, 200, 25_000L))
        SpaceLlm.resetCooling()
        assertNull(llm(body = """{"error":"x"}""").chat("s", "u", null, 200, 25_000L))
    }

    // ── the brain on top of it ──

    private fun ctx(goal: String): BrainContext {
        val alarm = JSONObject().put("name", "set_alarm").put("description", "Set an alarm.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("hour", JSONObject().put("type", "integer"))).put("required", JSONArray(listOf("hour"))))
        return BrainContext(goal = goal, screen = JSONObject(), history = emptyList(), geminiTools = JSONArray(),
            openAiTools = JSONArray().put(JSONObject().put("type", "function").put("function", alarm)))
    }

    private fun everythingSent() = sent.joinToString("\n") { contents(it.third).joinToString("\n") }

    @Test fun the_space_brain_decides_from_the_models_reply() = runBlocking {
        val d = SpaceLlm.plannerBrain(llm()).decide(ctx("wake me at six"))
        assertTrue(d is AgentDecision.CallTool)
        assertEquals("set_alarm", (d as AgentDecision.CallTool).name)
        assertEquals(1, sent.size)
        assertEquals(listOf("system", "user"), roles(sent.single().third))
    }

    @Test fun a_space_brain_sends_no_memory_or_hints_by_default() = runBlocking {
        PlannerMemory.source = { listOf("My wife is Pearl, her number is on the phone.", "I usually bank with Fidelity.") }
        // shareWithCloud is the real default (Settings.shareMemoryWithCloud = false); the test seam reads it the same way.
        PlannerMemory.shareWithCloud = { false }
        val d = SpaceLlm.plannerBrain(llm()).decide(ctx("call my wife"))
        assertTrue(d is AgentDecision.CallTool)
        val all = everythingSent()
        assertFalse(all.contains("Pearl"))
        assertFalse(all.contains("Fidelity"))
        assertFalse(all.contains("What I know"))
    }

    @Test fun the_same_brain_includes_the_memory_only_when_the_user_shares_it() = runBlocking {
        PlannerMemory.source = { listOf("My wife is Pearl.") }
        PlannerMemory.shareWithCloud = { true }
        SpaceLlm.plannerBrain(llm()).decide(ctx("call my wife"))
        assertTrue("shared on purpose", everythingSent().contains("Pearl"))
    }

    @Test fun a_429_makes_the_brain_fail_so_the_cloud_chain_can_class_it() = runBlocking {
        val d = SpaceLlm.plannerBrain(llm(code = 429)).decide(ctx("wake me at six"))
        assertTrue(d is AgentDecision.Fail)
        assertTrue(com.tekyerema.agent.routing.ModelRouting.isQuota((d as AgentDecision.Fail).reason))
    }

    @Test fun a_timeout_makes_the_brain_fail_as_a_network_error() = runBlocking {
        val d = SpaceLlm.plannerBrain(llm(code = null)).decide(ctx("wake me at six"))
        assertTrue(d is AgentDecision.Fail)
        assertTrue(com.tekyerema.agent.FallbackBrain.isNetworkError((d as AgentDecision.Fail).reason))
    }

    // ── config defaults ──

    @Test fun the_default_planner_chain_is_the_space_only() {
        AppConfig.loadFrom(emptyList())
        assertEquals(listOf(ModelRoles.SPACE), ModelRoles.plannerChain())
        assertFalse(ModelRoles.GROQ in ModelRoles.plannerChain())
        assertFalse(ModelRoles.GEMINI in ModelRoles.plannerChain())
    }

    @Test fun the_shipped_config_names_only_the_space() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        val models = java.io.File(dir, "models.json").readText()
        val router = java.io.File(dir, "router.json").readText()
        val chain = JSONObject(models).getJSONObject("models").getJSONObject("planner").getJSONArray("chain")
        assertEquals(listOf("space"), (0 until chain.length()).map { chain.getString(it) })
        val search = JSONObject(router).getJSONObject("answers").getJSONObject("search").getJSONArray("providers")
        assertEquals(listOf("space"), (0 until search.length()).map { search.getString(it) })
    }

    @Test fun a_developer_can_bring_groq_back_in_config() {
        AppConfig.loadFrom(listOf("config/models.json" to """{"models":{"planner":{"chain":["space","groq"]}}}"""))
        assertEquals(listOf("space", "groq"), ModelRoles.plannerChain())
    }
}
