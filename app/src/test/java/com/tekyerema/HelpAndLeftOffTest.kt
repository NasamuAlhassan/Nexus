package com.tekyerema

import com.tekyerema.agent.ContextHelp
import com.tekyerema.agent.LeftOff
import com.tekyerema.history.HistoryEntry
import com.tekyerema.history.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K-01 / K-10 "what can I say here?" and H-10 "where did I leave off?". */
class HelpAndLeftOffTest {

    @Test fun help_phrasings_here_and_general() {
        for (s in listOf("what can i say here", "what can you do in this app", "what can i do here", "help me with this app",
            "tekyerema, what can i say here", "how do i use this app"))
            assertEquals(s, true, ContextHelp.classify(s))
        for (s in listOf("what can you do", "what can i say", "what else can you do", "what are your features", "how can you help me"))
            assertEquals(s, false, ContextHelp.classify(s))
    }

    @Test fun a_cry_for_help_is_never_a_feature_tour() {
        // "help me" / "help" said in distress belong to the emergency path (LocalBrain.RE_EMERGENCY).
        for (s in listOf("help", "help me", "help me please", "i need help", "what can you do and call ama"))
            assertNull(s, ContextHelp.classify(s))
    }

    @Test fun help_names_what_works_in_the_app_in_front() {
        val wa = ContextHelp.forApp("com.whatsapp", "WhatsApp")
        assertTrue(wa, wa.startsWith("In WhatsApp you can say: “what did I miss”"))
        assertTrue(wa, wa.endsWith("Or say “read the screen” to hear what's here."))
        assertTrue(ContextHelp.forApp("com.sec.android.gallery3d", "Gallery").contains("describe this picture"))
        // Our own screen, the home screen or nothing known → the general tour (which ends with the emergency line).
        assertEquals(ContextHelp.GENERAL, ContextHelp.forApp(null, null))
        assertEquals(ContextHelp.GENERAL, ContextHelp.forApp("com.tekyerema", "Tɛkyerɛma"))
        assertEquals(ContextHelp.GENERAL, ContextHelp.forApp("com.sec.android.app.launcher", "One UI Home"))
        assertTrue(ContextHelp.GENERAL.endsWith("In an emergency, say “call the ambulance”."))
        val other = ContextHelp.forApp("com.example.bank", "My Bank")
        assertTrue(other, other.startsWith("In My Bank you can say “read the screen”"))
    }

    @Test fun the_general_tour_is_current_and_short() {
        // Voice-UX (2026-09-29): today's features, each as a phrase that works — in about 20 seconds.
        val g = ContextHelp.GENERAL
        for (s in listOf("WhatsApp", "text", "reading it back first", "Mobile Money up to the PIN, which you enter",
            "messages and notifications", "apps and settings", "reminders, routines and alerts", "“play my downloads”",
            "“take a selfie”", "“check my phone”", "“call my helper”", "“speak Twi”", "“what can I say here?”"))
            assertTrue(s, g.contains(s))
        // At the phone's normal pace (~16.6 characters a second, about 175 words a minute) — a little over 20 seconds at
        // most; the old tour ran past 30.
        assertTrue("${g.length} chars", g.length <= 380)
        assertTrue("${g.split(" ").size} words", g.split(" ").size <= 66)
        assertFalse("no numbers or counts spoken", g.any { it.isDigit() })
        // Every quoted phrase is a request the fast path knows.
        for (p in listOf("play my downloads", "take a selfie", "check my phone", "what can I say here", "call the ambulance"))
            assertTrue(p, com.tekyerema.agent.FastIntentClassifier.classify(p) != null)
    }

    @Test fun left_off_phrasings() {
        for (s in listOf("where did i leave off", "where was i", "where were we", "what was i doing", "what was my last request",
            "pick up where we left off"))
            assertTrue(s, LeftOff.matches(s))
        assertFalse(LeftOff.matches("where is the nearest bank"))
        assertFalse(LeftOff.matches("where was i born"))
    }

    @Test fun left_off_says_how_the_last_request_ended_and_how_to_go_on() {
        val now = 10_000_000L
        assertEquals("Your last request, 12 minutes ago, was “send Ama a message” — it was stopped before it finished. " +
            "Say it again and I'll try again.",
            LeftOff.line(HistoryEntry(now - 12 * 60_000L, "send Ama a message", Outcome.STOPPED), false, null, now))
        assertEquals("The last thing I did, a minute ago: called Prince.",
            LeftOff.line(HistoryEntry(now - 61_000L, "Called Prince", Outcome.COMPLETED), false, null, now))
        assertEquals("You were in WhatsApp. I was partway through reading the screen to you — say “continue” to hear the rest.",
            LeftOff.line(null, true, "WhatsApp", now))
        assertEquals("There's nothing to pick up — you haven't asked me to do anything recently.", LeftOff.line(null, false, null, now))
        assertEquals("yesterday", LeftOff.ago(30 * 3_600_000L))
    }
}
