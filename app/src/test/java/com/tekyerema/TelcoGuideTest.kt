package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.money.BankUssd
import com.tekyerema.money.TelcoGuide
import com.tekyerema.money.TelcoSession
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.TelcoUssdTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The guided network menu (data bundles, Mashup, offers): the researched steps are a PLAN — a key is pressed only
 *  when the live screen's own row matches; otherwise the user chooses; a payment or PIN screen ends with the user. */
class TelcoGuideTest {

    @Before fun fresh() { TelcoGuide.resetForTest(); com.tekyerema.voice.Coaching.resetForTest() }

    private fun parse(goal: String, net: String = "mtn") = TelcoUssd.parse(goal, net)

    @Test fun `data, balance, mashup and offers are understood — airtime is not ours`() {
        assertEquals("*138#", (parse("buy a data bundle") as TelcoUssd.Request.Open).flow.code)
        assertEquals("data_balance", (parse("check my data balance") as TelcoUssd.Request.Open).flow.key)
        assertEquals("*567#", (parse("buy a mashup bundle") as TelcoUssd.Request.Open).flow.code)
        assertEquals("*141#", (parse("what are my offers") as TelcoUssd.Request.Open).flow.code)
        assertEquals("*700#", (parse("buy data", net = "telecel") as TelcoUssd.Request.Open).flow.code)
        assertTrue((parse("buy data", net = "telecel") as TelcoUssd.Request.Open).flow.auto.isEmpty())   // tree unpublished → all guided
        assertNull(parse("buy airtime"))                       // Mobile Money's, as before
        assertNull(parse("send 50 cedis to Ama"))              // sends are never ours
        assertNull(parse("buy data", net = "unknown"))
        // A network without a checked code is told honestly — nothing is dialled.
        val u = parse("what are my offers", net = "telecel")
        assertTrue(u is TelcoUssd.Request.Unsupported && u.line.contains("never guess"))
    }

    /** The A20e hands the WHOLE menu as one line (seen live 2026-09-30) — rows are found anywhere in it. */
    @Test fun `a single-line Samsung menu still yields its rows`() {
        val live = "Welcome to the Bundle Portal. Please select an option 1. Proceed to buy bundle (No 4G bonus) 99. More"
        assertEquals(listOf("1" to "Proceed to buy bundle (No 4G bonus)", "99" to "More"), TelcoUssd.rows(live))
        assertEquals("1", TelcoUssd.autoAnswer(live, Regex("(?i)buy|proceed")))
        assertEquals("99", TelcoUssd.moreOption(live))
        // Figures are not rows: "GHS 5.00" and "4G" never become options.
        assertEquals(listOf("1" to "Daily GHS 5.00 bundle", "2" to "Weekly"), TelcoUssd.rows("1. Daily GHS 5.00 bundle 2. Weekly"))
    }

    @Test fun `an auto-step presses only the row the LIVE screen shows`() {
        val screen = "MTN Data\n1. Proceed to buy bundle\n3. Kokrokoo\n99. More"
        assertEquals("1", TelcoUssd.autoAnswer(screen, Regex("(?i)buy|proceed")))
        assertNull(TelcoUssd.autoAnswer(screen, Regex("(?i)data bundle")))   // not on this page → never pressed blind
        assertEquals("99", TelcoUssd.moreOption(screen))
        assertNull(TelcoUssd.moreOption("1. Buy\n2. Balance"))
        assertEquals("2", TelcoUssd.autoAnswer("1) Airtime\n2) Buy Data Bundles", Regex("(?i)data bundle")))
    }

    private fun flow(vararg steps: Regex) = TelcoUssd.Flow("data", "*138#", steps.toList(), "a data bundle", "MTN")
    private val openLine = """telco_ussd({"_action":"open","_code":"*138#"}) → success MTN: menu"""
    private val replyLine = """telco_ussd({"_action":"reply","_text":"1"}) → success MTN: menu"""

