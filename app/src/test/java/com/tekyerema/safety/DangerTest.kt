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

    @Test fun pauseResumeAgreeAndDelete() {
        assertEquals(DangerCommands.Cmd.Switch(false, pause = true), DangerCommands.parse("pause the danger code word"))
        assertEquals(DangerCommands.Cmd.Switch(false, pause = true), DangerCommands.parse("pause danger listening"))
        assertEquals(DangerCommands.Cmd.Switch(true), DangerCommands.parse("resume the danger code word"))
        assertEquals(DangerCommands.Cmd.Agree, DangerCommands.parse("I agree to the danger code word."))
        assertEquals(DangerCommands.Cmd.Delete, DangerCommands.parse("delete my danger data"))
        assertEquals(DangerCommands.Cmd.Delete, DangerCommands.parse("forget my code word"))
        assertNull(DangerCommands.parse("pause the music"))
        assertNull(DangerCommands.parse("delete my messages"))
    }

    @Test fun sosIsAWholeCallForHelpNotAWordInARequest() {
        assertTrue(DangerSos.matches("SOS"))
        assertTrue(DangerSos.matches("S.O.S."))
        assertTrue(DangerSos.matches("Please, this is an emergency!"))
        assertTrue(DangerSos.matches("I need help now"))
        assertFalse(DangerSos.matches("help me write a message to Ama"))
        assertFalse(DangerSos.matches("what should I do in an emergency"))
        assertFalse(DangerSos.matches("call the police station number"))
        assertFalse(DangerSos.matches("help"))
    }

    @Test fun holdingTheKeyFourSecondsFiresOnceAndReleaseResets() {
        val up = android.view.KeyEvent.KEYCODE_VOLUME_UP
        val h = SosHold(up, 4_000L)
        assertFalse(h.onKey(up, true, 1_000)); assertFalse(h.onKey(up, true, 3_000))
        assertTrue(h.onKey(up, true, 5_000)); assertFalse(h.onKey(up, true, 6_000))
        assertFalse(h.onKey(up, false, 6_100))
        assertFalse(h.onKey(up, true, 7_000)); assertFalse(h.onKey(up, true, 9_000))   // a fresh press starts the count again
        val short = SosHold(up, 4_000L)
        short.onKey(up, true, 0); short.onKey(up, false, 500)
        assertFalse(short.onKey(up, true, 9_000))
    }

    @Test fun theSosTextSaysSosAndTheCodeWordTextSaysCodeWord() {
        assertTrue(DangerPlan.message(null, null, "14:20", manual = true).contains("pressed SOS"))
        assertTrue(DangerPlan.message(null, null, "14:20").contains("danger code word"))
    }
}
