package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.phone.CallShield
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.NoisyPlace
import com.tekyerema.voice.SpeechControl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MASTER_STATUS #39 speech controls, #36 call shield, #40 noisy-place profile (2 Nov 2026). */
class SpeechShieldNoisyTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    // ---------------------------------------------------------------- #39

    @Test fun speech_controls_are_whole_phrases() {
        assertEquals(SpeechControl.Kind.AGAIN, SpeechControl.classify("Again."))
        assertEquals(SpeechControl.Kind.AGAIN, SpeechControl.classify("one more time please"))
        assertEquals(SpeechControl.Kind.SLOWER, SpeechControl.classify("Slower"))
        assertEquals(SpeechControl.Kind.SLOWER, SpeechControl.classify("say that slower"))
        assertEquals(SpeechControl.Kind.SLOWER, SpeechControl.classify("again, slowly"))
        assertEquals(SpeechControl.Kind.SLOWER, SpeechControl.classify("Say it more slowly please"))
        assertEquals(SpeechControl.Kind.SPELL, SpeechControl.classify("Spell it"))
        assertEquals(SpeechControl.Kind.SPELL, SpeechControl.classify("spell that number for me"))
        assertEquals(SpeechControl.Kind.SPELL, SpeechControl.classify("How do you spell that?"))
        for (s in listOf("spell necessary", "call Ama again", "send it again", "speak slower", "slow down", "read it again tomorrow"))
            assertNull(s, SpeechControl.classify(s))
    }

    @Test fun spell_takes_the_last_name_or_number() {
        assertEquals("Ama Serwaa: A, M, A. S, E, R, W, A, A.", SpeechControl.spellLast("You're in WhatsApp, Ama Serwaa."))
        assertEquals("Kwame: K, W, A, M, E.", SpeechControl.spellLast("Send fifty cedis to Kwame?"))
        assertEquals("Zero two four, one two three, four five six seven.", SpeechControl.spellLast("Calling Ama on 024 123 4567."))
        assertEquals("Five five eight, two zero one, three four.", SpeechControl.spellLast("Reference 55820134. Done."))
        assertNull(SpeechControl.spellLast("Okay, it's on."))
        assertNull(SpeechControl.spellLast("The time is 3:40 PM."))                       // trial 2 Nov: was "PM: P, M."
        assertNull(SpeechControl.spellLast("The time is three forty PM."))
        assertEquals("Kofi: K, O, F, I.", SpeechControl.spellLast("Kofi called at 9:15 AM."))
        assertNull(SpeechControl.spellLast(""))
    }

    // ---------------------------------------------------------------- #36

    @Test fun the_shield_flags_only_a_first_time_stranger() {
        assertTrue(CallShield.isFirstTime(isContact = false, seenBefore = false, inCallLog = false))
        assertFalse(CallShield.isFirstTime(isContact = true, seenBefore = false, inCallLog = false))
        assertFalse(CallShield.isFirstTime(isContact = false, seenBefore = true, inCallLog = false))
        assertFalse(CallShield.isFirstTime(isContact = false, seenBefore = false, inCallLog = true))
        assertTrue(CallShield.shouldAsk(flagged = true, answered = true, quiet = false, busy = false))
        assertFalse(CallShield.shouldAsk(flagged = true, answered = false, quiet = false, busy = false))   // a missed call: no question
        assertFalse(CallShield.shouldAsk(flagged = true, answered = true, quiet = true, busy = false))
        assertFalse(CallShield.shouldAsk(flagged = true, answered = true, quiet = false, busy = true))
    }

    @Test fun the_shield_answers_warn_only_and_keeps_no_number() {
        assertEquals(CallShield.WARN, CallShield.reply(Confirmation.Answer.YES))
        assertEquals(CallShield.NO, CallShield.reply(Confirmation.Answer.NO))
        assertNull(CallShield.reply(Confirmation.Answer.UNCLEAR))
        assertFalse(CallShield.WARN.any { it.isDigit() })
        val a = CallShield.hash("salt", "0241234567")!!
        assertEquals(a, CallShield.hash("salt", "+233 24 123 4567"))   // one number, two ways of writing it
        assertNotEquals(a, CallShield.hash("other", "0241234567"))
        assertFalse(a.contains("241234567"))
        assertNull(CallShield.hash("salt", "12"))
        // The question is open for a while, answered once.
        CallShield.openForTest(1_000L)
        assertEquals(CallShield.WARN, CallShield.answer("yes", now = 5_000L))
        assertNull(CallShield.answer("yes", now = 6_000L))
        CallShield.openForTest(1_000L)
        assertNull(CallShield.answer("yes", now = 200_000L))   // too late: an ordinary turn
    }

    // ---------------------------------------------------------------- #40

    private fun run(d: NoisyPlace.Detector, level: Float, fromMs: Long, toMs: Long, burst: Float? = null): List<Boolean> {
        val out = ArrayList<Boolean>()
        var t = fromMs
        var i = 0
        while (t < toMs) {
            val rms = if (burst != null && i % 10 == 0) burst else level
            d.feed(rms, t)?.let { out.add(it) }
            t += 80; i++
        }
        return out
    }

    @Test fun a_place_that_stays_loud_switches_on_and_a_quiet_one_back_off() {
        val d = NoisyPlace.Detector(enterLevel = 0.035, exitLevel = 0.015, enterMs = 30_000, exitMs = 90_000, windowSec = 20, quantile = 0.2)
        assertEquals(emptyList<Boolean>(), run(d, 0.004f, 0, 60_000, burst = 0.2f))   // a quiet room with speech in it
        assertEquals(listOf(true), run(d, 0.05f, 60_000, 160_000))                     // a market
        assertTrue(d.noisy)
        assertEquals(emptyList<Boolean>(), run(d, 0.004f, 160_000, 200_000))            // quiet, but not long enough yet
        assertEquals(listOf(false), run(d, 0.004f, 200_000, 400_000))
    }

    @Test fun the_background_is_a_low_quantile_not_the_mean() {
        assertEquals(0.01, NoisyPlace.backgroundOf(listOf(0.01, 0.01, 0.01, 0.01, 0.9), 0.2), 1e-9)
        assertEquals(0.0, NoisyPlace.backgroundOf(emptyList(), 0.2), 1e-9)
    }
}
