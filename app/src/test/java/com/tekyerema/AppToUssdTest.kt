package com.tekyerema

import com.tekyerema.money.AppToUssd
import com.tekyerema.money.AppToUssd.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppToUssdTest {
    private val yes: (String) -> Boolean = { it.trim().lowercase().startsWith("yes") }
    private val inApp = listOf(
        "open_app({\"app\":\"MoMo\"}) → opened com.mtn.momo",
        "tap({\"text\":\"Send Money\"}) → ok")

    @Test fun onlineOrNotAnAppTaskIsLeftAlone() {
        assertNull(AppToUssd.decide(inApp, online = true, menuCanDoIt = true, isYes = yes))
        assertNull(AppToUssd.decide(listOf("run_ussd({\"code\":\"*124#\"}) → ok"), online = false, menuCanDoIt = true, isYes = yes))
        assertNull(AppToUssd.decide(emptyList(), online = false, menuCanDoIt = true, isYes = yes))
    }

    @Test fun theNetworkDropsMidAppTaskTheMenuIsOffered() {
        assertEquals(Outcome.Ask, AppToUssd.decide(inApp, online = false, menuCanDoIt = true, isYes = yes))
    }

    @Test fun noOfflineMenuForTheRequestSaysSo() {
        assertEquals(Outcome.Stop(AppToUssd.NO_MENU), AppToUssd.decide(inApp, online = false, menuCanDoIt = false, isYes = yes))
    }

    @Test fun yesSwitchesWithOnlyTheStepsSinceNoStops() {
        val asked = inApp + "ask_user(\"${AppToUssd.ASK}\") → the user answered: \"yes please\""
        val o = AppToUssd.decide(asked, online = false, menuCanDoIt = true, isYes = yes)
        assertTrue(o is Outcome.Switch && o.history.isEmpty())
        val later = asked + "mobile_money({\"_kind\":\"balance\"}) → ok"
        val o2 = AppToUssd.decide(later, online = false, menuCanDoIt = true, isYes = yes)
        assertEquals(Outcome.Switch(listOf("mobile_money({\"_kind\":\"balance\"}) → ok")), o2)
        val no = inApp + "ask_user(\"${AppToUssd.ASK}\") → the user answered: \"no\""
        assertEquals(Outcome.Stop(AppToUssd.STOPPED), AppToUssd.decide(no, online = false, menuCanDoIt = true, isYes = yes))
    }

    @Test fun afterTheConfirmationTheMoMoMessagesAreReadFirst() {
        val d = AppToUssd.stopDecision(AppToUssd.CHECK_FIRST, inApp)
        assertTrue(d is com.tekyerema.agent.AgentDecision.CallTool && d.name == com.tekyerema.money.MoneyStatusTool.NAME)
        assertEquals(com.tekyerema.agent.AgentDecision.Done(AppToUssd.STOPPED), AppToUssd.stopDecision(AppToUssd.STOPPED, inApp))
    }

    @Test fun afterTheReadBackOrThePinTheMenuIsNeverOffered() {
        val confirmed = inApp + "ask_user(\"Send 20 cedis to Ama Mensah, 024 123 4567?\") → the user answered: \"yes\""
        assertEquals(Outcome.Stop(AppToUssd.CHECK_FIRST), AppToUssd.decide(confirmed, online = false, menuCanDoIt = true, isYes = yes))
        val atPin = inApp + "secure_step(handed to the user at the PIN)"
        assertEquals(Outcome.Stop(AppToUssd.CHECK_FIRST), AppToUssd.decide(atPin, online = false, menuCanDoIt = true, isYes = yes))
        val declined = inApp + "ask_user(\"Send 20 cedis to Ama Mensah?\") → the user answered: \"no\""
        assertEquals(Outcome.Ask, AppToUssd.decide(declined, online = false, menuCanDoIt = true, isYes = yes))
    }
}
