package com.tekyerema

import com.tekyerema.memory.MemEntry
import com.tekyerema.memory.MemKind
import com.tekyerema.memory.MemState
import com.tekyerema.memory.MemoryCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCoreTest {
    private val day = MemoryCore.DAY_MS
    private val t0 = 1_800_000_000_000L

    private data class C(val name: String, val number: String)

    private fun used(s: MemState, name: String, number: String, times: Int, at: Long): MemState {
        var st = s
        repeat(times) { st = MemoryCore.upsert(st, MemKind.CONTACT, MemoryCore.contactKey(name, number), name, false, at) }
        return st
    }

    // ---- what is never stored
    @Test fun secretsAndNumbersAreRefused() {
        assertFalse(MemoryCore.safeFact("my pin is something"))
        assertFalse(MemoryCore.safeFact("my balance is low"))
        assertFalse(MemoryCore.safeFact("my number is 0244123456"))
        assertFalse(MemoryCore.safeFact("I paid ₵50"))
        assertFalse(MemoryCore.safeFact("my otp"))
        assertTrue(MemoryCore.safeFact("Holy Trinity"))
        assertTrue(MemoryCore.safeFact("I live in Accra"))
    }

    @Test fun taskWordingsThatSendPayOrCallAreRefused() {
        assertTrue(MemoryCore.safeTask("check my balance"))
        assertTrue(MemoryCore.safeTask("read my messages"))
        assertFalse(MemoryCore.safeTask("send money to Ama"))
        assertFalse(MemoryCore.safeTask("text Kofi I am late"))
        assertFalse(MemoryCore.safeTask("call my mother"))
        assertFalse(MemoryCore.safeTask("pay 20 cedis"))
        assertFalse(MemoryCore.safeTask("my pin is"))
        assertFalse(MemoryCore.safeTask("what is the time in london and also tell me the weather"))   // too long
    }

    @Test fun aNumberSavedAsANameIsNotKept() {
        assertFalse(MemoryCore.safeName("0244 123 456"))
        assertTrue(MemoryCore.safeName("Mummy 2"))
        assertTrue(MemoryCore.safeName("Ama Mensah"))
        val s = MemoryCore.upsert(MemState(), MemKind.CONTACT, "x", "0244123456", false, t0)
        assertTrue(s.entries.isEmpty())
    }

    // ---- counting, ranking, decay
    @Test fun repeatedUseCountsUp() {
        val s = used(MemState(), "Ama Mensah", "0244111111", 3, t0)
        assertEquals(1, s.entries.size)
        assertEquals(3, s.entries[0].count)
        assertEquals("ama mensah#1111", s.entries[0].key)
        assertEquals("Ama Mensah", s.entries[0].value)
    }

    @Test fun contactKeyKeepsOnlyTheLastFourDigits() {
        assertEquals("ama#3456", MemoryCore.contactKey("Ama", "+233 24 412 3456"))
        assertEquals("ama", MemoryCore.contactKey("Ama", ""))
    }

    @Test fun weightHalvesEveryHalfLife() {
        val s = used(MemState(), "Ama", "0244111111", 1, t0)
        val e = s.entries[0]
        assertEquals(1.0, MemoryCore.score(e, t0), 1e-9)
        assertEquals(0.5, MemoryCore.score(e, t0 + (MemoryCore.HALF_LIFE_DAYS * day).toLong()), 1e-6)
    }

    @Test fun reuseAfterALongGapStartsFromTheDecayedWeight() {
        var s = used(MemState(), "Ama", "0244111111", 4, t0)
        val later = t0 + (MemoryCore.HALF_LIFE_DAYS * day).toLong()
        s = used(s, "Ama", "0244111111", 1, later)
        assertEquals(3.0, s.entries[0].weight, 1e-6)    // 4 / 2 + 1
        assertEquals(5, s.entries[0].count)
    }

    @Test fun learnedEntriesExpireButToldOnesStay() {
        var s = used(MemState(), "Ama", "0244111111", 1, t0)
        s = MemoryCore.upsert(s, MemKind.FACT, "my church", "Holy Trinity", true, t0)
        val far = t0 + (MemoryCore.EXPIRE_DAYS + 5) * day
        val pruned = MemoryCore.prune(s, far)
        assertEquals(listOf(MemKind.FACT), pruned.entries.map { it.kind })
    }

    // ---- preferring the frequent contact
    private val ama1 = C("Ama Mensah", "0244111111")
    private val ama2 = C("Ama Boateng", "0205222222")

    private fun pick(s: MemState, said: String, hits: List<C>) =
        MemoryCore.preferFrequent(s, t0, said, hits, { it.name }, { it.number })

    @Test fun aClearlyMoreUsedContactWinsATie() {
        var s = used(MemState(), ama1.name, ama1.number, 6, t0)
        s = used(s, ama2.name, ama2.number, 1, t0)
        assertEquals(listOf(ama1), pick(s, "Ama", listOf(ama2, ama1)))
    }

    @Test fun noOneWinsWhenUseIsCloseOrThin() {
        var s = used(MemState(), ama1.name, ama1.number, 4, t0)
        s = used(s, ama2.name, ama2.number, 3, t0)
        assertEquals(2, pick(s, "Ama", listOf(ama1, ama2)).size)
        val thin = used(MemState(), ama1.name, ama1.number, 2, t0)
        assertEquals(2, pick(thin, "Ama", listOf(ama1, ama2)).size)
    }

    @Test fun anExactNameIsNeverOverridden() {
        var s = used(MemState(), ama1.name, ama1.number, 9, t0)
        s = used(s, "Ama", "0201000000", 0, t0)
        val exact = C("Ama", "0201000000")
        assertEquals(2, pick(s, "Ama", listOf(ama1, exact)).size)
        assertEquals(2, pick(s, "my ama", listOf(ama1, exact)).size)
    }

    @Test fun switchedOffContactsDoNotRank() {
        var s = used(MemState(), ama1.name, ama1.number, 9, t0)
        s = MemoryCore.withSwitch(s, MemKind.CONTACT, on = false)
        assertEquals(2, pick(s, "Ama", listOf(ama1, ama2)).size)
    }

    @Test fun learningStopsWhenACategoryIsOffButTheUsersOwnWordsStillGoIn() {
        var s = MemoryCore.withSwitch(MemState(), MemKind.CONTACT, on = false)
        s = used(s, "Ama", "0244111111", 3, t0)
        assertTrue(s.entries.isEmpty())
        s = MemoryCore.upsert(s, MemKind.FACT, "my church", "Holy Trinity", true, t0)
        assertEquals(1, s.entries.size)
        s = MemoryCore.withSwitch(s, MemKind.CONTACT, on = true)
        assertTrue(s.off.isEmpty())
    }

    // ---- caps
    @Test fun eachKindIsCappedAndLearnedGoesFirst() {
        var s = MemoryCore.upsert(MemState(), MemKind.CONTACT, "told#0001", "Told Person", true, t0)
        for (i in 0 until 80) s = used(s, "Person $i", "02440000%02d".format(i), 1, t0 + i)
        val contacts = s.entries.filter { it.kind == MemKind.CONTACT }
        assertTrue(contacts.size <= 60)
        assertTrue(contacts.any { it.told })
    }

    @Test fun theEncodedFileStaysUnderTheHardCap() {
        var s = MemState()
        for (i in 0 until 40) s = MemoryCore.upsert(s, MemKind.FACT, "my thing $i", "x".repeat(90), true, t0 + i)
        for (i in 0 until 60) s = used(s, "Contact number $i with a long name here", "02440000%02d".format(i), 1, t0 + i)
        assertTrue(MemoryCore.encode(s).length <= MemoryCore.MAX_CHARS)
        assertTrue(s.entries.size <= MemoryCore.MAX_TOTAL)
    }

    // ---- forget and correct
    @Test fun forgetAKindRemovesOnlyThatKind() {
        var s = used(MemState(), "Ama", "0244111111", 2, t0)
        s = MemoryCore.upsert(s, MemKind.FACT, "my church", "Holy Trinity", true, t0)
        val (after, n) = MemoryCore.removeKind(s, MemKind.CONTACT)
        assertEquals(1, n)
        assertEquals(listOf(MemKind.FACT), after.entries.map { it.kind })
    }

    @Test fun forgetAboutAPersonMatchesWholeWordsOnly() {
        var s = used(MemState(), "Ama Mensah", "0244111111", 2, t0)
        s = used(s, "Kwama", "0244222222", 2, t0)
        s = MemoryCore.upsert(s, MemKind.FACT, "my boss", "Ama", true, t0)
        val (after, n) = MemoryCore.removeAbout(s, "Ama")
        assertEquals(2, n)
        assertEquals(listOf("Kwama"), after.entries.map { it.value })
    }

    @Test fun correctingAFactReplacesItsValue() {
        var s = MemoryCore.upsert(MemState(), MemKind.FACT, "my church", "Holy Trinity", true, t0)
        s = MemoryCore.upsert(s, MemKind.FACT, "my church", "St Peter's", true, t0 + 1)
        assertEquals(1, s.entries.size)
        assertEquals("St Peter's", s.entries[0].value)
        assertEquals("your church is St Peter's", MemoryCore.spokenEntry(s.entries[0]))
    }

    @Test fun removeEntryDeletesExactlyThatOne() {
        var s = MemoryCore.upsert(MemState(), MemKind.FACT, "my church", "Holy Trinity", true, t0)
        s = MemoryCore.upsert(s, MemKind.FACT, "i live in", "Accra", true, t0)
        s = MemoryCore.removeEntry(s, MemKind.FACT, "My Church")
        assertEquals(listOf("i live in"), s.entries.map { it.key })
    }

    // ---- speaking and paging
    @Test fun spokenFormsReadNaturally() {
        assertEquals("you live in Accra", MemoryCore.spokenFact("i live in", "Accra"))
        assertEquals("you are from Kumasi", MemoryCore.spokenFact("i am from", "Kumasi"))
        assertEquals("you like me to speak slowly", MemoryCore.spokenPref("rate", "slow"))
        assertEquals("you prefer Twi for messages", MemoryCore.spokenPref("language:messages", "twi"))
        assertEquals("you like short answers", MemoryCore.spokenPref("verbosity", "brief"))
    }

    @Test fun pagingCapsAndContinues() {
        val lines = (1..9).map { "thing $it" }
        val (first, next) = MemoryCore.page(lines, 0)
        assertEquals("Thing 1. Thing 2. Thing 3. Thing 4.", first)
        assertEquals(4, next)
        val (last, end) = MemoryCore.page(lines, 8)
        assertEquals("Thing 9.", last)
        assertEquals(9, end)
    }

    @Test fun summaryListsToldThingsThenLearnedAndNamesWhatIsSwitchedOff() {
        var s = MemoryCore.upsert(MemState(), MemKind.PREF, "rate", "slow", true, t0)
        s = MemoryCore.upsert(s, MemKind.FACT, "my church", "Holy Trinity", true, t0)
        s = used(s, "Ama Mensah", "0244111111", 3, t0)
        s = MemoryCore.withSwitch(s, MemKind.TASK, on = false)
        val lines = MemoryCore.summaryLines(s, mapOf("mother" to "Ama Mensah"), "Prince", t0)
        assertEquals("you like to be called Prince", lines[0])
        assertEquals("your mother is Ama Mensah", lines[1])
        assertTrue(lines.contains("you like me to speak slowly"))
        assertTrue(lines.contains("your church is Holy Trinity"))
        assertTrue(lines.any { it.startsWith("you often contact Ama Mensah") })
        assertTrue(lines.last().startsWith("I'm not keeping track of"))
    }

    @Test fun aboutAPersonListsRelationsAndEntries() {
        var s = used(MemState(), "Ama Mensah", "0244111111", 3, t0)
        s = used(s, "Kofi", "0244222222", 3, t0)
        val lines = MemoryCore.aboutLines(s, mapOf("mother" to "Ama Mensah"), "Ama")
        assertEquals(2, lines.size)
        assertTrue(MemoryCore.aboutLines(s, emptyMap(), "Yaw").isEmpty())
    }

    // ---- the stored form
    @Test fun encodeDecodeRoundTrips() {
        var s = MemoryCore.upsert(MemState(), MemKind.FACT, "my church", "Holy Trinity", true, t0)
        s = used(s, "Ama", "0244111111", 3, t0)
        s = MemoryCore.withSwitch(s, MemKind.TASK, on = false)
        val back = MemoryCore.decode(MemoryCore.encode(s))
        assertNotNull(back)
        assertEquals(s, back)
    }

    @Test fun decodeToleratesGarbage() {
        assertNull(MemoryCore.decode("not json"))
        assertNull(MemoryCore.decode(null))
        val partial = MemoryCore.decode("""{"v":1,"e":[{"k":"nope","key":"a","val":"b"},{"k":"fact","key":"my x","val":"y","t":1,"n":1,"w":1.0,"f":1,"l":1}]}""")
        assertEquals(1, partial!!.entries.size)
    }
}
