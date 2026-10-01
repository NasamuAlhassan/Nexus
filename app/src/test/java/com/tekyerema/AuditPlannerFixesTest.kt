package com.tekyerema

import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.DirectAnswer
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.RetrievalNeed
import com.tekyerema.agent.WebFirst
import com.tekyerema.money.GuidedSend
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.ussd.UssdNavigatorTool
import com.tekyerema.tools.GhanaMobileMoney
import com.tekyerema.tools.MoMoSendTool
import com.tekyerema.tools.MobileMoneyTool
import com.tekyerema.tools.ToolArgs
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.Verbalize
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The black-box audit of 2026-10-01 (audit/blackbox-2026-10-01/REPORT.md), planner half: the on-phone rules routed on
 * the NOUN and ignored the verb (S32, C17, R04, R05, S23), an office-holder was answered from the small model's memory
 * (R01), there was no route to a Mobile Money balance (F04, L06), and the read-backs left out the reference (F09).
 */
class AuditPlannerFixesTest {

    private fun decide(goal: String, brain: AgentBrain = LocalBrain()): AgentDecision =
        runBlocking { brain.decide(BrainContext(goal, JSONObject(), emptyList(), JSONArray(), JSONArray())) }

    private fun tool(d: AgentDecision): AgentDecision.CallTool {
        assertTrue("expected a tool call, got $d", d is AgentDecision.CallTool); return d as AgentDecision.CallTool
    }

    private fun deferred(goal: String, reason: String? = null) {
        val d = decide(goal)
        assertTrue("'$goal' should go to the planner, got $d", d is AgentDecision.CannotDo)
        if (reason != null) assertEquals(goal, reason, (d as AgentDecision.CannotDo).reasonClass)
    }

    // ------------------------------------------------------------------ fix 5: the verb decides, not the noun

    @Test fun an_alarm_to_remove_or_ask_about_is_never_set_again() {
        // S32: "delete the 6:30 alarm" began with set_alarm(6:30).
        for (g in listOf("delete the 6:30 alarm", "cancel my 7 am alarm", "remove the alarm for 6", "turn off the 6 o'clock alarm",
                "change the alarm to 7", "stop the alarm", "yi alarm no"))
            deferred(g, LocalBrain.DEFER)
        for (g in listOf("what alarms do I have", "do I have an alarm for 6", "is my alarm on for 7"))
            assertFalse(g, (decide(g) as? AgentDecision.CallTool)?.name == "set_alarm")
        // Setting one is untouched — and a label that merely contains a manage verb AFTER "alarm" is still a new alarm.
        val a = tool(decide("set an alarm for 6:30 am"))
        assertEquals("set_alarm", a.name); assertEquals(6, a.args.getInt("hour")); assertEquals(30, a.args.getInt("minute"))
        assertEquals("set_alarm", tool(decide("set an alarm for 6 to turn off the stove")).name)
    }

    @Test fun a_timer_to_stop_is_never_started_again() {
        for (g in listOf("cancel the 5 minute timer", "stop the 10 minutes timer", "pause the 5 minute timer"))
            deferred(g, LocalBrain.DEFER)
        assertEquals(300, tool(decide("set a timer for 5 minutes")).args.getInt("seconds"))
    }

    @Test fun a_reply_that_names_nobody_is_never_a_text_to_the_last_message() {
        // C17: send_sms(contact = "the last message"). The last text's sender is asked for as "last sender" — send_sms
        // finds them in the inbox and reads them back (or says a service sender can't be replied to).
        for (g in listOf("reply to the last message saying okay thanks", "Respond to the latest text saying okay thanks.",
                "answer my last message with okay thanks", "reply to the last message: okay thanks")) {
            val t = tool(decide(g))
            assertEquals(g, "send_sms", t.name)
            assertEquals(g, LocalBrain.LAST_SENDER, t.args.getString("contact")); assertEquals(g, "okay thanks", t.args.getString("message"))
        }
        assertEquals("last sender", LocalBrain.LAST_SENDER)   // the value send_sms documents for "whoever texted last"
        assertEquals(LocalBrain.Sms("last sender", null, "hi", false), LocalBrain.parseSms("text the last message saying hi"))
        assertEquals("last sender", LocalBrain.parseSms("message the sender saying thanks")!!.contact)
        // A pronoun, "that message", or nobody at all needs the conversation: the planner's, never a guess.
        for (g in listOf("reply to it saying ok", "reply saying ok", "reply back with thank you", "answer that message saying I'm coming",
                "reply to him saying fine", "respond to this saying no"))
            deferred(g, LocalBrain.DEFER)
        assertNull(LocalBrain.parseSms("text him saying hello"))
        assertNull(LocalBrain.parseSms("text that message saying hello"))
        // A named person is still a text, read back before it goes.
        val t = tool(decide("reply to Ama on WhatsApp saying yes"))
        assertEquals("send_sms", t.name); assertEquals("Ama", t.args.getString("contact")); assertEquals("whatsapp", t.args.getString("via"))
        assertEquals("Prince", LocalBrain.parseSms("text Prince saying I'm on my way")!!.contact)
        assertEquals("my brother", LocalBrain.parseSms("text my brother saying hi")!!.contact)
    }

