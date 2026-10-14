package com.tekyerema

import com.tekyerema.money.LastSend
import com.tekyerema.money.MoneyRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's demo flow (14 Oct 2026): a message asks for money; the send; "tell him I've sent it". Fake people only. */
class DemoStepsTest {
    @Test fun the_message_gives_the_amount_the_number_in_the_chat_and_the_name() {
        val r = MoneyRequest.parse("Kofi Demo", "Hi, it's Kofi. Please send me 5 cedis for transport. My MoMo number is 050 000 2222.")!!
        assertEquals("5", r.amount)
        assertEquals("0500002222", r.number)
        assertEquals("Kofi Demo", r.name)
        val s = MoneyRequest.summary(r)
        assertTrue(s, s.contains("5 cedis") && s.contains("0 5 0") && s.contains("Telecel"))
    }

    @Test fun a_named_recipient_and_other_amount_shapes() {
        val r = MoneyRequest.parse("Kofi Demo", "Send GHS 7.50 to Ama Test on 0240001111 please")!!
        assertEquals("7.50", r.amount); assertEquals("0240001111", r.number); assertEquals("Ama Test", r.name)
        assertEquals("5", MoneyRequest.parse("Kofi Demo", "can you send me five cedis")!!.amount)
    }

    @Test fun a_message_that_asks_for_no_money_is_not_a_request() {
        assertNull(MoneyRequest.parse("Kofi Demo", "I'm on my way, see you soon"))
        assertNull(MoneyRequest.parse("Kofi Demo", "Call me when you are free"))
    }

    @Test fun ive_sent_it_says_what_was_sent() {
        LastSend.note("Kofi Demo", "0500002222", "5", "transport")
        assertEquals("I've sent you 5 cedis by Mobile Money. Reference: transport.", LastSend.expand("I've sent it", "Kofi Demo"))
        assertEquals("I've sent 5 cedis to Kofi Demo by Mobile Money. Reference: transport.", LastSend.expand("I have sent it", "Ama Test"))
        assertNull(LastSend.expand("I'm on my way", "Kofi Demo"))
        LastSend.clear()
        assertNull(LastSend.expand("I've sent it", "Kofi Demo"))
    }

    @Test fun no_reference_becomes_payment() {
        assertEquals("Payment", com.tekyerema.money.MoneyFlow.referenceOf("no reference"))
        assertEquals("transport", com.tekyerema.money.MoneyFlow.referenceOf("transport"))
    }

    @Test fun the_reference_is_always_asked_by_voice_and_then_sent_with_the_money() {
        com.tekyerema.core.config.AppConfig.loadFrom(emptyList())
        val q = com.tekyerema.money.MoneyFlow.decide("send 5 cedis to 0240001111", emptyList()) as com.tekyerema.agent.AgentDecision.CallTool
        assertEquals("ask_user", q.name)
        val question = q.args.getString("question")
        assertTrue(question, question.startsWith(com.tekyerema.money.MoneyFlow.REF_PREFIX))
        val history = listOf("ask_user(\"$question\") → the user answered: \"transport\"")
        val go = com.tekyerema.money.MoneyFlow.decide("send 5 cedis to 0240001111", history) as com.tekyerema.agent.AgentDecision.CallTool
        assertEquals(com.tekyerema.money.ussd.UssdNavigatorTool.NAME, go.name)
        assertEquals("transport", go.args.getString("reference"))
    }

    @Test fun send_money_to_him_uses_the_message_that_was_just_read() {
        com.tekyerema.core.config.AppConfig.loadFrom(emptyList())
        MoneyRequest.note(MoneyRequest.parse("Kofi Demo", "Please send me 5 cedis. My MoMo number is 050 000 2222.")!!)
        val history = listOf("ask_user(\"${com.tekyerema.money.MoneyFlow.REF_PREFIX}? For example, say “transport” or “school fees”.\") → the user answered: \"transport\"")
        val go = com.tekyerema.money.MoneyFlow.decide("send money to him", history) as com.tekyerema.agent.AgentDecision.CallTool
        assertEquals("0500002222", go.args.getString("number"))
        assertEquals("5", go.args.getString("amount"))
        assertEquals("Kofi Demo", go.args.getString(com.tekyerema.money.ussd.UssdNavigatorTool.ARG_CONTACT))
        assertTrue(go.args.optBoolean(com.tekyerema.money.ussd.UssdNavigatorTool.ARG_FROM_MESSAGE))
        MoneyRequest.clear()
    }
}
