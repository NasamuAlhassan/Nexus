package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.money.BankUssd
import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.PinPad
import com.tekyerema.money.TelcoGuide
import com.tekyerema.money.TelcoSession
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.TelcoUssdTool
import com.tekyerema.money.ussd.Boundary
import com.tekyerema.money.ussd.MenuBrain
import com.tekyerema.money.ussd.MenuBrain.Move
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.TxContext
import com.tekyerema.money.ussd.TxIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The menu brain, on the screens MTN really showed in the USSD sweep of 2 October 2026 (audit/ussd-2026-10-02). */
class MenuBrainTest {

    @Before fun fresh() { TelcoGuide.resetForTest(); com.tekyerema.voice.Coaching.resetForTest() }

    private val mtn = MenuBrain.Goal(net = "MTN")

    // ---- the screens that are about money

    @Test fun `a loan offer is never accepted for the user - it is asked, and says it is a loan`() {
        val s = "Uh oh. Looks like you don't have enough balance to complete your purchase. You'll be offered an advance if you're eligible 1. Accept 2. Cancel"
        val m = MenuBrain.decide(mtn.copy(amount = "3", priceConfirmed = true), s) as Move.Ask
        assertEquals(MenuBrain.Kind.LOAN, m.kind)
        assertTrue(m.question, m.question.contains("advance") && m.question.contains("loan"))
        assertEquals("1", m.options["yes"]); assertEquals("2", m.options["no"])
        assertTrue(m.urgent)
    }

    @Test fun `paying from airtime instead is asked - no PIN - but Mobile Money instead is taken`() {
        val toAirtime = "Your Momo balance is insufficient to buy this bundle press 1 to pay with Airtime. 1. Airtime 0. Cancel"
        val a = MenuBrain.decide(mtn.copy(pay = "momo"), toAirtime) as Move.Ask
        assertEquals(MenuBrain.Kind.SWITCH_PAY, a.kind)
        assertTrue(a.question, a.question.contains("no PIN"))
        assertEquals("1", a.options["yes"]); assertEquals("0", a.options["no"])
        val toMomo = "Your Airtime balance is insufficient to buy this bundle press 1 to pay with Mobile Money 1. Mobile Money 0. Cancel"
        assertEquals("1", (MenuBrain.decide(mtn, toMomo) as Move.Press).option)
        // …unless the user said they'd pay from airtime: then the switch is theirs to make.
        assertTrue(MenuBrain.decide(mtn.copy(pay = "airtime"), toMomo) is Move.Ask)
    }

    @Test fun `how to pay - Mobile Money unless the user said airtime`() {
        val s = "Choose payment mode 1. Airtime 2. Mobile Money"
        assertEquals("2", (MenuBrain.decide(mtn, s) as Move.Press).option)
        assertEquals("1", (MenuBrain.decide(mtn.copy(pay = "airtime"), s) as Move.Press).option)
    }

    @Test fun `a product's Buy is pressed at the approved price, otherwise asked plainly`() {
        val s = "Y'ello! The GHC 3 Data Bundle will give you 406.89 MB. This bundle does not expire. 1. Buy"
        assertEquals("1", (MenuBrain.decide(mtn.copy(amount = "3", priceConfirmed = true), s) as Move.Press).option)
        val ask = MenuBrain.decide(mtn, s) as Move.Ask
        assertEquals(MenuBrain.Kind.CONFIRM, ask.kind)
        assertTrue(ask.question, ask.question.startsWith("MTN offers: The 3 cedis Data Bundle will give you 406.89 megabytes"))
        assertTrue(ask.question, ask.question.endsWith("Buy it? Say yes or no."))
        // A different price than the one approved is asked, never pressed.
        assertTrue(MenuBrain.decide(mtn.copy(amount = "1", priceConfirmed = true), s) is Move.Ask)
        // A welcome screen with "Proceed" and no price is not a purchase.
        assertNull(MenuBrain.decide(mtn, "Welcome to the Bundle Portal. Please select an option 1. Proceed to buy bundle (No 4G bonus) 99. More"))
    }

