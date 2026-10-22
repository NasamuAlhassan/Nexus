package com.tekyerema

import com.tekyerema.money.ussd.PushedPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pushed PIN prompt (owner, A20e, 13 October 2026): which last screens announce it, and what is said around it. */
class PushedPinTest {

    @Test fun the_last_screens_that_announce_a_pushed_prompt() {
        // MTN's own words, as logged on the A20e (3 and 13 October 2026).
        assertTrue(PushedPin.promptComing("Your request to send airtime to 0508797323 is being processed. You will soon receive payment prompt. If prompt delays, dial *170# to approve."))
        assertTrue(PushedPin.promptComing("Thank you. You will receive a prompt to enter your MoMo PIN shortly."))
        assertTrue(PushedPin.promptComing("Your request is being processed.\nYou will soon receive a payment prompt."))
        assertTrue(PushedPin.promptComing("Transaction initiated. Please approve the payment when prompted."))
        // Not a coming prompt: the prompt itself, a receipt, a refusal, a menu.
        assertFalse(PushedPin.promptComing("Send GHS 0.1 Airtime to 0552093690. Fee is GHS 0.00. Enter MM PIN or 2 to cancel"))
        assertFalse(PushedPin.promptComing("Payment made for GHS 0.10 to PRINCE NASAMU ALHASSAN 0552093690. Current Balance: GHS 12.40. Transaction ID: 76712833868."))
        assertFalse(PushedPin.promptComing("Not enough funds to perform this transaction."))
        assertFalse(PushedPin.promptComing("Choose payment mode 1. Airtime 2. Mobile Money"))
    }

    @Test fun the_lines_are_short_and_name_the_network() {
        val waiting = PushedPin.waitingLine("MTN")
        assertTrue(waiting, waiting.startsWith("MTN will ask for your PIN in a moment"))
        assertTrue("short enough to be over before the prompt lands", waiting.length < 140)
        assertEquals("MTN is asking for your PIN. Enter it now.", PushedPin.arrivedLine())
        // the approvals are opened FOR the user — never "dial star 1 7 0 hash" (owner, 22 Oct 2026)
        assertTrue(PushedPin.lateLine("MTN").contains("open my approvals") && !PushedPin.lateLine("MTN").contains("dial"))
        assertTrue(PushedPin.confirmFirstLine("MTN").contains("confirm"))
        // Nothing here ever says, keeps or types a PIN digit.
        for (l in listOf(waiting, PushedPin.arrivedLine(), PushedPin.lateLine("MTN"), PushedPin.confirmFirstLine("MTN")))
            assertFalse(l, Regex("\\b\\d{4}\\b").containsMatchIn(l))
    }
}
