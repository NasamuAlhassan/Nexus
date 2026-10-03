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
        // S32: "delete the 6:30 alarm" began with set_alarm(6:30). Since 3 October 2026 (QA D08) a cancel WITH a time is
        // the Clock's own DISMISS_ALARM request (cancel_alarm) — never set_alarm, never a list driven by hand.
        for ((g, hm) in listOf("delete the 6:30 alarm" to (6 to 30), "cancel my 7 am alarm" to (7 to 0), "remove the alarm for 6" to (6 to 0),
                "turn off the 6 o'clock alarm" to (6 to 0), "cancel the 6:30 pm alarm" to (18 to 30))) {
            val c = tool(decide(g))
            assertEquals(g, "cancel_alarm", c.name); assertEquals(g, hm.first, c.args.getInt("hour")); assertEquals(g, hm.second, c.args.getInt("minute"))
        }
        // No time, a change, "all" or a Twi form: still the planner's.
        for (g in listOf("change the alarm to 7", "stop the alarm", "yi alarm no", "cancel the alarm", "cancel all my alarms"))
            deferred(g, LocalBrain.DEFER)
        for (g in listOf("what alarms do I have", "do I have an alarm for 6", "is my alarm on for 7"))
            assertFalse(g, (decide(g) as? AgentDecision.CallTool)?.name == "set_alarm")
        // Setting one is untouched — and a label that merely contains a manage verb AFTER "alarm" is still a new alarm.
        val a = tool(decide("set an alarm for 6:30 am"))
        assertEquals("set_alarm", a.name); assertEquals(6, a.args.getInt("hour")); assertEquals(30, a.args.getInt("minute"))
        assertEquals("set_alarm", tool(decide("set an alarm for 6 to turn off the stove")).name)
    }

    // QA D11, 3 October 2026: "What is Richmond's phone number" went to the cloud, which answered "The phone number I found
    // is 024 616 8805" — the match was Richmond's Dad, and the name was dropped. Now a phone look-up, answered with the name.
    @Test fun a_contacts_number_question_is_looked_up_on_the_phone_and_answered_with_the_name() {
        for ((g, who) in listOf("What is Richmond's phone number" to "Richmond", "what's Ama's number?" to "Ama",
                "Tell me Kofi Mensah's mobile number" to "Kofi Mensah", "what is the phone number of Prince Melanin" to "Prince Melanin")) {
            val c = tool(decide(g))
            assertEquals(g, "search_contacts", c.name); assertEquals(g, who, c.args.getString("query"))
        }
        val b = LocalBrain()
        assertEquals("Richmond's Dad: 0246168805.", b.contactAnswer("Matches for 'Richmond': Richmond's Dad — 0246168805"))
        assertEquals("I have 2: Prince Ice P: +233536523611; Princess: +233538185281.",
            b.contactAnswer("Matches for 'Prince': Prince Ice P — +233536523611; Princess — +233538185281"))
        assertEquals("Volume up.", b.contactAnswer("Volume up."))
        // Not a contact: the planner's.
        assertFalse((decide("what is the number of the president") as? AgentDecision.CallTool)?.name == "search_contacts")
    }

    // QA R_D08 (3 October 2026): Samsung Clock answered DISMISS_ALARM by opening its list with the alarm still ON; the tool
    // reads the row's state and flips the row's own switch.
    @Test fun the_clock_list_says_whether_the_alarm_is_on_and_which_element_switches_it() {
        val b = com.tekyerema.accessibility.Box(0, 0, 100, 100)
        fun row(on: Boolean) = com.tekyerema.accessibility.UiSnapshot("com.sec.android.app.clockpackage", "Alarm", listOf(
            com.tekyerema.accessibility.UiElement(9, "android.view.ViewGroup", "06 45 , Sunday, 4 October, ${if (on) "On" else "Off"}, Double tap to edit.", b, clickable = true),
            com.tekyerema.accessibility.UiElement(10, "android.widget.Switch", "06 45 , Sunday, 4 October", b, clickable = true, checkable = true, checked = on),
            com.tekyerema.accessibility.UiElement(11, "android.widget.Switch", "06 00 , Sunday, 4 October", b, clickable = true, checkable = true, checked = true)), 0L)
        assertEquals(com.tekyerema.tools.CancelAlarmTool.AlarmState.ON, com.tekyerema.tools.CancelAlarmTool.alarmState(row(true), 6, 45))
        assertEquals(10, com.tekyerema.tools.CancelAlarmTool.switchFor(row(true), 6, 45)?.id)
        assertEquals(com.tekyerema.tools.CancelAlarmTool.AlarmState.OFF, com.tekyerema.tools.CancelAlarmTool.alarmState(row(false), 6, 45))
        assertEquals(com.tekyerema.tools.CancelAlarmTool.AlarmState.GONE, com.tekyerema.tools.CancelAlarmTool.alarmState(row(true), 7, 15))
    }

    // QA R_TW03 (3 October 2026): the network refused a text (no airtime) — the tool said so and declared a dead end, yet
    // the router handed the task to the cloud as a "deviation", which drove Samsung Messages for 9 model calls.
    @Test fun a_dead_end_the_tool_declared_ends_the_task_with_its_own_line() {
        val line = "send_sms({contact:Prince Melanin}) → fail [native]; verified=false (action failed); The text to Prince Melanin was not sent — " +
            "the network didn't accept it; the SIM may be out of airtime. — this route is a genuine dead-end (environment-blocked); only call cannot_do if NO other route"
        assertEquals("The text to Prince Melanin was not sent — the network didn't accept it; the SIM may be out of airtime.",
            com.tekyerema.agent.RoutingBrain.deadEnd(listOf(line)))
        assertNull(com.tekyerema.agent.RoutingBrain.deadEnd(listOf("send_sms({}) → fail [native]; verified=false (x); No contact matches 'Ama'. — re-observe the new screen and try a DIFFERENT approach")))
        assertNull(com.tekyerema.agent.RoutingBrain.deadEnd(listOf("send_sms({}) → success [native]; verified=true (sent)")))
    }

    @Test fun an_app_that_is_not_installed_is_said_on_the_phone() {
        val line = "open_app({app:Signal}) → fail [none]; verified=false (action failed); I couldn't find an app called Signal on this phone. " +
            "It isn't installed, or the name was misheard. Ask the user which app they meant (ask_user). — only the user can settle this"
        assertEquals("I couldn't find an app called Signal on this phone — it isn't installed, or I misheard the name. Say it again if I got it wrong.",
            com.tekyerema.agent.RoutingBrain.appNotInstalled(listOf(line)))
        assertNull(com.tekyerema.agent.RoutingBrain.appNotInstalled(listOf("open_app({app:WhatsApp}) → success [intent]; verified=true (foreground)")))
        assertNull(com.tekyerema.agent.RoutingBrain.appNotInstalled(listOf("tap({}) → fail [node]; I couldn't find an app called X on this phone")))
    }

    @Test fun a_dead_end_preflight_ends_with_the_user_facing_part_only() {
        val line = "send_sms({contact:last sender}) → fail [preflight]; verified=false (not attempted); Your last text is from MTN — a service " +
            "sender that can't receive replies, so I haven't sent anything. Tell the user that; don't open Messages to try. — this route is a genuine dead-end (environment-blocked); only call cannot_do if NO other route"
        assertEquals("Your last text is from MTN — a service sender that can't receive replies, so I haven't sent anything.",
            com.tekyerema.agent.RoutingBrain.deadEnd(listOf(line)))
        assertEquals("I couldn't find Brent in your contacts.", com.tekyerema.agent.RoutingBrain.userFacing(
            "I couldn't find Brent in your contacts. Ask the user for the number (ask_user) — don't search other apps for a number to call."))
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

/** The natural-language round of 3 October 2026 (audit/qa-2026-10-03/nl1.sh): N08, N10, N12. */
class NaturalLanguageRoundTest {
    private fun decide(goal: String) = kotlinx.coroutines.runBlocking {
        com.tekyerema.agent.LocalBrain().decide(com.tekyerema.agent.BrainContext(goal, org.json.JSONObject(), emptyList(), org.json.JSONArray(), org.json.JSONArray()))
    }

    @org.junit.Test fun `tell X on WhatsApp that … is a message on the phone`() {
        val d = decide("tell Prince Melanin on WhatsApp that I'll call later") as com.tekyerema.agent.AgentDecision.CallTool
        org.junit.Assert.assertEquals("send_sms", d.name)
        org.junit.Assert.assertEquals("Prince Melanin", d.args.getString("contact"))
        org.junit.Assert.assertEquals("I'll call later", d.args.getString("message"))
        org.junit.Assert.assertEquals("whatsapp", d.args.optString("via"))
    }

    @org.junit.Test fun `wake me at 6 tomorrow is an alarm`() {
        val d = decide("wake me at 6 tomorrow") as com.tekyerema.agent.AgentDecision.CallTool
        org.junit.Assert.assertEquals("set_alarm", d.name); org.junit.Assert.assertEquals(6, d.args.getInt("hour")); org.junit.Assert.assertEquals(0, d.args.getInt("minute"))
        org.junit.Assert.assertEquals("set_alarm", (decide("wake me up at 6:30 am") as com.tekyerema.agent.AgentDecision.CallTool).name)
    }

    @org.junit.Test fun `a correction inside the sentence replaces the amount or the person`() {
        val a = com.tekyerema.money.MoneyAsk.parse("send 10 pesewas to Prince Melanin, actually make it 20")!!
        org.junit.Assert.assertEquals("0.2", a.amount); org.junit.Assert.assertEquals("Prince Melanin", a.name)
        val b = com.tekyerema.money.MoneyAsk.parse("send 10 pesewas to Prince Melanin, no wait, 2 cedis")!!
        org.junit.Assert.assertEquals("2", b.amount); org.junit.Assert.assertEquals("Prince Melanin", b.name)
        val c = com.tekyerema.money.MoneyAsk.parse("send 10 pesewas to Prince Melanin, I mean Kofi")!!
        org.junit.Assert.assertEquals("0.1", c.amount); org.junit.Assert.assertEquals("Kofi", c.name)
        val d = com.tekyerema.money.MoneyAsk.parse("send 10 pesewas to 0552093690, actually 0508797323")!!
        org.junit.Assert.assertEquals("0508797323", d.number)
        // no correction: unchanged
        org.junit.Assert.assertEquals("Prince Melanin", com.tekyerema.money.MoneyAsk.parse("send 10 pesewas to Prince Melanin")!!.name)
    }
}

class WhoSentItTest {
    @org.junit.Test fun `who sent it is answered from what was just read`() {
        com.tekyerema.agent.LocalBrain.noteRead("Recent notifications:\n• Telegram — DSATuz | TopTierPrep | ACE your exam")
        org.junit.Assert.assertEquals("It was from DSATuz, on Telegram.", com.tekyerema.agent.LocalBrain.whoSentIt())
        val d = kotlinx.coroutines.runBlocking { com.tekyerema.agent.LocalBrain().decide(com.tekyerema.agent.BrainContext("who sent it?", org.json.JSONObject(), emptyList(), org.json.JSONArray(), org.json.JSONArray())) }
        org.junit.Assert.assertEquals("It was from DSATuz, on Telegram.", (d as com.tekyerema.agent.AgentDecision.Done).summary)
        com.tekyerema.agent.LocalBrain.noteRead("Volume up.")   // not a reading: the last one stays
        org.junit.Assert.assertEquals("It was from DSATuz, on Telegram.", com.tekyerema.agent.LocalBrain.whoSentIt())
    }
}

class LocalPlanReadTest {
    @org.junit.Test fun `open an app and read it says the reading`() = kotlinx.coroutines.runBlocking {
        val b = com.tekyerema.agent.LocalBrain()
        fun ctx(h: List<String>) = com.tekyerema.agent.BrainContext("open WhatsApp and read my messages", org.json.JSONObject().put("app", "com.whatsapp"), h, org.json.JSONArray(), org.json.JSONArray())
        val s1 = b.decide(ctx(emptyList())) as com.tekyerema.agent.AgentDecision.CallTool
        org.junit.Assert.assertEquals("open_app", s1.name)
        val h1 = listOf("open_app({app:WhatsApp}) → success [intent]; verified=true (foreground is com.whatsapp)")
        val s2 = b.decide(ctx(h1)) as com.tekyerema.agent.AgentDecision.CallTool
        org.junit.Assert.assertTrue(s2.name, s2.name.startsWith("read_"))
        val h2 = h1 + "${s2.name}({}) → success [none]; verified=false (x); You're in WhatsApp. Back. Prince Melanin. Video call."
        val end = b.decide(ctx(h2)) as com.tekyerema.agent.AgentDecision.Done
        org.junit.Assert.assertTrue(end.summary, end.summary.contains("You're in WhatsApp"))
    }

    @org.junit.Test fun `who sent it skips the app's own notification and reads App dash Sender`() {
        com.tekyerema.agent.LocalBrain.noteRead("Recent notifications:\n• Tɛkyerɛma: Your last text is from MTN — a service sender\n• Telegram — DSATuz | TopTierPrep | x")
        org.junit.Assert.assertEquals("It was from DSATuz, on Telegram.", com.tekyerema.agent.LocalBrain.whoSentIt())
        com.tekyerema.agent.LocalBrain.noteRead("Recent notifications:\n• MTN: Your data bundle balance")
        org.junit.Assert.assertEquals("It was a notification from MTN.", com.tekyerema.agent.LocalBrain.whoSentIt())
    }
}

class Round2Build12Test {
    @org.junit.Test fun `a reading's own words are never the assistant's claim`() {
        val reading = "You're in WhatsApp. Back. Prince Melanin. Read. Sent. Received sticker. Double tap to view."
        val history = listOf(
            "open_app({app:WhatsApp}) → success [intent]; verified=true (foreground is com.whatsapp)",
            "read_screen({}) → success [none]; verified=false (observation); $reading")
        org.junit.Assert.assertNull(com.tekyerema.agent.ClaimCheck.check("open WhatsApp and read my messages", "WhatsApp is open. $reading", history))
        // the assistant's OWN claim is still caught
        org.junit.Assert.assertNotNull(com.tekyerema.agent.ClaimCheck.check("open WhatsApp and tell Ama hi", "I sent it to Ama.", history))
    }

    @org.junit.Test fun `the Twi day and date questions are phone facts`() {
        val g = com.tekyerema.language.TwiCommands::gloss
        org.junit.Assert.assertEquals("what day is it today", g("Kyerɛ me sɛ ɛnnɛ yɛ da bɛn", { false }))
        org.junit.Assert.assertEquals("what day is it today", g("Ɛnnɛ yɛ da bɛn?", { false }))
        org.junit.Assert.assertEquals("what is the date today", g("ɛnnɛ yɛ da a ɛtɔ so sɛn", { false }))
        org.junit.Assert.assertEquals(com.tekyerema.agent.FastIntent.Day, com.tekyerema.agent.FastIntentClassifier.classify("what day is it today"))
        org.junit.Assert.assertEquals(com.tekyerema.agent.FastIntent.Date, com.tekyerema.agent.FastIntentClassifier.classify("what is the date today"))
    }

    @org.junit.Test fun `bro is a whole word - Brown is not my brother`() {
        org.junit.Assert.assertFalse(com.tekyerema.android.ContactResolver.ownsFamilyWord("Brown", "bro"))
        org.junit.Assert.assertTrue(com.tekyerema.android.ContactResolver.ownsFamilyWord("Bro Ganiu", "bro"))
        org.junit.Assert.assertTrue(com.tekyerema.android.ContactResolver.ownsFamilyWord("My brother", "brother"))
        org.junit.Assert.assertFalse(com.tekyerema.android.ContactResolver.ownsFamilyWord("Meshark's brother", "brother"))
    }

    @org.junit.Test fun `a family choice is asked in English`() {
        val c = com.tekyerema.tools.ContactChoice.build("my brother", listOf(
            com.tekyerema.android.ContactResolver.Contact("Bro Ganiu", "0241"), com.tekyerema.android.ContactResolver.Contact("Bro Kwe", "0242")))
        org.junit.Assert.assertEquals("Which one is your brother: Bro Ganiu, or Bro Kwe?", c.question)
        org.junit.Assert.assertEquals("Which Prince: A, or B?", com.tekyerema.tools.ContactChoice.build("Prince", listOf(
            com.tekyerema.android.ContactResolver.Contact("A", "1"), com.tekyerema.android.ContactResolver.Contact("B", "2"))).question)
    }
}

class OwnNotificationsTest {
    private fun rec(pkg: String, text: String, ongoing: Boolean = false) =
        com.tekyerema.notifications.NotificationRecord("k$pkg", pkg, "t", text, 0L, false, null, appLabel = pkg, ongoing = ongoing)

    @org.junit.Test fun `read_notifications never reads the app's own lines`() {
        val all = listOf(rec("com.tekyerema", "Okay, I've left it."), rec("com.google.android.googlequicksearchbox", "26° in Accra"),
            rec("com.whatsapp", "Checking for new messages…", ongoing = true))
        val kept = com.tekyerema.tools.ReadNotificationsTool.readable(all, "com.tekyerema")
        org.junit.Assert.assertEquals(listOf("com.google.android.googlequicksearchbox"), kept.map { it.packageName })
    }
}

class ResumeAfterReadingTest {
    /** The engine clears the reading cursor when a new request starts (AgentEngine.submitGoal); this pins the contract the
     *  fix relies on: with no reading pending, a bare "carry on" resumes the paused task. */
    @org.junit.Test fun `carry on resumes the paused task once the old reading is superseded`() {
        com.tekyerema.agent.ScreenReadCursor.start(listOf("part one", "part two"), "com.whatsapp|chat")
        org.junit.Assert.assertTrue(com.tekyerema.agent.ScreenReadCursor.pending)
        org.junit.Assert.assertFalse(com.tekyerema.agent.journal.ResumeIntent.shouldResume("carry on", true, com.tekyerema.agent.ScreenReadCursor.pending))
        // a new request ("Send a Telegram message to Richmond …") is neither a continue nor a read → the engine clears the cursor
        org.junit.Assert.assertFalse(com.tekyerema.agent.ScreenReadIntent.isContinue("Send a Telegram message to Richmond saying recovery test"))
        com.tekyerema.agent.ScreenReadCursor.clear()
        org.junit.Assert.assertTrue(com.tekyerema.agent.journal.ResumeIntent.shouldResume("carry on", true, com.tekyerema.agent.ScreenReadCursor.pending))
        org.junit.Assert.assertTrue(com.tekyerema.agent.ScreenReadIntent.isContinue("carry on"))
    }
}

class CancelAlarmSamsungListTest {
    private val b = com.tekyerema.accessibility.Box(0, 0, 100, 100)
    private fun el(id: Int, role: String, label: String, checkable: Boolean = false, checked: Boolean = false) =
        com.tekyerema.accessibility.UiElement(id, role, label, b, clickable = true, checkable = checkable, checked = checked)
    /** The A20e's Clock list on 3 October 2026, 19:05: two 06:00 alarms ON (a full hour is shown as "06 ,"), one 06:50 OFF. */
    private val list = com.tekyerema.accessibility.UiSnapshot("com.sec.android.app.clockpackage", "Clock", listOf(
        el(2, "android.widget.Button", "Add alarm"),
        el(6, "android.view.ViewGroup", "06 , Sunday, 4 October, On, Double tap to edit."),
        el(7, "android.widget.Switch", "06 , Sunday, 4 October", checkable = true, checked = true),
        el(9, "android.view.ViewGroup", "Wake up, 06 , Sunday, 4 October, On, Double tap to edit."),
        el(11, "android.widget.TextView", "06:00"),
        el(13, "android.widget.Switch", "Wake up, 06 , Sunday, 4 October", checkable = true, checked = true),
        el(15, "android.view.ViewGroup", "06 50 , Sunday, 4 October, Off, Double tap to edit"),
        el(18, "android.widget.Switch", "06 50 , Sunday, 4 October", checkable = true, checked = false)), 0L)
    private val editor = com.tekyerema.accessibility.UiSnapshot("com.sec.android.app.clockpackage", "Edit alarm", listOf(
        el(1, "android.widget.NumberPicker", "6"), el(2, "android.widget.NumberPicker", "00"), el(3, "android.widget.Button", "Save")), 0L)

    @org.junit.Test fun `a full-hour row is found and every ON switch at that time is the target - never the row or the text`() {
        org.junit.Assert.assertTrue(com.tekyerema.tools.SetAlarmTool.showsTime("06 , Sunday, 4 October, On", 6, 0))
        org.junit.Assert.assertFalse(com.tekyerema.tools.SetAlarmTool.showsTime("06 50 , Sunday, 4 October", 6, 0))
        org.junit.Assert.assertEquals(com.tekyerema.tools.CancelAlarmTool.AlarmState.ON, com.tekyerema.tools.CancelAlarmTool.alarmState(list, 6, 0))
        org.junit.Assert.assertEquals(listOf(7, 13), com.tekyerema.tools.CancelAlarmTool.switchesFor(list, 6, 0).map { it.id })
        org.junit.Assert.assertEquals(com.tekyerema.tools.CancelAlarmTool.AlarmState.OFF, com.tekyerema.tools.CancelAlarmTool.alarmState(list, 6, 50))
        org.junit.Assert.assertTrue(com.tekyerema.tools.CancelAlarmTool.listShowing(list))
    }

    @org.junit.Test fun `the editor is not the list - no switch, and nothing to verify from`() {
        org.junit.Assert.assertTrue(com.tekyerema.tools.CancelAlarmTool.switchesFor(editor, 6, 0).isEmpty())
        org.junit.Assert.assertFalse(com.tekyerema.tools.CancelAlarmTool.listShowing(editor))
        org.junit.Assert.assertEquals(com.tekyerema.tools.CancelAlarmTool.AlarmState.GONE, com.tekyerema.tools.CancelAlarmTool.alarmState(editor, 6, 0))
    }
}
