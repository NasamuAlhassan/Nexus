package com.tekyerema

import com.tekyerema.money.LastSend
import com.tekyerema.money.MoneyRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's demo flow (14 Oct 2026): a message asks for money; the send; "tell him I've sent it". Fake people only. */
class DemoStepsTest {
    @Test fun another_networks_name_in_brackets_is_not_the_person_paid() {
        val other = com.tekyerema.money.MoMoOutcome.parse("Payment made for GHS 5.00 to 0500002222 (Telecel). Fee charged: GHS 0.50. " +
            "Current Balance: GHS 7.00. Reference: transport.")!!
        assertNull(other.party)
        assertEquals("0500002222", other.partyNumber)
        val person = com.tekyerema.money.MoMoOutcome.parse("Payment made for GHS 5.00 to KOFI DEMO 0240001111. Current Balance: GHS 7.50. " +
            "Reference: transport. Transaction ID: 123456.")!!
        assertEquals("KOFI DEMO", person.party)
    }

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
        // A wrong PIN / not enough funds: never "I've sent you" afterwards — the truth, read back before it goes.
        LastSend.note("Kofi Demo", "0500002222", "5", "transport")
        LastSend.markFailed()
        assertEquals("I tried to send you 5 cedis by Mobile Money, but it didn't go through.", LastSend.expand("I've sent it", "Kofi Demo"))
        LastSend.note("Kofi Demo", "0500002222", "5", "fuel")   // a new send starts clean
        assertEquals("I've sent you 5 cedis by Mobile Money. Reference: fuel.", LastSend.expand("I've sent it", "Kofi Demo"))
        LastSend.clear()
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

    @Test fun a_new_request_of_another_kind_is_not_taken_as_the_answer_to_a_waiting_question() {
        val n = com.tekyerema.voice.understand.AnswerOrNew
        // the rehearsal's cases: a send asked "How much?" / "Who?" and the user moved on
        assertTrue(n.isNewRequest("now call him", "send money to Kofi"))
        assertTrue(n.isNewRequest("tell him I've sent it", "send Kofi Demo the money"))
        assertTrue(n.isNewRequest("read his message", "send money to him"))
        assertTrue(n.isNewRequest("okay open WhatsApp", "call Ama"))
        // answers stay answers
        assertTrue(!n.isNewRequest("Kofi Demo", "send money to him"))
        assertTrue(!n.isNewRequest("five cedis", "send money to him"))
        assertTrue(!n.isNewRequest("send it to Kofi", "send money to him"))
        assertTrue(!n.isNewRequest("send 5 cedis", "send money to him"))
        assertTrue(!n.isNewRequest("call Kofi Demo", "call Kofi"))
        assertTrue(!n.isNewRequest("school fees", "send money to him"))
        assertTrue(!n.isNewRequest("call him", null))                 // no task to compare with: an answer
        assertEquals(com.tekyerema.voice.understand.AnswerOrNew.Kind.MESSAGE, n.kindOf("send him a message"))
        assertEquals(com.tekyerema.voice.understand.AnswerOrNew.Kind.MONEY, n.kindOf("send him the money"))
    }

    @Test fun the_recognisers_eh_at_the_edges_is_dropped_but_words_inside_stay() {
        val f = com.tekyerema.voice.EdgeFillers
        assertEquals("read my message please", f.strip("eh read my message please eh"))
        assertEquals("send money to him please", f.strip("eh send money to him please"))
        assertEquals("now call him", f.strip("now call him eh"))
        assertEquals("kenkan me nkra no", f.strip("eh kenkan me nkra no eh"))
        assertEquals("call Kofi.", f.strip("Umm, call Kofi."))
        assertEquals("tell Kofi eh I'm coming", f.strip("tell Kofi eh I'm coming"))
        assertEquals("call Emma", f.strip("call Emma"))
        assertEquals("send it to Uber", f.strip("send it to Uber"))
        assertEquals("eh", f.strip("eh"))
        // …and what is left still goes the phone's own way
        assertTrue(com.tekyerema.agent.FastIntentClassifier.classify("read my message please") is com.tekyerema.agent.FastIntent.ReadMessage)
    }

    @Test fun a_correction_after_no_is_a_change_of_amount_never_a_plain_no() {
        val prompt = "Send 5 cedis to My wife, ending 3 6 9 0, an MTN number, with Mobile Money, reference food?"
        for (said in listOf("no, 10 cedis", "no 10", "daabi 10 cedis", "no, ten cedis")) {
            assertTrue(said, com.tekyerema.voice.Confirmation.looksLikeRevision(said, prompt))
        }
        assertEquals(false, com.tekyerema.voice.Confirmation.looksLikeRevision("no", prompt))
        assertEquals(false, com.tekyerema.voice.Confirmation.looksLikeRevision("no, cancel", prompt))
    }

    @Test fun reading_the_message_a_person_sent_is_the_phones_own_read() {
        val c = com.tekyerema.agent.FastIntentClassifier
        for (say in listOf("read the message my wife sent", "read the message my wife sent me", "read my wife's message",
            "read the last message from my wife", "please read the message that my wife texted")) {
            val i = c.classify(say)
            assertTrue("$say -> $i", i is com.tekyerema.agent.FastIntent.ReadMessage)
            assertTrue(say, (i as com.tekyerema.agent.FastIntent.ReadMessage).from!!.endsWith("wife"))
        }
        assertEquals(null, (c.classify("read my message") as com.tekyerema.agent.FastIntent.ReadMessage).from)
    }

