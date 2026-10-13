package com.tekyerema

import com.tekyerema.core.Privacy
import com.tekyerema.voice.AsrException
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.HybridAsr
import com.tekyerema.voice.Lang
import com.tekyerema.voice.SpeechToRaw
import com.tekyerema.voice.Transcript
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * J-15 + Secure Input Mode: while the user types a PIN / password / code, the microphone is open only to hear "done" —
 * and a screen reader may be speaking the digits. Nothing heard then may reach logcat or a cloud recogniser.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecureStepPrivacyTest {
    @After fun reset() { Privacy.secureStep = false }

    private class Device(override val ready: Boolean = true) : SpeechToRaw {
        override val name = "device"
        var ran = false
        override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? { ran = true; return "done" }
    }

    private class Cloud : AsrProvider {
        override val name = "cloud"
        var called = false
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            called = true
            return Transcript(raw = "done", canonical = "done", language = Lang.GH_EN, backend = name)
        }
    }

    @Test fun log_content_is_words_only_when_allowed_and_never_during_a_secure_step() {
        assertEquals("send 50 to Kofi", Privacy.content("send 50 to Kofi", logAllowed = true, secure = false))
        assertEquals("(15 chars)", Privacy.content("send 50 to Kofi", logAllowed = false, secure = false))
        // Even a debug build (content logging allowed) keeps a secure step's words out of logcat.
        assertEquals("(4 chars)", Privacy.content("4821", logAllowed = true, secure = true))
        assertEquals("null", Privacy.content(null, logAllowed = true, secure = true))
        // The default reads the live flag.
        Privacy.secureStep = true
        assertEquals("(4 chars)", Privacy.content("4821"))
    }

    @Test fun a_secure_step_is_transcribed_on_the_phone_only() = runTest {
        val dev = Device(); val cloud = Cloud()
        val hybrid = HybridAsr(dev, onlineFor = { cloud }, forceLanguage = { Lang.GH_EN })
        Privacy.secureStep = true
        val tr = hybrid.transcribe(ByteArray(10), Lang.GH_EN, Lang.GH_EN)
        assertFalse("a PIN spoken by a screen reader must never be sent to an online recogniser", cloud.called)
        assertTrue(dev.ran)
        assertEquals("device", tr.backend)
    }

    @Test fun a_secure_step_without_an_on_device_model_fails_rather_than_going_online() = runTest {
        val cloud = Cloud()
        val hybrid = HybridAsr(Device(ready = false), onlineFor = { cloud }, forceLanguage = { null })
        Privacy.secureStep = true
        try {
            hybrid.transcribe(ByteArray(10), Lang.TWI, null)
            fail("expected an honest failure (the Done button still works)")
        } catch (e: AsrException) { /* expected */ }
        assertFalse(cloud.called)
    }

    @Test fun outside_a_secure_step_the_online_route_is_unchanged() = runTest {
        val dev = Device(); val cloud = Cloud()
        val tr = HybridAsr(dev, onlineFor = { cloud }, forceLanguage = { Lang.GH_EN }).transcribe(ByteArray(10), Lang.GH_EN, Lang.GH_EN)
        assertTrue(cloud.called)
        assertEquals("cloud", tr.backend)
    }

    /** S-03 (P2-04, 13 Oct 2026): the money hand-off's SecureStep raises and lowers the privacy flag itself — before, only
     *  the agent's own credential-screen wait did, so a PIN hand-off through the navigator left logs and the vision gate open. */
    @Test fun the_money_hand_off_raises_the_privacy_flag_and_lowers_it_on_end_or_expiry() {
        val t = 1_790_000_000_000L
        com.tekyerema.voice.SecureStep.end()
        assertFalse(Privacy.secureStep)
        com.tekyerema.voice.SecureStep.begin(t)
        assertTrue(Privacy.secureStep); assertTrue(com.tekyerema.voice.SecureStep.active(t + 1_000))
        assertEquals("(4 chars)", Privacy.content("1234", logAllowed = true))
        com.tekyerema.voice.SecureStep.end()
        assertFalse(Privacy.secureStep)
        com.tekyerema.voice.SecureStep.begin(t)
        assertFalse("past MAX_MS the step is over", com.tekyerema.voice.SecureStep.active(t + com.tekyerema.voice.SecureStep.MAX_MS + 1))
        assertFalse("…and the flag went with it", Privacy.secureStep)
    }
}
