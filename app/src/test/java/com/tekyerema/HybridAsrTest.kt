package com.tekyerema

import com.tekyerema.voice.AsrException
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.HybridAsr
import com.tekyerema.voice.Lang
import com.tekyerema.voice.SpeechToRaw
import com.tekyerema.voice.Transcript
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 hybrid ASR routing policy (§12/§13) — pure logic, no Android/network/device. `onlineFor(lang)`
 * returns the online provider for that language (Twi→Khaya, Gh-EN→whisper endpoint) or null to stay
 * on-device; callers fold network state in (return null when offline). Proves:
 *  - a language WITH an online provider routes online,
 *  - a language with onlineFor==null (offline OR no endpoint) routes on-device and NEVER calls online,
 *  - an online failure degrades to on-device (fallbackUsed=true),
 *  - nothing available ⇒ fail FAST (never fake success),
 *  - per-call force language selects the right provider.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HybridAsrTest {

    private class FakeOnDevice(override val ready: Boolean = true, val raw: String = "open whatsapp") : SpeechToRaw {
        override val name = "fake-ondevice"
        var ran = false
        override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? { ran = true; return raw }
    }

    private class FakeOnline(val tag: String, val fail: Boolean = false) : AsrProvider {
        override val name = "fake-online-$tag"
        var called = false
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            called = true
            if (fail) throw AsrException("unreachable")
            return Transcript(raw = tag, canonical = tag, language = Lang.TWI, backend = name)
        }
    }

    private val wav = ByteArray(10)

    @Test fun twi_routes_online_when_khaya_is_configured() = runTest {
        val dev = FakeOnDevice(); val khaya = FakeOnline("khaya")
        val hybrid = HybridAsr(dev, onlineFor = { lang -> if (lang == Lang.TWI) khaya else null }, forceLanguage = { Lang.TWI })
        val tr = hybrid.transcribe(wav, Lang.TWI, Lang.TWI)
        assertTrue("Khaya used for Twi when configured", khaya.called)
        assertFalse(dev.ran)
        assertEquals("fake-online-khaya", tr.backend)
    }

    @Test fun twi_offline_needs_a_trusted_on_device_model_and_says_so_otherwise() = runTest {
        val khaya = FakeOnline("khaya")
        // Offline Twi is OFF for now (owner, 3 October 2026): no trusted on-device Twi → an honest "needs the internet".
        val plain = FakeOnDevice()
        val err = try { HybridAsr(plain, onlineFor = { null }, forceLanguage = { Lang.TWI }).transcribe(wav, Lang.TWI, Lang.TWI); null }
            catch (e: AsrException) { e.message }
        assertEquals(com.tekyerema.voice.TWI_NEEDS_INTERNET, err)
        assertFalse("never guesses with an untrusted model", plain.ran)
        // Switched back on (a trusted model): offline Twi is heard on the phone, and online is never called.
        val trusted = object : SpeechToRaw by FakeOnDevice() {
            var ran = false
            override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? { ran = true; return "frɛ kwame" }
            override fun trustedFor(lang: String) = true
        }
        val tr = HybridAsr(trusted, onlineFor = { null }, forceLanguage = { Lang.TWI }).transcribe(wav, Lang.TWI, Lang.TWI)
        assertFalse("online must NOT be called when unconfigured/offline", khaya.called)
        assertTrue(trusted.ran)
    }

    @Test fun ghen_routes_online_when_endpoint_configured() = runTest {
        val dev = FakeOnDevice(); val whisper = FakeOnline("whisper")
        val hybrid = HybridAsr(dev, onlineFor = { lang -> if (lang == Lang.GH_EN) whisper else null }, forceLanguage = { Lang.GH_EN })
        val tr = hybrid.transcribe(wav, Lang.GH_EN, Lang.GH_EN)
        assertTrue(whisper.called); assertFalse(dev.ran); assertFalse(tr.fallbackUsed)
    }

    @Test fun offline_or_no_endpoint_routes_on_device_and_never_calls_online() = runTest {
        val dev = FakeOnDevice(); val whisper = FakeOnline("whisper")
        val hybrid = HybridAsr(dev, onlineFor = { null }, forceLanguage = { Lang.GH_EN })
        val tr = hybrid.transcribe(wav, Lang.GH_EN, Lang.GH_EN)
        assertFalse("online never called with no endpoint (no hang)", whisper.called)
        assertTrue(dev.ran); assertEquals("fake-ondevice", tr.backend)
    }

    @Test fun ghen_online_failure_degrades_to_on_device() = runTest {
        // Gh-EN on-device is a real model, so an online failure legitimately degrades to it.
        val dev = FakeOnDevice(); val whisper = FakeOnline("whisper", fail = true)
        val hybrid = HybridAsr(dev, onlineFor = { whisper }, forceLanguage = { Lang.GH_EN })
        val tr = hybrid.transcribe(wav, Lang.GH_EN, Lang.GH_EN)
        assertTrue(whisper.called); assertTrue(dev.ran); assertTrue(tr.fallbackUsed)
        assertEquals("fake-ondevice", tr.backend)
    }

    @Test fun twi_online_failure_does_not_degrade_to_disqualified_model() = runTest {
        // §10.3 — the on-device Twi model is DISQUALIFIED, so a Khaya failure must fail HONESTLY, not
        // silently degrade to it and speak confident garbage.
        val dev = FakeOnDevice(); val khaya = FakeOnline("khaya", fail = true)
        val hybrid = HybridAsr(dev, onlineFor = { khaya }, forceLanguage = { Lang.TWI })
        var threw = false
        try { hybrid.transcribe(wav, Lang.TWI, Lang.TWI) } catch (e: AsrException) { threw = true }
        assertTrue("Twi must not fall back to the disqualified on-device model", threw)
        assertFalse("on-device Twi must NOT run on a Khaya failure", dev.ran)
    }

    @Test fun nothing_available_fails_fast_and_honestly() = runTest {
        val dev = FakeOnDevice(ready = false); val khaya = FakeOnline("khaya")
        val hybrid = HybridAsr(dev, onlineFor = { null }, forceLanguage = { Lang.TWI })
        var threw = false
        try { hybrid.transcribe(wav, Lang.TWI, Lang.TWI) } catch (e: AsrException) { threw = true }
        assertTrue("must fail fast, never fake success", threw)
        assertFalse(khaya.called)
    }

    @Test fun auto_mode_two_pass_escalates_confident_twi_to_khaya() = runTest {
        // §C.6 — no forced language: transcribe on-device, detect Twi, escalate to Khaya.
        val dev = FakeOnDevice(raw = "frɛ Kofi"); val khaya = FakeOnline("khaya")
        val hybrid = HybridAsr(dev, onlineFor = { lang -> if (lang == Lang.TWI) khaya else null }, forceLanguage = { null })
        val tr = hybrid.transcribe(wav, Lang.GH_EN, forceLanguage = null)
        assertTrue("on-device first pass ran", dev.ran)
        assertTrue("confident-Twi escalates to Khaya", khaya.called)
        assertEquals("fake-online-khaya", tr.backend)
    }

    @Test fun auto_mode_two_pass_keeps_english_on_device() = runTest {
        // §C.6 — English on-device result is NOT escalated to the Twi provider.
        val dev = FakeOnDevice(raw = "open whatsapp"); val khaya = FakeOnline("khaya")
        val hybrid = HybridAsr(dev, onlineFor = { lang -> if (lang == Lang.TWI) khaya else null }, forceLanguage = { null })
        val tr = hybrid.transcribe(wav, Lang.GH_EN, forceLanguage = null)
        assertTrue(dev.ran); assertFalse("English must not go to the Twi model", khaya.called)
        assertEquals("fake-ondevice", tr.backend)
    }

    @Test fun per_call_force_language_selects_provider() = runTest {
        val dev = FakeOnDevice(); val khaya = FakeOnline("khaya")
        // onlineFor gives Khaya only for Twi; a forced-Twi call must reach it.
        val hybrid = HybridAsr(dev, onlineFor = { lang -> if (lang == Lang.TWI) khaya else null }, forceLanguage = { Lang.GH_EN })
        hybrid.transcribe(wav, Lang.GH_EN, forceLanguage = Lang.TWI)
        assertTrue("forced-Twi call routes to Khaya", khaya.called)
        assertFalse(dev.ran)
    }
}
