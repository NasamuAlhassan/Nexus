package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.OpenAiCompatBrain
import com.tekyerema.tools.ToolArgs
import com.tekyerema.tools.defaultTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-06 — tool arguments are validated against the declared schema; the parser knows every registered tool. */
class ToolArgsTest {
    private val tools = defaultTools().associateBy { it.name }
    private fun check(tool: String, json: String): Pair<String?, JSONObject> {
        val a = JSONObject(json)
        return ToolArgs.validate(tools.getValue(tool), a) to a
    }

    @Test fun missing_required_and_blank_values_are_rejected() {
        assertTrue(check("type", """{}""").first!!.contains("'text'"))
        assertTrue(check("reply_to_notification", """{"sender":"Ama"}""").first!!.contains("'message'"))
        assertTrue(check("run_ussd", """{"code":"  "}""").first!!.contains("empty"))
        assertNull(check("tap", """{"label":"Send"}""").first)          // tap's target params are all optional
    }

    @Test fun enums_are_normalised_or_rejected() {
        val (e1, a1) = check("key_event", """{"key":"Quick Settings"}""")
        assertNull(e1); assertEquals("quick_settings", a1.getString("key"))
        val (e2, a2) = check("scroll", """{"direction":" DOWN "}""")
        assertNull(e2); assertEquals("down", a2.getString("direction"))
        assertTrue(check("scroll", """{"direction":"sideways"}""").first!!.contains("must be one of"))
    }

    @Test fun scalars_are_coerced_and_structures_rejected() {
        val (e1, a1) = check("set_alarm", """{"hour":"7","minute":"30"}""")
        assertNull(e1); assertEquals(7, a1.getInt("hour")); assertEquals(30, a1.getInt("minute"))
        val (e2, a2) = check("set_volume", """{"level":"mute"}""")        // tool accepts words too: left alone
        assertNull(e2); assertEquals("mute", a2.getString("level"))
        val (e3, a3) = check("type", """{"text":"hello","submit":"yes"}""")
        assertNull(e3); assertEquals(true, a3.getBoolean("submit"))
        val (e4, a4) = check("momo_send", """{"number":"0241234567","amount":0.1}""")
        assertNull(e4); assertEquals("0.1", a4.getString("amount"))
        assertNotNull(check("type", """{"text":["a","b"]}""").first)
    }

    @Test fun every_registered_tool_is_known_to_the_parser() {
        for (name in tools.keys) assertTrue(name, name in AgentDecision.knownTools)
        // Previously missing from the hard-coded list: a prose call to them was not recovered.
        for (name in listOf("momo_send", "mobile_money", "play_music", "open_setting", "pinch")) {
            val d = OpenAiCompatBrain.recoverToolCallFromContent("""$name({"x":1})""")
            assertTrue("$name should be recovered from prose", d is AgentDecision.CallTool && d.name == name)
        }
        assertTrue(AgentDecision.fromText("""momo_send({"number":"024"})""") is AgentDecision.Malformed)
    }

    @Test fun unparseable_tool_arguments_become_a_rejection_not_an_empty_call() {
        val resp = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
            .put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject()
                .put("name", "type").put("arguments", "{\"text\": \"hi\"")))))))
        val d = OpenAiCompatBrain.parseResponse(resp.toString())
        assertTrue(d is AgentDecision.CallTool && d.argError != null)
        // Fenced / trailing-prose arguments are repaired, not rejected.
        val fenced = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
            .put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject()
                .put("name", "type").put("arguments", "```json\n{\"text\": \"hi\"}\n```")))))))
        val f = OpenAiCompatBrain.parseResponse(fenced.toString())
        assertTrue(f is AgentDecision.CallTool && f.argError == null && f.args.getString("text") == "hi")
    }
}
