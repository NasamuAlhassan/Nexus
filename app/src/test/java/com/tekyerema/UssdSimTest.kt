package com.tekyerema

import com.tekyerema.accessibility.TelephonyWindows
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.UssdSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug USSD simulator (P2-01): MTN's `*170#` as the navigator's own screen reader understands it, and inert elsewhere. */
class UssdSimTest {
    private fun kind(s: UssdSim.Shown) = MenuModel.read(s.text, s.input, s.masked).kind

    @Test fun `a send walks menus, prompts, the registered-name PIN screen and the receipt`() {
        val s = UssdSim.Script("*170#")
        assertEquals(MenuModel.Kind.MENU, kind(s.shown()))
        assertTrue(MenuModel.read(s.shown().text, true, false).rows.any { it.second.contains("Transfer Money") })
        s.enter("1"); assertEquals(MenuModel.Kind.MENU, kind(s.shown()))
        assertTrue(s.shown().text.contains("MoMo User"))
        s.enter("1"); assertEquals(MenuModel.Kind.INPUT, kind(s.shown())); assertEquals("Enter mobile number", s.shown().text)
        s.enter("0552093690"); assertEquals("Confirm Number", s.shown().text)
        s.enter("0552093690"); assertEquals("Enter Amount", s.shown().text)
        s.enter("0.1"); assertEquals("Enter Reference", s.shown().text)
        s.enter("test")
        val pin = s.shown()
        assertTrue(pin.masked); assertTrue(pin.input)
        assertEquals(MenuModel.Kind.PIN, kind(pin))
        // MTN's own PIN sentence (A20e, 3 October 2026) — what Boundary checks the name and the amount against.
        assertEquals("Transfer to PRINCE NASAMU ALHASSAN for GHS 0.1 with Reference: test. Fee is GHS 0.00, Tax amount is GHS 0.00. Enter MM PIN", pin.text)
        s.enter("1234")
        val receipt = s.shown()
        assertTrue(receipt.final); assertFalse(receipt.input)
        assertTrue(receipt.text, Regex("Payment made for GHS 0\\.10 to PRINCE NASAMU ALHASSAN 0552093690\\. Current Balance: GHS 12\\.40\\. Available Balance: GHS 12\\.40\\. " +
            "Reference: test\\. Transaction ID: \\d{11}\\. Fee charged: GHS 0\\.00 TAX charged: GHS 0\\.00\\.").matches(receipt.text))
        // Two sessions never share a transaction ID with a fixed one (the ledger would take the second receipt for a repeat).
        assertTrue(receipt.text.contains(Regex("Transaction ID: \\d{11}")))
        // More than the wallet holds: MTN's own ending, nothing sent.
        val poor = UssdSim.Script("*170#"); listOf("1", "1", "0552093690", "0552093690", "50", "rent", "1234").forEach { poor.enter(it) }
        assertEquals("Not enough funds to perform this transaction.", poor.shown().text); assertTrue(poor.shown().final)
    }

    @Test fun `a balance check walks Wallet, the PIN and the balance`() {
        val s = UssdSim.Script("*170#")
        s.enter("6"); assertTrue(s.shown().text.contains("Check Balance"))
        s.enter("1"); assertEquals("Enter MM PIN", s.shown().text); assertTrue(s.shown().masked)
        assertEquals(MenuModel.Kind.PIN, kind(s.shown()))
        s.enter("1234")
        assertEquals("Current Balance: GHS 12.50 Available Balance: GHS 12.50", s.shown().text)
        assertTrue(s.shown().final)
    }

    @Test fun `the network's refusals end the session as it does`() {
        val wrong = UssdSim.Script("*170#"); wrong.enter("9")
        assertEquals("Incorrect choice.", wrong.shown().text); assertTrue(wrong.shown().final)
        val badPin = UssdSim.Script("*170#"); badPin.enter("6"); badPin.enter("1"); badPin.enter("0000")
        assertEquals("Wrong PIN. You have 2 attempts left.", badPin.shown().text)
        val mismatch = UssdSim.Script("*170#"); mismatch.enter("1"); mismatch.enter("1"); mismatch.enter("0552093690"); mismatch.enter("0552093691")
        assertEquals("The numbers do not match.", mismatch.shown().text)
        val big = UssdSim.Script("*170#"); big.enter("1"); big.enter("1"); big.enter("0552093690"); big.enter("0552093690"); big.enter("5000")
        assertEquals("Transfer limit exceeded.", big.shown().text)
        val other = UssdSim.Script("*124#")
        assertTrue(other.shown().text.startsWith("Connection problem"))
    }

    @Test fun `off by default and never widening the production detector`() {
        assertFalse(UssdSim.enabled())
        assertFalse(UssdSim.isSimWindow("com.tekyerema", UssdSim.TITLE))
        assertFalse(TelephonyWindows.matches("com.tekyerema"))
        assertTrue(TelephonyWindows.matches("com.android.phone"))
    }
}
