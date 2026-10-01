package com.tekyerema

import com.tekyerema.camera.CameraSpeech
import com.tekyerema.camera.CediClassifier
import com.tekyerema.language.AudioSink
import com.tekyerema.language.FailureKind
import com.tekyerema.language.HttpReply
import com.tekyerema.language.HttpSpeechSpec
import com.tekyerema.language.HttpTtsProvider
import com.tekyerema.language.ProviderGuard
import com.tekyerema.language.ProviderHealth
import com.tekyerema.language.SpeechTransport
import com.tekyerema.language.unavailableReason
import com.tekyerema.voice.AsrException
import com.tekyerema.voice.AsrLowMemoryException
import com.tekyerema.voice.AsrMemory
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.FallbackTts
import com.tekyerema.voice.HybridAsr
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.RoutingTts
import com.tekyerema.voice.SpeechToRaw
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.TwiVoiceStatus
import com.tekyerema.voice.VoiceAvailability
import com.tekyerema.voice.io.CapturedAudioInput
import com.tekyerema.voice.io.Heard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Black-box audit 2026-10-01, fixes 8a / 8b / 10a:
 *  - a Twi reply is never silent and the user is told once when it is read by the English voice (L16/L17);
 *  - the offline recogniser is refused, not loaded, when the phone has no room for it (V00/V01);
 *  - the money reader says it isn't installed (CAM04).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAuditFixesTest {

    // ------------------------------------------------------------------ 8a: the Twi voice

    private class Voice(override val name: String, var accept: Set<String>, var why: String? = null) : TtsProvider, VoiceAvailability {
        val said = ArrayList<Pair<String, String>>()
        override suspend fun speak(text: String, language: String): Boolean { said.add(text to language); return language in accept }
        override fun stop() {}
        override fun shutdown() {}
        override fun whyUnavailable(): String? = why
    }

    @Test fun a_twi_reply_is_never_silent_and_the_user_is_told_once_per_session() = runTest {
        var now = 1_000L
        val status = TwiVoiceStatus(clock = { now }, sessionGapMs = { 30 * 60_000L })
        val phone = Voice("system", setOf(Lang.GH_EN))
        val lab = Voice("http:lab", emptySet(), why = "key rejected")
        val routing = RoutingTts(english = phone, twi = lab, twiStatus = status)

        assertTrue("the reply is spoken by the English voice", routing.speak("Me ho yɛ.", Lang.TWI))
        assertEquals(listOf(TwiVoiceStatus.NOTICE, "Me ho yɛ."), phone.said.map { it.first })
        assertTrue("both in the English voice", phone.said.all { it.second == Lang.GH_EN })
        assertEquals("twi voice: unavailable (key rejected)", status.status())
        assertTrue(status.problem()!!.contains("key rejected"))

        now += 60_000L
        routing.speak("Yoo.", Lang.TWI)
        assertEquals("no second notice in the same session", listOf(TwiVoiceStatus.NOTICE, "Me ho yɛ.", "Yoo."), phone.said.map { it.first })

        now += 31 * 60_000L          // a new session: half an hour with no Twi reply
        routing.speak("Maakye.", Lang.TWI)
        assertEquals(TwiVoiceStatus.NOTICE, phone.said[3].first)
        assertEquals("Maakye.", phone.said[4].first)
    }

    @Test fun the_notice_is_due_again_after_the_twi_voice_has_worked_in_between() = runTest {
        val status = TwiVoiceStatus(clock = { 0L }, sessionGapMs = { Long.MAX_VALUE })
        val phone = Voice("system", setOf(Lang.GH_EN))
        val lab = Voice("http:lab", emptySet(), why = "service not reachable")
        val routing = RoutingTts(english = phone, twi = lab, twiStatus = status)
        routing.speak("Yoo.", Lang.TWI)
        assertEquals(2, phone.said.size)
        lab.accept = setOf(Lang.TWI); lab.why = null          // the voice is back
        assertTrue(routing.speak("Yoo.", Lang.TWI))
        assertEquals("spoken by the Twi voice, nothing more from the phone", 2, phone.said.size)
        assertEquals("twi voice: ok", status.status())
        assertNull(status.problem())
        lab.accept = emptySet(); lab.why = "quota used up"    // and gone again → the user is told again
        routing.speak("Yoo.", Lang.TWI)
        assertEquals(TwiVoiceStatus.NOTICE, phone.said[2].first)
        assertEquals("twi voice: unavailable (quota used up)", status.status())
    }

    @Test fun english_replies_never_trigger_the_twi_notice() = runTest {
        val status = TwiVoiceStatus(clock = { 0L })
        val phone = Voice("system", setOf(Lang.GH_EN))
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), twiStatus = status)
        assertTrue(routing.speak("The time is 18:43.", Lang.GH_EN))
        assertEquals(listOf("The time is 18:43."), phone.said.map { it.first })
        assertEquals("twi voice: not used yet", status.status())
    }

    @Test fun a_phone_with_no_twi_voice_at_all_says_so_plainly() {
        val status = TwiVoiceStatus(clock = { 0L })
        assertTrue(status.onUnavailable(null))
        assertFalse(status.onUnavailable(null))
        assertEquals("twi voice: unavailable (${TwiVoiceStatus.NO_VOICE})", status.status())
    }

    @Test fun the_reason_a_network_voice_is_out_is_put_in_plain_words() {
        assertNull(unavailableReason(cloudAllowed = true, resting = false, lastFailure = null, quotaSpent = false))
        assertEquals("key rejected", unavailableReason(true, true, FailureKind.AUTH, false))
        assertEquals("quota used up", unavailableReason(true, true, FailureKind.QUOTA, false))
        assertEquals("service not reachable", unavailableReason(true, true, FailureKind.TRANSIENT, false))
        assertEquals("service error", unavailableReason(true, true, FailureKind.BAD_RESPONSE, false))
        assertEquals("quota used up", unavailableReason(true, false, null, true))
        assertEquals("the cloud is switched off", unavailableReason(false, true, FailureKind.AUTH, true))
        // a past failure that is no longer resting the provider is not a reason
        assertNull(unavailableReason(true, false, FailureKind.AUTH, false))
    }

    private class FakeTransport(var reply: (Request) -> HttpReply) : SpeechTransport {
        val requests = ArrayList<Request>()
        override suspend fun send(request: Request, connectMs: Long, callMs: Long): HttpReply { requests.add(request); return reply(request) }
    }

    private class FakeSink : AudioSink {
        override suspend fun play(audio: ByteArray): Boolean = true
        override fun stop() {}
    }

    @Test fun a_rejected_key_costs_one_call_then_the_lab_voice_rests_and_the_reply_is_still_heard() = runTest {
        val secrets = mapOf("SPEECH_LAB_URL" to "https://lab.example.test/v1/tts", "SPEECH_LAB_KEY" to "k-123")
        val spec = HttpSpeechSpec.parse("lab", JSONObject("""
            {"kind":"tts","enabled":true,"endpoint":"${'$'}{SPEECH_LAB_URL}",
             "auth":{"header":"x-api-key","key":"${'$'}{SPEECH_LAB_KEY}"},
             "languages":{"twi":"tw"}}""".trimIndent()))!!
        val transport = FakeTransport { HttpReply(401, "{}".toByteArray(), "application/json", null) }
        val lab = HttpTtsProvider(spec, { secrets }, transport, ProviderGuard(ProviderHealth("lab-audit"), 30_000L), FakeSink())
        assertNull("usable until it has been tried", lab.whyUnavailable())

        val phone = Voice("system", setOf(Lang.GH_EN))
        val systemAsTwi = Voice("system", emptySet(), why = null)          // the phone's engine has no Twi voice
        val twiChain = FallbackTts(listOf(lab, systemAsTwi))
        val status = TwiVoiceStatus(clock = { 0L }, sessionGapMs = { Long.MAX_VALUE })
        val routing = RoutingTts(english = phone, twi = twiChain, twiStatus = status)

        assertTrue(routing.speak("Me din de Tɛkyerɛma.", Lang.TWI))
        assertEquals(1, transport.requests.size)
        assertEquals("key rejected", twiChain.whyUnavailable())
        assertEquals("twi voice: unavailable (key rejected)", status.status())
        assertEquals(listOf(TwiVoiceStatus.NOTICE, "Me din de Tɛkyerɛma."), phone.said.map { it.first })

        assertTrue(routing.speak("Yoo.", Lang.TWI))
        assertEquals("no second failed round-trip while the key is known to be rejected", 1, transport.requests.size)
        assertEquals("Yoo.", phone.said.last().first)
    }

    // ------------------------------------------------------------------ 8b: room for the offline recogniser

    private val mb = AsrMemory.MB
    /** The bundled offline English model: 652 MB encoder + decoder + joiner. */
    private val parakeet = 661L * mb

    @Test fun the_a20e_case_is_refused_not_loaded() {
        // 2026-10-01: ~800 MB available, the 660 MB model read from the APK → the app was killed. Now: release, re-check, refuse.
        val first = AsrMemory.decide(availBytes = 800L * mb, lowMemory = false, modelBytes = parakeet, alreadyReleased = false)
        assertEquals(AsrMemory.Verdict.RELEASE_THEN_LOAD, first.verdict)
        val second = AsrMemory.decide(availBytes = 820L * mb, lowMemory = false, modelBytes = parakeet, alreadyReleased = true)
        assertEquals(AsrMemory.Verdict.REFUSE, second.verdict)
        assertEquals("needs about twice the model plus a reserve", (661 * 2.2).toLong() + 100, second.neededBytes / mb)
    }

    @Test fun with_room_to_spare_the_model_loads_and_stays() {
        val d = AsrMemory.decide(1_700L * mb, lowMemory = false, modelBytes = parakeet, alreadyReleased = false)
        assertEquals(AsrMemory.Verdict.LOAD, d.verdict)
        assertFalse(d.unloadAfterUse)
        // releasing made the difference
        assertEquals(AsrMemory.Verdict.LOAD, AsrMemory.decide(1_600L * mb, false, parakeet, alreadyReleased = true).verdict)
    }

    @Test fun the_systems_own_low_memory_flag_overrides_the_numbers() {
        assertEquals(AsrMemory.Verdict.RELEASE_THEN_LOAD, AsrMemory.decide(3_000L * mb, lowMemory = true, modelBytes = parakeet, alreadyReleased = false).verdict)
        assertEquals(AsrMemory.Verdict.REFUSE, AsrMemory.decide(3_000L * mb, lowMemory = true, modelBytes = parakeet, alreadyReleased = true).verdict)
    }

    @Test fun a_small_model_that_only_just_fits_is_unloaded_after_use() {
        val files = AsrMemory.Limits(peakFactor = AsrMemory.PEAK_FROM_FILES)
        val tiny = 128L * mb
        assertEquals(128L * 3 / 2 + 100, AsrMemory.needed(tiny, files) / mb)
        val tight = AsrMemory.decide(350L * mb, false, tiny, alreadyReleased = false, limits = files)
        assertEquals(AsrMemory.Verdict.LOAD, tight.verdict)
        assertTrue("222 MB would be left with it resident — let it go after the utterance", tight.unloadAfterUse)
        val roomy = AsrMemory.decide(600L * mb, false, tiny, alreadyReleased = false, limits = files)
        assertEquals(AsrMemory.Verdict.LOAD, roomy.verdict)
        assertFalse(roomy.unloadAfterUse)
        assertEquals(AsrMemory.Verdict.REFUSE, AsrMemory.decide(200L * mb, false, tiny, alreadyReleased = true, limits = files).verdict)
    }

    @Test fun unknown_sizes_never_block_a_load() {
        assertEquals(AsrMemory.Verdict.LOAD, AsrMemory.decide(10L * mb, true, modelBytes = 0L, alreadyReleased = false).verdict)
        val unreadable = AsrMemory.decide(Long.MAX_VALUE, false, parakeet, alreadyReleased = false)   // MemoryInfo couldn't be read
        assertEquals(AsrMemory.Verdict.LOAD, unreadable.verdict)
        assertFalse(unreadable.unloadAfterUse)
    }

    @Test fun release_hooks_run_and_a_failing_one_stops_nothing() {
        val ownerA = Any(); val ownerB = Any()
        var freed = 0
        AsrMemory.onRelease(ownerA) { throw IllegalStateException("boom") }
        AsrMemory.onRelease(ownerB) { freed++ }
        AsrMemory.release()
        assertEquals(1, freed)
        AsrMemory.onRelease(ownerB) { freed += 10 }          // re-registering replaces the owner's hook
        AsrMemory.release()
        assertEquals(11, freed)
        assertTrue("owners are held weakly — keep them alive to the end of the test", ownerA !== ownerB)
    }

    @Test fun the_refusal_is_a_typed_failure_with_the_sentence_for_the_user() {
        val e = AsrLowMemoryException(availMb = 800, neededMb = 1554)
        assertTrue(e is AsrException)
        assertEquals(AsrMemory.SPOKEN, e.message)
        assertTrue(AsrMemory.SPOKEN.contains("memory is too low"))
        assertTrue(AsrMemory.SPOKEN.contains("online"))
    }

    private class NoRoom : SpeechToRaw {
        override val name = "sherpa-test"
        override val ready = true
        var calls = 0
        override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? { calls++; throw AsrLowMemoryException(800, 1554) }
    }

    @Test fun offline_the_hybrid_recogniser_passes_the_refusal_on_unchanged() = runTest {
        val hybrid = HybridAsr(NoRoom(), onlineFor = { null })
        try {
            hybrid.transcribe(ByteArray(64), Lang.GH_EN, null)
            fail("a refused load must not look like a transcript")
        } catch (e: AsrLowMemoryException) {
            assertEquals(AsrMemory.SPOKEN, e.message)
        }
    }

    @Test fun the_voice_boundary_reports_low_memory_as_its_own_cause_with_the_spoken_line() = runTest {
        val silent = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult = CaptureResult.NoSpeech
        }
        val refusing = object : AsrProvider {
            override val name = "hybrid(test)"
            override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript =
                throw AsrLowMemoryException(800, 1554)
        }
        val input = CapturedAudioInput(silent, silent, refusing, { Lang.GH_EN }, { null })
        val heard = input.transcribe(ByteArray(64)) as Heard.Unavailable
        assertEquals(Heard.Cause.LOW_MEMORY, heard.cause)
        assertEquals(AsrMemory.SPOKEN, heard.detail)
        // any other recogniser failure keeps its old cause
        val failing = object : AsrProvider {
            override val name = "hybrid(test)"
            override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript =
                throw AsrException("nothing")
        }
        val other = CapturedAudioInput(silent, silent, failing, { Lang.GH_EN }, { null }).transcribe(ByteArray(64)) as Heard.Unavailable
        assertEquals(Heard.Cause.OTHER, other.cause)
    }

    // ------------------------------------------------------------------ 10a: the camera says what it can't do

    @Test fun the_missing_money_reader_has_one_plain_sentence() {
        assertEquals("The money reader isn't installed on this phone yet.", CediClassifier.NOT_INSTALLED)
    }

    @Test fun a_line_handed_to_another_voice_gets_time_to_be_heard_before_the_screen_closes() {
        assertEquals("a short line still needs the voice to start", 1_500L, CameraSpeech.hearingMs("Okay."))
        assertEquals(CediClassifier.NOT_INSTALLED.length * 70L, CameraSpeech.hearingMs(CediClassifier.NOT_INSTALLED))
        assertEquals("never held open for long", 8_000L, CameraSpeech.hearingMs("x".repeat(1_000)))
    }
}
