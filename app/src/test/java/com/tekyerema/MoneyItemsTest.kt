package com.tekyerema

import com.tekyerema.camera.DocumentLines
import com.tekyerema.camera.ItemsAnnouncer
import com.tekyerema.camera.KeyRest
import com.tekyerema.camera.MoneyItems
import com.tekyerema.camera.MoneyItems.Reading
import com.tekyerema.camera.MoneyTally
import com.tekyerema.camera.ObjectCrop
import com.tekyerema.camera.VisionPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counting several notes and coins on the phone, one crop each (27 Oct 2026: the cloud read a coin as 2, 1 and 0.5 cedis). */
class MoneyItemsTest {
    @Test fun classNamesBecomeValues() {
        assertEquals(MoneyItems.Item(50.0, true), MoneyItems.itemOf("note_50"))
        assertEquals(MoneyItems.Item(0.2, false), MoneyItems.itemOf("coin_20_pesewas"))
        assertEquals(MoneyItems.Item(0.01, false), MoneyItems.itemOf("coin_1_pesewa"))
        assertEquals(MoneyItems.Item(1.0, false), MoneyItems.itemOf("coin_1_cedi"))
        assertEquals(MoneyItems.Item(2.0, false), MoneyItems.itemOf("coin_2_cedis"))
        assertNull(MoneyItems.itemOf("not_money"))
    }

    @Test fun aNoteAndACoinBothSureAreTotalled() {
        val c = MoneyItems.count(listOf(Reading("note_50", 0.99), Reading("coin_1_cedi", 0.99)))
        assertEquals(51.0, c.total, 1e-9)
        assertEquals("I read a 50 cedis note and a 1 cedi coin. That's 51 cedis in all.", MoneyItems.spoken(c))
    }

    @Test fun anItemBelowItsOwnGateIsNotReadAndSaidSo() {
        // coin_1_cedi needs 0.97 (the 10 pesewas coin read as a cedi came with 0.85-0.96)
        val c = MoneyItems.count(listOf(Reading("note_50", 0.99), Reading("coin_1_cedi", 0.92)))
        assertEquals(1, c.sure.size); assertEquals(1, c.unsure)
        assertEquals("I read a 50 cedis note. That's 50 cedis for the ones I read. I could not read one item — put it on its own, flat, and closer.",
            MoneyItems.spoken(c))
    }

    @Test fun aShadowIsNotAnUnreadItemButAnUncertainBoxIs() {
        val c = MoneyItems.count(listOf(Reading("note_10", 0.95), Reading("not_money", 0.9), Reading("not_money", 0.3)))
        assertEquals(1, c.ignored); assertEquals(1, c.unsure); assertEquals(1, c.sure.size)
    }

    @Test fun sameItemsAreCountedAndPesewasAreSaid() {
        val two = MoneyItems.count(listOf(Reading("note_10", 0.95), Reading("note_10", 0.97)))
        assertEquals("I read two 10 cedis notes. That's 20 cedis in all.", MoneyItems.spoken(two))
        val mixed = MoneyItems.count(listOf(Reading("coin_50_pesewas", 0.95), Reading("note_5", 0.95)))
        assertEquals("I read a 5 cedis note and a 50 pesewas coin. That's 5 cedis 50 pesewas in all.", MoneyItems.spoken(mixed))
    }

    @Test fun nothingReadSaysNoTotal() {
        val c = MoneyItems.count(listOf(Reading("coin_1_cedi", 0.5), Reading("note_20", 0.4)))
        val said = MoneyItems.spoken(c)!!
        assertTrue(said, said.startsWith("I can see 2 items but I could not read any of them"))
        assertTrue(said, !said.contains("in all"))
        assertNull(MoneyItems.spoken(MoneyItems.count(emptyList())))
    }

    @Test fun twiLineNeedsNoTranslator() {
        val c = MoneyItems.count(listOf(Reading("note_50", 0.99), Reading("coin_1_cedi", 0.99)))
        val tw = MoneyItems.spokenTwi(c)!!
        assertTrue(tw, tw.startsWith("Mahu sidi "))
        assertTrue(tw, tw.contains(" krataa") && tw.contains(" sika dadeɛ") && tw.contains("Nyinaa yɛ sidi "))
        assertTrue(tw, tw.none { it.isDigit() })      // Twi words only: the lab voice skips digits
        assertEquals(true, MoneyItems.spokenTwiOf("note_20")!!.endsWith(" krataa."))
        assertNull(MoneyItems.spokenTwiOf("not_money"))
    }

