package com.tekyerema

import com.tekyerema.camera.MoneyBurst
import com.tekyerema.camera.MoneyItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Money decided once from a brief capture (owner, 29 Oct 2026). */
class MoneyBurstTest {
    private fun run(vararg f: Triple<Long, String, Double>): MoneyBurst.Verdict? {
        val b = MoneyBurst(); var v: MoneyBurst.Verdict? = null
        for ((t, c, p) in f) { b.offerSingle(c, p, t)?.let { v = it } }
        return v
    }

    @Test fun aHeldNoteIsDecidedOnceFromTheWindow() {
        val v = run(Triple(0, "note_50", 0.95), Triple(500, "note_50", 0.97), Triple(1000, "note_50", 0.96), Triple(1500, "note_50", 0.9),
            Triple(2000, "note_50", 0.93), Triple(2500, "note_50", 0.99))
        assertEquals(MoneyBurst.Verdict.Sure(listOf(MoneyItems.Item(50.0, true))), v)
    }

    /** The owner's 50 cedis note, frames as logged 28 Oct 2026: 200 at 0.95+ in some frames, unsure in the others. */
    @Test fun aNoteTheModelFlickersOnIsNotSure() {
        val v = run(Triple(0, "note_50", 0.61), Triple(500, "note_200", 0.98), Triple(1000, "note_200", 0.95), Triple(1500, "note_50", 0.75),
            Triple(2000, "note_100", 0.80), Triple(2500, "note_200", 0.57))
        assertEquals(MoneyBurst.Verdict.Unsure, v)
    }

    @Test fun nothingBeforeMoneyAndOneDecisionOnly() {
        val b = MoneyBurst()
        assertNull(b.offerSingle("not_money", 0.99, 0))
        assertEquals(false, b.started)
        listOf(100L, 600L, 1100L, 1600L).forEach { assertNull(b.offerSingle("note_10", 0.97, it)) }
        assertTrue(b.offerSingle("note_10", 0.97, 2200) is MoneyBurst.Verdict.Sure)
        assertNull(b.offerSingle("note_10", 0.97, 2700))                    // decided: no second answer
    }

    @Test fun aPileIsSureOnlyWhenEveryPieceIsReadAndTheCloudCountMatches() {
        val b = MoneyBurst()
        val pile = MoneyItems.Count(listOf(MoneyItems.Item(10.0, true), MoneyItems.Item(1.0, false)), 0, 0)
        var v: MoneyBurst.Verdict? = null
        for (t in listOf(0L, 600L, 1200L, 1800L, 2400L)) b.offerItems(pile, t)?.let { v = it }
        val items = (v as MoneyBurst.Verdict.Sure).items
        assertTrue(MoneyBurst.agrees(items, mapOf((10.0 to true) to 1, (1.0 to false) to 1)))
        assertEquals(false, MoneyBurst.agrees(items, mapOf((10.0 to true) to 1, (2.0 to false) to 1)))
        assertEquals(false, MoneyBurst.agrees(items, null))
    }
}
