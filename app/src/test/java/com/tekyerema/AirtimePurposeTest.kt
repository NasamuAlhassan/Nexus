package com.tekyerema

import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.MoneyAsk.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit, 22 Oct 2026: "… airtime to call my wife" is what the airtime is for, not who it is for. */
class AirtimePurposeTest {
    private fun air(amount: String? = null, number: String? = null, name: String? = null, self: Boolean = true) =
        MoneyAsk.Ask(Kind.AIRTIME, amount, number, null, name, null, self)

    @Test fun airtimeToCallSomeoneIsAirtimeForThisPhone() {
        assertEquals(air("5"), MoneyAsk.parse("buy 5 cedis airtime to call my wife"))
        assertEquals(air(), MoneyAsk.parse("buy airtime to call my wife"))
    }

    @Test fun aPersonOrANumberTheAirtimeIsForIsKept() {
        assertEquals(air("5", name = "Prince Melanin", self = false), MoneyAsk.parse("send 5 cedis airtime to Prince Melanin"))
        assertEquals(air(name = "my mother", self = false), MoneyAsk.parse("buy airtime for my mother"))
        assertEquals(air("5", number = "0552093690", self = false), MoneyAsk.parse("send 5 cedis airtime to phone number 0552093690"))
        assertEquals(air("5", number = "0552093690", self = false), MoneyAsk.parse("buy 5 cedis airtime to phone number 0552093690"))
    }
}