    @Test fun finding_a_place_or_a_thing_never_searches_the_contacts() {
        // R05: "find the nearest pharmacy to me" → search_contacts("nearest pharmacy to me").
        for (g in listOf("find the nearest pharmacy to me", "find a pharmacy near me", "look for the closest ATM", "find a chop bar nearby"))
            deferred(g, LocalBrain.NETWORK)
        for (g in listOf("find a restaurant", "find my phone", "look for cheap flights", "find the meaning of love", "find me a taxi"))
            assertFalse(g, (decide(g) as? AgentDecision.CallTool)?.name == "search_contacts")
        // People are still found.
        val c = tool(decide("find prince in my contacts"))
        assertEquals("search_contacts", c.name); assertEquals("prince", c.args.getString("query"))
        assertEquals("Ama", tool(decide("find Ama")).args.getString("query"))
        assertEquals("Kofi Mensah", tool(decide("look up Kofi Mensah")).args.getString("query"))
        // Said to be a contact in so many words → searched, whatever the name looks like.
        assertEquals("pharmacy man", tool(decide("find the pharmacy man in my contacts")).args.getString("query"))
        assertTrue(LocalBrain.looksLikeContact("find richmond's dad in my contacts", "Richmond's Dad"))
        assertFalse(LocalBrain.looksLikeContact("find the nearest pharmacy to me", "nearest pharmacy to me"))
    }

    @Test fun notifications_to_close_or_change_are_not_read_out() {
        // S23: "close notifications" → read_notifications, then the planner cleared them all.
        for (g in listOf("close notifications", "close the notifications", "clear my notifications", "dismiss the notifications",
                "turn off notifications for WhatsApp", "hide notifications", "open notification settings"))
            assertFalse(g, (decide(g) as? AgentDecision.CallTool)?.name == "read_notifications")
        deferred("close notifications", LocalBrain.DEFER)
        assertEquals("read_notifications", tool(decide("read my notifications")).name)
        assertEquals("read_notifications", tool(decide("any new notifications")).name)
        assertEquals("whatsapp", tool(decide("read my WhatsApp messages")).args.getString("filter"))
    }

    @Test fun a_question_about_prices_is_never_a_purchase() {
        // R04: "compare MTN and Telecel data bundle prices and tell me which is cheaper" → "Open MTN's menu for a data bundle?"
        val r04 = "compare MTN and Telecel data bundle prices and tell me which is cheaper for 5 gigabytes"
        assertNull(TelcoUssd.parse(r04, "mtn"))
        for (g in listOf("which network has cheaper data bundles", "how much is a data bundle", "what is the price of a mashup bundle",
                "data bundle prices"))
            assertNull(g, TelcoUssd.parse(g, "mtn"))
        // Buying is untouched, and a balance is not a price.
        assertEquals("*138#", (TelcoUssd.parse("buy a data bundle", "mtn") as TelcoUssd.Request.Open).flow.code)
        assertEquals("*138#", (TelcoUssd.parse("buy the cheapest data bundle", "mtn") as TelcoUssd.Request.Open).flow.code)
        assertEquals("data_balance", (TelcoUssd.parse("how much data do I have left", "mtn") as TelcoUssd.Request.Open).flow.key)
        assertEquals("data_balance", (TelcoUssd.parse("check my data balance", "mtn") as TelcoUssd.Request.Open).flow.key)
        assertEquals("*141#", (TelcoUssd.parse("what are my offers", "mtn") as TelcoUssd.Request.Open).flow.code)
        // The on-phone brain doesn't take it either: no telco menu, no airtime purchase, no send read-back.
        assertTrue(decide(r04) is AgentDecision.CannotDo)
        deferred("compare airtime prices", LocalBrain.NETWORK)
        assertFalse((decide("which is cheaper to send 50 cedis to 0244123456") as? AgentDecision.CallTool)?.name == "ussd_navigate")
        assertEquals("mobile_money", tool(decide("buy airtime of 5 cedis")).name)
    }

    // ------------------------------------------------------------------ fix 10: balances, on the phone