    @Test fun theSameSetOnTwoFramesIsSaidOnceThenQuietUntilItChanges() {
        val a = ItemsAnnouncer(agreeFrames = 2, repeatMs = 6_000L)
        val set = MoneyItems.count(listOf(Reading("note_50", 0.99), Reading("coin_1_cedi", 0.99)))
        assertNull(a.offer(set, 0L))
        assertNotNull(a.offer(set, 500L))
        assertNull(a.offer(set, 1_000L))
        assertNotNull(a.offer(set, 7_000L))                  // the same set held up again after the repeat window
        val other = MoneyItems.count(listOf(Reading("note_50", 0.99), Reading("note_20", 0.99)))
        assertNull(a.offer(other, 8_000L))
        assertNotNull(a.offer(other, 8_500L))
        assertNull(a.offer(MoneyItems.count(listOf(Reading("coin_1_cedi", 0.5))), 9_000L))
    }

    @Test fun orderOfItemsDoesNotChangeTheSet() {
        val x = MoneyItems.count(listOf(Reading("note_50", 0.99), Reading("coin_1_cedi", 0.99)))
        val y = MoneyItems.count(listOf(Reading("coin_1_cedi", 0.99), Reading("note_50", 0.99)))
        assertEquals(x.signature, y.signature)
    }

    // ----------------------------------------------------------------------------------------------- cloud boxes re-read on the phone

    private fun piece(kind: String, v: Double, box: String, sure: Boolean) = """{"kind":"$kind","value_ghs":$v,"box":$box,"sure":$sure}"""

    @Test fun theCoinTheCloudGotWrongTakesTheValueTheModelOnThePhoneReads() {
        val json = """{"notes":[${piece("note", 50.0, "[100,50,500,950]", true)},${piece("coin", 2.0, "[600,400,800,600]", false)}],"unreadable":0}"""
        val checked = MoneyTally.withOnDevice(json) { box ->
            when (box[0]) { 100f -> MoneyItems.Item(50.0, true); 600f -> MoneyItems.Item(1.0, false); else -> null }
        }!!
        assertEquals(2, checked.read); assertEquals(1, checked.changed); assertEquals(2, checked.boxed)
        assertEquals("I see one 50 cedi note and one 1 cedi coin. That's 51 cedis in all.", VisionPrompts.spokenMoney(checked.json))
    }

    @Test fun aPieceThePhoneIsNotSureOfKeepsWhatTheCloudSaid() {
        val json = """{"notes":[${piece("note", 20.0, "[100,50,500,950]", true)},${piece("coin", 0.5, "[600,400,800,600]", false)}]}"""
        val checked = MoneyTally.withOnDevice(json) { box -> if (box[0] == 100f) MoneyItems.Item(20.0, true) else null }!!
        assertEquals(1, checked.read); assertEquals(0, checked.changed)
        assertEquals("I see one 20 cedi note and one 50 pesewa coin (not sure). That's about 20 cedis 50 pesewas in all.",
            VisionPrompts.spokenMoney(checked.json))
    }

    @Test fun anAnswerThatIsNotAMoneyListIsLeftAlone() {
        assertNull(MoneyTally.withOnDevice("I can't be sure of the total.") { MoneyItems.Item(1.0, true) })
        assertNull(MoneyTally.withOnDevice("""{"items":[{"kind":"note","value_ghs":10,"count":2}]}""") { MoneyItems.Item(1.0, true) })
    }

    // ----------------------------------------------------------------------------------------------- finding several objects

    private fun grid(side: Int, vararg rects: Triple<IntRange, IntRange, Triple<Float, Float, Float>>): FloatArray {
        val px = FloatArray(side * side * 3) { 128f }
        for ((xs, ys, c) in rects) for (y in ys) for (x in xs) {
            val i = (y * side + x) * 3
            px[i] = c.first; px[i + 1] = c.second; px[i + 2] = c.third
        }
        return px
    }

    @Test fun twoSeparateObjectsAreTwoBoxesLeftToRight() {
        val g = grid(96, Triple(62..83, 40..60, Triple(30f, 30f, 200f)), Triple(12..32, 40..60, Triple(200f, 30f, 30f)))
        val boxes = ObjectCrop.findAll(g, 96)
        assertEquals(2, boxes.size)
        assertEquals(12f, boxes[0].x0, 1f); assertEquals(33f, boxes[0].x1, 1f)
        assertEquals(62f, boxes[1].x0, 1f); assertEquals(84f, boxes[1].x1, 1f)
    }

    @Test fun oneObjectIsOneBoxAndAPlainFrameIsNone() {
        assertEquals(1, ObjectCrop.findAll(grid(96, Triple(30..60, 30..60, Triple(200f, 30f, 30f))), 96).size)
        assertTrue(ObjectCrop.findAll(grid(96), 96).isEmpty())
    }

