package com.tekyerema

import com.tekyerema.camera.MoneyTally
import com.tekyerema.camera.VisionPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone counts the notes, not the model (owner's demo, 24 Oct 2026: one 10 cedi note counted as two = 20). */
class MoneyTallyTest {
    private fun note(v: Int, box: String, sure: Boolean = true) = """{"kind":"note","value_ghs":$v,"box":$box,"sure":$sure}"""
    private fun ans(vararg notes: String) = """{"notes":[${notes.joinToString(",")}],"unreadable":0,"confidence":"high","fix":""}"""

    @Test fun oneTenListedByItsTwoCornerNumeralsIsOneNote() {
        // The 24 Oct shape: two small square boxes on the corners of ONE landscape note.
        val json = ans(note(10, "[300,100,450,250]"), note(10, "[550,750,700,900]"))
        val t = MoneyTally.of(json)!!
        assertEquals(1, t.counts[10.0 to true]); assertEquals(1, t.merged)
        assertEquals("I see one 10 cedi note (not sure). That's about 10 cedis in all.", VisionPrompts.spokenMoney(json))
    }

    @Test fun theSameNoteListedTwiceIsOneNote() {
        val json = ans(note(10, "[300,100,700,900]"), note(10, "[320,120,690,880]"))
        assertEquals("I see one 10 cedi note. That's 10 cedis in all.", VisionPrompts.spokenMoney(json))
    }

    @Test fun mixedPilesSpreadOutAreCountedNoteByNote() {
        // three 10s and five 5s, each in its own place (the judges' question)
        val tens = (0 until 3).map { note(10, "[${it * 300},0,${it * 300 + 250},500]") }
        val fives = (0 until 5).map { note(5, "[${it * 190},550,${it * 190 + 170},950]") }
        val json = ans(*(tens + fives).toTypedArray())
        assertEquals("I see three 10 cedi notes and five 5 cedi notes. That's 55 cedis in all.", VisionPrompts.spokenMoney(json))
        assertEquals(55.0, VisionPrompts.moneyTotal(json)!!, 1e-9)
    }

    @Test fun twoNotesSideBySideStayTwo() {
        // two whole landscape notes next to each other: note-shaped boxes, not numerals
        val json = ans(note(20, "[100,50,400,950]"), note(20, "[500,50,800,950]"))
        assertEquals("I see two 20 cedi notes. That's 40 cedis in all.", VisionPrompts.spokenMoney(json))
    }

    @Test fun overlappingNotesOfDifferentValuesGiveNoTotal() {
        val json = ans(note(10, "[200,100,600,900]"), note(50, "[250,150,650,950]"))
        val said = VisionPrompts.spokenMoney(json)!!
        assertTrue(said, said.startsWith("Some of the notes overlap"))
        assertFalse(said.contains("in all"))
        assertNull(VisionPrompts.moneyTotal(json))
    }

    @Test fun twoModelsWithTheSameTotalButDifferentNotesDisagree() {
        val a = ans(note(10, "[0,0,300,600]"), note(10, "[400,0,700,600]"))
        val b = ans(note(20, "[0,0,300,600]"))
        assertTrue(VisionPrompts.moneyDisagreement(a, b) != null)
        assertNull(VisionPrompts.moneyDisagreement(a, a))
    }

    @Test fun theOldAnswerShapeStillReads() {
        val said = VisionPrompts.spokenMoney("""{"items":[{"kind":"note","value_ghs":20,"count":2,"sure":true},{"kind":"note","value_ghs":5,"count":1,"sure":true}]}""")
        assertEquals("I see two 20 cedi notes and one 5 cedi note. That's 45 cedis in all.", said)
    }

    // P5-a: two frames of the same notes, one model — accept only an identical per-denomination tally.
    @Test fun twoFramesWithTheSameNotesAgreeEvenWhenTheBoxesMove() {
        val a = ans(note(10, "[0,0,300,600]"), note(5, "[400,0,700,600]"))
        val b = ans(note(5, "[420,10,720,610]"), note(10, "[10,5,310,605]"))
        assertTrue(MoneyTally.sameCounts(a, b))
    }

    @Test fun twoFramesThatCountDifferentlyDoNotAgree() {
        val one = ans(note(10, "[0,0,300,600]"))
        val two = ans(note(10, "[0,0,300,600]"), note(10, "[400,0,700,600]"))
        assertFalse(MoneyTally.sameCounts(one, two))
        assertFalse("same total, different notes", MoneyTally.sameCounts(ans(note(10, "[0,0,300,600]"), note(10, "[400,0,700,600]")),
            ans(note(20, "[0,0,300,600]"))))
    }

    @Test fun anUnstableOrUnreadableFrameNeverAgrees() {
        val ok = ans(note(10, "[0,0,300,600]"))
        val overlap = ans(note(10, "[0,0,300,600]"), note(20, "[10,10,310,610]"))
        assertFalse(MoneyTally.sameCounts(overlap, overlap))
        assertFalse(MoneyTally.sameCounts(ok, "not json"))
    }
}