    @Test fun airtime_balance_is_one_code_in_english_and_twi() {
        for (g in listOf("what is my airtime balance", "What's my airtime balance?", "check my airtime", "how much credit do I have",
                "how much airtime is left", "airtime balance", "me credit aka sɛn", "me airtime aka sɛn", "hwɛ me credit")) {
            val t = tool(decide(g))
            assertEquals(g, "run_ussd", t.name); assertEquals(g, "*124#", t.args.getString("code"))
        }
        // Not a balance: buying, sharing, prices, Mobile Money, a credit card.
        for (g in listOf("buy 5 cedis airtime", "me pɛ sɛ me tɔ credit 1 cedi", "send airtime to Kofi", "how much is 5 cedis airtime",
                "compare airtime prices", "check my credit card balance", "what is my momo balance"))
            assertFalse(g, LocalBrain.isAirtimeBalanceAsk(g.lowercase()))
        // A code the user dialled in words is the code that runs.
        assertEquals("*156#", tool(decide("check my airtime with *156#")).args.getString("code"))
    }

    @Test fun mobile_money_balance_is_a_guided_walk_to_the_pin() {
        for (g in listOf("check my mobile money balance", "what's my momo balance", "What is my MoMo balance?", "momo balance",
                "how much money do I have on momo", "how much is in my momo wallet", "me momo sika yɛ sɛn", "hwɛ me momo balance")) {
            val t = tool(decide(g))
            assertEquals(g, UssdNavigatorTool.NAME, t.name)
            assertEquals(g, UssdNavigatorTool.CHECK_BALANCE, t.args.getString(UssdNavigatorTool.ARG_INTENT))
            assertFalse(g, t.args.has("number")); assertFalse(g, t.args.has("amount"))
        }
        for (g in listOf("what is my momo limit", "how much does momo charge", "send my momo balance to Ama", "open momo",
                "how much can I send with momo", "check my bank balance"))
            assertFalse(g, LocalBrain.isMomoBalanceAsk(g.lowercase()))
        // A send is still a send (no intent → the default).
        val s = tool(decide("send 5 cedis to 0244123456"))
        assertEquals(UssdNavigatorTool.NAME, s.name); assertFalse(s.args.has(UssdNavigatorTool.ARG_INTENT))
        assertEquals("0244123456", s.args.getString("number"))
    }