    // ----------------------------------------------------------------------------------------------- rejected keys

    @Test fun aRefusedKeyIsSkippedForHoursAndARateLimitedOneForAShortWhile() {
        var now = 1_000L
        val rest = KeyRest { now }
        val keys = listOf("k1", "k2", "k3")
        assertEquals(keys, rest.usable(keys))
        rest.rest("k1", KeyRest.restFor(429, "Rate limit reached", 6 * 3_600_000L, 30_000L))
        rest.rest("k2", KeyRest.restFor(401, "Invalid API Key", 6 * 3_600_000L, 30_000L))
        assertEquals(listOf("k3"), rest.usable(keys))
        now += 31_000L
        assertEquals(listOf("k1", "k3"), rest.usable(keys))
        now += 6 * 3_600_000L
        assertEquals(keys, rest.usable(keys))
        assertEquals(3_600_000L, KeyRest.restFor(429, "Rate limit reached ... tokens per day (TPD)", 1L, 30_000L))
    }

    // ----------------------------------------------------------------------------------------------- Twi reader lines

    @Test fun documentLinesInTwiAreWordsNotDigits() {
        assertEquals("Kratafa baako.", DocumentLines.introTwi(1))
        assertTrue(DocumentLines.introTwi(3).startsWith("Nkratafa "))
        assertTrue(DocumentLines.pageStartTwi(1).none { it.isDigit() })
        assertTrue(DocumentLines.emptyTwi(2, 5).none { it.isDigit() })
    }

    @Test fun aNoteHeldAnyWayUpIsTrustedOnlyWhenTheQuarterTurnsAgree() {
        val n50 = Reading("note_50", 0.97); val n200 = Reading("note_200", 0.97); val unsure = Reading("note_50", 0.4)
        assertEquals(MoneyItems.Item(50.0, true), MoneyItems.consensus(listOf(n50, n50, n50, n50)))
        assertEquals(MoneyItems.Item(50.0, true), MoneyItems.consensus(listOf(n50, unsure, n50, unsure)))
        assertNull(MoneyItems.consensus(listOf(n50, unsure, unsure, unsure)))      // one turn alone is not enough
        assertNull(MoneyItems.consensus(listOf(n200, n50, n50, n50)))              // a turn that says something else vetoes
        assertNull(MoneyItems.consensus(listOf(unsure, unsure, unsure, unsure)))
    }

    @Test fun tightAndWideCropsNeedThreeAgreeingReadings() {
        val n50 = Reading("note_50", 0.97); val unsure = Reading("note_50", 0.4)
        assertNull(MoneyItems.consensus(listOf(n50, n50, unsure, unsure, unsure, unsure, unsure, unsure)))     // two of eight: not enough
        assertEquals(MoneyItems.Item(50.0, true), MoneyItems.consensus(listOf(n50, n50, n50, unsure, unsure, unsure, unsure, unsure)))
    }

    @Test fun quarterTurnsOfTheCropRoundTrip() {
        val side = 4
        val a = FloatArray(side * side * 3) { it.toFloat() }
        assertTrue(com.tekyerema.camera.CediCrops.turned(a, side, 4).contentEquals(a))
        var r = a
        repeat(4) { r = com.tekyerema.camera.CediCrops.turned(r, side, 1) }
        assertTrue(r.contentEquals(a))
        assertTrue(com.tekyerema.camera.CediCrops.turned(com.tekyerema.camera.CediCrops.turned(a, side, 2), side, 2).contentEquals(a))
        // the top-left pixel goes to the top-right after one clockwise quarter-turn
        val t = com.tekyerema.camera.CediCrops.turned(a, side, 1)
        assertEquals(a[0], t[(0 * side + (side - 1)) * 3], 0f)
    }

    @Test fun aLiveReadingNeedsTwoOfTheOtherThreeTurnsToAgree() {
        val first = MoneyItems.Reading("note_50", 0.97)
        fun r(c: String, p: Double) = MoneyItems.Reading(c, p)
        assertEquals(first, MoneyItems.crossChecked(first, listOf(r("note_50", 0.95), r("note_50", 0.9), r("note_50", 0.4))))
        assertEquals(0.0, MoneyItems.crossChecked(first, listOf(r("note_50", 0.95), r("note_20", 0.2), r("not_money", 0.3))).conf, 0.0)   // one agrees only
        assertEquals(0.0, MoneyItems.crossChecked(first, listOf(r("note_50", 0.95), r("note_50", 0.9), r("note_200", 0.93))).conf, 0.0)   // a sure turn disagrees
        val weak = MoneyItems.Reading("note_50", 0.4)
        assertEquals(weak, MoneyItems.crossChecked(weak, emptyList()))
    }
}
