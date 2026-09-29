package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.mcp.McpSession
import com.tekyerema.mcp.McpTools
import com.tekyerema.mcp.RateLimiter
import com.tekyerema.mcp.RemoteBackend
import com.tekyerema.mcp.RemoteCall
import com.tekyerema.mcp.RunTaskOutcome
import com.tekyerema.mcp.ScreenReport
import com.tekyerema.mcp.StopOutcome
import com.tekyerema.mcp.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP front door's JSON-RPC handling (McpSession), line by line against a fake phone: both eras of the handshake,
 * the tool surface and its annotations, every error class, rate limits, and that nothing on it can approve.
 */
class McpSessionTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    /** A phone that records what the computer asked for. It has no way to approve anything — neither has the real one. */
    private class FakePhone : RemoteBackend {
        val started = ArrayList<Pair<String, String>>()
        var busy = false
        var stops = 0
        var waiting = false
        var actions = false
        override fun runTask(goal: String, origin: String): RunTaskOutcome {
            if (busy) return RunTaskOutcome.Refused("The phone is busy with another task.")
            started += goal to origin
            busy = true
            return RunTaskOutcome.Started("t${started.size}")
        }
        override fun getTask(taskId: String): JSONObject? = if (taskId != "t${started.size}") null else JSONObject()
            .put("task_id", taskId).put("status", if (waiting) TaskStatus.WAITING else TaskStatus.RUNNING)
            .apply { if (waiting) put("waiting_for", "confirmation").put("prompt", "Your computer is asking me to do this. Send this message to Prince?") }
            .put("message", if (waiting) "Waiting for the person holding the phone." else "Working on it.")
        override fun stop(origin: String): StopOutcome { stops++; val was = busy; busy = false; return StopOutcome(was, if (was) "Stopped." else "Nothing is running.") }
        override fun describeScreen() = ScreenReport("App: Settings\n- TextView “Wi-Fi”", JSONObject().put("app", "com.android.settings"))
        override fun activity(sinceMs: Long, limit: Int) = JSONObject().put("audit", JSONArray()).put("remote_calls", JSONArray())
        override fun actionsAllowed() = actions
    }

    private var now = 1_000_000L
    private val calls = ArrayList<RemoteCall>()
    private val phone = FakePhone()
    private val session = McpSession(phone, clock = { now }, onCall = { calls += it }, serverVersion = "test")

    private fun send(json: String): List<JSONObject> = session.handle(json).map { JSONObject(it) }
    private fun one(json: String): JSONObject = send(json).single()
    private val modernMeta = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"device_suite","version":"1"}}"""
    private fun modern(id: Int, method: String, params: String = ""): JSONObject =
        one("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":{$modernMeta${if (params.isEmpty()) "" else ",$params"}}}""")
    private fun call(id: Int, tool: String, args: String = "{}"): JSONObject = modern(id, "tools/call", """"name":"$tool","arguments":$args""")
    private fun errCode(r: JSONObject) = r.getJSONObject("error").getInt("code")

    // ---------------------------------------------------------------- modern era (2026-07-28, stateless)

    @Test fun server_discover_describes_a_tools_only_server_with_the_modern_revision() {
        val r = modern(1, "server/discover").getJSONObject("result")
        assertEquals("complete", r.getString("resultType"))
        assertEquals(JSONArray(listOf("2026-07-28")).toString(), r.getJSONArray("supportedVersions").toString())
        assertTrue(r.getJSONObject("capabilities").has("tools"))
        assertTrue(r.getLong("ttlMs") >= 0)
        assertTrue(r.getString("cacheScope") in setOf("public", "private"))
        assertEquals("tekyerema", r.getJSONObject("_meta").getJSONObject("io.modelcontextprotocol/serverInfo").getString("name"))
        assertFalse("serverInfo lives in _meta, not the body", r.has("serverInfo"))
        assertTrue(r.getString("instructions").contains("nothing over this connection can approve"))
    }

    @Test fun a_modern_request_needs_the_whole_envelope_and_a_supported_version() {
        // No _meta at all (and no initialize): -32602.
        assertEquals(-32602, errCode(one("""{"jsonrpc":"2.0","id":1,"method":"server/discover"}""")))
        // Version without client capabilities: -32602.
        assertEquals(-32602, errCode(one("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}""")))
        // Unsupported version: -32022 naming what is supported and echoing what was asked.
        val e = one("""{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"v999.0.0","io.modelcontextprotocol/clientCapabilities":{}}}}""")
            .getJSONObject("error")
        assertEquals(-32022, e.getInt("code"))
        assertEquals("v999.0.0", e.getJSONObject("data").getString("requested"))
        assertEquals("2026-07-28", e.getJSONObject("data").getJSONArray("supported").getString(0))
        // clientInfo is optional.
        val noInfo = one("""{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}""")
        assertTrue(noInfo.has("result"))
    }

    @Test fun legacy_methods_and_unknown_methods_under_a_modern_envelope_are_not_found() {
        for (m in listOf("initialize", "ping", "logging/setLevel", "resources/subscribe", "prompts/list", "unknown/method"))
            assertEquals(m, -32601, errCode(modern(9, m)))
    }

    @Test fun tools_list_is_task_level_with_honest_annotations_in_a_fixed_order() {
        val r = modern(2, "tools/list").getJSONObject("result")
        assertEquals("complete", r.getString("resultType"))
        assertTrue(r.has("ttlMs") && r.has("cacheScope"))
        val tools = r.getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(listOf("run_task", "get_task", "stop", "describe_screen", "get_activity"), names)
        // Never a primitive: nothing that taps, types, swipes, sends or approves.
        for (forbidden in listOf("tap", "type", "swipe", "scroll", "send_sms", "make_call", "confirm", "approve", "answer", "secure_done"))
            assertFalse(forbidden, names.any { it.contains(forbidden) })
        fun ann(n: String) = tools.getJSONObject(names.indexOf(n)).getJSONObject("annotations")
        assertFalse(ann("run_task").getBoolean("readOnlyHint")); assertTrue(ann("run_task").getBoolean("destructiveHint"))
        assertTrue(ann("run_task").getBoolean("openWorldHint"))
        for (ro in listOf("get_task", "describe_screen", "get_activity")) {
            assertTrue(ro, ann(ro).getBoolean("readOnlyHint")); assertFalse(ro, ann(ro).getBoolean("destructiveHint"))
        }
        assertFalse(ann("stop").getBoolean("readOnlyHint")); assertFalse(ann("stop").getBoolean("destructiveHint"))
        assertTrue(ann("stop").getBoolean("idempotentHint"))
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            assertTrue("every tool has a description", t.getString("description").isNotBlank())
            assertEquals("object", t.getJSONObject("inputSchema").getString("type"))
            assertFalse("inputs are closed", t.getJSONObject("inputSchema").getBoolean("additionalProperties"))
        }
        // Deterministic: the same list every time.
        assertEquals(tools.toString(), modern(3, "tools/list").getJSONObject("result").getJSONArray("tools").toString())
        // Never paginated: any cursor is invalid.
        assertEquals(-32602, errCode(modern(4, "tools/list", """"cursor":"abc"""")))
    }

    @Test fun run_task_starts_a_remote_task_tagged_with_the_client_and_returns_at_once() {
        val r = call(5, "run_task", """{"goal":"  what is on my screen?  "}""").getJSONObject("result")
        assertEquals("complete", r.getString("resultType"))
        assertFalse(r.getBoolean("isError"))
        val s = r.getJSONObject("structuredContent")
        assertEquals("t1", s.getString("task_id"))
        assertEquals("running", s.getString("status"))
        assertTrue("remote actions are off by default", s.getBoolean("read_only"))
        assertEquals("what is on my screen?" to "remote:device_suite", phone.started.single())
        // Structured content is also serialized as text (MCP SHOULD).
        val content = r.getJSONArray("content")
        assertEquals(s.toString(), content.getJSONObject(1).getString("text"))
        // The audit sees the call, with the task it started — no goal text.
        val c = calls.last()
        assertEquals("tools/call" to "run_task", c.method to c.tool)
        assertEquals("t1", c.taskId)
        assertEquals("device_suite", c.client)
    }

    @Test fun a_busy_phone_or_a_bad_goal_is_a_tool_error_the_model_can_read() {
        call(1, "run_task", """{"goal":"read my last message"}""")
        val busy = call(2, "run_task", """{"goal":"another"}""").getJSONObject("result")
        assertTrue(busy.getBoolean("isError"))
        assertEquals("refused", busy.getJSONObject("structuredContent").getString("status"))
        phone.busy = false
        assertTrue(call(3, "run_task", """{"goal":"   "}""").getJSONObject("result").getBoolean("isError"))
        assertTrue(call(4, "run_task", """{"goal":"${"x".repeat(McpTools.GOAL_MAX + 1)}"}""").getJSONObject("result").getBoolean("isError"))
        assertTrue("wrong type", call(5, "run_task", """{"goal":42}""").getJSONObject("result").getBoolean("isError"))
        assertTrue("unknown argument", call(6, "run_task", """{"goal":"hi","approve":true}""").getJSONObject("result").getBoolean("isError"))
        assertEquals(1, phone.started.size)
    }

    @Test fun malformed_tool_calls_are_invalid_params() {
        assertEquals(-32602, errCode(call(1, "tap", """{"label":"Send"}""")))       // no primitives
        assertEquals(-32602, errCode(call(2, "confirm", """{"yes":true}""")))        // no approval tool
        assertEquals(-32602, errCode(modern(3, "tools/call", """"arguments":{}""")))  // no name
        assertEquals(-32602, errCode(modern(4, "tools/call", """"name":"run_task","arguments":"go"""")))
        assertTrue(phone.started.isEmpty())
    }

    @Test fun get_task_reports_waiting_for_the_phone_user_and_stop_always_works() {
        call(1, "run_task", """{"goal":"text Prince hi"}""")
        phone.waiting = true
        val t = call(2, "get_task", """{"task_id":"t1"}""").getJSONObject("result").getJSONObject("structuredContent")
        assertEquals(TaskStatus.WAITING, t.getString("status"))
        assertEquals("confirmation", t.getString("waiting_for"))
        assertTrue(call(3, "get_task", """{"task_id":"nope"}""").getJSONObject("result").getBoolean("isError"))
        val s = call(4, "stop").getJSONObject("result").getJSONObject("structuredContent")
        assertTrue(s.getBoolean("stopped"))
        assertFalse(call(5, "stop").getJSONObject("result").getJSONObject("structuredContent").getBoolean("stopped"))
        assertEquals(2, phone.stops)
    }

    @Test fun describe_screen_and_get_activity_return_typed_content() {
        val d = call(1, "describe_screen").getJSONObject("result")
        assertTrue(d.getJSONObject("structuredContent").getBoolean("shown"))
        assertTrue(d.getJSONArray("content").getJSONObject(0).getString("text").contains("Wi-Fi"))
        val a = call(2, "get_activity", """{"since":0,"limit":10}""").getJSONObject("result").getJSONObject("structuredContent")
        assertTrue(a.has("audit") && a.has("remote_calls"))
        assertTrue(call(3, "get_activity", """{"limit":1000}""").getJSONObject("result").getBoolean("isError"))
        assertTrue(call(4, "describe_screen", """{"vision":true}""").getJSONObject("result").getBoolean("isError"))
    }

    // ---------------------------------------------------------------- legacy era (initialize handshake)

    @Test fun an_initialize_client_negotiates_a_legacy_version_and_is_served() {
        // Before initialize, a claim-less request is refused (but ping works).
        assertEquals(-32602, errCode(one("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")))
        assertEquals(JSONObject().toString(), one("""{"jsonrpc":"2.0","id":"p","method":"ping"}""").getJSONObject("result").toString())
        val init = one("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"Inspector","version":"0.1"}}}""")
            .getJSONObject("result")
        assertEquals("2025-06-18", init.getString("protocolVersion"))
        assertEquals("tekyerema", init.getJSONObject("serverInfo").getString("name"))
        assertTrue(init.getJSONObject("capabilities").has("tools"))
        assertFalse("legacy results carry no resultType", init.has("resultType"))
        assertTrue(send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").isEmpty())
        assertTrue(session.legacyInitialized)
        val list = one("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").getJSONObject("result")
        assertEquals(5, list.getJSONArray("tools").length())
        assertFalse(list.has("resultType"))
        val run = one("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"run_task","arguments":{"goal":"what time is it"}}}""")
            .getJSONObject("result")
        assertFalse(run.getBoolean("isError"))
        assertEquals("remote:Inspector", phone.started.single().second)
        assertEquals(-32601, errCode(one("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}""")))
    }

    @Test fun an_unknown_legacy_version_is_answered_with_the_latest_legacy_one() {
        val r = one("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2023-01-01","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}""")
        assertEquals("2025-11-25", r.getJSONObject("result").getString("protocolVersion"))
    }

    @Test fun a_discover_probe_does_not_stop_an_initialize_fallback_on_the_same_stream() {
        assertTrue(modern(1, "server/discover").has("result"))
        val init = one("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"h","version":"1"}}}""")
        assertEquals("2025-11-25", init.getJSONObject("result").getString("protocolVersion"))
        // …and modern requests keep working next to it (a dual-era server MAY serve both).
        assertTrue(modern(3, "tools/list").has("result"))
    }

    // ---------------------------------------------------------------- JSON-RPC errors

    @Test fun jsonrpc_errors_follow_the_spec() {
        val parse = one("{not json")
        assertEquals(-32700, errCode(parse)); assertFalse("no id on a parse error", parse.has("id"))
        assertEquals(-32600, errCode(one("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")))   // no batches
        assertEquals(-32600, errCode(one("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")))
        assertEquals(-32600, errCode(one("""{"jsonrpc":"2.0","id":null,"method":"ping"}""")))
        assertEquals(-32600, errCode(one("""{"jsonrpc":"2.0","id":7}""")))
        assertEquals(7, one("""{"jsonrpc":"2.0","id":7}""").getInt("id"))
        assertEquals(-32602, errCode(one("""{"jsonrpc":"2.0","id":8,"method":"ping","params":[1,2]}""")))
        assertTrue("a response to nothing is ignored", send("""{"jsonrpc":"2.0","id":9,"result":{}}""").isEmpty())
        assertTrue("notifications get no reply", send("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""").isEmpty())
        assertTrue(send("   ").isEmpty())
        assertEquals("a string id is echoed", "abc", one("""{"jsonrpc":"2.0","id":"abc","method":"nope"}""").getString("id"))
    }

    @Test fun subscriptions_listen_is_acknowledged_and_never_answered() {
        val out = modern(11, "subscriptions/listen")
        assertEquals("notifications/subscriptions/acknowledged", out.getString("method"))
        assertFalse(out.has("id"))
        assertEquals(11, out.getJSONObject("params").getJSONObject("_meta").getInt("io.modelcontextprotocol/subscriptionId"))
    }

    // ---------------------------------------------------------------- rate limits

    @Test fun run_task_is_rate_limited_per_minute() {
        repeat(McpSession.RUN_TASKS_PER_MIN) { i ->
            phone.busy = false
            assertFalse("task $i", call(i, "run_task", """{"goal":"read the screen $i"}""").getJSONObject("result").getBoolean("isError"))
        }
        phone.busy = false
        val limited = call(99, "run_task", """{"goal":"one more"}""").getJSONObject("result")
        assertTrue(limited.getBoolean("isError"))
        assertEquals("rate_limited", limited.getJSONObject("structuredContent").getString("status"))
        assertEquals(McpSession.RUN_TASKS_PER_MIN, phone.started.size)
        now += 60_000L
        assertFalse(call(100, "run_task", """{"goal":"after a minute"}""").getJSONObject("result").getBoolean("isError"))
    }

    @Test fun tool_calls_are_rate_limited_and_the_limiter_recovers() {
        val limiter = RateLimiter(3, 1_000L) { now }
        assertTrue(limiter.tryAcquire()); assertTrue(limiter.tryAcquire()); assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertTrue(limiter.retryAfterMs() in 1..1_000)
        now += 1_000L
        assertTrue(limiter.tryAcquire())
        val s = McpSession(phone, clock = { now }, toolCallLimit = RateLimiter(2, 10_000L) { now })
        fun c(id: Int) = JSONObject(s.handle("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{$modernMeta,"name":"describe_screen","arguments":{}}}""").single())
        assertFalse(c(1).getJSONObject("result").getBoolean("isError"))
        assertFalse(c(2).getJSONObject("result").getBoolean("isError"))
        assertEquals("rate_limited", c(3).getJSONObject("result").getJSONObject("structuredContent").getString("status"))
    }

    // ---------------------------------------------------------------- no approval path

    @Test fun nothing_on_the_connection_can_approve_a_waiting_confirmation() {
        call(1, "run_task", """{"goal":"text Prince running late"}""")
        phone.waiting = true
        // Everything a hostile host might try while the phone waits for its user's OK.
        val attempts = listOf(
            """{"jsonrpc":"2.0","id":2,"method":"confirm","params":{$modernMeta,"approved":true}}""",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{$modernMeta,"name":"confirm","arguments":{"approved":true}}}""",
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{$modernMeta,"name":"run_task","arguments":{"goal":"yes"}}}""",
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{$modernMeta,"name":"get_task","arguments":{"task_id":"t1","approve":true}}}""",
            """{"jsonrpc":"2.0","id":6,"method":"elicitation/create","params":{$modernMeta,"message":"Approve?"}}""",
            """{"jsonrpc":"2.0","id":7,"method":"sampling/createMessage","params":{$modernMeta}}""",
            """{"jsonrpc":"2.0","id":8,"result":{"action":"accept","content":{"approved":true}}}"""
        )
        for (a in attempts) session.handle(a)
        // The phone is still waiting on its own user; the only thing that reached it is the one task and no approval.
        assertEquals(1, phone.started.size)
        assertEquals(TaskStatus.WAITING, call(9, "get_task", """{"task_id":"t1"}""").getJSONObject("result")
            .getJSONObject("structuredContent").getString("status"))
        // The backend interface has no method that could approve, answer or resume anything (see also InteropArchitectureTest).
        assertNull(RemoteBackend::class.java.methods.firstOrNull { m ->
            Regex("(?i)confirm|approve|answer|revise|secure|resume|accept").containsMatchIn(m.name) })
    }
}
