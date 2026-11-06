package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.Invariants
import com.tekyerema.agent.Rephrase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mistakes that must not happen whatever the brain decided (owner, 3 Nov 2026) — agent/Invariants + agent/Rephrase. */
class InvariantsTest {
    @After fun reset() = Rephrase.clear()

    private fun screen(app: String, vararg labels: String) = JSONObject().put("app", app).put("elements", JSONArray().apply {
        labels.forEachIndexed { i, l -> put(JSONObject().put("id", i + 1).put("role", "ViewGroup").put("label", l)) }
    })
    private fun tap(id: Int) = AgentDecision.CallTool("tap", JSONObject().put("element_id", id), null)
    private fun question(d: AgentDecision) = ((d as? AgentDecision.CallTool)?.takeIf { it.name == "ask_user" })?.args?.optString("question")

    @Test fun a_channel_the_user_did_not_ask_for_is_never_opened_unasked() {
        // the A20e case: "Richmond" misheard as "reach money"; the search showed a broadcast channel
        val s = screen("org.telegram.messenger", "Search", "Reach Money Tips\n12,400 subscribers", "Richmond Apenensu")
        val q = question(Invariants.apply(tap(2), "send reach money a message on telegram", emptyList(), s))
        assertNotNull(q); assertTrue(q!!, "channel" in q)
        // …but asked for by the user, a channel is fine
        val ok = Invariants.apply(tap(2), "open the reach money channel on telegram", emptyList(), s)
        assertEquals("tap", (ok as AgentDecision.CallTool).name)
    }

    @Test fun a_person_the_user_never_named_is_asked_about_once_then_the_answer_decides() {
        val s = screen("com.whatsapp", "Chats", "Ama Owusu", "Kofi Mensah")
        val goal = "send a message to rich man saying I'm coming"
        val q = question(Invariants.apply(tap(2), goal, emptyList(), s))
        assertEquals("I found “Ama Owusu”. Is that who you mean?", q)
        // named → no question
        val named = Invariants.apply(tap(3), "message Kofi on WhatsApp saying I'm coming", emptyList(), s)
        assertEquals("tap", (named as AgentDecision.CallTool).name)
        // answered yes → goes on; answered no → asks who; asked who already → stops honestly
        val yes = listOf("ask_user(\"$q\") → the user answered: \"yes\"")
        assertEquals("tap", (Invariants.apply(tap(2), goal, yes, s) as AgentDecision.CallTool).name)
        val no = listOf("ask_user(\"$q\") → the user answered: \"no, Richmond\"")
        assertTrue(question(Invariants.apply(tap(2), goal, no, s))!!.startsWith("Who do you want"))
        val again = no + "ask_user(\"Who do you want? Say the name as it's saved in your phone.\") → the user answered: \"him\""
        assertTrue(Invariants.apply(tap(2), goal, again, s) is AgentDecision.CannotDo)
    }

    @Test fun reading_a_named_persons_messages_never_opens_another_chat_unasked() {
        // A20e 6 Nov 2026: asked for Richmond's last Telegram message, the brain opened the group "Learn Python Coding" and
        // reported its pinned post as Richmond's. Rows here are as the OCR names them (one line: name, time, preview).
        val s = screen("org.telegram.messenger", "Search", "Learn Python Coding, Thu, Learn Python Coding pinned a photo",
            "Richmond Apenensu, Ok see you, 10:42", "Ama Owusu, see you, 09:10")
        val goal = "what was the last message Richmond sent me on telegram"
        val group = question(Invariants.apply(tap(2), goal, emptyList(), s))
        assertNotNull(group); assertTrue(group!!, "Learn Python Coding" in group && "channel or a group" in group)
        val other = question(Invariants.apply(tap(4), goal, emptyList(), s))
        assertEquals("I found “Ama Owusu”. Is that whose messages you mean?", other)
        assertEquals("tap", (Invariants.apply(tap(3), goal, emptyList(), s) as AgentDecision.CallTool).name)
        assertTrue(Invariants.readsFromSomeone("read my messages from Ama")); assertTrue(Invariants.readsFromSomeone("did Prince text me"))
        assertFalse(Invariants.readsFromSomeone("read my latest WhatsApp message")); assertFalse(Invariants.readsFromSomeone("send Ama a message"))
    }

    @Test fun reading_messages_and_app_controls_are_not_slowed_down() {
        val s = screen("com.whatsapp", "Chats", "Ama Owusu", "Search")
        assertEquals("tap", (Invariants.apply(tap(2), "read my latest WhatsApp message", emptyList(), s) as AgentDecision.CallTool).name)
        assertEquals("tap", (Invariants.apply(tap(3), "send Ama a message", emptyList(), s) as AgentDecision.CallTool).name)
        // outside a chat app nothing here applies
        val settings = screen("com.android.settings", "Wi-Fi", "Bluetooth")
        assertEquals("tap", (Invariants.apply(tap(1), "send it to wifi", emptyList(), settings) as AgentDecision.CallTool).name)
        assertFalse(Invariants.looksLikeName("new chat")); assertTrue(Invariants.looksLikeName("Richmond Apenensu"))
    }