    @Test fun the_balance_read_back_is_one_plain_question_and_carries_no_send() {
        val nav = JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.CHECK_BALANCE)
        assertEquals("Check your Mobile Money balance? I'll go through the menu; you'll enter your PIN.", UssdNavigatorTool.confirmationPrompt(nav))
        assertTrue(UssdNavigatorTool.isBalance(nav))
        val mm = JSONObject().put("_kind", MobileMoneyTool.KIND_BALANCE)
        assertEquals(UssdNavigatorTool.BALANCE_PROMPT, MobileMoneyTool.confirmationPrompt(mm))
        assertNull("no 'check the number' warning for a balance", MobileMoneyTool.warning(mm))
        // The argument check accepts a balance (no number, no amount) and a send alike; a made-up intent is refused.
        assertNull(ToolArgs.validate(UssdNavigatorTool, JSONObject().put(UssdNavigatorTool.ARG_INTENT, "check_balance")))
        assertNull(ToolArgs.validate(UssdNavigatorTool, JSONObject().put("number", "0244123456").put("amount", "5")))
        assertNotNull(ToolArgs.validate(UssdNavigatorTool, JSONObject().put(UssdNavigatorTool.ARG_INTENT, "cash_out")))
        assertNull(ToolArgs.validate(MobileMoneyTool, mm))
        // A balance check that reaches the PIN is not a send: nothing is counted against the limits or the receipt window.
        GuidedSend.resetForTest()
        GuidedSend.approved("check my momo balance", "mobile_money", mm, now = 1_000L)
        assertFalse(GuidedSend.atPin("check my momo balance", "momo_pin", now = 2_000L))
        GuidedSend.resetForTest()
    }

    // ------------------------------------------------------------------ read-back polish (F03, F09, F16)

    @Test fun read_backs_say_the_reference_and_one_cedi() {
        assertEquals("1 cedi", GhanaMobileMoney.cedisPhrase("1"))
        assertEquals("1 cedi", GhanaMobileMoney.cedisPhrase("1.00"))
        assertEquals("5 cedis", GhanaMobileMoney.cedisPhrase("5"))
        assertEquals("0.1 cedis", GhanaMobileMoney.cedisPhrase("0.1"))
        assertEquals("", GhanaMobileMoney.referencePhrase(null)); assertEquals("", GhanaMobileMoney.referencePhrase("  "))
        val a = JSONObject().put("number", "0552093690").put("amount", "20").put("reference", "school fees").put(MoMoSendTool.ARG_SIM, "mtn")
        for (p in listOf(UssdNavigatorTool.confirmationPrompt(a), MoMoSendTool.confirmationPrompt(a))) {
            assertTrue(p, p.startsWith("Send 20 cedis to 0 5 5, 2 0 9, 3 6 9 0, an MTN number, with Mobile Money, reference “school fees”?\n"))
            assertEquals("still one question", 1, Confirmation.question(p).count { it == '?' })
        }
        // No reference given: the read-back is exactly what it was.
        val plain = JSONObject().put("number", "0552093690").put("amount", "1").put(MoMoSendTool.ARG_SIM, "mtn")
        assertTrue(UssdNavigatorTool.confirmationPrompt(plain).startsWith("Send 1 cedi to 0 5 5, 2 0 9, 3 6 9 0, an MTN number, with Mobile Money?\n"))
        assertTrue(MoMoSendTool.confirmationPrompt(plain).startsWith("Send 1 cedi to 0 5 5, 2 0 9, 3 6 9 0, an MTN number, with Mobile Money?\n"))
        assertTrue(MobileMoneyTool.confirmationPrompt(JSONObject().put("_kind", "buy_airtime").put("_amount", "1")).startsWith("Buy 1 cedi of airtime"))
        // The local brain carries the reference through to the tool that reads it back.
        assertEquals("school fees", tool(decide("send 20 cedis to 0552093690 with reference school fees")).args.getString("reference").lowercase())
    }

    @Test fun amounts_under_a_cedi_are_spoken_as_pesewas() {
        // F16: the figure stays "0.1 cedis" in the text; the voice says it in pesewas.
        val p = UssdNavigatorTool.confirmationPrompt(JSONObject().put("number", "0552093690").put("amount", "0.1").put(MoMoSendTool.ARG_SIM, "mtn"))
        val spoken = Verbalize.apply(p)
        assertTrue(spoken, spoken.contains("ten pesewas")); assertFalse(spoken, spoken.contains("zero point one"))
        assertTrue(Verbalize.apply("Send 1 cedi to Ama with Mobile Money?").contains("one cedi "))
    }

    // ------------------------------------------------------------------ fix 9a: who holds an office is a fresh fact

    @Test fun office_holders_are_searched_not_remembered() {
        // R01: "The president of Ghana is Nana Addo Samuel Kufuor." — from the small model's memory.
        for (q in listOf("who is the president of Ghana", "Who's the current vice president of Ghana?", "who is the Minister of Finance",
                "who is the Black Stars coach", "who is Ghana's president", "what is the name of the Speaker of Parliament",
                "how old is the president of Ghana", "who leads the NPP", "who is the president now", "who is the first lady of Ghana"))
            assertEquals(q, RetrievalNeed.Need.SINGLE, RetrievalNeed.classify(q))
        assertTrue(WebFirst.eligible("who is the president of Ghana", RetrievalNeed.classify("who is the president of Ghana")))
        assertTrue("still a direct question (no screen needed)", DirectAnswer.isKnowledgeQuestion("who is the president of Ghana"))
        // Timeless knowledge and history stay with the model.
        for (q in listOf("What is the capital of Ghana?", "who wrote Things Fall Apart", "Explain inflation",
                "who was the first president of Ghana", "who was the president of Ghana in 1960", "how many regions does Ghana have"))
            assertEquals(q, RetrievalNeed.Need.NONE, RetrievalNeed.classify(q))
        // Prices asked the everyday way are fresh too.
        assertEquals(RetrievalNeed.Need.SINGLE, RetrievalNeed.classify("how much is a bag of cement in Ghana"))
    }

    @Test fun a_price_comparison_is_research_even_when_it_names_data() {
        val r04 = "compare MTN and Telecel data bundle prices and tell me which is cheaper for 5 gigabytes"
        assertTrue(DirectAnswer.isKnowledgeQuestion(r04))
        assertEquals(RetrievalNeed.Need.MULTI, RetrievalNeed.classify(r04))
        assertTrue(DirectAnswer.isKnowledgeQuestion("how much does a phone cost in Ghana"))
        // The user's OWN things are still the phone's business.
        for (q in listOf("compare my data usage with last month", "how much does my data bundle cost", "what is the price of this app",
                "How do I turn on WiFi", "how much data do I have"))
            assertFalse(q, DirectAnswer.isKnowledgeQuestion(q))
    }
}
