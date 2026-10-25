package com.tekyerema

import com.tekyerema.voice.Verbalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** P6 (25 October 2026): the English voice's text normalisation on the things a screen, page or document really contains. */
class EnglishReadingVerbalizeTest {
    private fun say(s: String) = Verbalize.apply(s)

    @Test fun groupedNumbersAreOneNumber() {
        assertEquals("Rated by one thousand two hundred thirty four users", say("Rated by 1,234 users"))
        assertEquals("sales of two million five hundred thousand units", say("sales of 2,500,000 units"))
        assertEquals("one billion", say("1,000,000,000"))
        assertEquals("one thousand two hundred thirty four point five", say("1,234.5"))
    }

    @Test fun groupedNumbersLeaveListsAndMoneyAlone() {
        assertEquals("one, two, three", say("1, 2, 3"))
        assertEquals("one thousand two hundred fifty cedis fifty pesewas", say("GH₵1,250.50"))
        assertFalse(say("1,234").contains(","))
    }
}
