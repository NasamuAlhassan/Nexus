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

    @Test fun ordinalsAreWords() {
        assertEquals("first, second and third place; twenty first century", say("1st, 2nd and 3rd place; 21st century"))
        assertEquals("the twelfth and the one hundredth", say("the 12th and the 100th"))
        assertEquals("twentieth", say("20th"))
    }

    @Test fun figuresGluedToUnitsAreSpaced() {
        assertEquals("Meet at five pm", say("Meet at 5pm"))
        assertEquals("five thirty pm", say("5:30pm"))
        assertEquals("thirty one degrees Celsius", say("31°C"))
        assertEquals("five point five kilograms", say("5.5kg"))
        assertEquals("sixty kilometres per hour", say("60km/h"))
        assertEquals("one kilometre", say("1 km"))
        assertEquals("five gigahertz", say("5GHz"))
    }

    @Test fun versionsAreSaidAsVersions() {
        assertEquals("version one point two", say("v1.2"))
        assertEquals("build two point zero point one", say("build 2.0.1"))
        // a plain decimal is still a decimal, not a version
        assertEquals("four point five", say("4.5"))
    }

    @Test fun groupedNumbersLeaveListsAndMoneyAlone() {
        assertEquals("one, two, three", say("1, 2, 3"))
        assertEquals("one thousand two hundred fifty cedis fifty pesewas", say("GH₵1,250.50"))
        assertFalse(say("1,234").contains(","))
    }
}
