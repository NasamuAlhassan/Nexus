package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.money.BankUssd
import com.tekyerema.money.TelcoGuide
import com.tekyerema.money.TelcoSession
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.TelcoUssdTool
import org.junit.Assert.assertEquals
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

    @Test fun `the user's answer is typed as said - cancel stops`() {
        val f = flow()
        TelcoSession.opened(f, "1. Bundles\n2. Offers", BankUssd.ScreenKind.MENU)
        val ask = """ask_user("MTN says: “1 Bundles; 2 Offers”. What should I enter?") → success — the user answered: "option 2""""
        val d = TelcoGuide.decide("buy data", listOf(openLine, ask), network = "mtn")
        assertTrue(d is AgentDecision.CallTool && (d as AgentDecision.CallTool).args.optString("_text") == "2")
        val c = TelcoGuide.decide("buy data", listOf(openLine,
            """ask_user("MTN says: …") → success — the user answered: "cancel""""), network = "mtn")
        assertTrue(c is AgentDecision.Done && (c as AgentDecision.Done).summary.contains("stopped"))
    }
}