    @Test fun `the walk - open, auto-press matching rows, ask when the menu does not match`() {
        val f = flow(Regex("(?i)buy|proceed"), Regex("(?i)data bundle"))
        // A fresh request opens the checked code (the tool's read-back asks first: TIER3).
        val d = TelcoGuide.decide("buy a data bundle", emptyList(), session = null, network = "mtn")
        assertTrue(d is AgentDecision.CallTool && d.name == TelcoUssdTool.NAME && d.args.optString("_code") == "*138#")
        // The first screen shows the researched row → pressed automatically, with the row's words in the call.
        TelcoSession.opened(f, "1. Proceed to buy bundle\n2. Other", BankUssd.ScreenKind.MENU)
        val a1 = TelcoGuide.decide("buy a data bundle", listOf(openLine), network = "mtn")
        assertTrue(a1 is AgentDecision.CallTool && a1.name == TelcoUssdTool.NAME &&
            a1.args.optString("_text") == "1" && a1.args.optBoolean("_auto"))
        // A screen that DOESN'T show the next row is read to the user instead — never a blind press.
        TelcoSession.screen("1. Promo of the day\n2. Games", BankUssd.ScreenKind.MENU, auto = true, more = false)
        val a2 = TelcoGuide.decide("buy a data bundle", listOf(openLine, replyLine), network = "mtn")
        assertTrue(a2 is AgentDecision.CallTool && a2.name == "ask_user")
        assertTrue((a2 as AgentDecision.CallTool).args.optString("question").contains("MTN says"))
    }

    @Test fun `a long menu is paged with More while looking for the step's row - bounded`() {
        val f = flow(Regex("(?i)data bundle"))
        TelcoSession.opened(f, "1. Airtime\n99. More", BankUssd.ScreenKind.MENU)
        val d = TelcoGuide.decide("buy data", listOf(openLine), network = "mtn")
        assertTrue(d is AgentDecision.CallTool && (d as AgentDecision.CallTool).args.optString("_text") == "99" && d.args.optBoolean("_more"))
        // After 3 More presses without the row, it asks instead of looping.
        repeat(3) { TelcoSession.screen("5. Something\n99. More", BankUssd.ScreenKind.MENU, auto = true, more = true) }
        val e = TelcoGuide.decide("buy data", listOf(openLine, replyLine), network = "mtn")
        assertTrue(e is AgentDecision.CallTool && (e as AgentDecision.CallTool).name == "ask_user")
    }

    /** The LIVE *567# menu folds a level (straight to "MashUp for Self/Others") — the first REMAINING step whose
     *  row is on screen wins, so the missing middle step no longer sends the walk paging into a dead end. */
    @Test fun `a folded menu skips ahead to the step that IS on screen`() {
        val f = flow(Regex("(?i)buy|proceed|bundle"), Regex("(?i)data bundle"), Regex("(?i)\\bself\\b"))
        TelcoSession.opened(f, "Welcome to MTN Pulse. 1. MashUp for Self 2. MashUp for Others 3. Convert Minutes", BankUssd.ScreenKind.MENU)
        TelcoSession.screen(TelcoSession.current()!!.screen, BankUssd.ScreenKind.MENU, auto = true, more = false, stepIdx = 0)
        val d = TelcoGuide.decide("buy a mashup bundle", listOf(openLine, replyLine), network = "mtn")
        assertTrue(d.toString(), d is AgentDecision.CallTool && (d as AgentDecision.CallTool).args.optString("_text") == "1" &&
            d.args.optBoolean("_auto") && d.args.optInt("_step", -1) == 2)
    }

    /** Once the user answers a screen by voice, the autopilot stays OFF — every later screen is read and asked
     *  (live A20e 2026-09-30: it resumed a stale step after the user's "0, go back" and looped into More). */
    @Test fun `after the user takes the wheel the walk stays guided`() {
        val f = flow(Regex("(?i)buy|proceed"))
        TelcoSession.opened(f, "1. Proceed to buy bundle 99. More", BankUssd.ScreenKind.MENU)
        TelcoSession.screen("1. Proceed to buy bundle 99. More", BankUssd.ScreenKind.MENU, auto = false, more = false, user = true)
        val d = TelcoGuide.decide("buy data", listOf(openLine, replyLine), network = "mtn")
        assertTrue(d.toString(), d is AgentDecision.CallTool && (d as AgentDecision.CallTool).name == "ask_user")
    }