    // QA run of 3 October 2026 (audit/qa-2026-10-03, F05 and F09): both notices were asked as "MTN says: 1 Confirm; 0 Back.
    // What should I enter?" — the words of the notice never spoken, and not a yes/no.
    @Test fun `a notice with one bare Confirm or Proceed is asked as a yes-no that says the notice`() {
        val midnight = "Y'ello, this bundle can only be used between the hours of 12am and 5am. 1. Confirm 0. Back"
        val m = MenuBrain.decide(mtn.copy(words = setOf("midnight")), midnight) as Move.Ask
        assertEquals(MenuBrain.Kind.CONFIRM, m.kind)
        assertEquals("MTN says: this bundle can only be used between the hours of 12am and 5am. Shall I go on? Say yes or no.", m.question)
        assertEquals("1", m.options["yes"]); assertEquals("0", m.options["no"])
        assertTrue(m.urgent)
        assertEquals(MenuBrain.Answer.Press("1"), MenuBrain.resolve(m, "yes", midnight))
        assertEquals(MenuBrain.Answer.Close, MenuBrain.resolve(m, "no", midnight))

        val warning = "Airtime purchased for other networks cannot be reversed 1. Proceed 2. Cancel"
        val w = MenuBrain.decide(mtn.copy(amount = "0.2", priceConfirmed = true), warning) as Move.Ask
        assertEquals(MenuBrain.Kind.CONFIRM, w.kind)
        assertEquals("MTN says: Airtime purchased for other networks cannot be reversed. Shall I go on? Say yes or no.", w.question)
        assertEquals("1", w.options["yes"]); assertEquals("2", w.options["no"])
        assertEquals(MenuBrain.Answer.Press("1"), MenuBrain.resolve(w, "go ahead", warning))
    }

    @Test fun `a bare Proceed under a mere prompt, or a Proceed with an object, is not a notice`() {
        assertNull(MenuBrain.decide(mtn, "Welcome to MTN Pulse. Please select an option 1. Proceed 99. More"))
        assertNull(MenuBrain.decide(mtn, "Welcome to MTN Pulse. Please select an option 1. Proceed to buy bundle (No 4G bonus) 99. More"))
        // Two real choices are a menu, not a notice.
        assertNull(MenuBrain.decide(mtn, "This bundle can only be used at night. 1. Confirm 2. Change bundle 0. Back"))
    }

    // ---- what the user asked for

    @Test fun `the user's own word picks its row - midnight is not plain data`() {
        val s = "Welcome to Bundle Portal. Please select your bundle. 1. Buy Data Bundle 2. Midnight Bundles 3. Kokrokoo Bundle 99. More"
        assertEquals(setOf("midnight"), MenuBrain.goalWords("Buy an MTN midnight bundle"))
        assertEquals("2", (MenuBrain.decide(mtn.copy(words = setOf("midnight")), s) as Move.Press).option)
        assertEquals("3", (MenuBrain.decide(mtn.copy(words = setOf("kokrokoo")), s) as Move.Press).option)
        assertNull(MenuBrain.decide(mtn, s))   // nothing said: the plan (or the user) decides
    }

    @Test fun `self or others`() {
        val s = "Please enter your choice 1. Buy For Self 2. Buy For Others"
        assertEquals("1", (MenuBrain.decide(mtn.copy(forOthers = false), s) as Move.Press).option)
        assertEquals("2", (MenuBrain.decide(mtn.copy(forOthers = true), s) as Move.Press).option)
    }

    @Test fun `price lists - the price said, the tier it falls in, or one question`() {
        val tiers = "MashUp Offers 1. MashUp GHC 1 2. MashUp GHC 5 3. MashUp GHC 10 4. MashUp GHC 30 0. Back"
        val q = MenuBrain.decide(mtn, tiers) as Move.Ask
        assertEquals(MenuBrain.Kind.PRICE, q.kind)
        assertEquals("MTN has these at 1, 5, 10 or 30 cedis. Which one do you want?", q.question)
        assertEquals("2", (MenuBrain.decide(mtn.copy(amount = "5"), tiers) as Move.Press).option)
        // The answer to the question picks the price — "5" is 5 cedis on a price list, not option 5.
        val r = MenuBrain.resolve(q, "5 cedis", tiers) as MenuBrain.Answer.Learned
        assertEquals("2", r.option); assertEquals("5", r.amount)
        assertEquals("1", (MenuBrain.resolve(q, "one cedi", tiers) as MenuBrain.Answer.Learned).option)
        // "1. GHC 1 2. Enter amount GHC (0.07 - 4.99)" with 1 cedi → the GHC 1 row.
        assertEquals("1", (MenuBrain.decide(mtn.copy(amount = "1"), "Select one of the following: 1. GHC 1 2. Enter amount GHC (GHC 0.07 - 4.99) 0. Back") as Move.Press).option)
    }

    private val mixes = "1. 15.27MB and 15.64Mins(available on mymtn app only) 2. 25.45MB and 11.17Mins 3. 30.53MB and 8.94Mins " +
        "4. 35.62MB and 6.7Mins 5. 50.89MB Only"