    @Test fun send_money_to_him_please_is_still_the_message_request() {
        com.tekyerema.core.config.AppConfig.loadFrom(emptyList())
        MoneyRequest.note(MoneyRequest.parse("Kofi Demo", "Please send me 5 cedis. My MoMo number is 050 000 2222.")!!)
        val history = listOf("ask_user(\"${com.tekyerema.money.MoneyFlow.REF_PREFIX}? For example, say “transport” or “school fees”.\") → the user answered: \"transport\"")
        val go = com.tekyerema.money.MoneyFlow.decide("send money to him please", history) as com.tekyerema.agent.AgentDecision.CallTool
        assertEquals("0500002222", go.args.getString("number"))
        MoneyRequest.clear()
    }

    @Test fun send_money_to_kofi_after_kofi_demos_message_is_that_request_but_another_kofi_is_not() {
        com.tekyerema.core.config.AppConfig.loadFrom(emptyList())
        val r = MoneyRequest.parse("Kofi Demo", "Please send me 5 cedis. My MoMo number is 050 000 2222.")!!
        MoneyRequest.note(r)
        val history = listOf("ask_user(\"${com.tekyerema.money.MoneyFlow.REF_PREFIX}? For example, say “transport” or “school fees”.\") → the user answered: \"transport\"")
        val go = com.tekyerema.money.MoneyFlow.decide("send money to Kofi", history) as com.tekyerema.agent.AgentDecision.CallTool
        assertEquals("0500002222", go.args.getString("number"))
        assertEquals("Kofi Demo", go.args.getString(com.tekyerema.money.ussd.UssdNavigatorTool.ARG_CONTACT))
        assertTrue(com.tekyerema.money.MoneyFlow.sameAsSender("kofi", r))
        assertTrue(!com.tekyerema.money.MoneyFlow.sameAsSender("Kofi Sample", r))
        assertTrue(!com.tekyerema.money.MoneyFlow.sameAsSender("Kof", r))
        assertTrue(com.tekyerema.money.MoneyFlow.sameAsSender("Kofe", r))          // the Twi path's misheard name
        assertTrue(!com.tekyerema.money.MoneyFlow.sameAsSender("Kwame", r))
        assertTrue(!com.tekyerema.money.MoneyFlow.sameAsSender("Kofe Sample", r))
        val go2 = com.tekyerema.money.MoneyFlow.decide("send money to Kofe",
            listOf("ask_user(\"${com.tekyerema.money.MoneyFlow.REF_PREFIX}? For example, say “transport” or “school fees”.\") → the user answered: \"transport\"")) as com.tekyerema.agent.AgentDecision.CallTool
        assertEquals("Kofi Demo", go2.args.getString(com.tekyerema.money.ussd.UssdNavigatorTool.ARG_CONTACT))
        MoneyRequest.clear()
    }

    @Test fun the_demo_said_in_twi_maps_to_the_same_commands() {
        val g = { t: String -> com.tekyerema.language.TwiCommands.gloss(t) }
        assertEquals("read my message", g("kenkan me nkra no"))
        assertEquals("send money to him", g("soma no sika"))
        assertEquals("tell him I've sent it", g("ka kyerɛ no sɛ masoma"))
        assertEquals("now call him", g("frɛ no seesei"))
        assertEquals("read my messages", g("kenkan me nkra"))   // the plural catch-up is unchanged
        // 19 Oct 2026: the recogniser wrote "nkran" for "nkra" (owner's live session) — still the demo commands
        assertEquals("read my message", g("kenkan me nkran no"))
        assertEquals("read the message my wife sent", g("kenkan nkran a me yere somaa me no"))
        assertEquals("read the message my wife sent", g("kenkan nkra a me yere somaa me"))
        assertEquals("read the message my wife sent", g("kenkan me yere nkran"))
        // the rehearsal's other phrasings (twi 11: "kenkan ne nkra" went to the translator and the cloud instead)
        assertEquals("read his message", g("kenkan ne nkra"))
        assertEquals("send money to him", g("fa sika no kɔma no"))
        assertEquals("now call him", g("frɛ no"))
        // the recogniser's "eh" at the edges is dropped before the Twi is read (voice/EdgeFillers runs first)
        assertEquals("read my message", g(com.tekyerema.voice.EdgeFillers.strip("eh kenkan me nkra no eh")))
    }

    @Test fun the_lab_is_retried_with_backoff_inside_a_time_limit() = kotlinx.coroutines.runBlocking {
        var clock = 0L
        val replies = ArrayDeque(listOf(502, 502, 200))
        var calls = 0
        val r = com.tekyerema.language.LabRetry.send(budgetMs = 14_000L, now = { clock }) {
            calls++; clock += 2_000L; com.tekyerema.language.HttpReply(replies.removeFirst(), ByteArray(0), null, null)
        }
        assertEquals(200, r.code); assertEquals(3, calls)
        // A slow 502 (8 s) leaves no time for another 8-s try inside 10 s: given up after one.
        clock = 0L; calls = 0
        val slow = com.tekyerema.language.LabRetry.send(budgetMs = 10_000L, now = { clock }) {
            calls++; clock += 8_000L; com.tekyerema.language.HttpReply(502, ByteArray(0), null, null)
        }
        assertEquals(502, slow.code); assertEquals(1, calls)
        // A refusal (4xx) is never retried.
        calls = 0
        com.tekyerema.language.LabRetry.send(budgetMs = 14_000L, now = { 0L }) { calls++; com.tekyerema.language.HttpReply(403, ByteArray(0), null, null) }
        assertEquals(1, calls)
    }
}
