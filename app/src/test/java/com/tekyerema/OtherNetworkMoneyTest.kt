package com.tekyerema

import com.tekyerema.money.BankUssd
import com.tekyerema.money.ussd.Boundary
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.SemanticMatch
import com.tekyerema.money.ussd.TxContext
import com.tekyerema.money.ussd.TxIntent
import com.tekyerema.money.ussd.UssdNavigatorTool
import com.tekyerema.money.ussd.UssdSim
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Money and airtime to ANOTHER network's number (the owner, 14 Oct 2026: "I tried sending to a different network and it's
 * having a hard time"). MTN's real screens up to the reference (A20e, 2–3 Oct 2026); the PIN screen after it is modelled.
 */
class OtherNetworkMoneyTest {

    @Test fun the_simulator_walks_money_to_a_telecel_wallet_with_its_fee() {
        val s = UssdSim.Script("*170#")
        s.enter("1"); s.enter("5")
        assertTrue(s.shown().text, s.shown().text.startsWith("Transfer Money to Other Network") && s.shown().text.contains("2) Telecel"))
        s.enter("2"); assertEquals("Enter mobile number", s.shown().text)
        s.enter("0508797323"); assertEquals("Confirm Mobile Number", s.shown().text)
        s.enter("0508797323"); assertEquals("Enter Amount to Transfer", s.shown().text)
        s.enter("5"); assertEquals("Enter Reference ID", s.shown().text)
        s.enter("test")
        val pin = s.shown()
        assertTrue(pin.input && pin.masked)
        assertEquals(BankUssd.ScreenKind.PIN, BankUssd.kind(pin.text, pin.input, pin.masked))
        s.enter("1234")
        assertTrue(s.shown().text, s.shown().text.contains("Fee charged: GHS 0.50") && s.shown().text.contains("GHS 7.00"))
        // 12 cedis + the fee is more than the 12.50 wallet: MTN's own refusal, before any money moves.
        val poor = UssdSim.Script("*170#"); poor.enter("1"); poor.enter("5"); poor.enter("2"); poor.enter("0508797323")
        poor.enter("0508797323"); poor.enter("12.20"); poor.enter("x"); poor.enter("1234")
        assertEquals("Not enough funds to perform this transaction.", poor.shown().text)
    }

    @Test fun the_navigator_picks_other_networks_then_the_recipients_network() {
        val transfer = TelcoRows.of(UssdSim.Script.TRANSFER)
        val v = SemanticMatch.best(TxIntent.SEND_MONEY, transfer, null, crossNetwork = true, recipientNetwork = "telecel")!!
        assertEquals("5", v.option)
        assertEquals("2", SemanticMatch.networkRow(TelcoRows.of(UssdSim.Script.TRANSFER_OTHER), "telecel")!!.first)
    }

    @Test fun the_pin_line_says_the_fee_and_asks_to_check_the_number_when_no_name_is_shown() {
        val tx = TxContext(TxIntent.SEND_MONEY, "mtn", recipient = "0508797323", amount = "5", reference = "test", confirmedUpFront = true)
        val m = MenuModel.read("Transfer GHS 5.00 to 0508797323 on Telecel with Reference: test. Fee is GHS 0.50. Enter MM PIN", hasInput = true, masked = true)
        val v = Boundary.decide(tx, m)
        assertTrue(v.toString(), v is Boundary.Verdict.HandOff)
        val line = (v as Boundary.Verdict.HandOff).line
        assertTrue(line, line.contains("5 cedis") && line.contains("no name") && line.contains("0 5 0, 8 7 9, 7 3 2 3"))
        assertTrue(line, line.contains("fee", ignoreCase = true))
        // A different number on the screen stops the walk.
        val wrong = MenuModel.read("Transfer GHS 5.00 to 0501111111 on Telecel with Reference: test. Fee is GHS 0.50. Enter MM PIN", hasInput = true, masked = true)
        assertTrue(Boundary.decide(tx, wrong) is Boundary.Verdict.Stop)
    }

    @Test fun the_guided_pin_after_the_reference_says_the_checked_line_not_a_bare_enter_your_pin() {
        // Emulator, 14 Oct 2026: after the reference question the guide reached the PIN and said only "MTN is asking for your
        // PIN" — no fee, no "check the number". A navigator hand-over now carries what was approved, and the line is checked.
        val flow = com.tekyerema.money.TelcoUssd.Flow(com.tekyerema.money.TelcoUssd.NAV, "*170#", emptyList(), "send money", "MTN",
            number = "0508797323", amount = "5", reference = "test", intent = "SEND_MONEY", sim = "mtn")
        val tx = com.tekyerema.money.TelcoUssdTool.navTx(flow)!!
        assertEquals(TxIntent.SEND_MONEY, tx.intent); assertEquals("0508797323", tx.recipient); assertEquals("5", tx.amount)
        val pinText = "Transfer GHS 5.00 to 0508797323 on Telecel with Reference: test. Fee is GHS 0.50. Enter MM PIN"
        val line = (Boundary.decide(tx, MenuModel.read(pinText, hasInput = true, masked = true)) as Boundary.Verdict.HandOff).line
        val view = com.tekyerema.money.TelcoSession.View(flow, pinText, BankUssd.ScreenKind.PIN, 0L, 0L, handOffLine = line)
        val said = com.tekyerema.money.TelcoGuide.handOffLine(view)
        assertTrue(said, said.startsWith(line) && said.contains("no name"))
        // Without a checked line (a guided menu of our own), the short line as before.
        val plain = com.tekyerema.money.TelcoGuide.handOffLine(view.copy(handOffLine = null))
        assertTrue(plain, plain.contains("asking for your PIN"))
        assertEquals(null, com.tekyerema.money.TelcoUssdTool.navTx(flow.copy(key = com.tekyerema.money.TelcoUssd.MENU)))
    }

    @Test fun the_airtime_read_back_says_the_two_warnings_as_one_sentence() {
        val args = JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.SEND_AIRTIME).put("number", "0508797323")
            .put("amount", "2").put("_sim", "mtn")
        val said = UssdNavigatorTool.confirmationPrompt(args)
        assertTrue(said, said.contains("Airtime to another network can't be reversed, so check the number."))
        assertFalse(said, said.contains("before you send"))
    }
}

/** The rows of a menu screen, as the navigator reads them. */
private object TelcoRows {
    fun of(screen: String): List<Pair<String, String>> = com.tekyerema.money.TelcoUssd.rows(screen)
}
