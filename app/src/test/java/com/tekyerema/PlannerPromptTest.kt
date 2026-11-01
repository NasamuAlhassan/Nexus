package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.LocalModelBrain
import com.tekyerema.agent.OnDeviceLlm
import com.tekyerema.agent.llm.PlannerPrompt
import com.tekyerema.agent.llm.ToolReply
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device planner's prompt is the agent benchmark's (campaign/agent_bench/bench.py) and its replies parse in every format. */
class PlannerPromptTest {

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
        .put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))

    private val tap = fn("tap", "Touch an item.", JSONObject().put("element_id", JSONObject().put("type", "integer")), emptyList())
    private val sms = fn("send_sms", "Send a text. Example: {\"contact\":\"Ama\"}",
        JSONObject().put("contact", JSONObject().put("type", "string")).put("message", JSONObject().put("type", "string"))
            .put("via", JSONObject().put("type", "string").put("enum", JSONArray(listOf("sms", "whatsapp"))))
            .put("expects", JSONObject().put("type", "string")),
        listOf("message"))

    @Test fun signature_matches_the_benchmark_shape() {
        val s = PlannerPrompt.signature(sms)
        assertTrue(s, s.startsWith("send_sms("))
        assertTrue("required arg plain, optional with ?, enum listed", s.contains("message") && s.contains("contact?") && s.contains("via?:sms|whatsapp"))
        assertTrue("expects is the engine's, not the model's", !s.contains("expects"))
        assertTrue("examples are cut from the description", s.endsWith("Send a text."))
    }

    @Test fun system_prompt_is_the_benchmark_system_plus_tools() {
        val offered = PlannerPrompt.offered(listOf(tap, sms), emptyMap())
        val sys = PlannerPrompt.system(offered)
        assertTrue(sys.startsWith("You are Nexus, an assistant that operates an Android phone for a blind user."))
        assertTrue("done is always offered", sys.contains("\ndone(summary)"))
        assertTrue(sys.endsWith("Reply with JSON only: {\"tool\": \"<name>\", \"args\": {...}}"))
    }

    @Test fun user_turn_renders_goal_history_and_screen() {
        val screen = JSONObject().put("app", "com.whatsapp").put("appName", "WhatsApp").put("screen", "Chats")
            .put("elements", JSONArray()
                .put(JSONObject().put("id", 5).put("role", "ViewGroup").put("label", "Richmond · Bro").put("actions", JSONArray(listOf("tap"))))
                .put(JSONObject().put("id", 9).put("role", "EditText").put("label", "Message").put("actions", JSONArray(listOf("type"))).put("flags", JSONArray(listOf("focused")))))
        val u = PlannerPrompt.userTurn("tell Richmond I'm late", listOf("open_app(app=\"WhatsApp\") -> ok"), screen, memory = listOf("Richmond is on WhatsApp"))
        assertEquals("""What I know (the user's own saved memory: use it; actions still get the usual confirmation):
- Richmond is on WhatsApp

Goal: tell Richmond I'm late

Done so far:
1. open_app(app="WhatsApp") -> ok

Screen (WhatsApp · Chats):
[5] ViewGroup "Richmond · Bro" (tap)
[9] EditText "Message" (type) [focused]

Next action?""", u)
        assertTrue(PlannerPrompt.userTurn("x", emptyList(), null).contains("Done so far:\nnothing yet\n\nScreen: the home screen"))
    }

    @Test fun replies_parse_in_every_model_format() {
        assertEquals("type", ToolReply.parse("""{"tool": "type", "args": {"element_id": 7, "text": "I'll meet you tomorrow"}}""")!!.tool)
        assertEquals("ask_user", ToolReply.parse("<tool_call>\n<function=ask_user>\n<parameter=question>\nWho?\n</parameter>\n</function>\n</tool_call>")!!.tool)
        val lfm = ToolReply.parse("""<|tool_call_start|>[set_alarm(hour=8, minute=30, message="exam")]<|tool_call_end|>ok""")!!
        assertEquals(8L, lfm.args.get("hour")); assertEquals("exam", lfm.args.getString("message"))
        val fg = ToolReply.parse("<start_function_call>call:send_sms{contact:<escape>Richmond<escape>,message:<escape>I'm late, sorry<escape>}<end_function_call>")!!
        assertEquals("I'm late, sorry", fg.args.getString("message"))
        assertEquals("open_app", ToolReply.parse("""<think>hmm {"tool":"x"</think> {"name":"open_app","arguments":"{\"app\":\"Bolt\"}"}""")!!.tool)
        assertNull("plain words are never a call", ToolReply.parse("I think you should open WhatsApp"))
        val cut = ToolReply.parse("""{"tool":"set_timer","args":{"seconds":900},"options":[],"reason":": The user wants a timer and the rice is""")!!
        assertEquals("a reply cut off after complete args still counts", 900, cut.args.getInt("seconds"))
        assertNull("cut-off arguments are never guessed", ToolReply.parse("""{"tool":"send_sms","args":{"contact":"Ama","message":"I'm on m"""))
    }

    private class SessionFake(val replies: ArrayDeque<String>) : OnDeviceLlm {
        override val id = "fake-session"
        val opened = ArrayList<String>()          // the system prompt of each conversation opened
        val sent = ArrayList<String>()            // every user turn, in order
        var held = 0
        override suspend fun complete(prompt: String, maxTokens: Int, timeoutMs: Long): String? = null
        override fun openSession(system: String, maxTokens: Int): com.tekyerema.agent.LlmSession {
            opened.add(system); held = 100
            return object : com.tekyerema.agent.LlmSession {
                override suspend fun send(user: String, jsonSchema: String?, timeoutMs: Long): String? { sent.add(user); held += user.length; return replies.removeFirstOrNull() }
                override val tokens: Int get() = held
                override fun close() {}
            }
        }
    }

    @Test fun one_conversation_per_task_and_each_step_sends_only_what_is_new() = runBlocking {
        val tap = fn("tap", "Touch.", JSONObject().put("element_id", JSONObject().put("type", "integer")), emptyList())
        val llm = SessionFake(ArrayDeque(listOf("""{"tool":"tap","args":{"element_id":3}}""", """{"tool":"done","args":{"summary":"Opened."}}""")))
        val brain = LocalModelBrain(llm)
        val c1 = ctx(tap).copy(goal = "open Ama's chat")
        assertTrue(brain.decide(c1) is AgentDecision.CallTool)
        assertTrue("first turn: goal and screen, no 'Done so far'", llm.sent[0].startsWith("Goal: open Ama's chat") && !llm.sent[0].contains("Done so far"))
        val c2 = c1.copy(history = listOf("tap(element_id=3) → ok: opened the chat"))
        assertTrue(brain.decide(c2) is AgentDecision.Done)
        assertEquals("the system prompt is read once per task", 1, llm.opened.size)
        assertTrue("the next turn carries only the new result", llm.sent[1].startsWith("Result: tap(element_id=3) → ok: opened the chat"))
    }

    private class Fake(val reply: String?) : OnDeviceLlm {
        override val id = "fake"
        var lastSchema: String? = null
        override suspend fun complete(prompt: String, maxTokens: Int, timeoutMs: Long) = reply
        override suspend fun chat(system: String, user: String, jsonSchema: String?, maxTokens: Int, timeoutMs: Long): String? {
            lastSchema = jsonSchema; return reply
        }
    }

    private fun ctx(vararg tools: JSONObject) = BrainContext(goal = "set an alarm", screen = JSONObject(), history = emptyList(),
        geminiTools = JSONArray(), openAiTools = JSONArray(tools.map { JSONObject().put("type", "function").put("function", it) }))

    @Test fun planner_turns_a_reply_into_a_decision_and_never_runs_what_was_not_offered() = runBlocking {
        val alarm = fn("set_alarm", "Set an alarm.", JSONObject().put("hour", JSONObject().put("type", "integer")), listOf("hour"))
        val ussd = fn("run_ussd", "Dial a code.", JSONObject().put("code", JSONObject().put("type", "string")), listOf("code"))
        val d = LocalModelBrain(Fake("""{"tool":"set_alarm","args":{"hour":6}}""")).decide(ctx(alarm, ussd))
        assertTrue(d is AgentDecision.CallTool && d.name == "set_alarm" && d.args.getInt("hour") == 6)
        val done = LocalModelBrain(Fake("""{"tool":"done","args":{"summary":"Your alarm is set for 6."}}""")).decide(ctx(alarm))
        assertTrue(done is AgentDecision.Done && done.summary == "Your alarm is set for 6.")
        val denied = LocalModelBrain(Fake("""{"tool":"run_ussd","args":{"code":"*170#"}}""")).decide(ctx(alarm, ussd))
        assertTrue("USSD is never the model's, even when the step offers it", denied is AgentDecision.Malformed)
        val f = Fake("""{"tool":"set_alarm","args":{"hour":6}}""")
        LocalModelBrain(f).decide(ctx(alarm, ussd))
        assertTrue("decoding is constrained to this step's tools", f.lastSchema!!.contains("set_alarm") && !f.lastSchema!!.contains("run_ussd"))
        assertTrue(LocalModelBrain(Fake(null)).decide(ctx(alarm)) is AgentDecision.CannotDo)
    }
}
