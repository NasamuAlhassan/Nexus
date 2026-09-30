package com.tekyerema

import com.tekyerema.ime.DictationText
import com.tekyerema.ime.VoicePlan
import com.tekyerema.ime.VoiceRoute
import com.tekyerema.voice.AsrException
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Dictation
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.Wav
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §3(a) interaction_details.md — the keyboard's mic key on OUR OWN ASR (voice/Dictation.kt + VoiceRoute.OWN_ASR).
 * Pure logic only, fakes in the style of VoiceLogicTest/HybridAsrTest: the route order, the state guards
 * (a running voice turn, a secure step, a missing stack), the mic hand-over bracket, the honest failure lines,
 * the TalkBack commit announcement — and a source guard that the facade never logs what was dictated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationTest {

    // ------------------------------------------------------------------------------------------ fakes

    private class FakeMic(val result: CaptureResult) : Recorder {
        var listened = false
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            listened = true; onListening(); return result
        }
    }

    private class FakeAsr(val raw: String = "buy credit for me", val fail: String? = null) : AsrProvider {
        override val name = "fake-dictation"
        var called = false
        var forced: String? = "never-set"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            called = true; forced = forceLanguage
            if (fail != null) throw AsrException(fail)
            return Transcript(raw = raw, canonical = raw, language = sessionLanguage, backend = name)
        }
    }

    private val wav = Wav.wrap(ByteArray(3200), 16000)
    private fun heard() = CaptureResult.Success(wav, 900)

    // ------------------------------------------------------------------------------------------ route choice

    @Test fun the_mic_key_prefers_our_own_asr_and_falls_back_in_order() {
        assertEquals(VoiceRoute.OWN_ASR, VoicePlan.route(true, true, true, true, ownAsrAvailable = true))
        assertEquals(VoiceRoute.IN_KEYBOARD, VoicePlan.route(true, true, true, true, ownAsrAvailable = false))
        // Our stack needs no system recognizer and no other voice keyboard.
        assertEquals(VoiceRoute.OWN_ASR, VoicePlan.route(true, true, false, false, ownAsrAvailable = true))
        // A password/PIN box always wins — no route at all.
        assertEquals(VoiceRoute.OFF_IN_THIS_FIELD, VoicePlan.route(false, true, true, true, ownAsrAvailable = true))
        // Without the mic permission our stack cannot record; the voice keyboard has its own permission.
        assertEquals(VoiceRoute.VOICE_KEYBOARD, VoicePlan.route(true, false, true, true, ownAsrAvailable = true))
        assertEquals("", VoicePlan.explain(VoiceRoute.OWN_ASR))
        // The old four-argument shape still means what it always meant (the callers that don't know OWN_ASR).
        assertEquals(VoiceRoute.IN_KEYBOARD, VoicePlan.route(true, true, true, true))
    }

    // ------------------------------------------------------------------------------------------ state guards

    @Test fun dictation_refuses_secure_steps_and_running_turns_without_fallback() {
        assertNull(Dictation.refusal(engineRunning = false, secure = false, ready = true))
        val secure = Dictation.refusal(engineRunning = false, secure = true, ready = true)!!
        assertFalse("secure audio must never be handed to another recognizer", secure.fallbackToSystem)
        assertTrue(secure.message.contains("secure step"))
        val busy = Dictation.refusal(engineRunning = true, secure = false, ready = true)!!
        assertFalse("a second recorder would fight the turn's mic", busy.fallbackToSystem)
        // Only a not-ready stack may hand the attempt to the phone's SpeechRecognizer.
        assertTrue(Dictation.refusal(engineRunning = false, secure = false, ready = false)!!.fallbackToSystem)
        // The secure guard wins over everything else.
        assertFalse(Dictation.refusal(engineRunning = true, secure = true, ready = false)!!.fallbackToSystem)
    }

    @Test fun a_running_voice_turn_keeps_the_dictation_mic_shut() = runTest {
        var handed = false
        val mic = FakeMic(heard()); val asr = FakeAsr()
        val out = Dictation.run(twi = false, engineRunning = true, secure = false, mic = mic, asr = asr,
            handOver = { handed = true }, reclaim = {}, onListening = {})
        assertTrue(out is Dictation.Outcome.None)
        assertFalse(handed); assertFalse(mic.listened); assertFalse(asr.called)
    }

    @Test fun a_secure_step_keeps_the_dictation_mic_shut() = runTest {
        val mic = FakeMic(heard())
        val out = Dictation.run(twi = true, engineRunning = false, secure = true, mic = mic, asr = FakeAsr(),
            handOver = {}, reclaim = {}, onListening = {})
        assertTrue(out is Dictation.Outcome.None)
        assertFalse(mic.listened)
    }

    @Test fun a_missing_stack_refuses_with_the_fallback_flag() = runTest {
        val out = Dictation.run(twi = false, engineRunning = false, secure = false, mic = null, asr = null,
            handOver = {}, reclaim = {}, onListening = {})
        assertTrue((out as Dictation.Outcome.None).fallbackToSystem)
    }

    // ------------------------------------------------------------------------------------------ the happy path

    @Test fun the_wake_word_hands_the_mic_over_and_always_gets_it_back() = runTest {
        val order = ArrayList<String>()
        val mic = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                order += "capture"; return heard()
            }
        }
        val out = Dictation.run(twi = false, engineRunning = false, secure = false, mic = mic, asr = FakeAsr(),
            handOver = { order += "handOver" }, reclaim = { order += "reclaim" }, onListening = {})
        assertEquals(listOf("handOver", "capture", "reclaim"), order)
        assertEquals("buy credit for me", (out as Dictation.Outcome.Text).text)
    }

    @Test fun the_keyboard_language_forces_the_asr_language() = runTest {
        val twi = FakeAsr()
        Dictation.run(twi = true, engineRunning = false, secure = false, mic = FakeMic(heard()), asr = twi,
            handOver = {}, reclaim = {}, onListening = {})
        assertEquals(Lang.TWI, twi.forced)
        val en = FakeAsr()
        Dictation.run(twi = false, engineRunning = false, secure = false, mic = FakeMic(heard()), asr = en,
            handOver = {}, reclaim = {}, onListening = {})
        assertEquals(Lang.GH_EN, en.forced)
    }

    // ------------------------------------------------------------------------------------------ honest failures

    @Test fun silence_gets_the_short_honest_line_and_never_reaches_the_recogniser() = runTest {
        for (result in listOf(CaptureResult.NoSpeech, CaptureResult.TooShort)) {
            val asr = FakeAsr()
            val out = Dictation.run(twi = false, engineRunning = false, secure = false, mic = FakeMic(result), asr = asr,
                handOver = {}, reclaim = {}, onListening = {})
            assertEquals(Dictation.DIDNT_CATCH, (out as Dictation.Outcome.None).message)
            assertFalse(out.fallbackToSystem)
            assertFalse(asr.called)
        }
    }

    @Test fun the_mic_is_reclaimed_even_when_the_recogniser_fails() = runTest {
        var reclaimed = false
        val out = Dictation.run(twi = true, engineRunning = false, secure = false, mic = FakeMic(heard()),
            asr = FakeAsr(fail = "The Twi voice service is unavailable right now"),
            handOver = {}, reclaim = { reclaimed = true }, onListening = {})
        assertTrue(reclaimed)
        assertEquals("The Twi voice service is unavailable right now", (out as Dictation.Outcome.None).message)
        assertFalse("the user already spoke — no silent second listen", out.fallbackToSystem)
    }

    @Test fun a_blank_transcript_is_nothing_not_an_empty_commit() = runTest {
        val out = Dictation.run(twi = false, engineRunning = false, secure = false, mic = FakeMic(heard()),
            asr = FakeAsr(raw = "   "), handOver = {}, reclaim = {}, onListening = {})
        assertEquals(Dictation.DIDNT_CATCH, (out as Dictation.Outcome.None).message)
    }

    @Test fun a_cancelled_capture_ends_quietly() = runTest {
        var reclaimed = false
        val out = Dictation.run(twi = false, engineRunning = false, secure = false, mic = FakeMic(CaptureResult.Cancelled),
            asr = FakeAsr(), handOver = {}, reclaim = { reclaimed = true }, onListening = {})
        assertTrue(out is Dictation.Outcome.Cancelled)
        assertTrue(reclaimed)
    }

    @Test fun a_dead_microphone_gets_its_own_honest_line() = runTest {
        val out = Dictation.run(twi = false, engineRunning = false, secure = false,
            mic = FakeMic(CaptureResult.Error("AudioRecord create failed for all sources")), asr = FakeAsr(),
            handOver = {}, reclaim = {}, onListening = {})
        assertTrue((out as Dictation.Outcome.None).message.contains("microphone"))
        assertFalse(out.fallbackToSystem)
    }

    // ------------------------------------------------------------------------------------------ TalkBack polish

    @Test fun the_commit_announcement_reads_the_words_without_the_spacing() {
        assertEquals("Typed: call me later", DictationText.announceTyped(" call me later"))
        assertEquals("Typed: Mepɛ sɛ", DictationText.announceTyped("Mepɛ sɛ"))
        assertEquals("", DictationText.announceTyped("   "))
        assertEquals("", DictationText.announceTyped(""))
    }

    // ------------------------------------------------------------------------------------------ privacy guard

    @Test fun nothing_dictated_can_reach_logcat_from_the_facade() {
        val src = File("src/main/java/com/tekyerema/voice/Dictation.kt").readText()
        assertFalse("not even the debug-gated content helper", src.contains("Privacy.content"))
        val logLines = src.lines().filter { Regex("\\bLog\\.[vdiwe]\\(").containsMatchIn(it) }
        assertTrue("the facade should log its stages (length-only)", logLines.isNotEmpty())
        for (l in logLines) {
            // A log line may mention the transcript only through its length; never the words themselves.
            val mentions = Regex("\\b(out|text|raw)\\b").containsMatchIn(l.substringAfter("Log."))
            assertTrue("dictated words must never be logged: $l", !mentions || l.contains(".length"))
        }
    }
}
