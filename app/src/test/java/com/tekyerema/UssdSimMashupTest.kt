package com.tekyerema

import com.tekyerema.money.BankUssd
import com.tekyerema.money.ussd.PushedPin
import com.tekyerema.money.ussd.UssdSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulator's Mashup branch (*567#), screen for screen as the A20e showed it — including the PIN the network pushes later. */
class UssdSimMashupTest {

    @Test fun a_mashup_for_someone_else_paid_with_momo_ends_with_the_promise_then_the_pushed_pin_then_the_receipt() {
        val s = UssdSim.Script("*567#")
        assertTrue(s.shown().text.startsWith("Welcome to MTN Pulse"))
        s.enter("1"); assertTrue(s.shown().text.contains("MashUp for Others"))
        s.enter("2"); assertEquals("Enter Phone Number", s.shown().text)
        s.enter("0552093690"); assertEquals("Confirm Phone Number", s.shown().text)
        s.enter("0552093690"); assertTrue(s.shown().text.startsWith("MashUp Offers"))
        s.enter("2")
        assertTrue(s.shown().text, s.shown().text.contains("MashUp GHC 5 Bundle gives you") && s.shown().text.endsWith("1. Buy"))
        s.enter("1"); assertTrue(s.shown().text.startsWith("Choose payment mode"))
        s.enter("2")
        val last = s.shown()
        assertTrue(last.final)
        assertTrue(last.text, PushedPin.promptComing(last.text))
        assertTrue(last.pushAfterMs > 0 && last.pushText!!.contains("Enter MM PIN or 2 to cancel"))
        // The pushed dialog, as a session of its own.
        val pushed = UssdSim.Script("*567#", pushedPin = last.pushText)
        val p = pushed.shown()
        assertTrue(p.input && p.masked && !p.final)
        assertEquals(BankUssd.ScreenKind.PIN, BankUssd.kind(p.text, p.input, p.masked))
        pushed.enter("1234")
        val receipt = pushed.shown()
        assertTrue(receipt.text, receipt.final && receipt.text.contains("successfully purchased GHC5 MashUp Bundle for 0552093690"))
        assertTrue(receipt.text, receipt.text.contains("GHS 7.50"))   // 12.50 − 5
    }

    @Test fun a_mashup_for_this_phone_at_one_cedi_goes_through_the_mixes_and_asks_the_pin_at_once() {
        val s = UssdSim.Script("*567#")
        s.enter("1"); s.enter("1"); s.enter("1")                    // proceed → for self → GHC 1 tier
        assertTrue(s.shown().text, s.shown().text.contains("Enter amount GHC"))
        s.enter("1")                                                // the fixed GHC 1
        assertTrue(s.shown().text, s.shown().text.contains("25.45MB and 11.17Mins"))
        s.enter("2"); assertTrue(s.shown().text.startsWith("Choose payment mode"))
        s.enter("2")
        val pin = s.shown()
        assertTrue(pin.text, pin.input && pin.masked && pin.text.contains("Enter MM PIN or 2 to cancel"))
        s.enter("2"); assertEquals("Transaction cancelled.", s.shown().text)
    }

    @Test fun the_app_only_mix_and_a_wrong_pin_end_the_session_the_way_the_network_does() {
        val s = UssdSim.Script("*567#")
        s.enter("1"); s.enter("1"); s.enter("1"); s.enter("1")
        s.enter("1")
        assertTrue(s.shown().text, s.shown().final && s.shown().text.contains("only available on MyMTN App"))
        val w = UssdSim.Script("*567#", pushedPin = "Buy MashUp GHC 5 for 0552093690. Fee is GHS 0.00. Enter MM PIN or 2 to cancel")
        w.enter("0000")
        assertTrue(w.shown().text, w.shown().final && w.shown().text.startsWith("Wrong PIN"))
        val a = UssdSim.Script("*567#")
        a.enter("1"); a.enter("1"); a.enter("2"); a.enter("1"); a.enter("1")   // proceed, for self, GHC 5 bundle, Buy, Airtime
        assertTrue(a.shown().text, a.shown().final && a.shown().text.contains("successfully purchased GHC5"))
    }
}
