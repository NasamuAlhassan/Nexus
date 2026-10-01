package com.tekyerema

import com.tekyerema.camera.CurrencyAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The money gate: a denomination is spoken ONLY on two agreeing confident frames — a wrong amount is worse
 *  than no answer (docs/agent/research/cedi_integration.md). */
class CurrencyAnnouncerTest {

    private fun a() = CurrencyAnnouncer(minConfidence = 0.85, agreeFrames = 2, repeatMs = 4_000L)

    @Test fun `two agreeing confident frames speak, one does not`() {
        val g = a()
        assertNull(g.offer("note_20", 0.97, 100))            // first confident frame: still settling
        assertEquals("Twenty cedis.", g.offer("note_20", 0.96, 600))
    }

    @Test fun `a sub-gate frame never speaks and breaks the run`() {
        val g = a()
        assertNull(g.offer("note_50", 0.70, 100))            // unsure
        assertNull(g.offer("note_50", 0.84, 600))            // still under the bar — never "fifty cedis"
        assertNull(g.offer("note_50", 0.99, 1100))           // run restarts here
        assertEquals("Fifty cedis.", g.offer("note_50", 0.99, 1600))
    }

    @Test fun `a disagreeing frame restarts the run — no flip-flop guesses`() {
        val g = a()
        assertNull(g.offer("note_5", 0.99, 100))
        assertNull(g.offer("note_50", 0.99, 600))            // changed its mind: run restarts, nothing said
        assertEquals("Fifty cedis.", g.offer("note_50", 0.99, 1100))
    }

    @Test fun `not_money is silent however confident`() {
        val g = a()
        assertNull(g.offer("not_money", 0.99, 100))
        assertNull(g.offer("not_money", 0.99, 600))
        assertTrue(g.quiet)
    }

    @Test fun `counting cash announces each change, and repeats only after a pause`() {
        val g = a()
        g.offer("note_10", 0.99, 100)
        assertEquals("Ten cedis.", g.offer("note_10", 0.99, 600))
        g.offer("note_20", 0.99, 1100)
        assertEquals("Twenty cedis.", g.offer("note_20", 0.99, 1600))   // next note: said at once
        assertNull(g.offer("note_20", 0.99, 2100))                       // same note, still in hand: quiet
        assertEquals("Twenty cedis.", g.offer("note_20", 0.99, 9000))    // it came back later: said again
    }

    @Test fun `coins are said as coins, notes as notes`() {
        assertEquals("One cedi.", CurrencyAnnouncer.spokenFor("note_1"))
        assertEquals("Two hundred cedis.", CurrencyAnnouncer.spokenFor("note_200"))
        assertEquals("Fifty pesewas — a coin.", CurrencyAnnouncer.spokenFor("coin_50_pesewas"))
        assertEquals("One cedi — a coin.", CurrencyAnnouncer.spokenFor("coin_1_cedi"))
        assertTrue(CurrencyAnnouncer.isMoney("note_5"))
        assertTrue(!CurrencyAnnouncer.isMoney(CurrencyAnnouncer.NOT_MONEY))
    }

    /** Every label the model can emit has a spoken form — no class ever reaches the user as a raw id. */
    @Test fun `every shipped label speaks as words`() {
        val labels = java.io.File("src/main/assets/cedi/labels.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(16, labels.size)
        for (l in labels.filter { it != CurrencyAnnouncer.NOT_MONEY })
            assertTrue(l, CurrencyAnnouncer.spokenFor(l) != l && CurrencyAnnouncer.spokenFor(l).endsWith("."))
    }
}
