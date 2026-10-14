package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.money.BankUssd
import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.MoneyFlow
import com.tekyerema.money.MoneyPlan
import com.tekyerema.money.ussd.PushedPin
import com.tekyerema.money.ussd.UssdNavigatorTool
import com.tekyerema.money.ussd.UssdSim
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The A20e on 14 October 2026, 00:11–00:15: "I want you to buy" was cut short by the recogniser; the planner asked, the owner
 * answered "…for a telegram number and the number is 050 879-7323 I want you to buy 2 cedis airtime", and the planner then
 * searched the web for Telecel codes and typed shortcuts into the dialer for two minutes. Now the money flow asks, and an
 * answer that is a money request is decided on the phone — whoever asked the question.
 */
class MoneyAnswersTest {
    private val answer = "I want you to buy it for a telegram number and the number is 050 879-7323 I want you to buy 2 cedis airtime"
    private fun answered(question: String, ans: String) = "ask_user(\"$question\") → the user answered: \"$ans\""

    @Test fun a_bare_buy_gets_the_money_flows_question_not_the_planner() {
        val d = MoneyFlow.decide("I want you to buy", emptyList()) as AgentDecision.CallTool
        assertEquals("ask_user", d.name)
        assertTrue(d.args.getString("question"), d.args.getString("question").startsWith(MoneyFlow.WHAT_PREFIX))
        for (g in listOf("buy", "I want to buy", "please buy for me", "Can you buy it")) assertTrue(g, MoneyFlow.decide(g, emptyList()) is AgentDecision.CallTool)
        assertNull("a complete request is not a bare buy", (MoneyFlow.decide("buy 2 cedis airtime for 0508797323", emptyList()) as AgentDecision.CallTool).args.optString("question").takeIf { it.isNotEmpty() })
    }

    @Test fun the_owners_answer_is_a_whole_airtime_request() {
        val a = MoneyAsk.parse(answer)!!
        assertEquals(MoneyAsk.Kind.AIRTIME, a.kind)
        assertEquals("2", a.amount)
        assertEquals("0508797323", a.number)
    }

    @Test fun the_answer_to_our_question_or_the_planners_is_decided_on_the_phone() {
        for (q in listOf("${MoneyFlow.WHAT_PREFIX} — airtime, data, or a bundle? Say the amount, and the number if it's for someone else.",
                "How much airtime would you like to buy and for which phone number?")) {
            val d = MoneyPlan.decide("I want you to buy", listOf(answered(q, answer)))
            assertTrue("$q → $d", d is AgentDecision.CallTool && (d as AgentDecision.CallTool).name == UssdNavigatorTool.NAME)
            val args = (d as AgentDecision.CallTool).args
            assertEquals(UssdNavigatorTool.SEND_AIRTIME, args.getString(UssdNavigatorTool.ARG_INTENT))
            assertEquals("0508797323", args.getString("number"))
            assertEquals("2", args.getString("amount"))
        }
        // An answer that is not a money request leaves the planner's task alone…
        assertNull(MoneyPlan.decide("open WhatsApp", listOf(answered("Which chat?", "Ama"))))
        // …and so does a money-shaped answer to a question that isn't about money (a message's words).
        assertNull(MoneyPlan.decide("text Kofi", listOf(answered("What should the message say?", "send 5 cedis to 0244123456 tomorrow"))))
        // After a walk, nothing is re-proposed from an old answer.
        assertNull(MoneyPlan.decide("I want you to buy", listOf(answered("How much?", answer), "${UssdNavigatorTool.NAME}({\"amount\":\"2\"}) → ok")))
    }

    @Test fun a_money_code_typed_by_the_planner_is_refused() {
        val typed = AgentDecision.CallTool("type", JSONObject().put("element_id", 4).put("text", "*170*0508797323*2#"), null)
        val d = MoneyFlow.fromPlanner(typed, "I want you to buy", emptyList())
        assertTrue(d.toString(), d is AgentDecision.CannotDo && (d as AgentDecision.CannotDo).detail == MoneyFlow.MADE_UP_CODE)
        val plain = AgentDecision.CallTool("type", JSONObject().put("element_id", 4).put("text", "hello Ama"), null)
        assertTrue(MoneyFlow.fromPlanner(plain, "text Ama hello", emptyList()) === plain)
        val saidCode = AgentDecision.CallTool("type", JSONObject().put("element_id", 4).put("text", "*124#"), null)
        assertTrue(MoneyFlow.fromPlanner(saidCode, "dial star 124 hash", emptyList()) === saidCode)
    }