    /** The request's own slots ride the walk: "Send ₵0.1 mashup to 055…" answers "Enter Phone Number" and
     *  "Enter Amount" itself — no dictating what was already said (live A20e 2026-09-30: the ask stalled twice
     *  on recogniser no-match for digits the user had already given). */
    @Test fun `the goal's number and amount answer the entry screens`() {
        val req = TelcoUssd.parse("Send ₵0.1 mashup to 0552093690", "mtn") as TelcoUssd.Request.Open
        assertEquals("0552093690", req.flow.number)
        assertEquals("0.1", req.flow.amount)
        // "10 pesewas" is 0.10 cedis, NEVER 10 — the 100× error the network rejected (live A20e 2026-09-30).
        assertEquals("0.10", (TelcoUssd.parse("send 10 pesewas mashup to 0552093690", "mtn") as TelcoUssd.Request.Open).flow.amount)
        assertEquals("5", (TelcoUssd.parse("buy 5 cedis mashup for 0552093690", "mtn") as TelcoUssd.Request.Open).flow.amount)
        TelcoSession.opened(req.flow, "Enter Phone Number", BankUssd.ScreenKind.MENU)
        val d = TelcoGuide.decide("Send ₵0.1 mashup to 0552093690", listOf(openLine, replyLine), network = "mtn")
        assertTrue(d.toString(), d is AgentDecision.CallTool && (d as AgentDecision.CallTool).args.optString("_text") == "0552093690")
        TelcoSession.screen("Enter Amount", BankUssd.ScreenKind.MENU, auto = false, more = false, user = true, typed = "0552093690")
        val a = TelcoGuide.decide("Send ₵0.1 mashup to 0552093690", listOf(openLine, replyLine), network = "mtn")
        assertTrue(a.toString(), a is AgentDecision.CallTool && (a as AgentDecision.CallTool).args.optString("_text") == "0.1")
        // "+233" folds to the 0-form; an unknown-length number stays null (never a guess).
        assertEquals("0552093690", (TelcoUssd.parse("mashup for +233 552093690", "mtn") as TelcoUssd.Request.Open).flow.number)
        assertNull((TelcoUssd.parse("buy a mashup bundle", "mtn") as TelcoUssd.Request.Open).flow.number)
    }

    /** A "wrong amount, enter again" re-prompt is NEVER auto-repeated, and an amount outside the shown range is
     *  not entered (live A20e 2026-09-30: "10" outside MTN's 0.07–4.99 was re-typed until the session timed out). */
    @Test fun `a rejected amount is asked, not blindly re-entered`() {
        assertTrue(TelcoGuide.inRange("0.1", "Enter amount (GHC 0.07 - 4.99)"))
        assertFalse(TelcoGuide.inRange("10", "Enter amount (GHC 0.07 - 4.99)"))
        assertTrue(TelcoGuide.inRange("10", "Enter amount"))          // no stated range → allowed

        val f = TelcoUssd.Flow("mashup", "*567#", emptyList(), "a bundle", "MTN", amount = "10")
        // In-range amount would be entered; an error re-prompt (same rejected value) must instead ASK.
        TelcoSession.opened(f, "You have entered wrong amount value. Enter Amount again", BankUssd.ScreenKind.MENU)
        TelcoSession.screen("You have entered wrong amount value. Enter Amount again", BankUssd.ScreenKind.MENU,
            auto = false, more = false, user = true)
        val d = TelcoGuide.decide("buy a mashup bundle", listOf(openLine, replyLine), network = "mtn")
        assertTrue(d.toString(), d is AgentDecision.CallTool && (d as AgentDecision.CallTool).name == "ask_user")
        // An out-of-range amount on a fresh amount screen is also asked, with the range read.
        TelcoSession.screen("Enter amount to buy your preferred bundle (GHC 0.07 - 4.99)", BankUssd.ScreenKind.MENU,
            auto = false, more = false, user = true)
        val e = TelcoGuide.decide("buy a mashup bundle", listOf(openLine, replyLine), network = "mtn")
        assertTrue(e.toString(), e is AgentDecision.CallTool && (e as AgentDecision.CallTool).name == "ask_user")
    }

