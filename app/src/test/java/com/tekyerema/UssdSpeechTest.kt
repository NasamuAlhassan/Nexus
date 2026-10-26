package com.tekyerema

import com.tekyerema.money.UssdSpeech
import com.tekyerema.voice.SpokenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The carrier's run-on reply as a blind user can follow it — the two REAL blobs from the A20e, 2026-09-30. */
class UssdSpeechTest {

    private val mtn = "Your a/c is GHC0.06Internet bundle:12068.27MB.Data Bonus: 0.00MB.BOR:0.00GHC,J4u:0.00GHC,Welcome to the new world."
    private val walk = "Y'ello Your data bundle balance: GHc3 Bundle: 63MB Y'ello Your bonus balance: 0"

    @Test fun `the glued balance blob becomes items`() {
        val items = UssdSpeech.items(mtn)
        assertEquals(items.toString(),
            listOf("Your a/c is GHC 0.06", "Internet bundle:12068.27 MB", "Data Bonus: 0.00 MB",
                "BOR:0.00 GHC", "J4u:0.00 GHC", "Welcome to the new world"), items)
    }

    /** A20e 2026-10-26: "Current Balance, five cedis eighty three pesewas. Available Balance, five cedis eighty three pesewas." */
    @Test fun `two balance lines with the same figure are read once`() {
        val same = UssdSpeech.sameBalanceOnce(listOf("Current Balance: GHS 5.83", "Available Balance: GHS 5.83"))
        assertEquals(listOf("Current and available balance: GHS 5.83"), same)
        // different figures stay as two items; an unrelated item is untouched
        val diff = listOf("Current Balance: GHS 5.83", "Available Balance: GHS 3.00")
        assertEquals(diff, UssdSpeech.sameBalanceOnce(diff))
        assertEquals(listOf("Bonus: 0", "Current and available balance: GHS 5.83"),
            UssdSpeech.sameBalanceOnce(listOf("Bonus: 0", "Current Balance: GHS 5.83", "Available Balance: GHS 5.83")))
        val ear = UssdSpeech.forEar("Current Balance: GHS 5.83. Available Balance: GHS 5.83.", intro = "M T N says")
        assertEquals(ear, 1, Regex("(?i)balance").findAll(ear).count())
    }

    @Test fun `zeros are said as none, big megabytes as gigabytes, one pause per item`() {
        val ear = UssdSpeech.forEar(mtn)
        assertTrue(ear, ear.contains("Internet bundle, about 11.8 gigabytes."))
        assertTrue(ear, ear.contains("No data bonus."))
        assertTrue(ear, ear.contains("No just 4 u."))
        assertTrue(ear, ear.contains("Your account is GHS 0.06."))   // "a/c" said as a word, the currency as cedis
        assertFalse(ear, ear.contains("0.00"))                       // zeros never read as figures
        assertTrue(ear, ear.count { it == SpokenText.PAUSE } >= 5)   // a breath between items
        assertTrue(ear, ear.startsWith("The network says."))
    }

    @Test fun `the walk's final screen splits at each label and drops the repeated greeting`() {
        val items = UssdSpeech.items(walk)
        assertEquals(items.toString(),
            listOf("Your data bundle balance: GHc 3", "Bundle: 63 MB", "Your bonus balance: 0"), items)
        val ear = UssdSpeech.forEar(walk, intro = "MTN says")
        assertTrue(ear, ear.contains("Your data bundle balance, GHS 3."))
        assertTrue(ear, ear.contains("Bundle, 63 megabytes."))
        assertTrue(ear, ear.contains("No bonus balance."))
    }

    @Test fun `an unrecognisable reply is still read, after the intro`() {
        val ear = UssdSpeech.forEar("thanks for choosing us")
        assertTrue(ear, ear.contains("thanks for choosing us."))
    }
}
