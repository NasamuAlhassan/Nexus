package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.FinalActionGuard
import com.tekyerema.agent.MessageFidelity
import com.tekyerema.policy.ActionSemantics
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S-05: a message the user dictated goes out in the user's words — a planner that rephrases is corrected or disclosed. */
class MessageFidelityTest {
    @Test fun `what the request dictates`() {
        assertEquals("I'm on my way", MessageFidelity.dictated("text Prince saying I'm on my way")!!.raw)
        assertEquals("meeting moved to 3", MessageFidelity.dictated("message Prince: meeting moved to 3")!!.raw)
        assertEquals("I don't have a cedi", MessageFidelity.dictated("tell Kofi that I don't have a cedi")!!.raw)
        val tell = MessageFidelity.dictated("tell Kofi that he should call me")!!
        assertEquals("he should call me", tell.raw); assertEquals("you should call me", tell.adapted)
        assertEquals("he should call me", MessageFidelity.dictated("send a message to Prince and tell him that he should call me")!!.raw)
        assertEquals("I'm on my way", MessageFidelity.dictated("tell Ama I'm on my way")!!.raw)
        // nothing dictated: a relay, a bare request, no message at all
        assertNull(MessageFidelity.dictated("tell Ama to call me"))
        assertNull(MessageFidelity.dictated("send Ama a message"))
        assertNull(MessageFidelity.dictated("open settings and go to the battery page"))
    }

    @Test fun `the same words in other clothes are left alone`() {
        assertTrue(MessageFidelity.sameWords("I'm on my way", "i'm on my way!"))
        assertTrue(MessageFidelity.sameWords("I’m on my way.", "I'm on my way"))
        assertTrue(MessageFidelity.sameWords("Meeting moved to 3", "meeting moved to 3."))
        assertEquals(false, MessageFidelity.sameWords("I'm on my way", "I am on my way now"))
        assertEquals(false, MessageFidelity.sameWords("", ""))
    }

    @Test fun `a planner send_sms with other words is sent with the dictated words`() {
        val goal = "text Prince saying I'm on my way"
        val rephrased = AgentDecision.CallTool("send_sms", JSONObject().put("contact", "Prince").put("message", "I am on my way now."), "planner")
        val fixed = FinalActionGuard.withUsersWords(rephrased, goal) as AgentDecision.CallTool
        assertEquals("I'm on my way", fixed.args.getString("message"))
        assertTrue(fixed.rationale!!.contains("replaced by the user's own words"))
        // the same words with the planner's punctuation: untouched
        val same = AgentDecision.CallTool("send_sms", JSONObject().put("contact", "Prince").put("message", "I'm on my way!"), "planner")
        assertEquals("I'm on my way!", (FinalActionGuard.withUsersWords(same, goal) as AgentDecision.CallTool).args.getString("message"))
        // a tell-form: the recipient's pronouns adapted by the planner count as the user's words; a rewrite does not
        val tellGoal = "tell Kofi that he should call me"
        val adapted = AgentDecision.CallTool("send_sms", JSONObject().put("contact", "Kofi").put("message", "You should call me."), "planner")
        assertEquals("You should call me.", (FinalActionGuard.withUsersWords(adapted, tellGoal) as AgentDecision.CallTool).args.getString("message"))
        val rewritten = AgentDecision.CallTool("send_sms", JSONObject().put("contact", "Kofi").put("message", "Please give me a call when free."), "planner")
        assertEquals("you should call me", (FinalActionGuard.withUsersWords(rewritten, tellGoal) as AgentDecision.CallTool).args.getString("message"))
        // nothing dictated: the planner's composition stands
        val relay = AgentDecision.CallTool("send_sms", JSONObject().put("contact", "Ama").put("message", "Please call me when you can."), "planner")
        assertEquals("Please call me when you can.", (FinalActionGuard.withUsersWords(relay, "tell Ama to call me") as AgentDecision.CallTool).args.getString("message"))
        // other tools are not touched
        val call = AgentDecision.CallTool("make_call", JSONObject().put("contact", "Ama"), "planner")
        assertEquals(call, FinalActionGuard.withUsersWords(call, goal))
    }

    @Test fun `a Send tap over a composer holding other words reads both`() {
        val chat = Screens.chat(composer = "I am on my way now")
        val send = chat.element(Screens.SEND_ID)!!
        val goal = "send a WhatsApp message to Ama saying I'm on my way"
        val prompt = ActionSemantics.confirmation("tap", send, "Send", chat, goal = goal)!!
        assertTrue(prompt, prompt.startsWith("The message in the box says “I am on my way now” — you said “I'm on my way”."))
        assertTrue(prompt, prompt.contains("Send it"))
        // the dictated words in the box: the ordinary read-back
        val ok = ActionSemantics.confirmation("tap", send, "Send", Screens.chat(composer = "I'm on my way"), goal = goal)!!
        assertTrue(ok, ok.startsWith("Send this message"))
        // no goal / nothing dictated: unchanged behaviour
        assertTrue(ActionSemantics.confirmation("tap", send, "Send", chat)!!.startsWith("Send this message"))
        assertTrue(ActionSemantics.confirmation("tap", send, "Send", chat, goal = "tell Ama to call me")!!.startsWith("Send this message"))
    }
}