    @Test fun `minute and data mixes - the user's preference, never the app-only row, else one question`() {
        // Owner: "select the option with more voice/call minutes… not the option that prompts you to open the MTN MoMo app".
        assertEquals("2", (MenuBrain.decide(mtn.copy(prefer = MenuBrain.Prefer.MINUTES), mixes) as Move.Press).option)
        assertEquals("5", (MenuBrain.decide(mtn.copy(prefer = MenuBrain.Prefer.DATA), mixes) as Move.Press).option)
        val q = MenuBrain.decide(mtn, mixes) as Move.Ask
        assertEquals(MenuBrain.Kind.PREFERENCE, q.kind)
        assertTrue(q.question, q.question.contains("more calls or more data"))
        assertFalse(q.question, q.question.contains("15.64") || q.question.contains("16 minutes"))   // the app-only row isn't offered
        assertEquals("2", (MenuBrain.resolve(q, "more calls", mixes) as MenuBrain.Answer.Learned).option)
        assertEquals("5", (MenuBrain.resolve(q, "data please", mixes) as MenuBrain.Answer.Learned).option)
        assertTrue(MenuBrain.appOnly("15.27MB and 15.64Mins(available on mymtn app only)"))
        assertEquals(MenuBrain.Prefer.MINUTES, MenuBrain.preferOf("Buy a Mashup bundle for my number with more minutes"))
        assertEquals(MenuBrain.Prefer.DATA, MenuBrain.preferOf("mashup with more data"))
        assertNull(MenuBrain.preferOf("Buy a Mashup bundle for my number"))
    }

    @Test fun `yes and no to the brain's questions`() {
        val s = "Your Momo balance is insufficient to buy this bundle press 1 to pay with Airtime. 1. Airtime 0. Cancel"
        val q = MenuBrain.decide(mtn, s) as Move.Ask
        assertEquals(MenuBrain.Answer.Press("1"), MenuBrain.resolve(q, "yes", s))
        assertEquals(MenuBrain.Answer.Close, MenuBrain.resolve(q, "no", s))
        assertEquals(MenuBrain.Answer.Unclear, MenuBrain.resolve(q, "the weather", s))
        val buy = MenuBrain.decide(mtn, "Y'ello! The GHC 3 Data Bundle will give you 406.89 MB. This bundle does not expire. 1. Buy") as Move.Ask
        assertEquals(MenuBrain.Answer.Close, MenuBrain.resolve(buy, "no", "Y'ello! The GHC 3 Data Bundle will give you 406.89 MB. 1. Buy"))
    }

    // ---- the guide, end to end on the real screens

    private fun mashupFlow(goal: String) = (TelcoUssd.parse(goal, "mtn") as TelcoUssd.Request.Open).flow
    private val openLine = """telco_ussd({"_action":"open","_code":"*567#"}) → success MTN: menu"""
    private val replyLine = """telco_ussd({"_action":"reply","_text":"1"}) → success MTN: menu"""
    private fun d(goal: String, vararg history: String) = TelcoGuide.decide(goal, history.toList(), network = "mtn")
    private fun AgentDecision?.text() = (this as AgentDecision.CallTool).args.optString("_text")
    private fun AgentDecision?.question() = (this as AgentDecision.CallTool).args.optString("question")

