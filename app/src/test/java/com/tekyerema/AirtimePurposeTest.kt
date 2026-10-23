package com.tekyerema

import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.MoneyAsk.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit, 22 Oct 2026: "… airtime to call my wife" is what the airtime is for, not who it is for. */
class AirtimePurposeTest {
    private fun air(amount: String? = null, number: String? = null, name: String? = null, self: Boolean = true) =
        MoneyAsk.Ask(Kind.AIRTIME, amount, number, null, name, null, self)

    @Test fun airtimeToCallSomeoneIsAirtimeForThisPhone() {
        assertEquals(air("5"), MoneyAsk.parse("buy 5 cedis airtime to call my wife"))
        assertEquals(air(), MoneyAsk.parse("buy airtime to call my wife"))
    }

    /** Models session's review, 23 Oct 2026: only the purpose clause goes — never the amount, the person or a question after it. */
    @Test fun whatComesAfterThePurposeIsKept() {
        assertEquals(air("5"), MoneyAsk.parse("buy airtime to call my wife, five cedis"))
        assertEquals(air(name = "Kofi", self = false), MoneyAsk.parse("buy airtime to talk to my mother, for Kofi"))
        assertEquals(air("5"), MoneyAsk.parse("buy airtime to call my wife for 5 cedis"))
        // The recogniser writes no commas (re-review, 23 Oct 2026).
        assertEquals(air("5"), MoneyAsk.parse("buy airtime to call my wife five cedis"))
        assertEquals(air("5"), MoneyAsk.parse("buy airtime to call my wife 5 cedis"))
        val question = MoneyAsk.parse("buy airtime to call abroad, how much does it cost")
        assertTrue("a question is not an airtime read-back: $question", question?.kind != Kind.AIRTIME)
    }

    /** "to phone number …" is a number, never a purpose: it is not cut (re-review N5). */
    @Test fun aPhoneNumberClauseIsNotAPurpose() {
        val r = MoneyAsk.parse("buy 5 cedis airtime to phone number zero five five")
        assertTrue("not read as this phone: $r", r == null || r.forSelf != true || r.number != null || r.lostNumber != null)
    }

    @Test fun aPersonOrANumberTheAirtimeIsForIsKept() {
        assertEquals(air("5", name = "Prince Melanin", self = false), MoneyAsk.parse("send 5 cedis airtime to Prince Melanin"))
        assertEquals(air(name = "my mother", self = false), MoneyAsk.parse("buy airtime for my mother"))
        assertEquals(air("5", number = "0552093690", self = false), MoneyAsk.parse("send 5 cedis airtime to phone number 0552093690"))
        assertEquals(air("5", number = "0552093690", self = false), MoneyAsk.parse("buy 5 cedis airtime to phone number 0552093690"))
    }
}