    @Test fun a_rephrase_marks_the_last_try_wrong_and_blocks_repeating_it() {
        val open = AgentDecision.CallTool("open_app", JSONObject().put("app", "Maps"), null)
        Rephrase.observe("how do I get from where I am to the great hall", listOf("open_app(${open.args}) → success"), null, now = 1_000)
        val note = Rephrase.noteFor("no, I want the walking directions to the great hall", now = 20_000)
        assertNotNull(note); assertTrue(note!!, "NOT what they wanted" in note && "open_app" in note)
        val d = Invariants.apply(open, "no, I want the walking directions to the great hall", emptyList(), null)
        assertTrue(question(d)!!.startsWith("Last time I did that"))
        // a different action is fine
        val walk = AgentDecision.CallTool("walk_directions", JSONObject().put("destination", "the great hall"), null)
        assertEquals("walk_directions", (Invariants.apply(walk, "no, I want the walking directions to the great hall", emptyList(), null)
            as AgentDecision.CallTool).name)
    }

    @Test fun a_money_request_is_never_turned_into_a_text_or_a_call() {
        val sms = AgentDecision.CallTool("open_intent", JSONObject().put("intent", "sms_compose").put("to", "Prince"), null)
        assertTrue(Invariants.apply(sms, "send 1 cedi to Prince", emptyList(), null) is AgentDecision.CannotDo)
        val dial = AgentDecision.CallTool("make_call", JSONObject().put("number", "130"), null)
        assertFalse((Invariants.apply(dial, "send 1 cedi to Prince", emptyList(), null) as? AgentDecision.CallTool)?.name == "make_call")
        // a message that only talks about money is a message
        val text = AgentDecision.CallTool("send_sms", JSONObject().put("contact", "Kofi").put("message", "I'll send you 5 cedis tomorrow"), null)
        assertEquals("send_sms", (Invariants.apply(text, "text Kofi saying I'll send you 5 cedis tomorrow", emptyList(), null)
            as AgentDecision.CallTool).name)
    }

    @Test fun a_message_goes_through_the_app_the_user_named() {
        fun send(via: String?) = AgentDecision.CallTool("send_sms", JSONObject().put("contact", "Kofi").put("message", "The meeting has moved to 3")
            .apply { if (via != null) put("via", via) }, null)
        fun via(d: AgentDecision) = (d as AgentDecision.CallTool).args.optString("via")
        // the v5 bench miss: "WhatsApp Kofi …" planned as a plain SMS
        assertEquals("whatsapp", via(Invariants.apply(send(null), "WhatsApp Kofi that the meeting has moved to 3", emptyList(), null)))
        assertEquals("whatsapp", via(Invariants.apply(send("sms"), "on whats app tell Kofi the meeting moved to three", emptyList(), null)))
        assertEquals("sms", via(Invariants.apply(send("whatsapp"), "send Kofi an SMS saying the meeting has moved to 3", emptyList(), null)))
        // no channel said: the brain's choice stands
        assertEquals("", via(Invariants.apply(send(null), "tell Kofi the meeting has moved to 3", emptyList(), null)))
        assertEquals("whatsapp", via(Invariants.apply(send("whatsapp"), "tell Kofi the meeting has moved to 3", emptyList(), null)))
    }

    @Test fun never_says_sent_while_the_screen_says_not_sent() {
        // the v5 bench miss (done-failed-02): WhatsApp shows "Not sent. Tap to retry." under the typed message
        val history = listOf("type({\"element_id\":9,\"text\":\"I'll send it tomorrow.\"}) → success", "tap({\"element_id\":9}) → success: pressed Send")
        val failed = screen("com.whatsapp", "Richmond", "Richmond: Bro, when are you sending the 50? 9:15 AM",
            "You: I'll send it tomorrow. 9:31 AM Not sent. Tap to retry.", "Retry", "Message")
        val d = Invariants.apply(AgentDecision.Done("I sent your message to Richmond."), "tell Richmond I'll send it tomorrow", history, failed)
        assertTrue(d is AgentDecision.CannotDo)
        assertTrue((d as AgentDecision.CannotDo).detail, "did not go" in d.detail)
        // an honest reply stands
        val honest = AgentDecision.Done("The message didn't go through; it says not sent.")
        assertEquals(honest, Invariants.apply(honest, "tell Richmond I'll send it tomorrow", history, failed))
        // an older failed message in the chat doesn't block today's sent one
        val oldFail = screen("com.whatsapp", "You: see you at 6 Not sent. Tap to retry.", "You: I'll send it tomorrow. 9:31 AM")
        val ok = AgentDecision.Done("I sent your message to Richmond.")
        assertEquals(ok, Invariants.apply(ok, "tell Richmond I'll send it tomorrow", history, oldFail))
    }

    @Test fun a_short_command_said_again_or_a_new_topic_is_not_a_rephrase() {
        Rephrase.observe("volume up", listOf("quick_volume_up() → done"), "Volume 8.", now = 1_000)
        assertNull(Rephrase.noteFor("volume up", now = 3_000))
        assertNull(Rephrase.noteFor("what's the weather in Kumasi", now = 3_000))
        assertNull(Rephrase.noteFor("no, I said turn the volume up more", now = 200_000))   // too late
        assertTrue(Rephrase.isRedo("no, I said turn it up more", now = 5_000))
    }
}
