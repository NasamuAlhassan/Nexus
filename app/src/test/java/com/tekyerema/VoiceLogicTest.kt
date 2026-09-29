package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.EnergyVad
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.VoiceStage
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.Verbalize
import com.tekyerema.voice.Wav
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2 voice: pure-logic + state-machine checks (§36). No Android, no network, no real mic/model.
 * Proves the VAD lifecycle, TTS verbalization, WAV framing, and that the voice controller drives the
 * SAME agent and never auto-confirms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLogicTest {

    private fun frame(amp: Int, n: Int = 320) = ShortArray(n) { amp.toShort() }

    // ---------------------------------------------------------------- EnergyVad
    @Test fun vad_rms_reflects_amplitude() {
        assertEquals(0.0, EnergyVad.rms(ShortArray(0)), 0.0)
        assertEquals(1000.0, EnergyVad.rms(frame(1000)), 1.0)
        assertTrue(EnergyVad.rms(frame(4000)) > EnergyVad.rms(frame(200)))
    }

    @Test fun vad_detects_onset_then_end_after_hangover() {
        val vad = EnergyVad()
        repeat(10) { assertEquals(EnergyVad.Event.SILENCE, vad.process(frame(30))) }   // quiet warm-up (raw domain)
        assertEquals(EnergyVad.Event.SILENCE, vad.process(frame(4000)))                 // onset debounce 1
        assertEquals(EnergyVad.Event.SILENCE, vad.process(frame(4000)))                 // onset debounce 2
        assertEquals(EnergyVad.Event.SPEECH_START, vad.process(frame(4000)))            // onset confirmed
        repeat(20) { assertEquals(EnergyVad.Event.SPEECH, vad.process(frame(4000))) }   // sustained speech
        var ended = false
        repeat(45) { if (vad.process(frame(30)) == EnergyVad.Event.SPEECH_END) ended = true }
        assertTrue("a natural pause of the hangover length must end the utterance", ended)
        assertTrue(vad.capturedSpeechMs > 0)
    }

    @Test fun vad_rejects_a_short_blip_as_noise() {
        val vad = EnergyVad()
        repeat(5) { vad.process(frame(30)) }
        vad.process(frame(4000)); vad.process(frame(4000))
        assertEquals(EnergyVad.Event.SPEECH_START, vad.process(frame(4000)))            // only ~3 speech frames
        var sawEnd = false; var sawTooShort = false
        repeat(50) {
            when (vad.process(frame(30))) {
                EnergyVad.Event.SPEECH_END -> sawEnd = true
                EnergyVad.Event.TOO_SHORT -> sawTooShort = true
                else -> {}
            }
        }
        assertFalse("a 3-frame blip is below min-speech and must NOT be accepted as an utterance", sawEnd)
        assertTrue("§3.4 a blip must surface as TOO_SHORT, not swallowed as silence", sawTooShort)
    }

    @Test fun vad_thresholds_on_raw_levels_room_noise_never_triggers() {
        // §3.1 — raw domain: a quiet room (~35 RMS) must never trigger onset; command speech (~260) must.
        val vad = EnergyVad()
        repeat(50) { assertEquals(EnergyVad.Event.SILENCE, vad.process(frame(35))) }    // sustained room noise
        vad.process(frame(260)); vad.process(frame(260))
        assertEquals(EnergyVad.Event.SPEECH_START, vad.process(frame(260)))             // raw speech onset
    }

    @Test fun asr_repetition_guard_collapses_runaway_loops() {
        // §3.10 — the tiny models loop on short tails; the guard collapses a ≥3× trailing run to one.
        assertEquals("Call Coffee", com.tekyerema.voice.SherpaAsr.guardRepetition("Call Coffee Coffee Coffee Coffee"))
        assertEquals("Open WhatsApp now", com.tekyerema.voice.SherpaAsr.guardRepetition("Open WhatsApp now"))  // no run → unchanged
        assertTrue(com.tekyerema.voice.SherpaAsr.guardRepetition(("wo ".repeat(200)).trim()).split(" ").size <= 60)
    }

    // ---------------------------------------------------------------- §A.1 Twi confirmation templates
    @Test fun twi_confirmation_preserves_name_verbatim_and_digits_intact() {
        // digit-by-digit Twi numerals, exact:
        assertEquals("zero mmienu anan anan zero zero zero zero baako baako", com.tekyerema.voice.ConfirmTemplates.twiDigits("0244000011"))
        assertEquals("0 2 4 4 0 0 0 0 1 1", com.tekyerema.voice.ConfirmTemplates.enDigits("0244000011"))
        // a call to a resolved contact: NON-ASCII name survives verbatim, 10-digit 0xx number intact
        val spec = com.tekyerema.voice.ConfirmTemplates.Spec(
            com.tekyerema.voice.ConfirmTemplates.Spec.Kind.CALL, name = "Kwabɛna Owusu-Ansah", number = "0244000011")
        val q = com.tekyerema.voice.ConfirmTemplates.twi(spec)
        assertTrue("name must pass through verbatim", q.contains("Kwabɛna Owusu-Ansah"))
        // …in the 3-3-4 groups people check numbers in (money safety pass, 2026-09-29)
        assertTrue("every digit must survive", q.contains("zero mmienu anan, anan zero zero, zero zero baako baako"))
        assertTrue("must be a Twi question", q.contains("Wopɛ sɛ mefrɛ") && q.contains("aane anaa daabi"))
        // send money: amount digit-by-digit, recipient verbatim
        val money = com.tekyerema.voice.ConfirmTemplates.twi(com.tekyerema.voice.ConfirmTemplates.Spec(
            com.tekyerema.voice.ConfirmTemplates.Spec.Kind.SEND_MONEY, name = "Adwoa", amount = "50"))
        assertTrue(money.contains("Adwoa") && money.contains("anum zero"))   // 5,0
    }

    // ---------------------------------------------------------------- Verbalize (§23)
    @Test fun verbalize_speaks_quantities_but_spells_long_digit_runs() {
        assertTrue(Verbalize.apply("You have 1500 cedis").contains("one thousand five hundred"))
        assertEquals("two cedis", Verbalize.apply("2 cedis"))
        assertEquals("Call zero five five two zero nine three six nine zero", Verbalize.apply("Call 0552093690"))
        assertEquals("zero", Verbalize.intToWords(0))
        assertEquals("twenty one", Verbalize.intToWords(21))
        assertEquals("three hundred five", Verbalize.intToWords(305))
    }

    // ---------------------------------------------------------------- WAV framing
    @Test fun wav_wrap_writes_a_valid_pcm16_header() {
        val pcm = ByteArray(8) { it.toByte() }
        val wav = Wav.wrap(pcm, 16000)
        assertEquals(44 + 8, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("data", String(wav.copyOfRange(36, 40), Charsets.US_ASCII))
        val sr = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
            ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
        assertEquals(16000, sr)
    }

    // ---------------------------------------------------------------- LangPipeline (local LID/normalize/entities §10/§13/§14)

    @Test fun normalizer_english_numbers_currency_and_digit_sequences() {
        assertEquals("set the volume to 0", com.tekyerema.voice.Normalizer.normalize("set the volume to zero", com.tekyerema.voice.Lang.GH_EN))
        assertEquals("1500", com.tekyerema.voice.Normalizer.normalize("one thousand five hundred", com.tekyerema.voice.Lang.GH_EN))
        assertEquals("21", com.tekyerema.voice.Normalizer.normalize("twenty-one", com.tekyerema.voice.Lang.GH_EN))
        // spoken digit-by-digit stays a sequence (phone/code), not summed
        assertEquals("0 5 5 2", com.tekyerema.voice.Normalizer.normalize("zero five five two", com.tekyerema.voice.Lang.GH_EN))
        // currency mishearing repaired only right after a digit
        assertEquals("5 cedis", com.tekyerema.voice.Normalizer.normalize("5 CDs", com.tekyerema.voice.Lang.GH_EN))
        assertTrue(com.tekyerema.voice.Normalizer.normalize("Accra city", com.tekyerema.voice.Lang.GH_EN).contains("city"))
    }

    @Test fun normalizer_does_not_miscompose_times_or_over_repair_currency() {
        val n = { s: String -> com.tekyerema.voice.Normalizer.normalize(s, com.tekyerema.voice.Lang.GH_EN) }
        // §5.1 — a unit/teen followed by a tens is a time, not a sum: "six thirty" ≠ 36.
        assertEquals("6 30", n("six thirty"))
        assertEquals("15 50", n("fifteen fifty"))
        assertEquals("36", n("thirty six"))          // valid tens+unit still composes
        assertEquals("1500", n("one thousand five hundred"))  // scale composition still works
        // §5.2 — "one city" must not become "1 cedis" (city is an ordinary word now).
        assertTrue(n("I have one city").contains("city"))
        assertEquals("5 cedis", n("5 CDs"))          // real acoustic mishearing still repaired
    }

    @Test fun normalizer_twi_du_is_ten_only_in_numeric_context() {
        val n = { s: String -> com.tekyerema.voice.Normalizer.normalize(s, com.tekyerema.voice.Lang.TWI) }
        assertEquals("10 cedi", n("du cedi"))         // §5.3 numeric neighbour → ten
        assertTrue("bare 'du' must stay the ordinary word", n("du").contains("du"))
    }

    @Test fun normalizer_twi_numbers_with_punctuation_and_case() {
        assertEquals("6, 7, 8, 9.", com.tekyerema.voice.Normalizer.normalize("Nsia, nson, mmɔkye, nkron.", com.tekyerema.voice.Lang.TWI))
        // colloquial currency words recovered to the canonical token (sidi→cedi), number still normalized
        assertEquals("cedi 20.", com.tekyerema.voice.Normalizer.normalize("Sidi aduonu.", com.tekyerema.voice.Lang.TWI))
    }

    @Test fun normalizer_twi_currency_variants_recover_the_currency_entity() {
        // observed clean-v1 outputs: cedi mispronounced as sidi/sedi/sidim; pesewa as paswa (§13 fix)
        assertEquals("cedi", com.tekyerema.voice.Normalizer.normalize("sidi", com.tekyerema.voice.Lang.TWI))
        assertEquals("cedi", com.tekyerema.voice.Normalizer.normalize("Sidim", com.tekyerema.voice.Lang.TWI))
        assertEquals("pesewa 50.", com.tekyerema.voice.Normalizer.normalize("Paswa aduonum.", com.tekyerema.voice.Lang.TWI))
        // end-to-end: a raw Twi transcript saying "sidi" yields the cedi currency entity (pipeline
        // normalizes, then derives entities from the canonical text — the real on-device path)
        val t = com.tekyerema.voice.LanguagePipeline.process("Sidi anum", com.tekyerema.voice.Lang.TWI, forceLanguage = com.tekyerema.voice.Lang.TWI)
        assertEquals("cedi 5", t.canonical)
        assertTrue(t.entities.contains("cedi" to "currency"))
        assertTrue(t.entities.contains("5" to "number"))
    }

    @Test fun lid_is_confidence_aware_and_never_flips_on_a_bare_name() {
        assertEquals(com.tekyerema.voice.Lang.GH_EN, com.tekyerema.voice.Lid.detect("Send five cedis to Prince").lang)
        assertEquals(com.tekyerema.voice.Lang.TWI, com.tekyerema.voice.Lid.detect("Frɛ me nua barima").lang)
        // bare name → session default, not a forced flip
        assertEquals(com.tekyerema.voice.Lang.GH_EN, com.tekyerema.voice.Lid.resolve("Prince", sessionDefault = com.tekyerema.voice.Lang.GH_EN).first)
        // low-signal honors the previous turn's language
        assertEquals(com.tekyerema.voice.Lang.TWI, com.tekyerema.voice.Lid.resolve("50", prevLang = com.tekyerema.voice.Lang.TWI).first)
    }

    @Test fun lid_english_confirmation_words_are_not_twi() {
        // §4.2 — "no"/"cancel" must score English, not flip to Twi (the audit's "No, cancel" → Twi 1.0 bug).
        assertEquals(com.tekyerema.voice.Lang.GH_EN, com.tekyerema.voice.Lid.detect("No, cancel").lang)
        assertEquals(com.tekyerema.voice.Lang.GH_EN, com.tekyerema.voice.Lid.resolve("No, cancel", sessionDefault = com.tekyerema.voice.Lang.GH_EN).first)
    }

    @Test fun lid_does_not_flip_session_on_a_single_token() {
        // §4.3 — a single lexicon token must not switch the session language; sustained evidence required.
        // One bare English word in a Twi session stays Twi:
        assertEquals(com.tekyerema.voice.Lang.TWI, com.tekyerema.voice.Lid.resolve("okay", prevLang = com.tekyerema.voice.Lang.TWI).first)
        // A Twi word WITH diacritics is sustained evidence and DOES resolve Twi from an English session:
        assertEquals(com.tekyerema.voice.Lang.TWI, com.tekyerema.voice.Lid.resolve("frɛ", sessionDefault = com.tekyerema.voice.Lang.GH_EN).first)
    }

    @Test fun entities_preserve_name_number_currency_app_command() {
        val e = com.tekyerema.voice.Entities.derive("Send Prince 5 cedis on WhatsApp", com.tekyerema.voice.Lang.GH_EN)
        assertTrue(e.contains("Prince" to "name"))
        assertTrue(e.contains("5" to "number"))
        assertTrue(e.contains("cedis" to "currency"))
        assertTrue(e.contains("whatsapp" to "app"))
        assertTrue(e.contains("send" to "command"))
    }

    @Test fun pipeline_produces_canonical_goal_and_preserves_the_test_identity() {
        // §14: the Prince phone number must survive ASR→normalize→entities intact.
        val tr = com.tekyerema.voice.LanguagePipeline.process("Call Prince at 0552093690", sessionLanguage = com.tekyerema.voice.Lang.GH_EN)
        assertEquals(com.tekyerema.voice.Lang.GH_EN, tr.language)
        assertEquals("Call Prince at 0552093690", tr.canonical)   // digits already-digits: untouched
        assertTrue(tr.entities.contains("Prince" to "name"))
        assertTrue(tr.entities.contains("0552093690" to "number"))
        assertTrue(tr.entities.contains("call" to "command"))
        // raw is preserved separately (§13)
        assertEquals("Call Prince at 0552093690", tr.raw)
    }

    @Test fun pipeline_forced_twi_uses_twi_normalization() {
        val tr = com.tekyerema.voice.LanguagePipeline.process("Bue WhatsApp", sessionLanguage = com.tekyerema.voice.Lang.GH_EN, forceLanguage = com.tekyerema.voice.Lang.TWI)
        assertEquals(com.tekyerema.voice.Lang.TWI, tr.language)
        assertEquals("explicit", tr.reason)
    }

    // ---------------------------------------------------------------- LocalAsrProvider (§34 local model → LanguagePipeline)

    @Test fun local_asr_provider_runs_model_output_through_the_local_pipeline() = runTest {
        val fakeModel = object : com.tekyerema.voice.SpeechToRaw {
            override val name = "fake"; override val ready = true
            override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?) = "Call Prince at 0552093690"
        }
        val provider = com.tekyerema.voice.LocalAsrProvider(fakeModel)
        val tr = provider.transcribe(ByteArray(10), com.tekyerema.voice.Lang.GH_EN, null)
        assertEquals("Call Prince at 0552093690", tr.canonical)
        assertTrue(tr.entities.contains("Prince" to "name"))
        assertTrue(tr.entities.contains("0552093690" to "number"))
        assertEquals("fake", tr.backend)
    }

    @Test fun local_asr_provider_fails_honestly_when_no_model_is_integrated() = runTest {
        var threw = false
        try { com.tekyerema.voice.LocalAsrProvider().transcribe(ByteArray(10), com.tekyerema.voice.Lang.GH_EN, null) }
        catch (e: com.tekyerema.voice.AsrException) { threw = true }
        assertTrue("must not fake success when no local model exists", threw)
    }

    // ---------------------------------------------------------------- Fakes for the controller
    private class FakeRecorder(val result: CaptureResult) : Recorder {
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            onListening(); return result
        }
    }
    private class FakeAsr(val tr: Transcript) : AsrProvider {
        override val name = "fake"
        var lastForce: String? = null
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            lastForce = forceLanguage; return tr
        }
    }
    private class FakeTts : TtsProvider {
        override val name = "fake"
        var spokenText: String? = null; var spokenLang: String? = null
        override suspend fun speak(text: String, language: String): Boolean { spokenText = text; spokenLang = language; return true }
        override fun stop() {}
        override fun shutdown() {}
    }
    private class FakeAgent(val terminal: AgentStatus, val result: String?) : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        var submitted: String? = null
        var stopped = false
        override fun submitGoal(goal: String) {
            submitted = goal
            val log = result?.let { listOf(LogEntry(0, LogEntry.Kind.RESULT, it)) } ?: emptyList()
            _s.value = AgentUiState(status = terminal, goal = goal, log = log)
        }
        override fun stop() { stopped = true }
    }

    // ---------------------------------------------------------------- VoiceController state machine (§26/§36)
    @Test fun voice_happy_path_reaches_agent_and_speaks_result() = runTest {
        val asr = FakeAsr(Transcript(raw = "Open WhatsApp.", canonical = "Open WhatsApp.", language = Lang.GH_EN))
        val tts = FakeTts()
        val agent = FakeAgent(AgentStatus.COMPLETED, "WhatsApp is now open.")
        var lang = Lang.GH_EN
        val vc = VoiceController(
            asr = asr, tts = tts,
            sessionLanguage = { lang }, onLanguageResolved = { lang = it },
            scope = this, hasMicPermission = { true },
            agent = agent, mic = FakeRecorder(CaptureResult.Success(ByteArray(200), 500))
        )
        vc.start(); advanceUntilIdle()
        assertEquals("the SAME agent receives the canonical goal", "Open WhatsApp.", agent.submitted)
        assertEquals("the verified result is spoken", "WhatsApp is now open.", tts.spokenText)
        assertEquals(Lang.GH_EN, tts.spokenLang)
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        assertEquals("WhatsApp is now open.", vc.state.value.spoken)
    }

    @Test fun voice_unverified_completion_is_spoken_honestly() = runTest {
        val tts = FakeTts()
        val vc = VoiceController(
            asr = FakeAsr(Transcript("do it", "do it", Lang.GH_EN)), tts = tts,
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { true },
            agent = FakeAgent(AgentStatus.COMPLETED_UNVERIFIED, "I changed the setting"),
            mic = FakeRecorder(CaptureResult.Success(ByteArray(50), 300))
        )
        vc.start(); advanceUntilIdle()
        assertTrue("must not imply success it couldn't verify (§35)",
            tts.spokenText!!.contains("could not confirm"))
    }

    @Test fun voice_without_mic_permission_fails_at_mic_stage() = runTest {
        val vc = VoiceController(
            asr = FakeAsr(Transcript("x", "x", Lang.GH_EN)), tts = FakeTts(),
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { false },
            agent = FakeAgent(AgentStatus.COMPLETED, "done"),
            mic = FakeRecorder(CaptureResult.NoSpeech)
        )
        vc.start(); advanceUntilIdle()
        assertEquals(VoiceState.ERROR, vc.state.value.state)
        assertEquals(VoiceStage.MIC, vc.state.value.failedStage)
    }

    @Test fun voice_no_speech_returns_to_idle_without_calling_agent() = runTest {
        val agent = FakeAgent(AgentStatus.COMPLETED, "done")
        val vc = VoiceController(
            asr = FakeAsr(Transcript("x", "x", Lang.GH_EN)), tts = FakeTts(),
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { true },
            agent = agent, mic = FakeRecorder(CaptureResult.NoSpeech)
        )
        vc.start(); advanceUntilIdle()
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        assertEquals("no audio → agent must not be invoked", null, agent.submitted)
    }

    @Test fun a_silent_follow_up_rests_quietly_without_nagging() = runTest {
        // §7/§32 — after answering we listen once more. If the user stays silent, the follow-up ends
        // quietly (so the screen can close) — NOT with the deliberate-tap "I didn't hear anything" nag.
        val tts = FakeTts()
        val agent = FakeAgent(AgentStatus.COMPLETED, "done")
        val vc = VoiceController(
            asr = FakeAsr(Transcript("x", "x", Lang.GH_EN)), tts = tts,
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { true },
            agent = agent, mic = FakeRecorder(CaptureResult.NoSpeech)
        )
        vc.followUp(); advanceUntilIdle()
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        assertEquals("a silent follow-up must not nag", null, tts.spokenText)
        assertEquals("silence must not invoke the agent", null, agent.submitted)
    }

    @Test fun a_follow_up_with_speech_continues_the_conversation() = runTest {
        // When the user does keep talking, the follow-up behaves like a normal turn: the agent runs.
        val agent = FakeAgent(AgentStatus.COMPLETED, "Calling him now.")
        val vc = VoiceController(
            asr = FakeAsr(Transcript("now call him", "now call him", Lang.GH_EN)), tts = FakeTts(),
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { true },
            agent = agent, mic = FakeRecorder(CaptureResult.Success(ByteArray(200), 500))
        )
        vc.followUp(); advanceUntilIdle()
        assertEquals("a spoken follow-up reaches the agent", "now call him", agent.submitted)
        assertEquals(VoiceState.IDLE, vc.state.value.state)
    }

    @Test fun voice_adopts_the_lid_resolved_language_for_the_session() = runTest {
        var lang = Lang.GH_EN
        val vc = VoiceController(
            asr = FakeAsr(Transcript(raw = "Bue WhatsApp", canonical = "Bue WhatsApp", language = Lang.TWI)),
            tts = FakeTts(),
            sessionLanguage = { lang }, onLanguageResolved = { lang = it },
            scope = this, hasMicPermission = { true },
            agent = FakeAgent(AgentStatus.COMPLETED, "done"),
            mic = FakeRecorder(CaptureResult.Success(ByteArray(50), 300))
        )
        vc.start(); advanceUntilIdle()
        assertEquals("session language follows the resolved LID", Lang.TWI, lang)
    }
}
