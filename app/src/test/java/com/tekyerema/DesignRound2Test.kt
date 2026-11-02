package com.tekyerema

import com.tekyerema.history.HistoryEntry
import com.tekyerema.history.Outcome
import com.tekyerema.ui.assistant.KeyFigure
import com.tekyerema.ui.home.Usuals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design round 2 (2 Nov 2026): the big figure on answers (KeyFigure) and "Your usual" tiles (Usuals). */
class DesignRound2Test {

    // ---------------------------------------------------------------- KeyFigure

    private fun fig(s: String) = KeyFigure.of(s)

    @Test fun `one amount is the figure`() {
        assertEquals(KeyFigure.Figure(KeyFigure.Kind.MONEY, "GH₵ 120.50"), fig("Your balance is GHS 120.50."))
        assertEquals("GH₵ 50", fig("You have 50 cedis left.")?.shown)
        assertEquals("GH₵ 1,250.00", fig("Your MoMo balance is GH₵ 1,250.00")?.shown)
        assertEquals("GH₵ 5", fig("Airtime of ₵5 bought.")?.shown)
    }

    @Test fun `the same amount twice is still one figure`() {
        assertEquals("GH₵ 20", fig("You sent GHS 20 to Kofi. GHS 20 left your wallet.")?.shown)
    }

    @Test fun `two amounts pick the balance one or none`() {
        assertEquals("GH₵ 120.50", fig("You sent GHS 20 to Ama. Your balance is GHS 120.50.")?.shown)
        assertEquals("GH₵ 98", fig("Your new balance: 98 cedis, after a 2 cedis fee.")?.shown)
        assertNull("no way to know which", fig("You sent GHS 20 to Ama and GHS 30 to Kofi."))
    }

    @Test fun `two amounts never fall through to another kind`() {
        assertNull(fig("At 10:45 you sent GHS 20 and GHS 30."))
    }

    @Test fun `times`() {
        assertEquals(KeyFigure.Figure(KeyFigure.Kind.TIME, "10:45 AM"), fig("It's 10:45 a.m."))
        assertEquals("9:05 PM", fig("Your alarm is set for 9:05 pm.")?.shown)
        assertEquals("14:30", fig("The meeting is at 14:30.")?.shown)
        assertNull("two times: which?", fig("Alarms at 6:00 AM and 7:30 AM."))
    }

    @Test fun `percent`() {
        assertEquals(KeyFigure.Figure(KeyFigure.Kind.PERCENT, "80%"), fig("Battery is at 80% and charging."))
        assertEquals("45%", fig("The battery is at 45 percent.")?.shown)
    }

    @Test fun `ghana phone numbers`() {
        assertEquals(KeyFigure.Figure(KeyFigure.Kind.PHONE, "024 123 4567"), fig("Kofi's number is 0241234567."))
        assertEquals("024 123 4567", fig("Ama: +233 24 123 4567")?.shown)
        assertEquals("055 987 6543", fig("Call 055-987-6543 for the shop.")?.shown)
        assertNull("too long to be a phone number", fig("Reference 02412345678901."))
    }

    @Test fun `plain words and long answers have none`() {
        assertNull(fig("Calculator is open."))
        assertNull(fig("It is cloudy today with a chance of rain."))
        assertNull(fig(""))
        assertNull(fig("Your balance is GHS 12. " + "More words. ".repeat(30)))
    }

    // ---------------------------------------------------------------- Usuals

    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private fun e(title: String, daysAgo: Int, outcome: Outcome = Outcome.COMPLETED) = HistoryEntry(now - daysAgo * day, title, outcome)

    @Test fun `asked twice and done becomes a usual`() {
        val r = Usuals.rank(listOf(e("Check my balance", 3), e("check my balance.", 1), e("What time is it", 2)), now)
        assertEquals(listOf("check my balance"), r.map { it.key })
        assertEquals("Check my balance", r.first().label)   // the latest wording, capitalised, no full stop
        assertEquals(2, r.first().count)
    }

    @Test fun `only verified completions in the last 30 days count`() {
        val r = Usuals.rank(listOf(
            e("Read my messages", 2, Outcome.FAILED), e("Read my messages", 1, Outcome.STOPPED),
            e("Open WhatsApp", 40), e("Open WhatsApp", 45),
            e("Turn on the torch", 1, Outcome.UNVERIFIED), e("Turn on the torch", 2, Outcome.UNVERIFIED)
        ), now)
        assertTrue(r.isEmpty())
    }

    @Test fun `money with an amount and destructive requests never become one tap`() {
        assertFalse(Usuals.safe("Send 50 cedis to Kofi"))
        assertFalse(Usuals.safe("Buy airtime for 5 cedis"))
        assertFalse(Usuals.safe("Pay GHS 20 to Ama"))
        assertFalse(Usuals.safe("Delete my messages"))
        assertFalse(Usuals.safe("Uninstall WhatsApp"))
        assertFalse(Usuals.safe("yes"))
        assertFalse(Usuals.safe("Stop"))
        assertTrue(Usuals.safe("Check my balance"))
        assertTrue(Usuals.safe("Call Mum"))
        assertTrue(Usuals.safe("Call 0241234567"))
        assertTrue(Usuals.safe("What time is it"))
    }

    @Test fun `order is count then recency, three at most, hidden ones never`() {
        val list = listOf(
            e("Call Mum", 5), e("Call Mum", 4), e("Call Mum", 3),
            e("Check my balance", 9), e("Check my balance", 8),
            e("Read my messages", 2), e("Read my messages", 1),
            e("What time is it", 6), e("What time is it", 6)
        )
        assertEquals(listOf("call mum", "read my messages", "what time is it", "check my balance"), Usuals.rank(list, now).map { it.key })
        assertEquals(listOf("call mum", "what time is it", "check my balance"),
            Usuals.rank(list, now, hidden = setOf("read my messages")).map { it.key })
    }

    @Test fun `tiles keep their place during the day and new ones join at the end`() {
        fun u(k: String, n: Int) = Usuals.Usual(k, k, n, now)
        val ranked = listOf(u("read", 9), u("call", 5), u("time", 3))
        // Yesterday: call, time. Today "read" overtook both — it joins at the end, nothing moves.
        assertEquals(listOf("call", "time", "read"), Usuals.settle(listOf("call", "time"), ranked, newDay = false).map { it.key })
        // A new day: the order is redone.
        assertEquals(listOf("read", "call", "time"), Usuals.settle(listOf("call", "time"), ranked, newDay = true).map { it.key })
        // A tile no longer a candidate (removed, or fell out of the window) goes; the rest keep their order.
        assertEquals(listOf("time", "read"), Usuals.settle(listOf("gone", "time"), listOf(u("read", 9), u("time", 3)), newDay = false).map { it.key })
        // Full: a new candidate waits for tomorrow.
        assertEquals(listOf("a", "b", "c"), Usuals.settle(listOf("a", "b", "c"),
            listOf(u("z", 99), u("a", 2), u("b", 2), u("c", 2)), newDay = false).map { it.key })
    }
}
