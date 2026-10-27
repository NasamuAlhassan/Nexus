package com.tekyerema.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DangerTest {
    @Test fun aCodeWordMustBeDistinctive() {
        assertNotNull(DangerCodeWord.problem("help"))
        assertNotNull(DangerCodeWord.problem("help me"))
        assertNotNull(DangerCodeWord.problem("go go"))
        assertNull(DangerCodeWord.problem("red kente cloth"))
    }

    @Test fun theWholePhraseInOrderMatchesInsideASentence() {
        assertTrue(DangerCodeWord.matches("please red kente cloth now", "red kente cloth"))
        assertTrue(DangerCodeWord.matches("Red, Kente cloth!", "red kente cloth"))
        assertFalse(DangerCodeWord.matches("kente red cloth", "red kente cloth"))
        assertFalse(DangerCodeWord.matches("red kente", "red kente cloth"))
        assertFalse(DangerCodeWord.matches("anything at all", ""))
        assertFalse(DangerCodeWord.matches("anything at all", "single"))
    }

    @Test fun oneSlipInALongWordIsForgivenButNotAnotherWord() {
        assertTrue(DangerCodeWord.matches("red kenty cloth", "red kente cloth"))
        assertFalse(DangerCodeWord.matches("red kenya cloth", "red kente cloth"))
        assertFalse(DangerCodeWord.matches("rod kente cloth", "red kente cloth"))   // a short word must be exact
        assertFalse(DangerCodeWord.matches("red kentes loth", "red kente cloth"))
    }

    @Test fun cancelByWordOrByPin() {
        assertTrue(DangerCodeWord.isCancel("cancel", ""))
        assertTrue(DangerCodeWord.isCancel("sorry it was a false alarm", ""))
        assertTrue(DangerCodeWord.isCancel("4729", "4729"))
        assertFalse(DangerCodeWord.isCancel("4730", "4729"))
        assertFalse(DangerCodeWord.isCancel("what is the time", "4729"))
        assertFalse(DangerCodeWord.isCancel("12", "12"))   // a PIN shorter than four digits is no PIN
    }

    @Test fun aTestNumberReplacesEveryRealOne() {
        assertEquals(listOf("191", "112"), DangerPlan.numbers(listOf("191", "112"), ""))
        assertEquals(listOf("0241234567"), DangerPlan.numbers(listOf("191", "112"), " 024 123 4567 "))
        assertEquals(listOf("191"), DangerPlan.numbers(listOf("191", "191", " "), ""))
    }

    @Test fun recipientsAreTheEmergencyContactThenTrustedOneNumberEach() {
        val r = DangerPlan.recipients("Ama" to "0244000001", listOf("Kofi" to "+233244000001", "Esi" to "0244000002", "Yaw" to "0244000003"), max = 3)
        assertEquals(listOf("Ama", "Esi", "Yaw"), r.map { it.first })   // Kofi's +233 number is Ama's
        assertTrue(DangerPlan.recipients(null, emptyList()).isEmpty())
    }

    @Test fun theTextCarriesTheLinkOrSaysThereIsNone() {
        assertTrue(DangerPlan.message("https://maps.google.com/?q=5.6,-0.2", 12, "14:20").contains("https://maps.google.com/?q=5.6,-0.2"))
        assertTrue(DangerPlan.message(null, null, "14:20").contains("could not get my location"))
    }

    @Test fun setUpCommands() {
        assertEquals(DangerCommands.Cmd.SetCodeWord("red kente cloth"), DangerCommands.parse("Set my code word to red kente cloth."))
        assertEquals(DangerCommands.Cmd.SetPin("4729"), DangerCommands.parse("set my cancel pin to four seven two nine"))
        assertEquals(DangerCommands.Cmd.SetPin("4729"), DangerCommands.parse("set my cancel PIN to 4 7 2 9"))
        assertNull(DangerCommands.parse("set my cancel pin to one two"))
        assertEquals(DangerCommands.Cmd.AddTrusted("ama"), DangerCommands.parse("add Ama as a trusted contact"))
        assertEquals(DangerCommands.Cmd.Switch(true), DangerCommands.parse("turn on the danger code word"))
        assertEquals(DangerCommands.Cmd.Switch(false), DangerCommands.parse("disable code word"))
        assertEquals(DangerCommands.Cmd.Status, DangerCommands.parse("is my code word on"))
        assertNull(DangerCommands.parse("what is the time"))
        assertNull(DangerCommands.parse("call my wife"))
    }
}
