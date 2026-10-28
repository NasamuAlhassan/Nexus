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

    @Test fun `a class v2 confuses needs a higher bar - a 1 cedi coin at 0_94 does not speak, at 0_98 it does`() {
        val g = a()
        assertNull(g.offer("coin_1_cedi", 0.94, 100))
        assertNull(g.offer("coin_1_cedi", 0.94, 600))        // two agreeing frames, but under that class's bar
        assertNull(g.offer("coin_1_cedi", 0.98, 1100))
        assertEquals("One cedi — a coin.", g.offer("coin_1_cedi", 0.98, 1600))
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
        assertNull(g.offer("note_50", 0.99, 1100))           // two agree, but the 5 a moment ago is still a rival (28 Oct 2026)
        assertNull(g.offer("note_50", 0.99, 1600))
        assertEquals("Fifty cedis.", g.offer("note_50", 0.99, 2100))   // four of five: the model has settled
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

    /** The owner's 50 cedis note on the Infinix, 28 Oct 2026 — frames as logged (time ms, class, confidence). It said "Two hundred
     *  cedis." three times; the seconds before each held other fairly-sure readings. */
    private fun replay(vararg f: Triple<Long, String, Double>): List<String> { val g = a(); return f.mapNotNull { (t, c, p) -> g.offer(c, p, t) } }

    @Test fun `a 50 note the model flickers on is never said as 200`() {
        assertEquals(emptyList<String>(), replay(Triple(38084, "note_50", 0.36), Triple(39537, "note_100", 0.84), Triple(40058, "note_10", 0.64),
            Triple(41082, "note_50", 0.37), Triple(41583, "note_200", 0.44), Triple(42243, "note_200", 0.0), Triple(42777, "note_200", 0.97),
            Triple(43247, "note_200", 0.98)))
        assertEquals(emptyList<String>(), replay(Triple(39882, "note_10", 0.42), Triple(40464, "note_100", 0.48), Triple(40878, "note_50", 0.61),
            Triple(41482, "note_200", 0.0), Triple(42048, "note_200", 0.98), Triple(42569, "note_200", 0.95)))
        assertEquals(emptyList<String>(), replay(Triple(47093, "note_20", 0.52), Triple(47946, "not_money", 0.61), Triple(48201, "note_200", 0.57),
            Triple(48714, "note_100", 0.81), Triple(49147, "not_money", 0.51), Triple(49684, "note_200", 0.78), Triple(50283, "note_200", 0.93),
            Triple(50809, "note_200", 0.86)))
    }

    @Test fun `a note held steady is still said, and a new note once the old readings have passed`() {
        assertEquals(listOf("Two hundred cedis."), replay(Triple(0, "note_200", 0.96), Triple(500, "note_200", 0.97)))
        val said = replay(Triple(0, "note_10", 0.96), Triple(500, "note_10", 0.97), Triple(5000, "note_20", 0.95), Triple(5500, "note_20", 0.96))
        assertEquals(listOf("Ten cedis.", "Twenty cedis."), said)
    }
}

/** The spoken phrases that open each camera mode — the money ones were dead for a day because a Kotlin
 *  "\b" is a BACKSPACE, not a word boundary (A20e 2026-10-01). */
class CameraPhrasesTest {
    private fun mode(said: String): String? =
        (com.tekyerema.agent.FastIntentClassifier.classify(said) as? com.tekyerema.agent.FastIntent.Camera)?.mode

    @Test fun `money phrases open the money reader`() {
        for (s in listOf("what note is this", "how much money is this", "check this money", "count my cash",
                "which note is this", "what kind of note is this", "how much is this"))
            assertEquals(s, "currency", mode(s))
    }

    @Test fun `reading a held thing opens the camera, not the screen reader`() {
        for (s in listOf("read this letter", "read this label", "read my page", "what does this receipt say",
                "read what I'm holding", "read it with the camera", "scan this"))
            assertEquals(s, "text", mode(s))
    }

    @Test fun `colour and describe still route as before, and the screen reader is untouched`() {
        assertEquals("color", mode("what colour is this"))
        assertEquals("describe", mode("what's in front of me"))
        // A bare "read this" is NOT a camera intent — it stays the screen reader unless the Camera app is in front
        // (that case is handled in LocalFastRouter, which has the foreground app).
        assertEquals(null, mode("read this"))
        assertEquals(null, mode("read my screen"))
    }
}
