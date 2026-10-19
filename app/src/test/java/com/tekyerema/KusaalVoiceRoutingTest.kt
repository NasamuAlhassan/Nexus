package com.tekyerema

import com.tekyerema.voice.Lang
import com.tekyerema.voice.OnDeviceVoice
import com.tekyerema.voice.RoutingTts
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.TwiVoiceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 19 October 2026: the Kusaal phone voice in the app — a Kusaal line goes to the on-device Kusaal voice, and
 *  what it cannot say goes to the English voice (never silent); English and Twi lines never reach it. */
class KusaalVoiceRoutingTest {
    private class Voice(override val name: String, var accept: Set<String>) : TtsProvider {
        val said = ArrayList<Pair<String, String>>()
        override suspend fun speak(text: String, language: String): Boolean { said.add(text to language); return language in accept }
        override fun stop() {}
        override fun shutdown() {}
    }

    private val status = TwiVoiceStatus(clock = { 0L })

    @Test fun aKusaalLineIsSpokenByTheKusaalVoice() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kus = Voice("on-device(kus-phone)", setOf("kus"))
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), twiStatus = status, others = mapOf("kus" to kus))
        assertTrue(routing.speak("Fʋ gɔŋ la?", "kus"))
        assertEquals(listOf("Fʋ gɔŋ la?" to "kus"), kus.said)
        assertTrue("the English voice says nothing", phone.said.isEmpty())
    }

    @Test fun aKusaalLineTheVoiceCannotSayFallsToTheEnglishVoice() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kus = Voice("on-device(kus-phone)", emptySet())          // the model would not load
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), twiStatus = status, others = mapOf("kus" to kus))
        assertTrue(routing.speak("Fʋ gɔŋ la?", "kus"))
        assertEquals(listOf("Fʋ gɔŋ la?" to Lang.GH_EN), phone.said)
    }

    @Test fun englishAndTwiNeverReachTheKusaalVoice() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val twi = Voice("lab", setOf(Lang.TWI))
        val kus = Voice("on-device(kus-phone)", setOf("kus"))
        val routing = RoutingTts(english = phone, twi = twi, twiStatus = status, others = mapOf("kus" to kus))
        routing.speak("The time is six.", Lang.GH_EN)
        routing.speak("Mabue no.", Lang.TWI)
        assertTrue(kus.said.isEmpty())
        assertEquals(1, phone.said.size)
        assertEquals(1, twi.said.size)
    }

    @Test fun eachLanguageHasItsOwnVoiceDirectory() {
        assertEquals("models/ondevice/kus/tts", OnDeviceVoice.dirFor("kus"))
        assertEquals("models/ondevice/kus/tts", OnDeviceVoice.dirFor(" KUS "))
        assertEquals("models/ondevice/eng/tts", OnDeviceVoice.dirFor("eng"))
    }

    @Test fun theRouterNamesItsKusaalVoice() {
        val routing = RoutingTts(english = Voice("system", emptySet()), twi = Voice("lab", emptySet()), twiStatus = status,
            others = mapOf("kus" to Voice("on-device(kus-phone)", setOf("kus"))))
        assertTrue(routing.name, routing.name.contains("kus=on-device(kus-phone)"))
    }
}
