package com.tekyerema

import com.tekyerema.voice.AccentTts
import com.tekyerema.voice.TtsAccent
import com.tekyerema.voice.TtsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 18 October 2026: Ghanaian English is the fourth choice in Settings › Voice, beside British, Nigerian, American. */
class GhanaianVoiceChoiceTest {
    private class Fake(override val name: String, private val ok: Boolean = true) : TtsProvider {
        val said = mutableListOf<String>()
        override suspend fun speak(text: String, language: String): Boolean { said += text; return ok }
        override fun stop() {}
        override fun shutdown() {}
    }

    @Test fun ghanaianIsAChoiceWithItsOwnLabel() {
        assertTrue(TtsAccent.GHANA in TtsAccent.CHOICES)
        assertEquals("Ghanaian English", TtsAccent.label("GH"))
        assertEquals(listOf("GH", "GB", "NG", "US"), TtsAccent.order("GH"))
    }

    @Test fun ownVoiceGoesFirstOnlyForAutomaticAndGhanaian() {
        assertTrue(TtsAccent.ownVoiceFirst(TtsAccent.AUTO))
        assertTrue(TtsAccent.ownVoiceFirst("GH"))
        for (c in listOf("GB", "NG", "US")) assertFalse(c, TtsAccent.ownVoiceFirst(c))
    }

    @Test fun aPickedCountryIsThePhonesVoiceAlone() = runBlocking {
        val own = Fake("own"); val system = Fake("system")
        var choice = "GB"
        val v = AccentTts(own, system) { TtsAccent.ownVoiceFirst(choice) }
        assertTrue(v.speak("Hello", "en"))
        assertEquals(emptyList<String>(), own.said)
        assertEquals(listOf("Hello"), system.said)
        choice = "GH"
        assertTrue(v.speak("Akwaaba", "en"))
        assertEquals(listOf("Akwaaba"), own.said)
        assertEquals(listOf("Hello"), system.said)
    }

    @Test fun aFailedOwnVoiceIsNeverSilence() = runBlocking {
        val own = Fake("own", ok = false); val system = Fake("system")
        val v = AccentTts(own, system) { true }
        assertTrue(v.speak("Your balance is ten cedis", "en"))
        assertEquals(listOf("Your balance is ten cedis"), system.said)
    }
}
