package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VadStrategy
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.io.BargeIn
import com.tekyerema.voice.io.CapturedAudioInput
import com.tekyerema.voice.io.Heard
import com.tekyerema.voice.io.LanguageRoutedInput
import com.tekyerema.voice.io.Hypothesis
import com.tekyerema.voice.io.InputCaps
import com.tekyerema.voice.io.ListenEvents
import com.tekyerema.voice.io.ListenPurpose
import com.tekyerema.voice.io.ListenSpec
import com.tekyerema.voice.io.NBest
import com.tekyerema.voice.io.RecognitionRequest
import com.tekyerema.voice.io.RecognizerCallbacks
import com.tekyerema.voice.io.RecognizerEngine
import com.tekyerema.voice.io.RecognizerErrors
import com.tekyerema.voice.io.RecognizerHandle
import com.tekyerema.voice.io.RecorderBargeIn
import com.tekyerema.voice.io.SystemSpeechConfig
import com.tekyerema.voice.io.SystemSpeechInput
import com.tekyerema.voice.io.VoiceInput
import com.tekyerema.voice.io.VoiceLayer
import com.tekyerema.voice.io.YesNo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Workstream E — the replaceable voice boundary (voice/io): the profile switch, the system recognizer's state machine
 * behind a fake SpeechRecognizer, yes/no on n-best results, and what the controller does with each input.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceBoundaryTest {

    // ------------------------------------------------------------------ the profile switch
    @Test fun the_profile_key_defaults_to_builtin_english() {
        assertEquals(VoiceLayer.BUILTIN_EN, VoiceLayer.profileOf(null))
        assertEquals(VoiceLayer.BUILTIN_EN, VoiceLayer.profileOf("  "))
        assertEquals(VoiceLayer.BUILTIN_EN, VoiceLayer.profileOf("klingon"))
        assertEquals(VoiceLayer.GHANA, VoiceLayer.profileOf(" Ghana "))
        val b = VoiceLayer.plan(VoiceLayer.BUILTIN_EN)
        assertEquals(VoiceLayer.InputKind.SYSTEM_SPEECH, b.input)
        assertFalse(b.buildOnDeviceAsr); assertFalse(b.buildLanguageLayer)
        assertEquals("builtin_en pins Ghanaian English", Lang.GH_EN, b.pinnedLanguage)
        val g = VoiceLayer.plan(VoiceLayer.GHANA)
        assertEquals(VoiceLayer.InputKind.CAPTURED_AUDIO, g.input)
        assertTrue(g.buildOnDeviceAsr); assertTrue(g.buildLanguageLayer)
        assertNull(g.pinnedLanguage)
    }

    private class CountingFactory : VoiceLayer.Factory<String, String> {
        val calls = mutableListOf<String>()
        override fun onDeviceAsr(): String { calls += "sherpa"; return "sherpa" }
        override fun languageLayer(): String { calls += "hcilab"; return "hcilab" }
        override fun systemInput(): VoiceInput { calls += "system"; return FakeInput("system") }
        override fun capturedInput(onDevice: String, layer: String): VoiceInput { calls += "captured($onDevice,$layer)"; return FakeInput("captured") }
    }
    private class FakeInput(override val id: String) : VoiceInput {
        override val caps = InputCaps(false, false, false, emptySet(), secureListen = false, patientEndpointing = false)
        override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard = Heard.NoSpeech
    }

    @Test fun builtin_en_never_builds_whisper_tiny_or_the_hci_lab_layer() {
        val f = CountingFactory()
        val built = VoiceLayer.build(VoiceLayer.plan(VoiceLayer.BUILTIN_EN), f)
        assertEquals(listOf("system"), f.calls)
        assertEquals("system", built.input.id)
        assertNull(built.onDevice); assertNull(built.layer)
    }

    @Test fun ghana_builds_todays_stack() {
        val f = CountingFactory()
        val built = VoiceLayer.build(VoiceLayer.plan(VoiceLayer.GHANA), f)
        assertEquals(listOf("sherpa", "hcilab", "captured(sherpa,hcilab)"), f.calls)
        assertEquals("captured", built.input.id)
    }

    // ------------------------------------------------------------------ gh_mixed: Google English + HCI Lab Twi (owner, 2026-09-29)
    @Test fun gh_mixed_builds_both_recognizers_and_routes_each_listen_by_language() = kotlinx.coroutines.runBlocking {
        assertEquals(VoiceLayer.GH_MIXED, VoiceLayer.profileOf(" GH_MIXED "))
        val p = VoiceLayer.plan(VoiceLayer.GH_MIXED)
        assertEquals(VoiceLayer.InputKind.MIXED, p.input)
        assertTrue(p.buildOnDeviceAsr); assertTrue(p.buildLanguageLayer); assertNull(p.pinnedLanguage)
        val f = CountingFactory()
        val built = VoiceLayer.build(p, f)
        assertEquals(listOf("sherpa", "hcilab", "captured(sherpa,hcilab)", "system"), f.calls)
        assertTrue(built.input is LanguageRoutedInput)

        var lang = Lang.GH_EN
        val heard = mutableListOf<String>()
        class Rec(override val id: String, secure: Boolean) : VoiceInput {
            override val caps = InputCaps(false, false, false, emptySet(), secureListen = secure, patientEndpointing = secure)
            override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard { heard += "$id/${spec.language}"; return Heard.NoSpeech }
        }
        val routed = LanguageRoutedInput(english = Rec("google", secure = false), twi = Rec("hcilab", secure = true)) { lang }
        routed.listen(ListenSpec(ListenPurpose.REQUEST), object : ListenEvents {})
        assertFalse("English: Google can't hear 'done' privately", routed.caps.secureListen)
        lang = Lang.TWI
        routed.listen(ListenSpec(ListenPurpose.REQUEST), object : ListenEvents {})
        assertTrue("Twi: the captured path keeps a secure listen on the phone", routed.caps.secureListen)
        assertEquals(listOf("google/${Lang.GH_EN}", "hcilab/${Lang.TWI}"), heard)
    }

    @Test fun the_voice_language_is_switched_by_saying_so() {
        val c = { s: String -> com.tekyerema.agent.FastIntentClassifier.classify(s) }
        for (s in listOf("speak Twi", "Speak Twi.", "switch to Twi", "talk to me in Twi", "kasa Twi", "use twi please"))
            assertEquals(s, com.tekyerema.agent.FastIntent.Preference("language", "twi"), c(s))
        for (s in listOf("speak English", "switch to English", "kasa borɔfo", "kasa brofo", "English please"))
            assertEquals(s, com.tekyerema.agent.FastIntent.Preference("language", "english"), c(s))
        for (s in listOf("how do you say thank you in Twi", "what is my name in Twi", "send Kofi a message in Twi"))
            assertFalse(s, (c(s) as? com.tekyerema.agent.FastIntent.Preference)?.key == "language")
    }

    // ------------------------------------------------------------------ the system recognizer, faked
    private class FakeEngine(vararg scripts: (RecognizerCallbacks) -> Unit, var available: Boolean = true) : RecognizerEngine {
        val queue = ArrayDeque(scripts.toList())
        val requests = mutableListOf<RecognitionRequest>()
        val seen = mutableListOf<RecognizerCallbacks>()
        var created = 0; var cancels = 0; var destroyed = 0
        override val name = "fake.recognizer"
        override fun available() = available
        override fun create(): RecognizerHandle { created++; return object : RecognizerHandle {
            override fun start(request: RecognitionRequest, callbacks: RecognizerCallbacks) {
                requests += request; seen += callbacks
                (queue.removeFirstOrNull() ?: { cb: RecognizerCallbacks -> cb.onError(RecognizerErrors.SPEECH_TIMEOUT) })(callbacks)
            }
            override fun cancel() { cancels++ }
            override fun destroy() { destroyed++ }
        } }
    }
    private fun said(vararg hyps: String, conf: FloatArray? = null): (RecognizerCallbacks) -> Unit = { cb ->
        cb.onReady(); cb.onBeginningOfSpeech(); cb.onRms(6f); cb.onPartial(hyps.firstOrNull()); cb.onEndOfSpeech()
        cb.onResults(hyps.toList(), conf)
    }
    private fun error(code: Int, afterSpeech: Boolean = false): (RecognizerCallbacks) -> Unit = { cb ->
        cb.onReady(); if (afterSpeech) cb.onBeginningOfSpeech(); cb.onError(code)
    }
    private class Events : ListenEvents {
        val log = mutableListOf<String>()
        override fun onReady() { log += "ready" }
        override fun onSpeechStart() { log += "speech" }
        override fun onCaptured() { log += "captured" }
        override fun onPartial(text: String) { log += "partial" }
    }
    private fun input(engine: RecognizerEngine, cfg: SystemSpeechConfig = SystemSpeechConfig(), online: Boolean = true,
                      secure: Boolean = false, handOvers: MutableList<String> = mutableListOf()) =
        SystemSpeechInput(engine, config = { cfg }, isOnline = { online }, main = EmptyCoroutineContext,
            micHandOver = { handOvers += "handOver" }, micReclaim = { handOvers += "reclaim" },
            secureStep = { secure }, logContent = { false }, log = {})

    @Test fun a_final_result_is_normalized_by_the_language_pipeline() = runTest {
        val engine = FakeEngine(said("send five cedis to Kofi", "send 5 cedis to coffee", conf = floatArrayOf(0.9f, -1f)))
        val ev = Events()
        val hand = mutableListOf<String>()
        val h = input(engine, handOvers = hand).listen(ListenSpec(ListenPurpose.REQUEST), ev)
        h as Heard.Speech
        assertEquals("raw stays as heard", "send five cedis to Kofi", h.transcript.raw)
        assertTrue("numbers normalized: ${h.transcript.canonical}", h.transcript.canonical.contains("5 cedis"))
        assertEquals("android-speech/fake.recognizer", h.transcript.backend)
        assertEquals(listOf(Hypothesis("send five cedis to Kofi", 0.9f), Hypothesis("send 5 cedis to coffee", null)), h.alternatives)
        assertEquals("our tone first (before the recognizer starts), then speech, partial, end",
            listOf("ready", "speech", "partial", "captured"), ev.log)
        assertEquals("the wake mic is handed over before, and re-armed after", listOf("handOver", "reclaim"), hand)
        val q = engine.requests.single()
        assertEquals("en-GH", q.languageTag); assertEquals(3, q.maxResults); assertTrue(q.partials)
        assertFalse("online: no offline preference", q.preferOffline)
        assertEquals("a fresh recognizer per listen, destroyed after", 1, engine.destroyed)
    }

    @Test fun yes_no_asks_for_five_hypotheses_and_offline_prefers_the_offline_engine() = runTest {
        val engine = FakeEngine(said("yes"))
        input(engine, online = false).listen(ListenSpec(ListenPurpose.YES_NO))
        assertEquals(5, engine.requests.single().maxResults)
        assertTrue(engine.requests.single().preferOffline)
    }

    @Test fun the_secure_step_never_starts_the_recognizer() = runTest {
        val engine = FakeEngine(said("done"))
        val h1 = input(engine).listen(ListenSpec(ListenPurpose.SECURE_DONE))
        val h2 = input(engine).listen(ListenSpec(ListenPurpose.REQUEST, sensitive = true))
        val h3 = input(engine, secure = true).listen(ListenSpec(ListenPurpose.INTERJECTION))   // any listen while the step runs
        for (h in listOf(h1, h2, h3)) assertEquals(Heard.Cause.NOT_ALLOWED_HERE, (h as Heard.Unavailable).cause)
        assertEquals("no recognizer was ever created", 0, engine.created)
        assertTrue(engine.requests.isEmpty())
    }

    @Test fun error_codes_map_to_what_the_controller_hears() {
        fun m(code: Int, began: Boolean = false) = SystemSpeechInput.mapError(code, began)
        assertEquals(Heard.NoSpeech, m(RecognizerErrors.SPEECH_TIMEOUT))
        assertEquals(Heard.NoSpeech, m(RecognizerErrors.NO_MATCH))
        assertEquals("heard something it couldn't match", Heard.TooShort, m(RecognizerErrors.NO_MATCH, began = true))
        assertEquals(Heard.Cause.OFFLINE, (m(RecognizerErrors.NETWORK) as Heard.Unavailable).cause)
        assertEquals(Heard.Cause.OFFLINE, (m(RecognizerErrors.NETWORK_TIMEOUT) as Heard.Unavailable).cause)
        assertEquals(Heard.Cause.AUDIO, (m(RecognizerErrors.AUDIO) as Heard.Unavailable).cause)
        assertEquals(Heard.Cause.BUSY, (m(RecognizerErrors.RECOGNIZER_BUSY) as Heard.Unavailable).cause)
        assertEquals(Heard.Cause.PERMISSION, (m(RecognizerErrors.INSUFFICIENT_PERMISSIONS) as Heard.Unavailable).cause)
        assertEquals(Heard.Cause.OTHER, (m(RecognizerErrors.CLIENT) as Heard.Unavailable).cause)
        assertEquals(Heard.Cause.OTHER, (m(RecognizerErrors.SERVER) as Heard.Unavailable).cause)
        assertEquals(Heard.Cause.LANGUAGE, (m(RecognizerErrors.LANGUAGE_NOT_SUPPORTED) as Heard.Unavailable).cause)
        assertEquals(Heard.Cause.NO_RECOGNIZER, (m(RecognizerErrors.CREATE_FAILED) as Heard.Unavailable).cause)
    }

    @Test fun client_or_busy_retries_once_on_a_fresh_recognizer() = runTest {
        val engine = FakeEngine(error(RecognizerErrors.RECOGNIZER_BUSY), said("open whatsapp"))
        val h = input(engine).listen(ListenSpec(ListenPurpose.REQUEST))
        assertEquals("open whatsapp", (h as Heard.Speech).transcript.raw)
        assertEquals(2, engine.created)
        val twice = FakeEngine(error(RecognizerErrors.CLIENT), error(RecognizerErrors.CLIENT), said("never reached"))
        assertEquals(Heard.Cause.OTHER, (input(twice).listen(ListenSpec(ListenPurpose.REQUEST)) as Heard.Unavailable).cause)
        assertEquals("one retry only", 2, twice.requests.size)
    }

    @Test fun a_network_error_retries_offline_once_but_never_after_the_user_spoke() = runTest {
        val engine = FakeEngine(error(RecognizerErrors.NETWORK), said("what time is it"))
        val h = input(engine).listen(ListenSpec(ListenPurpose.REQUEST))
        assertTrue(h is Heard.Speech)
        assertEquals(listOf(false, true), engine.requests.map { it.preferOffline })
        // After speech a retry would silently drop the user's words: report it instead.
        val spoke = FakeEngine(error(RecognizerErrors.NETWORK, afterSpeech = true), said("x"))
        assertEquals(Heard.Cause.OFFLINE, (input(spoke).listen(ListenSpec(ListenPurpose.REQUEST)) as Heard.Unavailable).cause)
        assertEquals(1, spoke.requests.size)
    }

    @Test fun a_refused_language_falls_back_to_en_us_for_good() = runTest {
        val engine = FakeEngine(error(RecognizerErrors.LANGUAGE_NOT_SUPPORTED), said("hello"), said("again"))
        val inp = input(engine)
        assertTrue(inp.listen(ListenSpec(ListenPurpose.REQUEST)) is Heard.Speech)
        inp.listen(ListenSpec(ListenPurpose.REQUEST))
        assertEquals(listOf("en-GH", "en-US", "en-US"), engine.requests.map { it.languageTag })
    }

    @Test fun patient_listening_listens_once_more_on_silence() = runTest {
        val engine = FakeEngine(error(RecognizerErrors.SPEECH_TIMEOUT), said("call my sister"))
        val h = input(engine).listen(ListenSpec(ListenPurpose.PATIENT))
        assertEquals("call my sister", (h as Heard.Speech).transcript.raw)
        val quick = FakeEngine(error(RecognizerErrors.SPEECH_TIMEOUT), said("x"))
        assertEquals(Heard.NoSpeech, input(quick).listen(ListenSpec(ListenPurpose.REQUEST)))
    }

    @Test fun callbacks_from_an_old_session_or_after_the_end_are_dropped() = runTest {
        val engine = FakeEngine(
            { cb -> cb.onReady(); cb.onError(RecognizerErrors.NO_MATCH); cb.onResults(listOf("late result"), null) },   // after terminal
            { cb -> cb.onReady() }   // the second session waits…
        )
        val inp = input(engine)
        assertEquals(Heard.NoSpeech, inp.listen(ListenSpec(ListenPurpose.REQUEST)))
        var second: Heard? = null
        val job = launch { second = inp.listen(ListenSpec(ListenPurpose.REQUEST)) }
        advanceUntilIdle()
        engine.seen[0].onResults(listOf("stale from session one"), null)   // a relic of the first session
        advanceUntilIdle()
        assertNull("the relic did not answer the new session", second)
        engine.seen[1].onResults(listOf("fresh"), null)
        advanceUntilIdle()
        assertEquals("fresh", (second as Heard.Speech).transcript.raw)
        job.join()
    }

    @Test fun cancelling_the_turn_cancels_the_recognizer() = runTest {
        val engine = FakeEngine({ cb -> cb.onReady(); cb.onBeginningOfSpeech() })   // never finishes
        val hand = mutableListOf<String>()
        val job = launch { input(engine, handOvers = hand).listen(ListenSpec(ListenPurpose.REQUEST)) }
        advanceUntilIdle()
        job.cancel(); advanceUntilIdle()
        assertTrue(engine.cancels >= 1)
        assertEquals(1, engine.destroyed)
        assertEquals("the wake word gets its mic back", listOf("handOver", "reclaim"), hand)
    }

    @Test fun no_recognition_service_is_said_plainly() = runTest {
        val engine = FakeEngine(available = false)
        assertEquals(Heard.Cause.NO_RECOGNIZER, (input(engine).listen(ListenSpec(ListenPurpose.REQUEST)) as Heard.Unavailable).cause)
    }

    @Test fun own_cue_none_leaves_the_tone_to_the_recognizer() = runTest {
        val engine = FakeEngine(said("hi"))
        val inp = input(engine, cfg = SystemSpeechConfig(ownCue = "none"))
        assertTrue(inp.caps.playsOwnCue)
        val ev = Events()
        inp.listen(ListenSpec(ListenPurpose.REQUEST), ev)
        assertEquals("ready fires at onReadyForSpeech instead", "ready", ev.log.first())
    }

    // ------------------------------------------------------------------ n-best
    @Test fun n_best_prefers_an_alternative_that_names_a_contact() {
        val vocab = EntityCorrector.Vocab.of(listOf("Kofi Mensah"), listOf("WhatsApp"))
        fun h(t: String, c: Float?) = Hypothesis(t, c)
        assertEquals(1, NBest.pick(listOf(h("call coffee", 0.8f), h("call kofi", 0.7f)), vocab))
        assertEquals("too unsure next to the top", 0, NBest.pick(listOf(h("call coffee", 0.8f), h("call kofi", 0.3f)), vocab))
        assertEquals("unknown confidence is no objection", 1, NBest.pick(listOf(h("call coffee", null), h("call kofi", null)), vocab))
        assertEquals("a different verb is a different request", 0, NBest.pick(listOf(h("call coffee", 0.8f), h("text kofi", 0.8f)), vocab))
        assertEquals("the top already names someone", 0, NBest.pick(listOf(h("open whatsapp", 0.8f), h("call kofi", 0.8f)), vocab))
        assertEquals(0, NBest.pick(listOf(h("call coffee", 0.8f), h("call kofi", 0.7f)), EntityCorrector.Vocab.EMPTY))
    }

    @Test fun yes_no_counts_only_the_top_answer_and_a_disagreeing_alternative_makes_it_unclear() {
        fun alts(vararg t: String) = t.map { Hypothesis(it, null) }
        assertEquals(Confirmation.Answer.YES, YesNo.decide("yes", alts("yes", "yeah", "yes please")))
        assertEquals(Confirmation.Answer.UNCLEAR, YesNo.decide("yes", alts("yes", "no")))
        assertEquals(Confirmation.Answer.UNCLEAR, YesNo.decide("no", alts("no", "yes")))
        assertEquals("an alternative never turns 'unclear' into a yes", Confirmation.Answer.UNCLEAR, YesNo.decide("hmm", alts("hmm", "yes")))
        assertEquals(Confirmation.Answer.NO, YesNo.decide("no", emptyList()))
        assertEquals(Confirmation.Answer.HOLD, YesNo.decide("wait", alts("wait", "yes")))
    }

    // ------------------------------------------------------------------ the captured adapter
    private class TagRecorder(val tag: String, val log: MutableList<String>) : Recorder {
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            log += "$tag:default"; onListening(); return CaptureResult.Success(ByteArray(10), 100)
        }
        override suspend fun captureWith(strategy: VadStrategy, onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            log += "$tag:${strategy.name}"; onListening(); return CaptureResult.Success(ByteArray(10), 100)
        }
    }
    private class SeqAsr(vararg texts: String) : AsrProvider {
        private val queue = ArrayDeque(texts.toList())
        override val name = "fake"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            val t = queue.removeFirstOrNull() ?: throw com.tekyerema.voice.AsrException("nothing")
            return Transcript(raw = t, canonical = t, language = Lang.GH_EN)
        }
    }

    @Test fun the_captured_path_uses_the_same_mic_and_strategy_per_purpose_as_before() = runTest {
        val log = mutableListOf<String>()
        val inp = CapturedAudioInput(TagRecorder("mic", log), TagRecorder("answer", log), SeqAsr(*Array(8) { "x" }), { Lang.GH_EN }, { null })
        for (p in ListenPurpose.values()) inp.listen(ListenSpec(p))
        inp.listen(ListenSpec(ListenPurpose.YES_NO, patient = true))
        assertEquals(listOf("mic:default", "mic:default", "mic:continue", "mic:patient", "answer:patient", "answer:default",
            "answer:default", "answer:quick", "answer:patient"), log)
        assertTrue(inp.caps.secureListen && inp.caps.transcribesAudio)
        assertEquals("an ASR failure is reported, not thrown", Heard.Cause.OTHER, (inp.transcribe(ByteArray(4)) as Heard.Unavailable).cause)
    }

    @Test fun barge_in_without_audio_releases_the_mic_at_the_onset() = runTest {
        var released = false
        val mic = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                onSpeechStart()
                try { kotlinx.coroutines.awaitCancellation() } finally { released = true }
            }
        }
        val gate = CompletableDeferred<Boolean>()
        val r: BargeIn = RecorderBargeIn(mic).speakWatching({ gate.await() }, onOnset = { gate.complete(false) }, keepAudio = false)
        assertTrue(r.interrupted); assertNull(r.wav); assertTrue("released before returning", released)
    }

    // ------------------------------------------------------------------ the controller with the system input
    private class ScriptedInput(vararg heard: Heard, private val secure: Boolean = false, private val audio: Boolean = false) : VoiceInput {
        val queue = ArrayDeque(heard.toList())
        val specs = mutableListOf<ListenSpec>()
        override val id = "scripted"
        override val caps = InputCaps(true, true, transcribesAudio = audio, languages = setOf(Lang.GH_EN), secureListen = secure, patientEndpointing = false)
        override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard {
            specs += spec; events.onReady()
            val h = queue.removeFirstOrNull() ?: Heard.NoSpeech
            if (h is Heard.Speech) { events.onSpeechStart(); events.onCaptured() }
            return h
        }
    }
    private fun speech(text: String, vararg alts: String) =
        Heard.Speech(Transcript(raw = text, canonical = text, language = Lang.GH_EN), (listOf(text) + alts).map { Hypothesis(it, null) })
    private class LogTts : TtsProvider {
        override val name = "fake"; val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: String): Boolean { spoken += text; return true }
        override fun stop() {}; override fun shutdown() {}
    }
    private class GatedAgent : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val answers = mutableListOf<Boolean>()
        override fun submitGoal(goal: String) {
            _s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, goal = goal, pendingConfirmation = "Call Prince?")
        }
        override fun confirmFor(seq: Long, approved: Boolean) {
            answers += approved
            _s.value = if (approved) AgentUiState(status = AgentStatus.COMPLETED, goal = _s.value.goal,
                log = listOf(LogEntry(0, LogEntry.Kind.RESULT, "Calling Prince.")))
            else AgentUiState(status = AgentStatus.STOPPED, goal = _s.value.goal)
        }
        override fun stop() {}
    }
    private fun controller(scope: kotlinx.coroutines.CoroutineScope, agent: AgentBridge, tts: TtsProvider, input: VoiceInput) = VoiceController(
        asr = SeqAsr(), tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = scope,
        hasMicPermission = { true }, agent = agent, input = input)

    @Test fun a_yes_the_recognizer_also_heard_as_no_is_asked_again_then_declined() = runTest {
        val agent = GatedAgent(); val tts = LogTts()
        val inp = ScriptedInput(speech("call Prince"), speech("yes", "no"), speech("yes", "no"))
        val vc = controller(this, agent, tts, inp)
        vc.start(); advanceUntilIdle()
        assertEquals("never approved on a disputed yes", listOf(false), agent.answers)
        assertTrue(tts.spoken.contains(Confirmation.REPEAT))
        assertTrue(tts.spoken.contains(Confirmation.DECLINED_UNCLEAR))
        assertEquals(listOf(ListenPurpose.REQUEST, ListenPurpose.YES_NO, ListenPurpose.YES_NO), inp.specs.map { it.purpose })
        vc.shutdown()
    }

    @Test fun a_clear_yes_on_the_system_recognizer_approves() = runTest {
        val agent = GatedAgent(); val tts = LogTts()
        val vc = controller(this, agent, tts, ScriptedInput(speech("call Prince"), speech("yes", "yeah")))
        vc.start(); advanceUntilIdle()
        assertEquals(listOf(true), agent.answers)
        assertEquals("Calling Prince.", tts.spoken.last())
        vc.shutdown()
    }

    @Test fun when_the_recognizer_cant_run_the_confirmation_waits_for_the_buttons() = runTest {
        val agent = GatedAgent(); val tts = LogTts()
        val vc = controller(this, agent, tts, ScriptedInput(speech("call Prince"), Heard.Unavailable(Heard.Cause.OFFLINE, "offline")))
        vc.start(); advanceUntilIdle()
        assertTrue("nothing approved or declined by voice", agent.answers.isEmpty())
        assertTrue(tts.spoken.any { it.contains("Tap Yes or No") })
        vc.shutdown()
    }

    private class SecureAgent : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        var done = 0
        override fun submitGoal(goal: String) { _s.value = AgentUiState(status = AgentStatus.SECURE_INPUT, goal = goal) }
        override fun secureInputDone() { done++ }
        override fun stop() {}
    }

    @Test fun at_the_secure_step_the_system_recognizer_is_never_asked_to_listen() = runTest {
        val agent = SecureAgent(); val tts = LogTts()
        val inp = ScriptedInput(speech("send 1 cedi to Kofi"), speech("done"))
        val vc = controller(this, agent, tts, inp)
        vc.start(); advanceUntilIdle()
        assertEquals("only the request was listened for", listOf(ListenPurpose.REQUEST), inp.specs.map { it.purpose })
        assertEquals(0, agent.done)
        assertTrue("tells the user to tap Done", tts.spoken.any { it.contains("secure step", true) && it.contains("tap Done") })
        assertFalse(tts.spoken.any { it.contains("Say 'done'") })
        vc.shutdown()
    }

    @Test fun an_unfinished_answer_gets_one_more_listen_when_the_recognizer_cant_wait() = runTest {
        val answers = mutableListOf<String?>()
        val agent = object : AgentBridge {
            private val _s = MutableStateFlow(AgentUiState())
            override val state: StateFlow<AgentUiState> = _s
            override fun submitGoal(goal: String) { _s.value = AgentUiState(status = AgentStatus.ASKING, goal = goal, pendingQuestion = "Which one?") }
            override fun answer(text: String?) { answers += text; _s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = _s.value.goal) }
            override fun stop() {}
        }
        val inp = ScriptedInput(speech("open the portal"), speech("the one for my"), speech("course materials"))
        val vc = controller(this, agent, LogTts(), inp)
        vc.start(); advanceUntilIdle()
        assertEquals(listOf<String?>("the one for my course materials"), answers)
        assertEquals(listOf(ListenPurpose.REQUEST, ListenPurpose.ANSWER, ListenPurpose.CONTINUATION), inp.specs.map { it.purpose })
        vc.shutdown()
    }

    @Test fun the_recognizer_being_offline_is_said_honestly() = runTest {
        val tts = LogTts()
        val agent = GatedAgent()
        val vc = controller(this, agent, tts, ScriptedInput(Heard.Unavailable(Heard.Cause.OFFLINE, "offline")))
        vc.start(); advanceUntilIdle()
        assertTrue(tts.spoken.any { it.contains("Voice needs the internet") })
        vc.shutdown()
    }

    @Test fun barge_in_on_the_system_recognizer_stops_the_reply_and_listens_afresh() = runTest {
        val tts = object : TtsProvider {
            override val name = "fake"; val spoken = mutableListOf<String>()
            private var gate: CompletableDeferred<Boolean>? = null
            override suspend fun speak(text: String, language: String): Boolean {
                spoken += text
                if (text.length < 60) return true
                return CompletableDeferred<Boolean>().also { gate = it }.await()
            }
            override fun stop() { gate?.complete(false) }
            override fun shutdown() {}
        }
        val submitted = mutableListOf<String>()
        val agent = object : AgentBridge {
            private val _s = MutableStateFlow(AgentUiState())
            override val state: StateFlow<AgentUiState> = _s
            override fun submitGoal(goal: String) {
                submitted += goal
                val reply = if (submitted.size == 1) "You're in Messages. Kofi says the meeting moved to Thursday afternoon, and Ama says see you at six."
                    else "It's ten o'clock."
                _s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = goal, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, reply)))
            }
            override fun stop() {}
        }
        var bargeCaptures = 0
        val barge = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                bargeCaptures++; onSpeechStart(); kotlinx.coroutines.awaitCancellation()
            }
        }
        val inp = ScriptedInput(speech("what's the time"))
        val vc = VoiceController(asr = SeqAsr(), tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = this,
            hasMicPermission = { true }, agent = agent, input = inp, bargeMic = barge, bargeInEnabled = { true })
        vc.submitText("read the screen"); advanceUntilIdle()
        assertEquals(1, bargeCaptures)
        assertEquals("the onset stopped the reply; a fresh listen heard the new request", listOf("read the screen", "what's the time"), submitted)
        assertEquals(listOf(ListenPurpose.REQUEST), inp.specs.map { it.purpose })
        assertEquals("It's ten o'clock.", tts.spoken.last())
        vc.shutdown()
    }
}