    @Test fun what_was_approved_is_not_asked_again_during_the_walk() {
        // The read-back of airtime for another network says it can't be reversed…
        val args = JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.SEND_AIRTIME).put("number", "0508797323")
            .put("amount", "2").put("_sim", "mtn")
        val readBack = UssdNavigatorTool.confirmationPrompt(args)
        assertTrue(readBack, readBack.contains(UssdNavigatorTool.IRREVERSIBLE_LINE) && readBack.contains("a Telecel number"))
        assertTrue("same network: no such line", !UssdNavigatorTool.confirmationPrompt(JSONObject(args.toString()).put("number", "0552093690"))
            .contains(UssdNavigatorTool.IRREVERSIBLE_LINE))
        // …so MTN's confirmation showing exactly that amount and number is pressed, and its notice accepted.
        val tx = com.tekyerema.money.ussd.TxContext(com.tekyerema.money.ussd.TxIntent.SEND_AIRTIME, "mtn", recipient = "0508797323",
            amount = "2", confirmedUpFront = true)
        val confirm = "You are sending GHS 2.00 airtime to this Telecel number: 0508797323. Reply 1 to confirm or 0 to start again"
        val m = com.tekyerema.money.ussd.MenuModel.read(confirm, hasInput = true, masked = false)
        assertTrue(com.tekyerema.money.ussd.Boundary.approvedExactly(tx, m))
        assertTrue("another amount", !com.tekyerema.money.ussd.Boundary.approvedExactly(tx.copy(amount = "5"), m))
        assertTrue("another number", !com.tekyerema.money.ussd.Boundary.approvedExactly(tx.copy(recipient = "0501111111"), m))
        assertTrue("not approved up front", !com.tekyerema.money.ussd.Boundary.approvedExactly(tx.copy(confirmedUpFront = false), m))
        val withFee = com.tekyerema.money.ussd.MenuModel.read("You are sending GHS 2.00 airtime to this Telecel number: 0508797323. " +
            "Fee is GHS 0.50. Reply 1 to confirm or 0 to start again", hasInput = true, masked = false)
        assertTrue("a fee the user didn't hear is asked about", !com.tekyerema.money.ussd.Boundary.approvedExactly(tx, withFee))
        val notice = "Airtime purchased for other networks cannot be reversed\n1. Proceed\n2. Cancel"
        val heard = com.tekyerema.money.ussd.MenuBrain.decide(com.tekyerema.money.ussd.MenuBrain.Goal(irreversibleSaid = true, net = "MTN"), notice)
        assertTrue(heard.toString(), heard is com.tekyerema.money.ussd.MenuBrain.Move.Press && heard.option == "1")
        val notHeard = com.tekyerema.money.ussd.MenuBrain.decide(com.tekyerema.money.ussd.MenuBrain.Goal(net = "MTN"), notice)
        assertTrue(notHeard.toString(), notHeard is com.tekyerema.money.ussd.MenuBrain.Move.Ask)
    }

    @Test fun the_simulator_walks_airtime_for_a_telecel_number_to_the_promise_and_the_pushed_pin() {
        val s = UssdSim.Script("*170#")
        s.enter("3"); assertTrue(s.shown().text.contains("1) Airtime"))
        s.enter("1"); assertTrue(s.shown().text.contains("4) Other Networks"))
        s.enter("4"); assertTrue(s.shown().text.contains("2) Telecel"))
        s.enter("2"); assertEquals("Enter recipient phone number", s.shown().text)
        s.enter("0508797323"); assertEquals("Re-enter recipient phone number", s.shown().text)
        s.enter("0508797323"); assertTrue(s.shown().text.startsWith("Enter amount (GHS)"))
        s.enter("2")
        val confirm = s.shown()
        assertEquals("You are sending GHS 2.00 airtime to this Telecel number: 0508797323. Reply 1 to confirm or 0 to start again", confirm.text)
        assertEquals(BankUssd.ScreenKind.MONEY, BankUssd.kind(confirm.text, confirm.input, confirm.masked))
        s.enter("1"); assertTrue(s.shown().text.startsWith("Airtime purchased for other networks cannot be reversed"))
        s.enter("1")
        val last = s.shown()
        assertTrue(last.text, last.final && PushedPin.promptComing(last.text) && last.pushText!!.contains("Send GHS 2.00 Airtime to 0508797323"))
        val pushed = UssdSim.Script("*170#", pushedPin = last.pushText)
        assertEquals(BankUssd.ScreenKind.PIN, BankUssd.kind(pushed.shown().text, pushed.shown().input, pushed.shown().masked))
        pushed.enter("1234")
        assertTrue(pushed.shown().text, pushed.shown().text.contains("sent GHS 2.00 airtime to 0508797323") && pushed.shown().text.contains("GHS 10.50"))
        // Airtime for this phone: straight to the PIN.
        val me = UssdSim.Script("*170#"); me.enter("3"); me.enter("1"); me.enter("1"); me.enter("1")
        assertTrue(me.shown().text, me.shown().masked && me.shown().text.contains("Enter MM PIN"))
    }
}