    /** MTN's Enter → Confirm double-entry: the number the user just dictated is offered back as ONE yes
     *  (the TIER2 read-back), never ten digits said twice (live A20e 2026-09-30). */
    @Test fun `a confirm-number screen reuses the number just dictated`() {
        val f = flow()
        TelcoSession.opened(f, "Enter Phone Number", BankUssd.ScreenKind.MENU)
        TelcoSession.screen("Confirm Phone Number", BankUssd.ScreenKind.MENU, auto = false, more = false,
            user = true, typed = "0552093690")
        val d = TelcoGuide.decide("buy a mashup bundle", listOf(openLine, replyLine), network = "mtn")
        // (2 October 2026: entered without a second yes — the number was read back once when it was dictated.)
        assertTrue(d.toString(), d is AgentDecision.CallTool && (d as AgentDecision.CallTool).name == TelcoUssdTool.NAME &&
            d.args.optString("_text") == "0552093690")
        // A later ordinary menu doesn't resend the number.
        TelcoSession.screen("MashUp Offers 1. MashUp GHC 1 2. MashUp GHC 5 0. Back", BankUssd.ScreenKind.MENU, auto = false, more = false, user = true)
        val e = TelcoGuide.decide("buy a mashup bundle", listOf(openLine, replyLine), network = "mtn")
        assertTrue(e.toString(), e is AgentDecision.CallTool && (e as AgentDecision.CallTool).name == "ask_user")
    }

    @Test fun `a payment confirmation or a PIN ends the task with the user`() {
        val f = flow()
        TelcoSession.opened(f, "Pay GHS 20.00 for 5GB. Enter PIN to confirm", BankUssd.ScreenKind.PIN)
        val end = TelcoGuide.end(listOf(openLine))
        assertEquals(AgentStatus.COMPLETED, end!!.first)
        assertTrue(end.second, end.second.contains("PIN") && end.second.contains("enter it yourself"))
        // A failed step ends honestly.
        val fail = TelcoGuide.end(listOf("""telco_ussd({"_action":"reply"}) → fail the menu didn't answer"""))
        assertEquals(AgentStatus.CANNOT_DO, fail!!.first)
        // A menu screen doesn't end anything.
        TelcoSession.screen("1. Bundles", BankUssd.ScreenKind.MENU, auto = false, more = false)
        assertNull(TelcoGuide.end(listOf(openLine)))
    }

    @Test fun `the user's answer is typed as said - cancel closes the menu`() {
        val f = flow()
        TelcoSession.opened(f, "1. Bundles\n2. Offers", BankUssd.ScreenKind.MENU)
        val ask = """ask_user("MTN says: “1 Bundles; 2 Offers”. What should I enter?") → success — the user answered: "option 2""""
        val d = TelcoGuide.decide("buy data", listOf(openLine, ask), network = "mtn")
        assertTrue(d is AgentDecision.CallTool && (d as AgentDecision.CallTool).args.optString("_text") == "2")
        // "Cancel": Tɛkyerɛma closes the network's menu itself (2 October 2026) — a blind user isn't sent to find Cancel.
        val c = TelcoGuide.decide("buy data", listOf(openLine,
            """ask_user("MTN says: …") → success — the user answered: "cancel""""), network = "mtn")
        assertTrue(c.toString(), c is AgentDecision.CallTool && c.args.optString("_action") == TelcoUssdTool.CLOSE)
    }
}
