package com.tekyerema

import com.tekyerema.agent.SpokenRepeat
import org.junit.Assert.assertEquals
import org.junit.Test

/** A20e offline, 27 Oct 2026: "I said read my screen read my screen" missed the local rules. */
class SpokenRepeatTest {
    @Test fun aCorrectionPrefaceAndAnExactDoublingAreDropped() {
        assertEquals("read my screen", SpokenRepeat.clean("I said read my screen read my screen"))
        assertEquals("open WhatsApp", SpokenRepeat.clean("No, I said open WhatsApp"))
        assertEquals("what is my balance", SpokenRepeat.clean("I mean, what is my balance"))
        assertEquals("What time is it", SpokenRepeat.clean("What time is it, what time is it?"))
    }

    @Test fun everythingElseIsLeftAlone() {
        for (s in listOf("read my screen", "tell Ama I said hello", "very very good", "no no", "send ten cedis send", "I said", "call my wife call my mum"))
            assertEquals(s, SpokenRepeat.clean(s))
    }
}
