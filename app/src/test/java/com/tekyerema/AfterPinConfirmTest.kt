package com.tekyerema

import com.tekyerema.money.ussd.AfterPinConfirm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MTN's confirm screen after the PIN is recognised; a PIN prompt or a plain receipt is not (21 Oct 2026). */
class AfterPinConfirmTest {
    @Test fun confirmScreensAfterThePin() {
        assertTrue(AfterPinConfirm.looksLikeConfirm("You are buying Mashup 5 GHS bundle.\n1. Confirm\n2. Cancel"))
        assertTrue(AfterPinConfirm.looksLikeConfirm("Pay GHS 5.00 to MTN Bundles? 1) Yes 2) No"))
        assertTrue(AfterPinConfirm.looksLikeConfirm("1:Accept 2:Decline"))
    }

    @Test fun notAPinPromptOrAReceipt() {
        assertFalse(AfterPinConfirm.looksLikeConfirm("Enter MM PIN to confirm"))
        assertFalse(AfterPinConfirm.looksLikeConfirm("Payment made for GHS 5.00 to KOFI MENSAH. Current Balance: GHS 12.28"))
        assertFalse(AfterPinConfirm.looksLikeConfirm("1) Transfer Money 2) MoMoPay & Pay Bill 3) Airtime & Bundles"))
    }
}
