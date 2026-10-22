package com.tekyerema

import com.tekyerema.voice.Lang
import com.tekyerema.voice.OfflineFirstTts
import com.tekyerema.voice.TtsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Twi line stopped while a voice plays it is over: the next voice in the chain must not read it again (audit, 22 Oct 2026). */
class OfflineFirstTtsStopTest {
    private class Recording(override val name: String) : TtsProvider {
        val said = ArrayList<String>()
        override suspend fun speak(text: String, language: String): Boolean { said += text; return true }
        override fun stop() {}
        override fun shutdown() {}
    }

    /** A voice whose clip is cut short by a stop: MediaAudioSink then reports false. */
    private class StoppedMidLine(override val name: String) : TtsProvider {
        var owner: OfflineFirstTts? = null
        override suspend fun speak(text: String, language: String): Boolean { owner?.stop(); return false }
        override fun stop() {}
        override fun shutdown() {}
    }

    private class Failing(override val name: String) : TtsProvider {
        override suspend fun speak(text: String, language: String): Boolean = false
        override fun stop() {}
        override fun shutdown() {}
    }

    @Test fun aLineStoppedOnTheNetworkVoiceIsNotSaidAgainByThePhonesVoice() = runBlocking {
        val net = StoppedMidLine("net")
        val local = Recording("local")
        val tts = OfflineFirstTts(net, local, isOnline = { true })
        net.owner = tts
        val ok = tts.speak("Hyɛ wo PIN no seesei.", Lang.TWI)
        assertTrue("a stopped line counts as said", ok)
        assertTrue("the stopped line was read again: ${local.said}", local.said.isEmpty())
    }

    @Test fun offlineALineStoppedOnThePhonesVoiceIsNotHandedToTheNetworkChain() = runBlocking {
        val local = StoppedMidLine("local")
        val net = Recording("net")
        val tts = OfflineFirstTts(net, local, isOnline = { false })
        local.owner = tts
        tts.speak("Aane.", Lang.TWI)
        assertTrue("the stopped line was read again: ${net.said}", net.said.isEmpty())
    }

    @Test fun aNetworkVoiceThatCannotSpeakStillFallsToThePhonesVoice() = runBlocking {
        val local = Recording("local")
        val ok = OfflineFirstTts(Failing("net"), local, isOnline = { true }).speak("Aane.", Lang.TWI)
        assertTrue(ok)
        assertEquals(listOf("Aane."), local.said)
    }
}
