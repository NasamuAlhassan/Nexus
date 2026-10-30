package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.OpenAiCompatBrain
import com.tekyerema.core.PrivacyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The privacy gate in front of the cloud brain (owner, 29 Oct 2026): details go out as tags and come back as values. */
class PrivacyGateTest {
    @Test fun `numbers, emails and account numbers leave as tags`() {
        val s = PrivacyGate.seal("Call 0244123456 or +233 24 412 3456, mail ama@example.com, account 12345678901234")
        assertFalse(s.text.contains("0244123456")); assertFalse(s.text.contains("412 3456"))
        assertFalse(s.text.contains("ama@example.com")); assertFalse(s.text.contains("12345678901234"))
        // the same number, written two ways, is ONE tag: the cloud can still tell they are the same
        assertEquals("Call [NUM2] or [NUM2], mail [EMAIL1], account [ID3]", s.text)
        assertEquals(3, s.count)
    }

    @Test fun `the reply's tags become the real details again`() {
        val s = PrivacyGate.seal("send_sms to 0551234567")
        assertEquals("""{"number":"0551234567"}""", s.open("""{"number":"[NUM1]"}"""))
        assertEquals("[NUM9]", s.open("[NUM9]"))   // a tag this request never made stays as it is
    }

    @Test fun `what the planner needs to act stays`() {
        val plain = """{"max_tokens":1024,"element_id":27,"text":"Tap My Husband","amount":"5 cedis","time":"3:43 PM","ending":"3 6 9 0"}"""
        assertEquals(plain, PrivacyGate.seal(plain).text)
    }

    @Test fun `a tagged tool call comes back usable`() {
        val sealed = PrivacyGate.seal("call 0244123456")
        val reply = """{"choices":[{"message":{"tool_calls":[{"id":"1","type":"function","function":{"name":"make_call","arguments":"{\"number\":\"[NUM1]\"}"}}]}}]}"""
        val d = OpenAiCompatBrain.parseResponse(sealed.open(reply))
        assertTrue(d is AgentDecision.CallTool)
        assertEquals("0244123456", (d as AgentDecision.CallTool).args.optString("number"))
    }
}
