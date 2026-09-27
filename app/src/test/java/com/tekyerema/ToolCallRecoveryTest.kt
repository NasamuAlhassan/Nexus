package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.OpenAiCompatBrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud-brain loop fix: gpt-oss-120b often emits its tool call as prose/JSON in `content` instead of
 * the tool_calls channel. Previously that became Malformed and the agent re-asked to MAX_STEPS. These
 * verify we RECOVER and execute the call from content across the shapes the model uses — and that ordinary
 * prose is NOT mistaken for a call (so a real spoken answer still comes through as Done).
 */
class ToolCallRecoveryTest {

    private fun recover(s: String?) = OpenAiCompatBrain.recoverToolCallFromContent(s)

    // ---- Groq rate-limit pacing: the reset-header parser drives how long a key is cooled ----
    private fun dur(s: String?) = OpenAiCompatBrain.parseDurationMs(s)

    @Test fun parses_rate_limit_reset_durations() {
        assertEquals(547L, dur("547ms"))
        assertEquals(24652L, dur("24.652s"))
        assertEquals(259200L, dur("4m19.2s"))   // 4*60000 + 19200
        assertEquals(60000L, dur("1m0s"))
        assertEquals(2500L, dur("2.5s"))
        assertNull(dur(null)); assertNull(dur("")); assertNull(dur("garbage"))
    }

    @Test fun recovers_bare_json_tool_call() {
        val d = recover("""{"name":"open_app","arguments":{"app":"YouTube"}}""")
        assertTrue(d is AgentDecision.CallTool)
        d as AgentDecision.CallTool
        assertEquals("open_app", d.name); assertEquals("YouTube", d.args.optString("app"))
    }

    @Test fun recovers_fenced_json_with_prose_around_it() {
        val d = recover("Sure, I'll open it.\n```json\n{\"name\": \"open_app\", \"arguments\": {\"app\": \"YouTube\"}}\n```")
        assertTrue(d is AgentDecision.CallTool)
        assertEquals("open_app", (d as AgentDecision.CallTool).name)
    }

    @Test fun recovers_prose_call_form() {
        val d = recover("""I will call open_app({"app":"Settings"}) now.""")
        assertTrue(d is AgentDecision.CallTool)
        assertEquals("Settings", (d as AgentDecision.CallTool).args.optString("app"))
    }

    @Test fun recovers_nested_under_function() {
        val d = recover("""{"function":{"name":"make_call","arguments":{"number":"0552093690"}}}""")
        assertTrue(d is AgentDecision.CallTool)
        assertEquals("make_call", (d as AgentDecision.CallTool).name)
    }

    @Test fun recovers_cannot_do_as_decision() {
        val d = recover("""{"name":"cannot_do","arguments":{"reason_class":"unsafe_or_uncertain","detail":"x"}}""")
        assertTrue(d is AgentDecision.CannotDo)
    }

    @Test fun plain_prose_is_not_a_tool_call() {
        assertNull(recover("I've opened WhatsApp for you."))
        assertNull(recover("You're on the home screen."))
        assertNull(recover(""))
    }

    @Test fun unknown_tool_name_is_not_recovered() {
        // A word that isn't a real tool must not be executed.
        assertNull(recover("""{"name":"frobnicate","arguments":{"x":1}}"""))
    }
}