    @Test fun `Mashup for this phone - asked only the price and calls-or-data, paid with Mobile Money`() {
        val goal = "Buy a Mashup bundle for my number"
        val f = mashupFlow(goal)
        assertEquals("momo", f.pay)
        TelcoSession.opened(f, "Welcome to MTN Pulse. Please select an option 1. Proceed to buy bundle (No 4G bonus) 99. More", BankUssd.ScreenKind.MENU)
        assertEquals("1", d(goal, openLine).text())
        TelcoSession.screen("Welcome to MTN Pulse. 1. MashUp for Self 2. MashUp for Others 3. Convert Minutes To Data", BankUssd.ScreenKind.MENU, auto = true, more = false, stepIdx = 0)
        assertEquals("1", d(goal, openLine, replyLine).text())
        val tiers = "MashUp Offers 1. MashUp GHC 1 2. MashUp GHC 5 3. MashUp GHC 10 4. MashUp GHC 30 0. Back"
        TelcoSession.screen(tiers, BankUssd.ScreenKind.MENU, auto = true, more = false, stepIdx = 1)
        val q = d(goal, openLine, replyLine)
        assertEquals("MTN has these at 1, 5, 10 or 30 cedis. Which one do you want?", q.question())
        val a = d(goal, openLine, replyLine, """ask_user("${q.question()}") → success — the user answered: "1 cedi"""")
        assertEquals("1", a.text())
        TelcoSession.screen("Select one of the following: 1. GHC 1 2. Enter amount GHC (GHC 0.07 - 4.99) 0. Back", BankUssd.ScreenKind.MENU, auto = false, more = false, user = true)
        assertEquals("1", d(goal, openLine, replyLine).text())    // the price learned from the answer
        TelcoSession.screen(mixes, BankUssd.ScreenKind.MENU, auto = true, more = false)
        val p = d(goal, openLine, replyLine)
        assertTrue(p.question(), p.question().contains("more calls or more data"))
        assertEquals("2", d(goal, openLine, replyLine, """ask_user("x") → success — the user answered: "more minutes"""").text())
        TelcoSession.screen("Choose payment mode 1. Airtime 2. Mobile Money", BankUssd.ScreenKind.MENU, auto = false, more = false, user = true)
        assertEquals("2", d(goal, openLine, replyLine).text())
        TelcoSession.screen("Your Momo balance is insufficient to buy this bundle press 1 to pay with Airtime. 1. Airtime 0. Cancel", BankUssd.ScreenKind.MENU, auto = true, more = false)
        val sw = d(goal, openLine, replyLine)
        assertTrue(sw.question(), sw.question().contains("no PIN"))
        // "No" closes the menu — Tɛkyerɛma does it, the user isn't sent to find Cancel.
        val no = d(goal, openLine, replyLine, """ask_user("x") → success — the user answered: "no"""")
        assertEquals(TelcoUssdTool.CLOSE, (no as AgentDecision.CallTool).args.optString("_action"))
        assertEquals(AgentStatus.COMPLETED, TelcoGuide.end(listOf("""telco_ussd({"_action":"close"}) → success Okay — I've closed MTN's menu. Nothing was bought."""))!!.first)
    }

    @Test fun `a preference in the request skips the question`() {
        val goal = "Buy a Mashup bundle for my number with more minutes"
        TelcoSession.opened(mashupFlow(goal), mixes, BankUssd.ScreenKind.MENU)
        assertEquals("2", d(goal, openLine, replyLine).text())
    }

    @Test fun `3 cedis of data - Buy pressed at the approved price, Mobile Money chosen, the loan asked`() {
        val goal = "Buy a 3 cedis data bundle"
        val f = (TelcoUssd.parse(goal, "mtn") as TelcoUssd.Request.Open).flow
        TelcoSession.opened(f, "Y'ello! The GHC 3 Data Bundle will give you 406.89 MB. This bundle does not expire. 1. Buy", BankUssd.ScreenKind.MENU)
        assertEquals("1", d(goal, openLine, replyLine).text())
        TelcoSession.screen("Choose payment mode 1. Airtime 2. Mobile Money", BankUssd.ScreenKind.MENU, auto = true, more = false)
        assertEquals("2", d(goal, openLine, replyLine).text())
        TelcoSession.screen("Uh oh. Looks like you don't have enough balance to complete your purchase. You'll be offered an advance if you're eligible 1. Accept 2. Cancel", BankUssd.ScreenKind.MENU, auto = true, more = false)
        assertTrue(d(goal, openLine, replyLine).question().contains("advance"))
    }

    @Test fun `a midnight bundle walks to Midnight Bundles, with no amount invented`() {
        val goal = "Buy an MTN midnight bundle"
        val f = (TelcoUssd.parse(goal, "mtn") as TelcoUssd.Request.Open).flow
        assertNull(f.amount)
        assertEquals("a midnight bundle", f.what)
        TelcoSession.opened(f, "Welcome to the Bundle Portal. Please select an option 1. Proceed to buy bundle (No 4G bonus) 99. More", BankUssd.ScreenKind.MENU)
        assertEquals("1", d(goal, openLine).text())
        TelcoSession.screen("Welcome to Bundle Portal. Please select your bundle. 1. Buy Data Bundle 2. Midnight Bundles 3. Kokrokoo Bundle 99. More", BankUssd.ScreenKind.MENU, auto = true, more = false, stepIdx = 0)
        assertEquals("2", d(goal, openLine, replyLine).text())
    }

    @Test fun `the open being confirmed is the flow used - not an earlier walk on the same code`() {
        val earlier = (TelcoUssd.parse("Buy a 3 cedis data bundle", "mtn") as TelcoUssd.Request.Open).flow
        TelcoSession.opened(earlier, "Choose payment mode 1. Airtime 2. Mobile Money", BankUssd.ScreenKind.MENU)
        TelcoGuide.decide("Buy an MTN midnight bundle", emptyList(), session = null, network = "mtn")
        val f = TelcoGuide.flowOf(org.json.JSONObject().put("_code", "*138#"))!!
        assertNull(f.amount)
        assertEquals(setOf("midnight"), f.words)
    }

    // ---- codes, statuses, the repeat guard

    @Test fun `a spoken code is a code - never a phone number`() {
        assertEquals("Dial *138#", TelcoUssd.withSpokenCodes("Dial star 138 hash"))
        assertEquals("dial *138#", TelcoUssd.withSpokenCodes("dial star one three eight hash"))
        assertEquals("open *170*1#", TelcoUssd.withSpokenCodes("open star 170 star 1 hash"))
        assertNull(TelcoUssd.withSpokenCodes("Dial *138#"))
        assertNull(TelcoUssd.withSpokenCodes("call the star player"))
        // A network menu named by its code is walked with the user, not run as a one-shot request.
        val open = TelcoUssd.parse("Dial *138#", "mtn") as TelcoUssd.Request.Open
        assertEquals(TelcoUssd.MENU, open.flow.key); assertEquals("*138#", open.flow.code)
        assertEquals("*567#", (TelcoUssd.parse("open *567# and buy a mashup", "mtn") as TelcoUssd.Request.Open).flow.code)
        assertNull(TelcoUssd.parse("check my airtime with *124#", "mtn"))   // a one-shot code stays run_ussd's
    }

    @Test fun `did my last money transfer go through is a status question`() {
        assertEquals(MoneyAsk.Kind.STATUS, MoneyAsk.parse("Did my last money transfer go through")?.kind)
        assertEquals(MoneyAsk.Kind.STATUS, MoneyAsk.parse("did my momo payment go through")?.kind)
    }

    @Test fun `opening a network menu is not remembered as a purchase`() {
        assertNull(com.tekyerema.agent.journal.EffectKeys.describe(TelcoUssdTool.NAME,
            org.json.JSONObject().put("_action", "open").put("_code", "*138#"), null, null, false, null))
    }

    // ---- the navigator's confirmation screen

    @Test fun `Reply 1 to confirm - a short checked line and one spoken question`() {
        val s = "You are sending GHS 0.20 airtime.\nto this Telecel number, 0508797323.\nReply 1 to confirm or 0 to start again."
        assertEquals("1" to "0", MenuBrain.confirmKeys(s))
        val tx = TxContext(TxIntent.SEND_AIRTIME, "mtn", recipient = "0508797323", amount = "0.2")
        val v = Boundary.decide(tx, MenuModel.read(s, hasInput = true, masked = false), confirmByVoice = true) as Boundary.Verdict.HandOff
        assertTrue(v.line, v.line.startsWith("You're about to buy 0.20 cedis of airtime for 0 5 0, 8 7 9, 7 3 2 3"))
        assertTrue(v.line, v.line.endsWith("Shall I confirm it, so you can enter your PIN?"))
        assertFalse(v.line, v.line.contains("Reply 1"))
        // A wrong amount on the screen still stops it.
        val wrong = TxContext(TxIntent.SEND_AIRTIME, "mtn", recipient = "0508797323", amount = "2")
        assertTrue(Boundary.decide(wrong, MenuModel.read(s, true, false), confirmByVoice = true) is Boundary.Verdict.Stop)
    }

    // ---- the PIN with the volume keys

    @Test fun `volume keys count digits - down ends one, ten is zero, hold takes one out`() {
        val c = PinPad.Counter()
        repeat(3) { c.up() }
        assertEquals(PinPad.Counter.Event.Digit(1), c.down())
        repeat(10) { c.up() }
        assertEquals(PinPad.Counter.Event.Digit(2), c.down())
        assertEquals(PinPad.Counter.Event.Ignored, c.down())            // nothing counted, fewer than 4 digits: nothing
        assertEquals(PinPad.Counter.Event.TooMany, run { repeat(10) { c.up() }; c.up() })
        c.up(); assertEquals(PinPad.Counter.Event.Digit(3), c.down())
        c.up(); c.up(); assertEquals(PinPad.Counter.Event.Removed(3), c.hold())   // a digit being counted is dropped
        assertEquals(PinPad.Counter.Event.Removed(2), c.hold())                    // then the last one entered
        c.up(); c.down(); repeat(7) { c.up() }; c.down()
        assertEquals(PinPad.Counter.Event.Send(4), c.down())
        c.wipe(); assertEquals(0, c.size)
    }
}
