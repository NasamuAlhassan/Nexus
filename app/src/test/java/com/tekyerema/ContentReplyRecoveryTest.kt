package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AppMention
import com.tekyerema.agent.OpenAiCompatBrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two false-"done" defects the emulator's multi suite exposed on 13 October 2026 (`storage_left`): the planner answered
 * "how much storage space is left on my phone" with a JSON plan in `content` and no tool call — spoken as the answer and the
 * task marked COMPLETED — after "on my phone" had already opened the Phone app as "the app the goal names".
 */
class ContentReplyRecoveryTest {
    private val planReply = """{
      "plan": [
        {"action": "open_app", "app": "Settings", "expects": "foreground_is: Settings"},
        {"action": "tap", "label": "Storage", "expects": "element_present: Storage"},
        {"action": "read_state", "vision": true, "expects": "field_contains: storage"}
      ]
    }"""

    @Test fun a_json_plan_in_the_content_becomes_its_first_action_with_the_plan_attached() {
        val d = OpenAiCompatBrain.recoverToolCallFromContent(planReply)
        assertTrue("$d", d is AgentDecision.CallTool)
        d as AgentDecision.CallTool
        assertEquals("open_app", d.name)
        assertEquals("Settings", d.args.getString("app"))
        assertEquals("foreground_is: Settings", d.expects)
        // The plan rides on the decision (lifted out of the arguments by fromToolCall), never into the tool.
        assertTrue("plan=${d.plan}", d.plan!!.contains("tap Storage") && d.plan!!.contains("read_state"))
        assertTrue(!d.args.has("plan") && !d.args.has("expects"))
        // Fenced, with prose around it, still recovered.
        val fenced = "Here is what I will do:\n```json\n$planReply\n```"
        assertTrue(OpenAiCompatBrain.recoverToolCallFromContent(fenced) is AgentDecision.CallTool)
    }

    @Test fun json_that_is_not_a_tool_call_is_never_spoken_as_the_answer() {
        val d = AgentDecision.fromText("""{"thought": "I should open Settings", "next": "later"}""")
        assertTrue("$d", d is AgentDecision.Malformed)
        assertTrue(AgentDecision.fromText("[1, 2, 3]") is AgentDecision.Malformed)
        assertTrue(AgentDecision.fromText("```json\n{\"a\": 1}\n```") is AgentDecision.Malformed)
        // A sentence is still an answer.
        assertTrue(AgentDecision.fromText("You have about 12 GB free.") is AgentDecision.Done)
    }

    @Test fun my_phone_is_the_device_not_the_phone_app() {
        assertEquals(emptyList<String>(), AppMention.candidates("how much storage space is left on my phone"))
        assertEquals(emptyList<String>(), AppMention.candidates("what is the android version on this phone"))
        // "phone bigger" may be captured as a phrase (no such app; the strict resolver drops it) — but never bare "phone".
        assertTrue(AppMention.candidates("make the text on my phone bigger").none { it == "phone" || it == "my phone" })
        assertEquals(listOf("phone"), AppMention.candidates("open the phone app and call Ama"))
        assertEquals(listOf("phone"), AppMention.candidates("open phone and call Ama"))
        assertEquals(listOf("chrome"), AppMention.candidates("search chrome for lake volta"))
    }
}
