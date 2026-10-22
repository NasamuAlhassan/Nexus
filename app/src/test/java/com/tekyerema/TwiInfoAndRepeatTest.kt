package com.tekyerema

import com.tekyerema.agent.TwiInfoLines
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Confirmation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Fixed Twi quick answers and "say it again" at a read-back (20 Oct 2026). */
class TwiInfoAndRepeatTest {
    @Before fun setUp() = AppConfig.loadFrom(emptyList())
    @After fun tearDown() = AppConfig.loadFrom(emptyList())

    @Test fun twiTimeNamesThePartOfTheDayAndTheHour() {
        assertEquals("Abɔ awia nnɔn 3 apa ho simma 15.", TwiInfoLines.time(15, 15))
        assertEquals("Abɔ anɔpa nnɔn 7.", TwiInfoLines.time(7, 0))
        assertEquals("Abɔ awia dɔn baako apa ho simma 5.", TwiInfoLines.time(13, 5))
        assertEquals("Abɔ anadwo nnɔn 12.", TwiInfoLines.time(0, 0))
        assertEquals("anwummerɛ", TwiInfoLines.partOfDay(17))
    }

    @Test fun twiBattery() {
        assertEquals("Wo batri no aka ɔha mu nkyɛmu 45.", TwiInfoLines.battery(45, charging = false))
        assertTrue(TwiInfoLines.battery(80, charging = true).contains("80"))
    }

    @Test fun aNativeSpeakerCanChangeTheWordingInConfig() {
        AppConfig.loadFrom(listOf("locales.json" to """{"locales":{"tw":{"twiBattery":"Batri: {pct}."}}}"""))
        assertEquals("Batri: 12.", TwiInfoLines.battery(12, charging = false))
    }

    @Test fun repeatRequestsInEnglishAndTwi() {
        for (s in listOf("say that again", "Pardon?", "repeat", "please repeat that", "what did you say", "ka bio", "San ka bio",
                "ka no bio", "mante", "Mante no.", "come again", "I didn't hear that", "what is the number again", "What's the amount?",
                "which number"))
            assertTrue(s, Confirmation.asksToRepeat(s))
        for (s in listOf("yes", "aane", "daabi", "no", "send it again to Kofi", "ka sidi du", "change the number to 0244", "", null))
            assertFalse("$s", Confirmation.asksToRepeat(s))
    }
}
