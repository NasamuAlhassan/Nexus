package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.BankUssd
import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.MoneyFlow
import com.tekyerema.money.TelcoGuide
import com.tekyerema.money.TelcoSession
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.TelcoUssdTool
import com.tekyerema.money.UssdSpeech
import com.tekyerema.money.ussd.SemanticMatch
import com.tekyerema.money.ussd.TxIntent
import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.SpokenText
import com.tekyerema.voice.io.Hypothesis
import com.tekyerema.voice.io.NBest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The owner's live runs on the A20e, 2 October 2026 02:38–02:42 — every screen and every heard sentence is REAL.
 *
 *  - "what's my airtime balance": answered with no "shall I?" — but as seven lines ("…No data bonus. No BOR. No just 4 u.
 *    Welcome to the new world."); the owner asked again in the middle of it.
 *  - "check my MOMO balance": no "shall I?", paged the main menu with MTN's own "#", My Wallet → Check Balance → PIN in
 *    12 s. The balance MTN then showed was never read out.
 *  - Airtime for another number: heard as "send airtime I'm 10 pesos airtime to a telegram number" and proposed as 10
 *    CEDIS of airtime for the user's own phone. With the number said, the walk asked at "Airtime / Schedule Airtime" and
 *    again at "Self / Others / Welcome Pack / Other Networks".
 */
class LiveRunsBalanceAirtimeTest {
    @Before fun setUp() { AppConfig.loadFrom(emptyList()); TelcoGuide.resetForTest() }
    @After fun tearDown() { AppConfig.loadFrom(emptyList()); TelcoGuide.resetForTest() }

    private val AIRTIME_MENU = "Enjoy exclusive 100% bonus airtime only on the MoMo App 1) Airtime 2) Internet Bundles 3) Fixed Broadband 4) Schedule Airtime 0) Back"
    private val WHO_MENU = "Airtime 1) Self 2) Others 3) Welcome Pack 4) Other Networks 0) Back"
    private fun sure(v: SemanticMatch.Verdict?) = v != null && v.confidence >= SemanticMatch.actBar() && v.margin >= SemanticMatch.marginBar()

    // ---------------------------------------------------------------- what was heard

    @Test fun ten_pesos_is_ten_pesewas() {
        assertEquals("send 10 pesewas to Kofi", MoneyAsk.heard("send 10 pesos to Kofi"))
        assertEquals("ten pesewas of airtime", MoneyAsk.heard("ten pesos of airtime"))
        assertEquals("a real currency question is left alone", "convert 10 pesos to cedis", MoneyAsk.heard("convert 10 pesos to cedis"))
        assertEquals("send 10 cedis to Kofi", MoneyAsk.heard("send 10 cedis to Kofi"))
    }

    @Test fun airtime_for_a_number_not_yet_said_asks_for_it_and_is_never_for_the_users_own_phone() {
        for (said in listOf("send airtime I'm 10 pesos airtime to a telegram number", "I want you to send 10 pesos of airtime to a telegram number",
                "send 10 pesewas airtime to a telephone number", "buy 1 cedi airtime for another number", "buy 1 cedi airtime for someone")) {
            val a = MoneyAsk.parse(said)
            assertEquals(said, MoneyAsk.Kind.AIRTIME, a?.kind)
            assertFalse(said, a!!.forSelf)
            assertNull(said, a.number); assertNull(said, a.name)
            val d = MoneyFlow.decide(said, emptyList()) as AgentDecision.CallTool
            assertEquals(said, "ask_user", d.name)
            assertTrue(said, d.args.getString("question").startsWith("Who should I send the airtime to?"))
        }
        assertEquals("0.1", MoneyAsk.parse("send airtime I'm 10 pesos airtime to a telegram number")!!.amount)
        // With the number said (the owner's third try): ten pesewas, to that number.
        val full = MoneyAsk.parse("I want you to send 10 pesos of airtime to 050-879-7323 it is a telephone number")!!
        assertEquals("0.1", full.amount)
        assertEquals("0508797323", full.number)
        // A send to "another number" asks who, too.
        val send = MoneyFlow.decide("send 10 pesewas to another number", emptyList()) as AgentDecision.CallTool
        assertEquals("ask_user", send.name)
        // Someone who IS named is untouched.
        assertEquals("Prince Melanin", MoneyAsk.parse("send 1 cedi airtime to Prince Melanin")!!.name)
    }

    @Test fun an_app_is_never_the_object_of_a_money_request() {
        val vocab = EntityCorrector.Vocab.of(contacts = listOf("Kofi Mensah"), apps = listOf("Telegram", "WhatsApp"))
        assertEquals("telephone is not corrected to Telegram", 0, NBest.pick(listOf(
            Hypothesis("send airtime I'm 10 pesos airtime to a telephone number", 0.89f),
            Hypothesis("send airtime I'm 10 pesos airtime to a telegram number", 0.88f)), vocab))
        assertEquals("a contact still is", 1, NBest.pick(listOf(
            Hypothesis("send 10 cedis to coffee", 0.9f), Hypothesis("send 10 cedis to Kofi", 0.8f)), vocab))
        assertEquals("and outside money an app still is", 1, NBest.pick(listOf(
            Hypothesis("open telegraph", 0.9f), Hypothesis("open telegram", 0.8f)), vocab))
    }

    // ---------------------------------------------------------------- 03:23 — the request with no verb, and what the cloud model did with it

    @Test fun an_amount_and_the_word_airtime_are_a_request_even_with_no_verb() {
        // Heard: "10 pesos airtime to a telephone number". It fell through to the cloud model.
        val said = MoneyAsk.heard("10 pesos airtime to a telephone number")
        val a = MoneyAsk.parse(said)!!
        assertEquals(MoneyAsk.Kind.AIRTIME, a.kind)
        assertEquals("0.1", a.amount)
        assertFalse(a.forSelf); assertNull(a.number)
        assertTrue(((MoneyFlow.decide(said, emptyList()) as AgentDecision.CallTool).args.getString("question")).startsWith("Who should I send the airtime to?"))
        assertEquals("5", MoneyAsk.parse("5 cedis airtime")!!.amount)
        assertTrue(MoneyAsk.parse("5 cedis airtime")!!.forSelf)
        for (not in listOf("airtime", "my airtime is finished", "airtime prices"))
            assertNull(not, MoneyAsk.parse(not))
    }

    @Test fun a_planners_money_call_is_walked_by_the_navigator_not_by_the_model() {
        // What the model proposed at 03:23:08 — with a number the user never said in that request.
        val proposed = AgentDecision.CallTool("mobile_money", org.json.JSONObject().put("_kind", "buy_airtime").put("_amount", "0.10")
            .put("_number", "0508797323"), "cloud")
        val d = MoneyFlow.fromPlanner(proposed, "10 pesewas airtime to a telephone number") as AgentDecision.CallTool
        assertEquals(com.tekyerema.money.ussd.UssdNavigatorTool.NAME, d.name)
        assertEquals(com.tekyerema.money.ussd.UssdNavigatorTool.SEND_AIRTIME, d.args.getString("intent"))
        assertEquals("0508797323", d.args.getString("number"))
        assertTrue("a number the user never said is flagged for the read-back", d.args.optBoolean(com.tekyerema.money.ussd.UssdNavigatorTool.ARG_UNSAID))
        d.args.put(com.tekyerema.tools.MoMoSendTool.ARG_SIM, "mtn")
        assertTrue(com.tekyerema.money.ussd.UssdNavigatorTool.confirmationPrompt(d.args)
            .contains("from your Mobile Money wallet?\nYou didn't say this number — I chose it myself, so check it."))
        // The fixed driver and a plain send go the same way; a number that WAS said is not flagged.
        val send = MoneyFlow.fromPlanner(AgentDecision.CallTool("momo_send", org.json.JSONObject().put("number", "0552093690").put("amount", "5"), "cloud"),
            "please help me send five cedis to 0552093690 thank you") as AgentDecision.CallTool
        assertEquals(com.tekyerema.money.ussd.UssdNavigatorTool.NAME, send.name)
        assertFalse(send.args.optBoolean(com.tekyerema.money.ussd.UssdNavigatorTool.ARG_UNSAID))
        val named = MoneyFlow.fromPlanner(AgentDecision.CallTool("mobile_money", org.json.JSONObject().put("_kind", "send_money").put("_amount", "5")
            .put("_recipient", "Prince Melanin"), "cloud"), "x") as AgentDecision.CallTool
        assertEquals("Prince Melanin", named.args.getString(com.tekyerema.money.ussd.UssdNavigatorTool.ARG_CONTACT))
        // Nothing to walk with (no amount): left as it was. Other tools: untouched.
        val bare = AgentDecision.CallTool("mobile_money", org.json.JSONObject().put("_kind", "buy_airtime"), "cloud")
        assertEquals(bare, MoneyFlow.fromPlanner(bare, "buy airtime"))
        val other = AgentDecision.CallTool("open_app", org.json.JSONObject().put("app", "Telegram"), "cloud")
        assertEquals(other, MoneyFlow.fromPlanner(other, "open telegram"))
    }

    @Test fun a_model_may_not_dial_a_code_it_made_up() {
        val made = AgentDecision.CallTool("run_ussd", org.json.JSONObject().put("code", "*124*0.10*0508797323#"), "cloud")
        val refused = MoneyFlow.fromPlanner(made, "10 pesewas airtime to a telephone number") as AgentDecision.CannotDo
        assertEquals(MoneyFlow.MADE_UP_CODE, refused.detail)
        fun ok(code: String, goal: String) = MoneyFlow.fromPlanner(AgentDecision.CallTool("run_ussd", org.json.JSONObject().put("code", code), "cloud"), goal) is AgentDecision.CallTool
        assertTrue("the checked balance code", ok("*124#", "what is my airtime balance"))
        assertTrue("a checked network menu", ok("*138#", "open the data menu"))
        assertTrue("a Mobile Money root (still confirmed as TIER3)", ok("*170#", "open mobile money"))
        assertTrue("a code the user typed", ok("*135#", "dial *135#"))
        assertTrue("a code the user said", ok("*135#", "dial star 135 hash"))
        assertFalse(ok("*135#", "check my number"))
    }

    // ---------------------------------------------------------------- MTN's real airtime menus

    @Test fun airtime_is_chosen_over_schedule_airtime() {
        val rows = TelcoUssd.rows(AIRTIME_MENU)
        for (intent in listOf(TxIntent.BUY_AIRTIME, TxIntent.SEND_AIRTIME)) {
            val v = SemanticMatch.best(intent, rows)
            assertEquals(intent.name, "1", v!!.option)
            assertTrue(intent.name + " " + v, sure(v))
        }
    }

    @Test fun whose_phone_and_which_network_decide_self_others_or_other_networks() {
        val rows = TelcoUssd.rows(WHO_MENU)
        val self = SemanticMatch.best(TxIntent.BUY_AIRTIME, rows)
        assertEquals("1", self!!.option); assertTrue(sure(self))
        val same = SemanticMatch.best(TxIntent.SEND_AIRTIME, rows, crossNetwork = false)
        assertEquals("2", same!!.option); assertTrue(sure(same))
        val other = SemanticMatch.best(TxIntent.SEND_AIRTIME, rows, crossNetwork = true)
        assertEquals("4", other!!.option); assertTrue(sure(other))
        assertFalse("network not known: asked, not guessed", sure(SemanticMatch.best(TxIntent.SEND_AIRTIME, rows)))
    }

    @Test fun after_the_users_one_answer_the_next_menu_is_chosen_by_meaning_again() {
        // The owner answered "1" at the airtime menu; the walk then read "Self / Others / Welcome Pack / Other Networks" and asked.
        val flow = TelcoUssd.Flow(TelcoUssd.NAV, "*170#", emptyList(), "send airtime", "MTN", number = "0508797323", amount = "0.1",
            intent = TxIntent.SEND_AIRTIME.name, sim = "mtn")
        val step = "telco_ussd({\"_action\":\"reply\",\"_text\":\"1\"}) → success [native]; verified=true (the network's next screen was read); MTN: Airtime 1) Self 2) Others"
        fun decide(screen: String) = TelcoGuide.decide("send airtime", listOf(step),
            TelcoSession.View(flow, screen, BankUssd.ScreenKind.MENU, 1L, 1L, userDrove = true), "mtn") as AgentDecision.CallTool
        val d = decide(WHO_MENU)
        assertEquals(TelcoUssdTool.NAME, d.name)
        assertEquals("a Telecel number from an MTN SIM", "4", d.args.getString(TelcoUssdTool.ARG_TEXT))
        assertTrue(d.args.optBoolean(TelcoUssdTool.ARG_AUTO))
        // A refused entry is never answered automatically; a menu nobody is sure about is still asked.
        assertEquals("ask_user", decide("Incorrect choice, try again. $WHO_MENU").name)
        assertEquals("ask_user", decide("1) Welcome Pack 2) Promotions 0) Back").name)
    }

    // ---------------------------------------------------------------- 03:41 — the amount screen

    private val AMOUNT_SCREEN = "Enter amount (GHS) minimum: 0.20, maximum: 500.00"
    private val REFUSED_SCREEN = "The amount you have entered is less than or greater than allowed amount. Enter amount (GHS) minimum: 0.20, maximum: 500.00"

    @Test fun mtns_other_network_airtime_prompts_are_read_as_what_they_ask_for() {
        for ((screen, slot) in listOf("Enter recipient phone number" to com.tekyerema.money.ussd.SlotKind.PHONE_NUMBER,
                "Re-enter recipient phone number" to com.tekyerema.money.ussd.SlotKind.CONFIRM_NUMBER,
                AMOUNT_SCREEN to com.tekyerema.money.ussd.SlotKind.AMOUNT)) {
            val m = com.tekyerema.money.ussd.MenuModel.read(screen, hasInput = true, masked = false)
            assertEquals(screen, com.tekyerema.money.ussd.MenuModel.Kind.INPUT, m.kind)
            assertEquals(screen, slot, m.slot)
        }
    }

    @Test fun the_range_a_screen_states_is_read_in_every_shape() {
        val r = com.tekyerema.money.ussd.AmountRange.of(AMOUNT_SCREEN)!!
        assertEquals(0, java.math.BigDecimal("0.20").compareTo(r.min)); assertEquals(0, java.math.BigDecimal("500").compareTo(r.max))
        assertEquals("between 20 pesewas and 500 cedis", r.spoken)
        assertFalse(com.tekyerema.money.ussd.AmountRange.allows("0.1", AMOUNT_SCREEN))
        assertTrue(com.tekyerema.money.ussd.AmountRange.allows("0.2", AMOUNT_SCREEN))
        assertFalse(com.tekyerema.money.ussd.AmountRange.allows("600", AMOUNT_SCREEN))
        assertTrue(com.tekyerema.money.ussd.AmountRange.allows("0.1", "Enter amount to buy your preferred bundle (GHC 0.07 - 4.99)"))
        assertFalse(com.tekyerema.money.ussd.AmountRange.allows("10", "Enter amount to buy your preferred bundle (GHC 0.07 - 4.99)"))
        assertTrue("no range stated: nothing to refuse", com.tekyerema.money.ussd.AmountRange.allows("10", "Enter Amount"))
        assertNull(com.tekyerema.money.ussd.AmountRange.of("Enter mobile number"))
    }

    @Test fun an_amount_outside_the_range_is_not_typed_and_a_refused_one_is_never_typed_again() {
        val tx = com.tekyerema.money.ussd.TxContext(TxIntent.SEND_AIRTIME, "mtn", recipient = "0508797323", amount = "0.1")
        val amount = com.tekyerema.money.ussd.SlotKind.AMOUNT
        // What should have happened at 03:41:27 — instead 0.1 was typed.
        assertEquals("MTN only takes between 20 pesewas and 500 cedis here, and you asked for 10 pesewas.",
            com.tekyerema.money.ussd.Navigator.entryStop(tx, amount, AMOUNT_SCREEN, "0.1", com.tekyerema.money.ussd.SlotKind.CONFIRM_NUMBER))
        // What should have happened at 03:41:29 — instead it was typed three more times.
        val again = com.tekyerema.money.ussd.Navigator.entryStop(tx, amount, REFUSED_SCREEN, "0.5", amount)
        assertTrue(again, again!!.startsWith("MTN didn't accept what I entered. It said: “The amount you have entered is less than or greater than allowed amount."))
        assertTrue("the words alone are enough", com.tekyerema.money.ussd.Navigator.entryStop(tx, amount, REFUSED_SCREEN, "0.5", null) != null)
        assertTrue("the same slot asked twice is a refusal whatever it says", com.tekyerema.money.ussd.Navigator.entryStop(tx, amount, "Enter Amount", "0.5", amount) != null)
        // An amount inside the range, a number, MTN's enter-then-re-enter: all typed.
        assertNull(com.tekyerema.money.ussd.Navigator.entryStop(tx, amount, AMOUNT_SCREEN, "0.5", com.tekyerema.money.ussd.SlotKind.CONFIRM_NUMBER))
        assertNull(com.tekyerema.money.ussd.Navigator.entryStop(tx, com.tekyerema.money.ussd.SlotKind.PHONE_NUMBER, "Enter recipient phone number", "0508797323", null))
        assertNull(com.tekyerema.money.ussd.Navigator.entryStop(tx, com.tekyerema.money.ussd.SlotKind.CONFIRM_NUMBER, "Re-enter recipient phone number", "0508797323",
            com.tekyerema.money.ussd.SlotKind.PHONE_NUMBER))
    }

    @Test fun the_planners_recovery_hint_is_never_read_to_the_user() {
        // The history line of 03:41:52, as the engine wrote it.
        val line = "ussd_navigate({\"amount\":\"0.1\",\"intent\":\"send_airtime\",\"number\":\"0508797323\"}) → fail [none]; verified=false (action failed; nothing to verify); " +
            "The network's menu didn't answer in time, so it may have closed. Nothing is paid without your PIN. — this route is a genuine dead-end " +
            "(environment-blocked); only call cannot_do if NO other route (accelerator or on-screen UI) could accomplish the goal."
        assertEquals("The network's menu didn't answer in time, so it may have closed. Nothing is paid without your PIN.",
            com.tekyerema.money.MoneyPlan.navigatorEnd(listOf(line))!!.second)
    }

    // ---------------------------------------------------------------- balances

    @Test fun the_mobile_money_balance_walk_pages_with_hash_and_finds_my_wallet() {
        assertEquals("#", TelcoUssd.moreOption("Unlock more deals, try our new MoMo App 1) Transfer Money 2) MoMoPay&Pay Bill 3) Airtime&Bundles 4) Allow Cash Out 5) Financial Services # for next"))
        val page2 = TelcoUssd.rows("Services 6) My Wallet 7) Just4U(Offers for you) 8) MoMo App (300MB free data)")
        assertEquals("6", SemanticMatch.best(TxIntent.CHECK_BALANCE, page2)!!.option)
        assertTrue(sure(SemanticMatch.best(TxIntent.CHECK_BALANCE, page2)))
        val wallet = TelcoUssd.rows("Download your statement on MoMo App now 1) Check Balance 2) Allow Cash Out 3) My Approvals")
        assertEquals("1", SemanticMatch.best(TxIntent.CHECK_BALANCE, wallet)!!.option)
    }

    @Test fun the_airtime_balance_is_said_as_an_answer() {
        val real = "Your a/c is GHC0.06Internet bundle:12068.27MB.Data Bonus: 0.00MB.BOR:0.00GHC,J4u:0.00GHC,Welcome to the new world."
        assertEquals("Your airtime balance is 6 pesewas.${SpokenText.PAUSE}You also have about 11.8 gigabytes of data.", UssdSpeech.airtimeBalance(real))
        assertEquals("Your airtime balance is 5 cedis 20 pesewas.", UssdSpeech.airtimeBalance("Your a/c is GHC5.20Internet bundle:0.00MB.Data Bonus: 0.00MB."))
        assertNull("not a balance reply: read in full instead", UssdSpeech.airtimeBalance("Y'ello Your data bundle balance: GHc3 Bundle: 63MB"))
    }
}
