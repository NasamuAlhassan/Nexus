package com.tekyerema

import com.tekyerema.camera.MoneyBurst
import com.tekyerema.camera.MoneyItems
import com.tekyerema.language.TwiCommands
import com.tekyerema.money.MoneyAsk
import com.tekyerema.phone.SimChoice
import com.tekyerema.voice.GhanaWords
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's offline rehearsal on the Infinix, 29 Oct 2026, 21:52–22:46: what missed, as the phone logged it. */
class OwnerNightRehearsalTest {
    private fun burst(vararg f: Pair<String, Double>): MoneyBurst.Verdict? {
        val b = MoneyBurst(windowMs = 2_000L, minFrames = 4); var v: MoneyBurst.Verdict? = null
        f.forEachIndexed { i, (c, p) -> b.offerSingle(c, p, i * 800L)?.let { v = it } }
        return v
    }

    @Test fun `a held 10 never over the gate is read as likely`() {
        // 22:42:16–26: 10 at 0.66, 0.97, 0.81 among weak frames, and no other value over 0.6
        val v = burst("note_10" to 0.66, "note_10" to 0.0, "note_10" to 0.97, "note_10" to 0.0, "note_10" to 0.81)
        assertEquals(MoneyBurst.Verdict.Sure(listOf(MoneyItems.Item(10.0, true)), likely = true), v)
    }

    @Test fun `two values in the window stay not sure`() {
        // 22:42:12–16: 20 at 0.73 and 10 at 0.82 — and the 28 Oct 50 that flickered to 200
        assertEquals(MoneyBurst.Verdict.Unsure, burst("not_money" to 0.57, "note_20" to 0.73, "note_100" to 0.49, "note_10" to 0.82, "note_10" to 0.7))
        assertEquals(MoneyBurst.Verdict.Unsure, burst("note_50" to 0.8, "note_200" to 0.96, "note_50" to 0.7, "note_50" to 0.75))
        // once over the floor is not enough (22:42:02–06)
        assertEquals(MoneyBurst.Verdict.Unsure, burst("not_money" to 0.55, "note_10" to 0.79, "not_money" to 0.57, "not_money" to 0.58, "not_money" to 0.58))
    }

    @Test fun `a slot with no service is not offered`() {
        val mtn = SimChoice.Sim(1, 0, "MTN GH", "MTN"); val none = SimChoice.Sim(2, 1, "No service", null)
        assertEquals(SimChoice.Decision.Use(mtn), SimChoice.decide(listOf(mtn, none), null, null))
        assertTrue(SimChoice.decide(listOf(mtn, SimChoice.Sim(2, 1, "Telecel", "Telecel")), null, null) is SimChoice.Decision.Ask)
    }

    @Test fun `call my husband however frɛ is written`() {
        fun g(s: String) = TwiCommands.gloss(Normalizer.normalize(s, Lang.TWI))
        assertEquals("call my husband", g("fr me kunu ma me"))
        assertEquals("call my husband", g("frɛe me kunu ma me"))
        assertEquals("call my husband", g("frɛ me kunu ma me"))
        assertEquals("send a message to my husband saying “Ɔmfrɛ me”", g("ka kyerɛ mekro sɛ ɔmfrɛ me"))
    }

    @Test fun `one cedi as the English recogniser writes it`() {
        assertEquals("Send 1 cedi to my husband.", GhanaWords.fix("Send once it is to my husband."))
        assertTrue(GhanaWords.fix("Sent 1 cedis to my husband.").startsWith("Send 1"))
        assertEquals(MoneyAsk.Kind.BALANCE_MOMO, MoneyAsk.parse("What's my move balance?")?.kind)
        // a message's own words are not touched
        assertEquals("Tell Ama I sent it", GhanaWords.fix("Tell Ama I sent it"))
    }
}
