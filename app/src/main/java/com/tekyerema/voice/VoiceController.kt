package com.tekyerema.voice

import android.util.Log
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Thin seam over the agent so the voice state machine can be unit-tested with a fake. */
interface AgentBridge {
    val state: StateFlow<AgentUiState>
    fun submitGoal(goal: String)
    fun stop()
    /** Answer a pending Tier-2 confirmation. Only ever called with an explicit, fresh user answer. */
    fun confirm(approved: Boolean) {}
    /** J-02: answer confirmation number [seq] (the one that was spoken) — a yes to an earlier one is refused. */
    fun confirmFor(seq: Long, approved: Boolean) = confirm(approved)
    /** A spoken reply to a pending confirmation that is NOT yes/no — a correction or a different
     *  request. The agent re-plans against the pending proposal instead of running it (§8/§9/§13/§14). */
    fun revise(text: String) {}
    /** The user finished entering their MoMo PIN and said "done" — leave Secure Input Mode and resume. */
    fun secureInputDone() {}
    /** N-04: warm the cloud connection while the user speaks. */
    fun prewarm() {}
    // C-12 / C-13 / C-19 task control while a task runs.
    val busy: Boolean get() = false
    fun pause() {}
    fun resume() {}
    fun steer(text: String) {}
    fun answer(text: String?) {}
    fun statusLine(): String = ""
}

/** Production bridge → the real [AgentEngine] singleton. */
object AgentEngineBridge : AgentBridge {
    override val state: StateFlow<AgentUiState> get() = AgentEngine.state
    override fun submitGoal(goal: String) = AgentEngine.submitGoal(goal)
    override fun stop() = AgentEngine.stop()
    override fun confirm(approved: Boolean) = AgentEngine.confirm(approved)
    override fun confirmFor(seq: Long, approved: Boolean) = AgentEngine.confirm(approved, seq)
    override fun revise(text: String) = AgentEngine.revise(text)
    override fun secureInputDone() = AgentEngine.secureInputDone()
    override fun prewarm() = AgentEngine.prewarm()
    override val busy: Boolean get() = AgentEngine.isBusy
    override fun pause() = AgentEngine.pause()
    override fun resume() = AgentEngine.resume()
    override fun steer(text: String) = AgentEngine.steer(text)
    override fun answer(text: String?) = AgentEngine.answer(text)
    override fun statusLine(): String = AgentEngine.statusLine()
}

/**
 * The Phase-2 voice lifecycle (§26). Orchestrates: mic → VAD (in [MicCapture]) → ASR+LID+normalize
 * ([AsrProvider]) → the SAME general [AgentEngine] (both languages, one agent) → verify (the agent's
 * own loop) → verbalize → TTS. Safety is unchanged (§35): the canonical goal goes through the exact
 * policy gate; the voice layer NEVER auto-confirms — a Tier-2 gate surfaces as CONFIRMATION_REQUIRED
 * and the user approves through the existing overlay. TTS never implies success unless the agent
 * actually verified it.
 */
class VoiceController(
    private val asr: AsrProvider,
    private val tts: TtsProvider,
    private val sessionLanguage: () -> String,
    private val onLanguageResolved: (String) -> Unit,
    private val scope: CoroutineScope,
    private val hasMicPermission: () -> Boolean,
    private val agent: AgentBridge = AgentEngineBridge,
    private val mic: Recorder = MicCapture(),
    private val agentTimeoutMs: Long = 180_000,
    /** Sound/haptic state cues (no-op by default so tests stay silent). */
    private val cues: VoiceCues = object : VoiceCues {},
    /** User's explicit language choice: a fixed language skips LID; null follows the speaker. */
    private val forceLanguage: () -> String? = { null },
    /** "Read responses aloud" — off means the UI/TalkBack carries the reply instead of our TTS. */
    private val speakAloud: () -> Boolean = { true },
    /** Microphone used for yes/no answers (a shorter silence timeout than a full request). */
    private val answerMic: Recorder = mic,
    /** Confirm by voice (ask aloud, listen for yes/no). Off = buttons only. */
    private val voiceConfirmation: () -> Boolean = { true },
    /** Show/hide a visible "Tɛkyerɛma is asking…" card while a follow-up question is waiting for a spoken
     *  answer (wired to OverlayConfirm by VoiceEngine; no-op in tests). */
    private val showAsking: (String) -> Unit = {},
    private val hideAsking: () -> Unit = {},
    /** Instant offline "thinking" filler backchannel played while the brain/ASR runs (null = disabled, e.g. tests). */
    private val fillers: FillerPlayer? = null,
    /** LOCAL FAST PATH: deterministic device ops (time, battery, volume, open app, back…) handled BEFORE the
     *  agent/LLM. Null in tests → the agent path runs unchanged. */
    private val fastRouter: com.tekyerema.agent.LocalFastRouter? = null,
    /** Offline system TTS, used ONLY to voice fast-path ACTION confirmations ("volume up") instantly so a slow
     *  online Twi voice can never delay them. Null → action confirmations use the normal [tts] chain. */
    private val systemVoice: TtsProvider? = null,
    /** B-07: echo-cancelled mic that listens while a reply plays (null = no barge-in, e.g. tests). */
    private val bargeMic: Recorder? = null,
    private val bargeInEnabled: () -> Boolean = { false },
    /** B-07: put the reply and the barge-in mic on the voice-communication path for one reply ([DuplexAudio]); true when
     *  entered. [duplexEnd] always follows. No-ops in tests. */
    private val duplexBegin: () -> Boolean = { false },
    private val duplexEnd: () -> Unit = {},
    /** B-13: "brief" | "normal" | "detailed". */
    private val verbosity: () -> String = { "normal" },
    /** N-16: a one-time spoken health notice (screen control off, background restricted), or null. */
    private val healthNotice: () -> String? = { null },
    /** voice/io boundary: listen once → normalized text (the phone's recognizer under `builtin_en`). Null = the
     *  captured-audio path over [mic] / [answerMic] / [asr] — today's stack, unchanged (profile `ghana`, and tests). */
    input: com.tekyerema.voice.io.VoiceInput? = null,
    /** First-run voice welcome (voice/Welcome.kt): what its answers change. Null = no welcome (tests, other hosts). */
    private val welcome: WelcomeHost? = null,
    /** "You ask this every Friday — shall I remind you?" (agent/habits/RepeatDetector.kt). Null = off (tests). */
    private val habits: com.tekyerema.agent.habits.HabitHost? = null
) {
    /** Every listen goes through here; see [com.tekyerema.voice.io.VoiceInput]. */
    private val voiceIn: com.tekyerema.voice.io.VoiceInput =
        input ?: com.tekyerema.voice.io.CapturedAudioInput(mic, answerMic, asr, sessionLanguage, forceLanguage)
    /** B-07 onset detection on the echo-cancelled mic (both profiles). */
    private val barge: com.tekyerema.voice.io.BargeInDetector? = bargeMic?.let { com.tekyerema.voice.io.RecorderBargeIn(it) }

    /** The listening tone — unless the recognizer plays its own (`systemSpeech.ownCue = none`). */
    private fun listenCue() { if (!voiceIn.caps.playsOwnCue) cues.listening() }

    private fun events(
        ready: () -> Unit = {}, speech: () -> Unit = {}, captured: () -> Unit = {}, partial: (String) -> Unit = {}
    ) = object : com.tekyerema.voice.io.ListenEvents {
        override fun onReady() = ready()
        override fun onSpeechStart() = speech()
        override fun onCaptured() = captured()
        override fun onPartial(text: String) = partial(text)
    }

    private suspend fun listen(purpose: com.tekyerema.voice.io.ListenPurpose, events: com.tekyerema.voice.io.ListenEvents, patient: Boolean? = null) =
        voiceIn.listen(com.tekyerema.voice.io.ListenSpec(purpose).let { if (patient != null) it.copy(patient = patient) else it }, events)

    private val _state = MutableStateFlow(VoiceUiState(language = sessionLanguage()))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var confirmJob: Job? = null
    /** Runs while the agent is in SECURE_INPUT (MoMo PIN): speaks the secure instruction and listens for
     *  "done". Never touches or forwards the PIN. */
    private var secureJob: Job? = null
    /** C-13: speaks an ask_user question and listens for the answer. */
    private var askJob: Job? = null
    /** C-12: a turn taken WHILE a task runs (stop / pause / continue / status / a correction). */
    private var interjectJob: Job? = null
    /** Set when the stop was already acknowledged aloud (a spoken "no", a "stop" interjection), so the turn's closing
     *  "Stopped." isn't said twice. Reset for every new task: it used to linger and silence a later, real "Stopped.". */
    @Volatile private var stopAlreadySaid = false
    /** A read-back or a question is being READ right now (the microphone opens only after it) — [answerNow] cuts it. */
    @Volatile private var promptSpeaking = false
    val readingPrompt: Boolean get() = promptSpeaking
    /** Consecutive auto follow-up listens in the current exchange, capped so mic noise can't loop forever.
     *  Reset whenever the user deliberately starts a turn (mic tap / typed). */
    private var followUpChain = 0
    private val maxFollowUps = 10
    /** Consecutive tap-to-talk turns that heard nothing — the "I didn't hear anything" line escalates, then goes quiet. */
    private var noSpeechRun = 0

    /** A voice turn, or a listen for a pending prompt's reply (both hold the microphone). */
    val isRunning: Boolean get() = job?.isActive == true || confirmJob?.isActive == true ||
        askJob?.isActive == true || secureJob?.isActive == true

    /** The task is waiting on the user (a confirmation, a question, the secure step) and nothing is listening for the
     *  reply — "Hey Tɛkyerɛma", the panel's Answer button or opening Tɛkyerɛma should reopen the mic for it ([respond]). */
    val canRespond: Boolean get() = agent.state.value.status in PROMPTS &&
        askJob?.isActive != true && confirmJob?.isActive != true && secureJob?.isActive != true

    /** C-12: a task is working and nothing is using the microphone — the wake word may listen and interject now. */
    val canInterject: Boolean get() = agent.busy && _state.value.state == VoiceState.EXECUTING &&
        interjectJob?.isActive != true && askJob?.isActive != true && confirmJob?.isActive != true && secureJob?.isActive != true

    /** Only our own reply (or the idle tail of a finished turn) is in the way: opening Tɛkyerɛma or tapping the circle
     *  stops it and listens ([start] / [interrupt]) instead of doing nothing until the reading ends. */
    val canPreempt: Boolean get() = route() == NewRequest.Route.PREEMPT && _state.value.state in NewRequest.TALKING_OR_RESTING

    private fun route(): NewRequest.Route =
        NewRequest.route(agent.state.value.status, agent.busy, job?.isActive == true, _state.value.state)

    /**
     * Start a voice turn once the previous one has fully let go. Cancelling a job is cooperative: the old capture used
     * to read one more frame on the SAME recorder and VAD while the new one opened (two AudioRecords, one Silero state),
     * and its "Cancelled." landed on top of the new turn's "Listening". A turn that is still talking is cut here.
     */
    private fun launchTurn(block: suspend () -> Unit) {
        val previous = job?.takeIf { !it.isCompleted }
        if (previous != null) { previous.cancel(); tts.stop(); fillers?.stop() }
        job = scope.launch {
            previous?.join()
            block()
        }
    }

    // The confirmation observer outlives any single turn, so it is not a child of the caller's scope
    // (a test scope would otherwise wait on it forever); shutdown() cancels it.
    private val observerScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    init {
        // Confirmation by voice runs for EVERY pending confirmation — whether the goal came from a
        // voice turn, typed text or ADB — and stops the moment the agent moves on (e.g. a button).
        observerScope.launch {
            var lastPrompt: String? = null
            var lastQuestion: String? = null   // asked once per question; after that the user reopens it (respond)
            agent.state.collect { st ->
                when (st.status) {
                    AgentStatus.WAITING_CONFIRMATION -> {
                        if (secureJob?.isActive == true) secureJob?.cancel()
                        // J-02: no prompt = the answer was already given (the engine is acting on it) — never ask a
                        // "Should I go ahead?" about nothing, whose yes would be queued for the next confirmation.
                        val prompt = st.pendingConfirmation
                        if (prompt != null && confirmJob?.isActive != true && prompt != lastPrompt) {
                            lastPrompt = prompt
                            val twi = st.pendingConfirmationTwi          // §A.1 structured Twi variant, or null
                            val seq = st.confirmationSeq                 // the yes names the question it answers
                            confirmJob = launchOnAnswerMic { confirmByVoice(prompt, twi, seq) }
                        }
                    }
                    // Secure Input Mode: the agent went blind for the MoMo PIN. Speak the secure instruction
                    // and listen for "done" — never for the PIN. Runs whatever app is in front (like confirm).
                    AgentStatus.SECURE_INPUT -> {
                        lastPrompt = null
                        if (confirmJob?.isActive == true) confirmJob?.cancel()
                        if (secureJob?.isActive != true) secureJob = launchOnAnswerMic { secureInputByVoice() }
                    }
                    // C-13: the task asked a question — say it, listen for the answer, hand it back to the run.
                    AgentStatus.ASKING -> {
                        val q = st.pendingQuestion
                        if (q != null && q != lastQuestion && askJob?.isActive != true) {
                            lastQuestion = q
                            askJob = launchOnAnswerMic { askByVoice(q, said = st.pendingQuestionTwi ?: awaitTwiWording(q)) }
                        }
                    }
                    else -> {
                        lastPrompt = null
                        lastQuestion = null
                        if (confirmJob?.isActive == true) confirmJob?.cancel()
                        if (secureJob?.isActive == true) secureJob?.cancel()
                        if (askJob?.isActive == true) askJob?.cancel()   // the prompt panel follows the agent's state
                        if (_state.value.confirmPhase != ConfirmPhase.NONE)
                            _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }
                        // A question or secure step answered while no voice turn is waiting on the task (a trigger, or
                        // the panel's buttons) left the screen showing "Asking for your answer" for the rest of the task
                        // — and the wake word waiting for an idle that never came.
                        if (_state.value.state == VoiceState.CONFIRMATION_REQUIRED && job?.isActive != true) idle("")
                    }
                }
            }
        }
    }

    /** Mic button: start a fresh utterance, or cancel the running one (§25). */
    fun toggle() { if (isRunning) cancel() else start() }

    /**
     * C-12 / C-19 — the user speaks WHILE a task runs (wake word, voice button): "stop", "pause", "continue",
     * "what are you doing?", or a correction ("make it 50", "send it to Kofi instead") that the running task folds
     * in. A tap on the circle still cancels outright (the kill switch), so this never replaces stopping.
     */
    fun interject() {
        if (!agent.busy) { start(); return }
        if (interjectJob?.isActive == true || askJob?.isActive == true || confirmJob?.isActive == true || secureJob?.isActive == true) return
        interjectionHeard = false
        interjectJob = observerScope.launch {
            try {
                // A "One moment please" or a check-in spoken now would be captured as the user's words.
                fillers?.stop()
                val lang = forceLanguage() ?: sessionLanguage()
                if (!hasMicPermission()) return@launch
                val heard = listen(com.tekyerema.voice.io.ListenPurpose.INTERJECTION, events(ready = { listenCue() },
                    speech = { _state.update { it.copy(speechDetected = true) } },
                    captured = { interjectionHeard = true; cues.captured() }))
                val text = (heard as? com.tekyerema.voice.io.Heard.Speech)?.transcript?.canonical.orEmpty()
                if (text.isBlank()) return@launch
                val line = sideAnswer(text, lang) ?: applyInterjection(text, lang == Lang.TWI)
                try { tts.speak(Verbalize.apply(line), lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
            } finally {
                // speechDetected used to stay true for the rest of the task after an interjection (the voice screen then
                // refused to close: "mid-sentence").
                _state.update { it.copy(speechDetected = false) }
            }
        }
    }

    /** The interjection listening now has already heard the user (it is transcribing or answering): let it finish. */
    @Volatile private var interjectionHeard = false

    /**
     * The answer microphone has one user at a time. A prompt (confirmation, question, secure step) takes it from an
     * interjection that is still only listening, and waits until that capture — or a prompt being cancelled — has let go
     * of the recorder: they used to open two AudioRecords on the same Silero state, and the interjection could take the
     * confirmation's question (our own voice) as the user's words. An interjection that already HEARD the user finishes
     * first, so what they said isn't lost (it becomes a revision of the proposal, see [applyInterjection]).
     */
    private fun launchOnAnswerMic(block: suspend () -> Unit): Job {
        val interjection = interjectJob?.takeIf { !it.isCompleted }
        if (interjection != null && !interjectionHeard) interjection.cancel()
        val leaving = listOfNotNull(interjection) + listOfNotNull(confirmJob, askJob, secureJob).filter { it.isCancelled && !it.isCompleted }
        return observerScope.launch {
            leaving.forEach { it.join() }
            block()
        }
    }

    /**
     * A question the phone answers by itself — the time, the battery, a sum — asked WHILE a task runs: is it one? Pure.
     * It used to be folded into the running task as a "correction", which then searched the web for the time and lost
     * its own thread (A20e audit 2026-10-01, I03). Only look-ups: nothing that presses a key or changes a setting.
     */
    internal fun isSideQuestion(text: String): Boolean {
        if (ControlVerbs.classify(text) != ControlVerbs.Verb.STEER) return false
        val intent = try { com.tekyerema.agent.FastIntentClassifier.classify(text, routines = false) } catch (_: Throwable) { null }
        return when (intent) {
            com.tekyerema.agent.FastIntent.Time, com.tekyerema.agent.FastIntent.Date, com.tekyerema.agent.FastIntent.Day,
            com.tekyerema.agent.FastIntent.Battery, com.tekyerema.agent.FastIntent.Storage, com.tekyerema.agent.FastIntent.DeviceInfo,
            com.tekyerema.agent.FastIntent.VolumeQuery, com.tekyerema.agent.FastIntent.FlashlightQuery,
            com.tekyerema.agent.FastIntent.WifiQuery, com.tekyerema.agent.FastIntent.BluetoothQuery -> true
            is com.tekyerema.agent.FastIntent.Calculate, is com.tekyerema.agent.FastIntent.DateAnswer,
            is com.tekyerema.agent.FastIntent.Convert, is com.tekyerema.agent.FastIntent.Spell,
            is com.tekyerema.agent.FastIntent.WorldTime -> true
            else -> false
        }
    }

    /** The answer to a side question, said without touching the running task; null = not one (handle it as before). */
    private suspend fun sideAnswer(text: String, language: String): String? {
        if (agent.state.value.status == AgentStatus.WAITING_CONFIRMATION || !isSideQuestion(text)) return null
        return try { fastRouter?.tryHandle(text, language)?.spoken?.takeIf { it.isNotBlank() } }
            catch (c: CancellationException) { throw c } catch (_: Throwable) { null }
    }

    /** C-12: act on words said (or typed) while a task runs, and return the line that tells the user what happened. */
    private fun applyInterjection(text: String, twi: Boolean): String = when (ControlVerbs.classify(text)) {
        // The turn's own "Stopped." would follow this one — say it once.
        ControlVerbs.Verb.STOP -> { stopAlreadySaid = true; agent.stop(); if (twi) "Magyae." else "Stopped." }
        ControlVerbs.Verb.PAUSE -> { agent.pause(); if (twi) "Matwɛn. Ka kɔ so na mɛtoa so." else "Paused. Say continue when you're ready." }
        ControlVerbs.Verb.RESUME -> { agent.resume(); if (twi) "Yoo, mɛtoa so." else "Carrying on." }
        ControlVerbs.Verb.STATUS -> agent.statusLine()
        // A correction that lands while the task waits for an OK is a revision of THAT proposal (re-planned and confirmed
        // again), never a steer queued behind it — a "yes" to the old proposal would otherwise run first.
        ControlVerbs.Verb.STEER -> if (agent.state.value.status == AgentStatus.WAITING_CONFIRMATION) {
            agent.revise(text); if (twi) "Yoo, ma mensesa." else "Okay, let me change that."
        } else { agent.steer(text); if (twi) "Yoo, mɛsesa." else "Got it, I'll change that." }
    }

    /**
     * C-13: speak the task's question and listen (patiently) for the answer. Two silences no longer end the task
     * (owner, 2026-09-28: "when it needs feedback, sometimes the thing doesn't appear for you to talk back") — the
     * question stays open on the prompt panel until the engine's own timeout, and "Hey Tɛkyerɛma", the panel's
     * Answer button or opening Tɛkyerɛma listens again ([respond]).
     */
    /** @param said the question as SPOKEN in a Twi conversation (the Twi line, amounts / names marked for the English
     *  voice) — the A20e read the English question with the Twi voice (3 October 2026). [question] stays the English. */
    private suspend fun askByVoice(question: String, skipSpeech: Boolean = false, said: String? = null) {
        val lang = forceLanguage() ?: sessionLanguage()
        try {
            fillers?.stop()
            if (!skipSpeech && speakAloud()) {
                promptSpeaking = true
                try { tts.speak(said?.takeIf { lang == Lang.TWI && it.isNotBlank() } ?: Verbalize.apply(question), lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                finally { promptSpeaking = false }
            }
            if (!hasMicPermission()) return   // typed answers still work (submitText)
            repeat(2) { attempt ->
                _state.update { it.copy(state = VoiceState.CONFIRMATION_REQUIRED, partialHint = question) }
                val onSpeech = { _state.update { it.copy(speechDetected = true) } }
                // A question that wants a phone number or an amount is answered in digits — heard by the recogniser that
                // is good at them (voice/io LanguageRoutedInput; owner's Twi test, 2 October 2026).
                val digits = DIGITS_ASKED.containsMatchIn(question)
                val heard = voiceIn.listen(com.tekyerema.voice.io.ListenSpec(com.tekyerema.voice.io.ListenPurpose.ANSWER, digits = digits),
                    events(ready = { listenCue() }, speech = onSpeech, captured = { cues.captured() }))
                if (heard is com.tekyerema.voice.io.Heard.Cancelled) return
                var text = (heard as? com.tekyerema.voice.io.Heard.Speech)?.transcript?.canonical.orEmpty()
                // voice/io: a recognizer that ends the turn on its own short pause can't be patient — an answer cut off
                // mid-phrase ("the one for my…") gets one more listen, joined on (B-02), instead of half an answer.
                if (text.isNotBlank() && !voiceIn.caps.patientEndpointing && TurnDetector.looksUnfinished(text)) {
                    val more = listen(com.tekyerema.voice.io.ListenPurpose.CONTINUATION, events(speech = onSpeech, captured = { cues.captured() }))
                    (more as? com.tekyerema.voice.io.Heard.Speech)?.transcript?.canonical?.takeIf { it.isNotBlank() }
                        ?.let { text = text.trimEnd(',', ' ') + " " + it }
                }
                if (text.isNotBlank()) { agent.answer(englishReply(text)); return }
                if (attempt == 0 && speakAloud()) try { tts.speak(if (lang == Lang.TWI) "Mante. San ka bio." else "I didn't catch that. Please say it again.", lang) }
                    catch (c: CancellationException) { throw c } catch (_: Exception) {}
            }
            if (speakAloud()) try { tts.speak(if (lang == Lang.TWI) "Mɛtwɛn. Sɛ woawie a, ka 'Hey Tɛkyerɛma'."
                else "I'll wait. Say \"Hey Tɛkyerɛma\" or tap Answer when you're ready.", lang) }
                catch (c: CancellationException) { throw c } catch (_: Exception) {}
        } finally {
            _state.update { it.copy(speechDetected = false) }
        }
    }

    /** Reopen the microphone for the prompt the task is waiting on (the panel's Answer, "Hey Tɛkyerɛma", opening
     *  Tɛkyerɛma). A confirmation still needs an explicit yes — silence or an unclear answer declines, as before. */
    fun respond() {
        if (answerNow()) return
        val st = agent.state.value
        when (st.status) {
            AgentStatus.ASKING -> st.pendingQuestion?.let { q -> if (askJob?.isActive != true) askJob = launchOnAnswerMic { askByVoice(q, said = st.pendingQuestionTwi) } }
            AgentStatus.WAITING_CONFIRMATION -> if (confirmJob?.isActive != true) st.pendingConfirmation?.let { p ->
                confirmJob = launchOnAnswerMic { confirmByVoice(p, st.pendingConfirmationTwi, st.confirmationSeq) } }
            AgentStatus.SECURE_INPUT -> if (secureJob?.isActive != true) secureJob = launchOnAnswerMic { secureInputByVoice() }
            else -> {}
        }
    }

    /**
     * The user wants to answer the waiting prompt NOW: the read-back or the question is cut short and the microphone
     * opens for the reply at once — volume-down twice, the panel's Answer, "Hey Tɛkyerɛma" or opening Tɛkyerɛma while it
     * is still being read. Owner, 2 October 2026: "you've seen the options and want to answer immediately but you
     * can't" (a menu of seven options was being read and nothing could stop it short of cancelling the task). Nothing
     * is answered FOR the user: a confirmation still needs its yes. False = nothing was being read.
     */
    fun answerNow(): Boolean {
        if (!promptSpeaking) return false
        val st = agent.state.value
        return when (st.status) {
            AgentStatus.ASKING -> {
                val q = st.pendingQuestion ?: return false
                Log.i("Tekyerema", "[VOICE] the question was cut short — listening for the answer")
                askJob?.cancel(); tts.stop()
                askJob = launchOnAnswerMic { askByVoice(q, skipSpeech = true, said = st.pendingQuestionTwi) }
                true
            }
            AgentStatus.WAITING_CONFIRMATION -> {
                val p = st.pendingConfirmation ?: return false
                Log.i("Tekyerema", "[VOICE] the read-back was cut short — listening for yes or no")
                confirmJob?.cancel(); tts.stop()
                val twi = st.pendingConfirmationTwi
                val seq = st.confirmationSeq
                confirmJob = launchOnAnswerMic { confirmByVoice(p, twi, seq, skipSpeech = true) }
                true
            }
            else -> false
        }
    }

    /** The prompt panel's Type box was opened: stop reading and close the answer microphone (our own voice, the
     *  keyboard's clicks or TalkBack must never be taken as the reply). The prompt stays waiting — for what is typed. */
    fun quietForTyping() {
        if (agent.state.value.status !in PROMPTS) return
        tts.stop()
        askJob?.cancel(); confirmJob?.cancel()
        _state.update { it.copy(confirmPhase = ConfirmPhase.NONE, speechDetected = false) }
    }

    /**
     * The user asked to talk (mic button, opening Tɛkyerɛma, a trigger). Routed by [NewRequest]: the answer to a waiting
     * prompt, an interjection while a task works, or a fresh turn — cutting our own reply short if it is still being read
     * (it used to do nothing until a 30-second reading ended). Already listening, or working out what was just said: the
     * request is already being heard, so nothing more is started.
     */
    fun start() {
        // Addressedness: a turn opened by a wake that only just cleared the bar is judged like a follow-up (voice/Addressedness.kt).
        val origin = WakeSignal.consume()
        when (route()) {
            NewRequest.Route.ANSWER -> respond()
            NewRequest.Route.INTERJECT -> interject()
            NewRequest.Route.START -> { followUpChain = 0; launchTurn { runOnce(origin = origin) } }
            NewRequest.Route.PREEMPT -> if (_state.value.state in NewRequest.TALKING_OR_RESTING) {
                Log.i("Tekyerema", "[VOICE] new request while ${_state.value.state} — stopping the reply to listen")
                followUpChain = 0
                launchTurn { runOnce(origin = origin) }
            }
            NewRequest.Route.WAIT -> Log.i("Tekyerema", "[VOICE] start ignored: the turn is acting on what was just said")
        }
    }

    /**
     * The circle tapped while Tɛkyerɛma is talking: stop talking and listen (C-12 v1.1 — stopping speech and cancelling a
     * task are separate). Silence then just rests, like a follow-up, so a tap meant as "be quiet" costs nothing. While a
     * task is working the tap stays the kill switch ([cancel]).
     */
    fun interrupt() {
        if (!canPreempt) { cancel(); return }
        Log.i("Tekyerema", "[VOICE] reply interrupted by a tap — listening")
        followUpChain = 0
        launchTurn { runOnce(quiet = true, origin = Addressedness.Origin.USER) }   // a tap: the user's own turn, never gated
    }

    /**
     * A conversational follow-up: after Tɛkyerɛma has answered, listen once more so the user can
     * simply keep talking ("okay, now call him", "what about tomorrow?") instead of re-triggering —
     * the cross-turn memory in the agent carries the context. Unlike [start], silence here is NOT an
     * error: if the user says nothing we rest quietly so the screen can close, with no "I didn't hear
     * anything" nag. This is what makes a sequence of turns feel like one conversation (§32), not a
     * string of isolated commands.
     */
    /** Volume-up twice: the last reply again, exactly (the hardware twin of "say that again"). A running turn or
     *  task is never talked over; with nothing said yet it says so briefly. */
    fun repeatLast() {
        if (isRunning || agent.busy || SecureStep.active() || !speakAloud()) return
        val line = _state.value.spoken.ifBlank { "I haven't said anything yet." }
        observerScope.launch {
            try { tts.speak(Verbalize.apply(line), forceLanguage() ?: sessionLanguage()) }
            catch (c: CancellationException) { throw c } catch (_: Exception) {}
        }
    }

    fun followUp() {
        // Never over a running task or a waiting prompt: a new goal would be refused by the busy engine and this turn
        // would then wait on it for the whole task budget.
        if (isRunning || agent.busy || agent.state.value.status in PROMPTS) return
        // Right after a Mobile Money hand-off the user is typing the PIN (TalkBack speaks the keys): don't listen.
        if (SecureStep.active()) return
        launchTurn { runOnce(quiet = true) }
    }

    // ------------------------------------------------------------------ first-run welcome, practice, habit offers
    // The logic is in voice/Welcome.kt and agent/habits/RepeatDetector.kt (pure, unit-tested); these are the voice hooks.

    /** The say/listen the welcome, the practice and the habit offer talk through — the same voice every turn uses. */
    private inner class TurnIo(private val host: WelcomeHost?) : Welcome.Io {
        /** The last thing heard, so a request said instead of an answer runs exactly as heard. */
        var last: Transcript? = null
        // Read like every reply: "Tɛkyerɛma", "MoMo", "9 AM" said as words.
        override suspend fun say(text: String) = this@VoiceController.say(Verbalize.apply(text))
        override suspend fun ask(question: String, yesNo: Boolean, patient: Boolean): Welcome.Reply {
            say(question)
            if (!hasMicPermission()) return Welcome.Reply.Silence
            val heard = listen(if (yesNo) com.tekyerema.voice.io.ListenPurpose.YES_NO else com.tekyerema.voice.io.ListenPurpose.ANSWER,
                events(ready = { listenCue(); set(VoiceState.LISTENING, question) },
                    speech = { _state.update { it.copy(speechDetected = true) } },
                    captured = { cues.captured(); set(VoiceState.TRANSCRIBING, "Transcribing…") }),
                patient = if (patient) true else null)
            return when (heard) {
                is com.tekyerema.voice.io.Heard.Speech -> {
                    last = heard.transcript
                    Welcome.Reply.Text(heard.transcript.canonical.ifBlank { heard.transcript.raw }, heard.transcript.raw,
                        heard.alternatives.map { it.text })
                }
                com.tekyerema.voice.io.Heard.Cancelled -> Welcome.Reply.Cancelled
                else -> Welcome.Reply.Silence
            }
        }
        override fun needsOk() = cues.needsOk()
        override fun apply(effect: Welcome.Effect): String? = host?.apply(effect)
    }

    /**
     * First-run voice welcome — started by Home the first time it opens after the permissions page, or by Settings ›
     * Replay the welcome. Never over a task, a prompt or another turn, and only when it can speak and listen. True when
     * it started (the caller then marks it done, so it never repeats).
     */
    fun startWelcome(): Boolean {
        val host = welcome ?: return false
        if (isRunning || agent.busy || agent.state.value.status in PROMPTS || !speakAloud() || !hasMicPermission()) return false
        followUpChain = 0
        launchTurn { runWelcome(host) }
        return true
    }

    private suspend fun runWelcome(host: WelcomeHost) {
        val io = TurnIo(host)
        try {
            val end = Welcome.run(io, host.twiVoice)
            Log.i("Tekyerema", "[WELCOME] ended: ${end::class.simpleName}")
            // A request said instead of an answer ends the welcome and runs like any other turn (never a gate).
            if (end is Welcome.Outcome.Command) { io.last?.let { runTurn(it, InputSource.ASR); return } }
            idle("")
        } catch (c: CancellationException) {
            cancelledToIdle()
        } catch (e: Exception) {
            Log.w("Tekyerema", "[WELCOME] error: ${e.message}")
            idle("")
        }
    }

    /** "Send a practice message" ([Practice]) — a rehearsal only; the agent and every tool are never involved. */
    private suspend fun runPractice() {
        if (!speakAloud()) {
            // The rehearsal is spoken: say on screen how to hear it rather than rehearse in silence.
            _state.update { it.copy(state = VoiceState.IDLE, spoken = "Practice is spoken. Turn on Read replies aloud in Settings › Voice to try it.",
                spokenAt = System.currentTimeMillis(), partialHint = "") }
            return
        }
        Log.i("Tekyerema", "[PRACTICE] rehearsal — nothing is sent")
        Practice.run(TurnIo(welcome))
        idle("")
    }

    /**
     * Habits: note a request that finished well ([habits] keeps only read/info kinds), and right after one the user makes
     * at the same time every week (or day) ask ONCE whether to set a reminder. A yes only saves a reminder that SPEAKS —
     * the request is never performed for them. Asked by voice only after a spoken request, with nothing else waiting.
     */
    private suspend fun offerHabit(tr: Transcript, source: InputSource, fastIntent: String?) {
        val h = habits ?: return
        val canAsk = source == InputSource.ASR && speakAloud() && hasMicPermission() && !agent.busy &&
            agent.state.value.status !in PROMPTS
        val offer = try { h.completed(tr.canonical, fastIntent, System.currentTimeMillis(), canAsk) }
            catch (e: Exception) { Log.w("Tekyerema", "[HABIT] ${e.message}"); null } ?: return
        val io = TurnIo(null)
        val reply = io.ask(offer.question, yesNo = true) as? Welcome.Reply.Text
        val answer = when (reply?.let { com.tekyerema.voice.io.YesNo.decide(it.text, it.alternatives.map { a -> com.tekyerema.voice.io.Hypothesis(a, null) }) }) {
            Confirmation.Answer.YES -> com.tekyerema.agent.habits.RepeatDetector.Answer.YES
            Confirmation.Answer.NO -> com.tekyerema.agent.habits.RepeatDetector.Answer.NO
            else -> com.tekyerema.agent.habits.RepeatDetector.Answer.NONE
        }
        val line = try { h.answered(offer, answer) } catch (e: Exception) { "" }
        // A request said instead of yes or no runs like any other; silence just rests (no nag).
        if (reply != null && answer == com.tekyerema.agent.habits.RepeatDetector.Answer.NONE && Welcome.looksLikeCommand(reply.text)) {
            io.last?.let { runTurn(it, InputSource.ASR); return }
        }
        if (reply != null && line.isNotBlank()) io.say(line)
        idle("")
    }

    /** What happened to a typed request — [accepted] clears the field; [notice] is shown (and announced) to the user. */
    data class TypedResult(val route: NewRequest.Route?, val accepted: Boolean, val notice: String = "")

    /**
     * Typed input. The text enters the agent through [runTurn] — the SAME function the ASR
     * transcript goes through — as if ASR had produced exactly this text. It never touches ASR,
     * LID or normalization, which is the point: a typed instruction is the experimental control
     * for the voice pipeline. Returns false only for blank text: a request typed while Tɛkyerɛma is busy is never
     * dropped any more (it was, silently, while a reply was being read) — see [submitTyped].
     */
    fun submitText(text: String): Boolean = submitTyped(text).accepted

    /** Typed input, routed like any new request ([NewRequest]); see [submitText]. */
    fun submitTyped(text: String): TypedResult = submitTyped(text, allowWait = true)

    private fun submitTyped(text: String, allowWait: Boolean): TypedResult {
        val t = text.trim()
        if (t.isEmpty()) return TypedResult(null, accepted = false)
        val language = forceLanguage() ?: sessionLanguage()
        val route = route().let { if (it == NewRequest.Route.WAIT && !allowWait) NewRequest.Route.PREEMPT else it }
        return when (route) {
            NewRequest.Route.ANSWER -> answerTyped(t)
            NewRequest.Route.INTERJECT -> {
                // Typed while a task works: the same verbs as a spoken interjection — "stop", "pause", "what are you
                // doing?", or a correction the running task folds in (C-19). A voice interjection still listening is superseded.
                interjectJob?.cancel()
                cues.captured()
                // "what time is it" typed while a task works is answered on the spot; the task carries on untouched.
                if (agent.state.value.status != AgentStatus.WAITING_CONFIRMATION && isSideQuestion(t)) {
                    interjectionHeard = true
                    interjectJob = observerScope.launch {
                        val answer = sideAnswer(t, language) ?: return@launch
                        Log.i("Tekyerema", "[VOICE] side question answered while the task runs (${answer.length} chars)")
                        if (speakAloud()) try { tts.speak(Verbalize.apply(answer), language) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                    }
                    return TypedResult(route, accepted = true, notice = "One moment…")
                }
                val line = applyInterjection(t, language == Lang.TWI)
                interjectionHeard = true   // a prompt arriving now lets this line finish before it asks
                if (speakAloud()) interjectJob = observerScope.launch {
                    try { tts.speak(Verbalize.apply(line), language) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                }
                TypedResult(route, accepted = true, notice = line)
            }
            NewRequest.Route.WAIT -> {
                // The turn may be changing a setting this very instant (the fast path): let it finish, then route again —
                // it will be speaking (preempted) or handing over to the agent (an interjection) by then.
                scope.launch {
                    var waited = 0L
                    while (route() == NewRequest.Route.WAIT && waited < WAIT_MAX_MS) { kotlinx.coroutines.delay(100); waited += 100 }
                    submitTyped(t, allowWait = false)
                }
                TypedResult(route, accepted = true, notice = "One moment…")
            }
            NewRequest.Route.START, NewRequest.Route.PREEMPT -> {
                if (route == NewRequest.Route.PREEMPT) {
                    Log.i("Tekyerema", "[VOICE] typed request while ${_state.value.state} — stopping that to handle it")
                    cues.captured()   // heard: the reply stops because of what you typed, not by accident
                }
                followUpChain = 0
                launchTurn { runTurn(Transcript(raw = t, canonical = t, language = language, reason = "typed"), InputSource.TYPED) }
                TypedResult(route, accepted = true)
            }
        }
    }

    /**
     * Typed text while the task waits on the user. A question takes it as the answer (C-13). A confirmation reads it
     * exactly as a spoken reply ([confirmByVoice]): a correction re-plans, a clear yes/no answers — typing "yes" is as
     * explicit and fresh as tapping Yes; anything else leaves the question waiting (never a yes). A typed YES must be
     * short ("yes", "go ahead"): a new request typed without noticing the question ("ok call mum") contains an
     * affirmative word and must never approve the action that is waiting. The secure step only listens for "done": any
     * other text is dropped unread and unlogged — it may be the PIN typed into the wrong box.
     */
    private fun answerTyped(t: String): TypedResult {
        val route = NewRequest.Route.ANSWER
        val seq = agent.state.value.confirmationSeq   // J-02: a typed yes/no answers the confirmation on screen NOW
        return when (agent.state.value.status) {
            AgentStatus.ASKING -> { askJob?.cancel(); hideAsking(); cues.captured(); agent.answer(englishReply(t)); TypedResult(route, accepted = true) }
            AgentStatus.WAITING_CONFIRMATION -> when {
                Confirmation.looksLikeRevision(englishReply(t)) -> { confirmJob?.cancel(); cues.captured(); agent.revise(englishReply(t)); TypedResult(route, accepted = true) }
                else -> when (Confirmation.interpret(englishReply(t))) {
                    Confirmation.Answer.YES -> if (t.split(Regex("\\s+")).size > 2)
                        TypedResult(route, accepted = false, notice = "Something is waiting for your OK. Type yes or no — or use the buttons")
                    else { confirmJob?.cancel(); cues.captured(); agent.confirmFor(seq, true); TypedResult(route, accepted = true) }
                    Confirmation.Answer.NO -> { confirmJob?.cancel(); cues.captured(); agent.confirmFor(seq, false); TypedResult(route, accepted = true) }
                    // No speech here: the voice confirmation may be listening, and our own words must never be its answer.
                    else -> TypedResult(route, accepted = false, notice = "Type yes or no — or use the buttons")
                }
            }
            AgentStatus.SECURE_INPUT ->
                if (Confirmation.isCompletion(t)) { secureJob?.cancel(); cues.captured(); agent.secureInputDone(); TypedResult(route, accepted = true) }
                // Trust (2026-09-29): the helper is never brought in at the PIN.
                else if (com.tekyerema.agent.helper.HelperCommands.involvesHelper(t)) TypedResult(route, accepted = true, notice = com.tekyerema.agent.helper.HelperGate.PIN_LINE)
                else TypedResult(route, accepted = true, notice = "Finish the secure step on the phone, then type done")
            else -> TypedResult(route, accepted = false)
        }
    }

    /**
     * The keyboard opened on the command field. If the microphone is listening, close it so it
     * never competes with typing; a turn already past the microphone (thinking, acting, speaking)
     * is left alone. Does not end the assistant session.
     */
    fun beginTyping() {
        if (_state.value.state == VoiceState.LISTENING) job?.cancel()
        if (job?.isActive != true && confirmJob?.isActive != true) set(VoiceState.TYPING)
    }

    /** The command field lost focus without a submission. */
    fun endTyping() {
        if (_state.value.state == VoiceState.TYPING) idle("")
    }

    /** Cancel safely at any point; if the agent is mid-task, stop it too. No consequential action leaks. */
    fun cancel() {
        hideAsking()
        job?.cancel()
        confirmJob?.cancel()
        secureJob?.cancel()
        askJob?.cancel()          // a question the task was waiting on must not keep listening after "stop"
        interjectJob?.cancel()
        // J-14 kill switch: EVERY in-flight state stops the task — including one waiting on a question (ASKING)
        // or paused by the user (PAUSED), which would otherwise wait forever and block the next request.
        if (agent.busy || agent.state.value.status in ACTIVE) agent.stop()
        tts.stop()
        idle("Cancelled.")
    }

    fun shutdown() { job?.cancel(); confirmJob?.cancel(); secureJob?.cancel(); askJob?.cancel(); interjectJob?.cancel(); observerScope.cancel(); tts.shutdown() }

    private val ACTIVE = setOf(AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.WAITING_CONFIRMATION,
        AgentStatus.SECURE_INPUT, AgentStatus.ASKING, AgentStatus.PAUSED)
    private val PROMPTS = setOf(AgentStatus.WAITING_CONFIRMATION, AgentStatus.ASKING, AgentStatus.SECURE_INPUT)
    /** How long a typed request waits for the turn in front of it to finish acting before it cuts in anyway. */
    private val WAIT_MAX_MS = 4_000L
    /** voice/io: listening can't work at all right now — a second try won't either. */
    private val UNRECOVERABLE = setOf(com.tekyerema.voice.io.Heard.Cause.OFFLINE, com.tekyerema.voice.io.Heard.Cause.NO_RECOGNIZER,
        com.tekyerema.voice.io.Heard.Cause.PERMISSION, com.tekyerema.voice.io.Heard.Cause.LANGUAGE,
        com.tekyerema.voice.io.Heard.Cause.NOT_ALLOWED_HERE, com.tekyerema.voice.io.Heard.Cause.LOW_MEMORY)
    private val CANT_LISTEN_CONFIRM = "I can't hear answers right now. Tap Yes or No when you're ready."
    /** A question whose answer is a phone number or an amount. */
    private val DIGITS_ASKED = Regex("(?i)\\b(?:phone number|number|digits|how much|amount|how many cedis)\\b")

    /** A reply given in Twi, as the English the answer / revise paths read ("daabi, soma pesewa aduonu" → "no, 20 pesewas";
     *  "pesewa du" → "10 pesewas"); anything else unchanged (language/TwiReply — QA TR10 / TR12, 3 October 2026). */
    private fun englishReply(t: String): String {
        if ((forceLanguage() ?: sessionLanguage()) != Lang.TWI) return t
        val out = com.tekyerema.language.TwiReply.toEnglish(t) { said ->
            val key = PhoneticKey.compact(said)
            SpeechVocabulary.current().entries.any { it.kind == EntityCorrector.Kind.CONTACT && it.compact == key }
        }
        if (out != t) Log.i("Tekyerema", "[TWI] reply understood on the phone → '${com.tekyerema.core.Privacy.content(out)}'")
        return out
    }

    /** The question's Twi wording when the engine is still fetching it (a bounded translator call) — waited for a
     *  moment, then the English is read. The ASKING state is already set, so an answer meanwhile is taken as the answer. */
    private suspend fun awaitTwiWording(question: String): String? {
        if (!agent.state.value.questionTwiPending) return null
        val deadline = android.os.SystemClock.uptimeMillis() + TWI_WORDING_WAIT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val s = agent.state.value
            if (s.pendingQuestion != question || s.status != AgentStatus.ASKING) return null
            if (!s.questionTwiPending) return s.pendingQuestionTwi
            kotlinx.coroutines.delay(100)
        }
        return agent.state.value.takeIf { it.pendingQuestion == question }?.pendingQuestionTwi
    }
    private val TWI_WORDING_WAIT_MS = 4_000L

    /** Offline in Twi: what the phone CAN do, said in English (there is no Twi voice offline). */
    private val OFFLINE_TWI_LIMIT = "Offline, I understand simple Twi requests — sending money, airtime, calls, your " +
        "balance, the time, volume and music. For anything else, say it in English, or connect to the internet."
    private val SECURE_TAP_DONE = "When you've finished, tap Done, or type done."

    /**
     * Ask the pending question aloud, listen for a spoken yes/no, and answer the agent.
     * One repeat on an unclear answer; then a safe decline. Buttons remain a parallel path — if
     * the agent moves on for any reason the observer cancels this job.
     */
    private suspend fun confirmByVoice(prompt: String, promptTwi: String? = null, seq: Long = 0L, skipSpeech: Boolean = false) {
        if (!voiceConfirmation() || !speakAloud()) return
        stopAlreadySaid = false
        if (!skipSpeech) cues.needsOk()
        var quiet = skipSpeech            // [answerNow]: the user cut the reading — listen straight away, once
        val lang = forceLanguage() ?: sessionLanguage()
        // §A.1 — in a Twi session, ask the confirmation in TWI when we have a structured Twi rendering
        // (name verbatim, number digit-by-digit); otherwise fall back to the English question. The Twi text
        // is spoken in Lang.TWI (an online Twi voice if one is configured, else the system voice reads the Twi words — the
        // words are Twi even when the voice is not), never MT'd at runtime.
        val useTwi = lang == Lang.TWI && !promptTwi.isNullOrBlank()
        val askLang = if (useTwi) Lang.TWI else Lang.GH_EN
        try {
            var spokenQuestion = if (useTwi) promptTwi!! else Confirmation.question(prompt)
            // B-09: "wait"/"hold on" keeps the question pending and listens patiently; it isn't an attempt (≤3 holds).
            var attempt = 0
            var holds = 0
            var patient = false
            while (attempt < 2) {
                _state.update { it.copy(confirmPhase = ConfirmPhase.ASKING, speechDetected = false) }
                // English confirmations are spoken in English; a Twi session with a structured Twi line is
                // spoken in Twi (§A.1). The ANSWER may be in either language.
                if (quiet) quiet = false
                else {
                    promptSpeaking = true
                    try { tts.speak(spokenQuestion, askLang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                    finally { promptSpeaking = false }
                }
                if (!hasMicPermission()) { _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }; return }

                _state.update { it.copy(confirmPhase = ConfirmPhase.LISTENING, partialHint = prompt) }
                val onSpeech = { _state.update { it.copy(speechDetected = true) } }
                // Final results only (partials never answer a confirmation); the captured path reports "captured" before
                // it transcribes, exactly where the old capture → transcribe split was.
                val result = listen(com.tekyerema.voice.io.ListenPurpose.YES_NO, events(ready = { listenCue() }, speech = onSpeech, captured = {
                    cues.captured()
                    _state.update { it.copy(confirmPhase = ConfirmPhase.HEARD, speechDetected = false) }
                }), patient = patient)
                val answer = when (result) {
                    is com.tekyerema.voice.io.Heard.Speech, is com.tekyerema.voice.io.Heard.Unavailable -> {
                        val tr = (result as? com.tekyerema.voice.io.Heard.Speech)?.transcript
                        // A Twi reply ("daabi, soma pesewa aduonu") in the English the revise path reads (language/TwiReply).
                        val heard = englishReply(tr?.canonical.orEmpty().ifBlank { tr?.raw.orEmpty() })
                        // voice/io: a recognizer that can't run here at all (offline, no service) won't answer on a second
                        // try either — say the buttons still work and leave the question waiting for them (never a yes).
                        if (result is com.tekyerema.voice.io.Heard.Unavailable && result.cause in UNRECOVERABLE) {
                            Log.i("Tekyerema", "[VOICE] confirmation: can't listen (${result.cause}) — waiting for the buttons")
                            _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }
                            try { tts.speak(CANT_LISTEN_CONFIRM, Lang.GH_EN) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                            return
                        }
                        // J-02: n-best alternatives that mean the opposite make the answer unclear (asked again, then declined).
                        val alternatives = (result as? com.tekyerema.voice.io.Heard.Speech)?.alternatives.orEmpty()
                        Log.i("Tekyerema", "[VOICE] confirmation heard='${com.tekyerema.core.Privacy.content(tr?.raw)}' → ${Confirmation.interpret(heard)}" +
                            if (alternatives.size > 1) " (n-best ${alternatives.size})" else "")   // J-15
                        // §8/§9/§13/§14 — a coherent reply that isn't yes/no ("send it to Kofi instead",
                        // "make it shorter", "actually call Ama") is a CORRECTION, not a decline. Hand the
                        // words to the brain, which re-plans against the pending proposal; the run continues
                        // and the next proposal (if any) is confirmed again. Buttons/plain yes-no are unchanged.
                        if (Confirmation.looksLikeRevision(heard)) {
                            agent.revise(heard)
                            _state.update { it.copy(confirmPhase = ConfirmPhase.NONE, speechDetected = false) }
                            try { tts.speak(if (lang == Lang.TWI) "Yoo, ma mensesa." else "Okay, let me change that.", lang) }
                            catch (c: CancellationException) { throw c } catch (_: Exception) {}
                            return
                        }
                        com.tekyerema.voice.io.YesNo.decide(heard, alternatives)
                    }
                    com.tekyerema.voice.io.Heard.Cancelled -> throw CancellationException("confirmation cancelled")
                    else -> Confirmation.Answer.UNCLEAR
                }
                when (answer) {
                    Confirmation.Answer.YES -> {
                        agent.confirmFor(seq, true)   // J-02: approves only the confirmation that was spoken
                        _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }
                        try { tts.speak(if (lang == Lang.TWI) "Yoo." else "Okay.", lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                        return
                    }
                    Confirmation.Answer.NO -> {
                        stopAlreadySaid = true
                        agent.confirmFor(seq, false)
                        _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }
                        try { tts.speak(if (lang == Lang.TWI) "Yoo, merenyɛ." else "Okay, I won't.", lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                        return
                    }
                    Confirmation.Answer.HOLD -> if (++holds <= 3) {
                        patient = true
                        spokenQuestion = Phrases.get("confirmHold", lang == Lang.TWI, if (lang == Lang.TWI) "Yoo, twɛn. Sɛ woawie a, ka aane anaa daabi."
                            else "Okay, take your time. Say yes or no when you're ready.")
                    } else {
                        if (attempt == 0) spokenQuestion = if (useTwi) "Mante no. Ka aane anaa daabi." else Confirmation.REPEAT
                        attempt++
                    }
                    Confirmation.Answer.UNCLEAR -> {
                        if (attempt == 0)
                            spokenQuestion = if (useTwi) "Mante no. Ka aane anaa daabi." else Confirmation.REPEAT  // §A.1 Twi repeat
                        attempt++
                    }
                }
            }
            // Two unclear answers: the safe default is to do nothing, and to say so.
            stopAlreadySaid = true
            agent.confirmFor(seq, false)
            _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }
            try { tts.speak(Confirmation.DECLINED_UNCLEAR, Lang.GH_EN) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
        } catch (c: CancellationException) {
            _state.update { it.copy(confirmPhase = ConfirmPhase.NONE, speechDetected = false) }
            throw c
        }
    }

    /**
     * Secure Input Mode (MoMo PIN). Tɛkyerɛma is blind here (the engine has suspended screen observation).
     * We ONLY: speak a clear instruction, and listen for the user to say "done". We never ask for the PIN,
     * never listen for a PIN, never echo digits, and never log the transcript. On "done" we tell the engine
     * to resume — which re-checks the screen before observing again (§9/§10). The user is in control: we do
     * NOT resume on a timer; if we can't hear a "done" we simply keep the secure state (the user can tap to
     * cancel and retry).
     */
    private suspend fun secureInputByVoice() {
        val lang = forceLanguage() ?: sessionLanguage()
        // G-13: name what the screen actually wants (it used to always say "MoMo PIN").
        val instruction = secureInstruction(agent.state.value.handoffReason, lang == Lang.TWI)
        val remind = if (lang == Lang.TWI) "Sɛ wowie a, ka sɛ 'awie'." else "Say 'done' when you've finished."
        cues.needsOk()
        // voice/io (J-15): an input that can't keep this audio on the phone (the phone's own recognizer may stream it to a
        // server) never listens here — it isn't even started. The user finishes on the phone and taps Done (or types it).
        if (!voiceIn.caps.secureListen) {
            _state.update { it.copy(state = VoiceState.CONFIRMATION_REQUIRED, partialHint = "Finish it yourself, then tap Done") }
            Log.i("Tekyerema", "[VOICE] secure step: not listening (${voiceIn.id} may send audio off the phone) — Done button / typed done")
            try { tts.speak(tapDoneInstead(instruction), Lang.GH_EN) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
            return
        }
        try {
            var attempt = 0
            while (attempt < 8) {
                _state.update { it.copy(state = VoiceState.CONFIRMATION_REQUIRED, partialHint = if (lang == Lang.TWI) "Hyɛ mu, na ka 'awie'" else "Finish it yourself, then say 'done'") }
                try { tts.speak(if (attempt == 0) instruction else remind, lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                if (!hasMicPermission()) return    // can't listen; stay secure (user completes on the phone, taps to cancel/retry)
                val result = listen(com.tekyerema.voice.io.ListenPurpose.SECURE_DONE, events(ready = { listenCue() },
                    speech = { _state.update { it.copy(speechDetected = true) } }))
                when (result) {
                    is com.tekyerema.voice.io.Heard.Speech, is com.tekyerema.voice.io.Heard.Unavailable -> {
                        val tr = (result as? com.tekyerema.voice.io.Heard.Speech)?.transcript
                        val heard = tr?.canonical.orEmpty().ifBlank { tr?.raw.orEmpty() }
                        // SECURITY: never log `heard` (it is user speech during a secure step). Log only the decision.
                        if (Confirmation.isCompletion(heard)) {
                            Log.i("Tekyerema", "[VOICE] secure step complete (user said done) → asking agent to resume")
                            _state.update { it.copy(state = VoiceState.IDLE, speechDetected = false) }
                            agent.secureInputDone()
                            return
                        }
                        // Trust (2026-09-29): "call my helper" at the PIN is answered, never acted on (agent/helper/HelperGate).
                        if (com.tekyerema.agent.helper.HelperCommands.involvesHelper(heard)) {
                            try { tts.speak(com.tekyerema.agent.helper.HelperGate.PIN_LINE, Lang.GH_EN) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                        }
                        // Not a completion word → gently re-prompt (do NOT treat anything else as the PIN).
                    }
                    com.tekyerema.voice.io.Heard.Cancelled -> throw CancellationException("secure input cancelled")
                    else -> { /* silence → re-prompt */ }
                }
                attempt++
            }
            // Exhausted listening attempts without "done": stay in Secure Input Mode (never auto-resume, §8).
            Log.i("Tekyerema", "[VOICE] secure step: no 'done' heard; staying in Secure Input Mode (user in control)")
        } catch (c: CancellationException) {
            _state.update { it.copy(speechDetected = false) }
            throw c
        }
    }

    /** One more capture + transcription with [strategy] (a continuation or a patient wait); null when nothing usable
     *  was said. Cancellation propagates. */
    private suspend fun listenMore(strategy: VadStrategy): Transcript? {
        val purpose = if (strategy.name == VadStrategy.PATIENT.name) com.tekyerema.voice.io.ListenPurpose.PATIENT
            else com.tekyerema.voice.io.ListenPurpose.CONTINUATION
        val heard = listen(purpose, events(ready = { listenCue() }, speech = { _state.update { it.copy(speechDetected = true) } },
            captured = { cues.captured() }))
        if (heard is com.tekyerema.voice.io.Heard.Cancelled) throw CancellationException("cancelled while listening")
        return (heard as? com.tekyerema.voice.io.Heard.Speech)?.transcript?.takeUnless { it.isBlank }
    }

    /** What the user hears when their words couldn't be recognised ([cause] from the voice boundary). */
    private fun cantHear(cause: com.tekyerema.voice.io.Heard.Cause): String = when (cause) {
        com.tekyerema.voice.io.Heard.Cause.OFFLINE -> "Voice needs the internet right now, or type your request."
        com.tekyerema.voice.io.Heard.Cause.NO_RECOGNIZER -> "This phone's speech service isn't available. You can type your request."
        com.tekyerema.voice.io.Heard.Cause.BUSY -> "The phone's speech service is busy. Tap and try again in a moment."
        com.tekyerema.voice.io.Heard.Cause.PERMISSION -> "I need microphone permission to hear you."
        com.tekyerema.voice.io.Heard.Cause.LANGUAGE -> "The phone's speech service doesn't have this language. You can type your request."
        // The offline recogniser was refused for lack of memory rather than letting Android kill the app (audit V01).
        com.tekyerema.voice.io.Heard.Cause.LOW_MEMORY -> AsrMemory.SPOKEN
        else -> "Sorry, I couldn't understand that. Try again, a little closer to the phone."
    }

    /** The secure instruction with "say done" swapped for the Done button — for an input that never listens there. */
    private fun tapDoneInstead(instruction: String): String {
        val say = Regex("Say 'done' when (?:you've|it's) finished\\.")
        return if (say.containsMatchIn(instruction)) instruction.replace(say, SECURE_TAP_DONE) else "$instruction $SECURE_TAP_DONE"
    }

    private fun secureInstruction(reason: String?, twi: Boolean): String {
        if (twi) return if (reason == null || reason == "momo_pin")
            "Yei yɛ ahotoso adeyɛ. Fa wo MoMo PIN no hyɛ wo fɔn no mu ankasa. Merenhwɛ screen no. Sɛ wowie a, ka sɛ 'awie'."
        else "Yei yɛ ahotoso adeyɛ. Wo ankasa hyɛ mu wɔ wo fɔn no so. Merenhwɛ screen no. Sɛ wowie a, ka sɛ 'awie'."
        return when (reason) {
            "pin" -> "This is a secure step. Please enter your PIN directly on the phone. I'm not looking at the screen. Say 'done' when you've finished."
            "password" -> "This screen wants your password. Please type it yourself — I'm not looking at the screen. Say 'done' when you've finished."
            "otp" -> "This screen wants a one-time code. Please enter it yourself — I'm not looking at the screen. Say 'done' when you've finished."
            "captcha" -> "This page wants to check that you're a person, and that step is yours. I'm not looking at the screen. Say 'done' when it's finished."
            else -> "This is a secure step. Please enter your MoMo PIN directly on the phone. I'm not looking at the screen. Say 'done' when you've finished."
        }
    }

    /** [origin]: who opened this listen — one Tɛkyerɛma opened by itself (a follow-up) is gated by [Addressedness];
     *  [questionPending]: the reply before it asked the user something. */
    private suspend fun runOnce(
        quiet: Boolean = false,
        origin: Addressedness.Origin = if (quiet) Addressedness.Origin.FOLLOW_UP else Addressedness.Origin.USER,
        questionPending: Boolean = false
    ) {
        try {
            if (!hasMicPermission()) {
                if (quiet) { idle(""); return }
                fail(VoiceStage.MIC, "Microphone permission is off.", "I need microphone permission to hear you."); return
            }

            // N-16: a problem the user can't see (screen control switched off by the system) is said once, up front.
            if (!quiet) healthNotice()?.let { say(it) }
            // Q-01: a voice turn starts at the trigger.
            com.tekyerema.core.telemetry.Telemetry.beginTurn(if (quiet) "follow_up" else "voice", ownedByVoice = true)
            // A varied, natural acknowledgement ("Go ahead." / "Uh-huh." …) is the listening hint; the
            // earcon plays to completion BEFORE the mic opens so it is never captured as speech.
            val ack = Acknowledgements.next(forceLanguage() ?: sessionLanguage())
            set(VoiceState.LISTENING, ack)
            agent.prewarm()   // N-04: open the brain's connection while the user talks
            val heard = listen(if (quiet) com.tekyerema.voice.io.ListenPurpose.FOLLOW_UP else com.tekyerema.voice.io.ListenPurpose.REQUEST, events(
                ready = { listenCue(); set(VoiceState.LISTENING, ack) },   // tone once the mic is open
                speech = {
                    com.tekyerema.core.telemetry.Telemetry.mark("speech_start")   // B-19: speech → end_of_turn is the endpoint wait
                    _state.update { it.copy(state = VoiceState.LISTENING, partialHint = ack, speechDetected = true) }
                },
                captured = {
                    com.tekyerema.core.telemetry.Telemetry.mark("end_of_turn")
                    set(VoiceState.CAPTURED, "Got it")
                    cues.captured()
                    set(VoiceState.TRANSCRIBING, "Transcribing…")
                },
                // voice/io: the recognizer's live words, for the screen only — never acted on, never logged, never spoken.
                partial = { p -> _state.update { it.copy(livePartial = p) } }
            ))
            var tr = when (heard) {
                is com.tekyerema.voice.io.Heard.Speech -> heard.transcript.also { com.tekyerema.core.telemetry.Telemetry.mark("asr_final"); noSpeechRun = 0 }
                // In a follow-up, silence just ends the conversation quietly (the screen then closes);
                // on a deliberate tap-to-talk it's worth telling the user we heard nothing.
                com.tekyerema.voice.io.Heard.NoSpeech, com.tekyerema.voice.io.Heard.TooShort -> {
                    // A QUESTION is waiting and the phone's recognizer came back empty (error 7 on the A20e, twice,
                    // on spoken digits — 2026-09-30): one more listen through OUR OWN recognizer (the keyboard's
                    // dictation core — offline-capable, tuned on Ghanaian speech) before the ask is allowed to die.
                    if (questionPending) {
                        answerViaOwnAsr()?.let { tr2 -> runTurn(tr2, InputSource.ASR); return }
                        sayThenIdle("I didn't catch that. The question is still waiting — say it once more, or tap to type.")
                        return
                    }
                    if (heard is com.tekyerema.voice.io.Heard.TooShort) {
                        if (quiet) idle("") else sayThenIdle("That was too short. Tap and try again.")
                    } else {
                        // Escalating, not word-for-word (Closers.noInput): short, then one example, then quiet.
                        if (quiet) idle("") else Closers.noInput(++noSpeechRun)?.let { sayThenIdle(it) } ?: idle("")
                    }
                    return
                }
                com.tekyerema.voice.io.Heard.Cancelled -> { cancelledToIdle(); return }
                is com.tekyerema.voice.io.Heard.Unavailable -> {
                    if (heard.cause == com.tekyerema.voice.io.Heard.Cause.AUDIO) {
                        if (quiet) { idle(""); return }
                        fail(VoiceStage.MIC, heard.detail, "I can't use the microphone right now."); return
                    }
                    fail(VoiceStage.ASR, heard.detail, cantHear(heard.cause)); return
                }
            }
            if (tr.isBlank) { fail(VoiceStage.ASR, "Empty transcript", "Sorry, I didn't catch that. Try again, a little closer to the phone."); return }
            // B-09: "wait" / "hold on" on its own → the user needs a moment: listen patiently for the real request.
            var holds = 0
            while (TurnDetector.isHold(tr.canonical) && holds++ < 2) {
                val twi = (forceLanguage() ?: sessionLanguage()) == Lang.TWI
                say(Phrases.get("holdAck", twi, if (twi) "Yoo, twɛn." else "Okay, take your time."))
                set(VoiceState.LISTENING, ack)
                val more = listenMore(VadStrategy.PATIENT) ?: run { idle(""); return }
                tr = more
            }
            // B-02: a request that stops mid-phrase ("call my…") was a pause, not the end — listen a little longer
            // and join the parts, instead of acting on half a request.
            if (TurnDetector.looksUnfinished(tr.canonical)) {
                set(VoiceState.LISTENING, ack)
                listenMore(VadStrategy.CONTINUE)?.let { tr = TurnDetector.join(tr, it) }
                set(VoiceState.TRANSCRIBING, "Transcribing…")
            }
            // Right after a reply (a reading part, a question, a tap to interrupt), "stop" / "that's enough" ends the
            // conversation here — it isn't a new command for the phone.
            if (quiet && ReadingPace.isEnough(tr.canonical)) {
                Log.i("Tekyerema", "[VOICE] follow-up: the user said that's enough")
                cues.captured()
                say(if ((forceLanguage() ?: sessionLanguage()) == Lang.TWI) "Yoo." else "Okay.")
                idle("")
                return
            }
            // Addressedness (a judge's session, A20e 2026-09-29): a listen WE opened heard the room — "I will eat today." was
            // answered "Okay, enjoy your meal!". Not said to us → rest quietly: no model call, no reply (voice/Addressedness.kt).
            if (origin.gated) {
                val v = Addressedness.check(tr.canonical, origin, questionPending, alt = tr.raw)
                if (!v.addressed) {
                    Log.i("Tekyerema", "[VOICE] ignored: not addressed (${origin.label}) — ${v.reason}" +
                        if (com.tekyerema.core.Privacy.logContent()) " '${tr.canonical}'" else "")
                    idle("")
                    return
                }
            }
            // J-15: what the user said stays out of logcat in release builds.
            if (com.tekyerema.core.Privacy.logContent())
                Log.i("Tekyerema", "[VOICE] raw='${tr.raw}' canonical='${tr.canonical}' lang=${tr.language} " +
                    "ASR=${tr.backend}${if (tr.fallbackUsed) "(fallback)" else ""} (${tr.reason})")
            else Log.i("Tekyerema", "[VOICE] len=${tr.canonical.length} lang=${tr.language} ASR=${tr.backend}${if (tr.fallbackUsed) "(fallback)" else ""}")

            runTurn(tr, InputSource.ASR)
        } catch (c: CancellationException) {
            cancelledToIdle()
        } catch (e: Exception) {
            fail(VoiceStage.NONE, e.message ?: "Voice error", "Sorry, something went wrong. Tap and try again, or type your request.")
        } finally {
            // A turn that never reached an answer (no speech, cancelled, an error) is not a measurement — drop it.
            com.tekyerema.core.telemetry.Telemetry.discard(byVoice = true)
        }
    }

    /**
     * The phone's recognizer failed on an ANSWER: one listen through our own stack (the keyboard's dictation core —
     * capture + our HybridAsr, offline Parakeet when there's no net). Null when it can't run or heard nothing.
     */
    private suspend fun answerViaOwnAsr(): Transcript? {
        val twi = (forceLanguage() ?: sessionLanguage()) == Lang.TWI
        if (SecureStep.active() || !Dictation.available(twi)) return null
        Log.i("Tekyerema", "[VOICE] answer retry via our own recognizer")
        val out = try {
            Dictation.run(twi, engineRunning = false, secure = SecureStep.active(),
                mic = VoiceEngine.dictationMic, asr = VoiceEngine.dictationAsr,
                handOver = { WakeWordService.handOver() },
                reclaim = { WakeWordService.reclaim() },
                onListening = { listenCue(); set(VoiceState.LISTENING, "") })
        } catch (c: CancellationException) { throw c } catch (_: Throwable) { null }
        val text = (out as? Dictation.Outcome.Text)?.text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return Transcript(raw = text, canonical = text, language = if (twi) Lang.TWI else Lang.GH_EN)
    }

    /**
     * THE canonical agent input. Recognized text — from ASR or the keyboard — enters the agent
     * here and nowhere else: session language, UNDERSTANDING, the agent run (planner → tools →
     * confirmation gate), then the spoken outcome. [source] is logged so a trace can tell the two
     * apart; it changes nothing about what the agent receives.
     */
    private suspend fun runTurn(heard: Transcript, source: InputSource) {
        // A PIN / password / one-time code said inside a request is removed here, at the one door every request comes
        // through — before the log line, the screen, the fast path and the agent (audit 2026-10-01, SEC02).
        // (…and "10 pesos" becomes "10 pesewas" in the request that is acted on — money/MoneyAsk.heard; the words as
        // heard are kept for the screen.)
        // Only what a recogniser wrote is repaired: typed words are the user's own (a typed "2 cities" means cities).
        val masked = com.tekyerema.core.Privacy.maskCredentials(heard.canonical)
        val repaired = if (source == InputSource.ASR) com.tekyerema.money.MoneyAsk.heard(masked) else masked
        // A core Twi request (send money, airtime, data, call, open, the time, the light…) is understood HERE, on the phone,
        // as the English command the rest of the app runs — online or offline, never through a translator that turns
        // "Frɛ Kofi" into "get some coffee" (language/TwiCommands; owner's Twi test, 2 October 2026).
        if (heard.language == Lang.TWI) VoiceEngine.warmTranslator()   // a Twi turn may need the translator in a moment
        val glossed = if (heard.language == Lang.TWI) com.tekyerema.language.TwiCommands.gloss(repaired) { said ->
            val key = PhoneticKey.compact(said)
            SpeechVocabulary.current().entries.any { it.kind == EntityCorrector.Kind.CONTACT && it.compact == key }
        }?.also {
            Log.i("Tekyerema", "[TWI] understood on the phone → '${com.tekyerema.core.Privacy.content(it)}'")
        } else null
        val tr = heard.copy(raw = com.tekyerema.core.Privacy.maskCredentials(heard.raw), canonical = glossed ?: repaired)
        try {
            hideAsking()   // a new turn (e.g. the answer to a question) clears any "asking" card
            // Session language context (§11): adopt the LID-resolved language (low-signal already
            // guarded server-side by lid.resolve → session default / previous language).
            onLanguageResolved(tr.language)
            _state.update {
                it.copy(state = VoiceState.UNDERSTANDING, lastRaw = tr.raw, lastCanonical = tr.canonical,
                    language = tr.language, partialHint = tr.canonical, failedStage = VoiceStage.NONE, error = "",
                    inputSource = source)
            }
            // SEC-P1-4: the spoken command ("send 50 to 024…") is sensitive — full text in debug only.
            if (com.tekyerema.BuildConfig.DEBUG) Log.i("Tekyerema", "[INPUT] source=$source text='${tr.canonical}' lang=${tr.language}")
            else Log.i("Tekyerema", "[INPUT] source=$source len=${tr.canonical.length} lang=${tr.language}")

            // First-run practice (voice/Welcome.kt): "send a practice message" REHEARSES the spoken confirmation for a text
            // to a made-up contact. It never reaches the fast path, the agent or any tool — nothing can be sent.
            if (Practice.matches(tr.canonical)) { runPractice(); return }

            // "Say that again." — the last reply is spoken again, exactly, never re-run (a blind user's reflex when a
            // line went by too fast; micro_details.md quick win 2). Before the fast path and the agent.
            if (RepeatIntent.matches(tr.canonical)) {
                val lastLine = _state.value.spoken
                set(VoiceState.SPEAKING, lastLine)
                val line = lastLine.ifBlank { "I haven't said anything yet." }
                try { if (speakAloud()) tts.speak(Verbalize.apply(line), tr.language) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                _state.update { it.copy(state = VoiceState.IDLE, spoken = line, spokenAt = System.currentTimeMillis()) }
                // A repeated QUESTION is still waiting for its answer — listen again, as the first asking did.
                if (line.trim().endsWith("?") && source == InputSource.ASR && speakAloud() && hasMicPermission() &&
                    followUpChain < maxFollowUps) {
                    followUpChain++
                    runOnce(quiet = true, questionPending = true)
                }
                return
            }

            // LOCAL FAST PATH (demo-critical): deterministic device ops execute HERE, before the agent/LLM —
            // no reasoning, no screen read, no app launch merely to answer (never open Clock to tell the time).
            // Returns null for anything it can't confidently handle → the agent path below runs unchanged.
            val tel = com.tekyerema.core.telemetry.Telemetry
            tel.ensureVoiceTurn(if (source == InputSource.ASR) "voice" else "typed")
            tel.update { goal = tr.canonical }
            fastRouter?.tryHandle(tr.canonical, tr.language)?.let { fast ->
                tel.mark("route"); tel.update { tier = com.tekyerema.agent.RouterTiers.FAST_PATH }   // Q-01 / Q-02
                if (fast.listenAfter) {} else if (fast.ok) cues.done() else cues.error()   // K-03: a failure line never plays the "done" sound
                set(VoiceState.SPEAKING, fast.spoken)
                // ACTION confirmations ("volume up") speak on the OFFLINE system engine (instant, never blocked
                // by a slow online Twi voice); information QUERIES (time/battery) stay on the normal voice/lang.
                val useSystem = fast.systemVoice && systemVoice != null
                val engine = if (useSystem) systemVoice!! else tts
                val lang = if (useSystem) Lang.GH_EN else tr.language
                tel.mark("reply_start")
                val spoke = if (!speakAloud() || fast.spoken.isBlank()) false
                    else try { engine.speak(Verbalize.apply(fast.spoken), lang) }
                    catch (c: CancellationException) { throw c } catch (e: Exception) { false }
                tel.endTurn(if (fast.ok) "full" else "failed", byVoice = true)
                _state.update {
                    // A command with nothing to say (the camera reader announces itself) is not a missing voice.
                    it.copy(state = VoiceState.IDLE, spoken = fast.spoken, spokenAt = System.currentTimeMillis(),
                        partialHint = if (spoke || !speakAloud() || fast.spoken.isBlank()) "" else "(no ${Lang.label(tr.language)} voice yet)")
                }
                // Habits (agent/habits): a request that went well is noted; one made at the same time every week (or day)
                // gets ONE offer of a spoken reminder — never of doing it for them.
                // "Did you mean …?" (the offline meaning matcher): listen for the yes or no right here, like the agent's questions.
                if (fast.listenAfter) {
                    if (source == InputSource.ASR && speakAloud() && hasMicPermission() && followUpChain < maxFollowUps) {
                        followUpChain++
                        runOnce(quiet = true, questionPending = true)
                    }
                } else if (fast.ok) offerHabit(tr, source, fast.intent)
                return
            }

            tel.mark("route")
            // Offline, a Twi request the phone's own Twi commands didn't understand can't be translated — say so plainly,
            // with what DOES work, instead of a vague failure (Abena study, 3 October 2026: its honest "Twi recognition is
            // limited… you can type"). [owner: verify the wording]
            if (tr.language == Lang.TWI && glossed == null && !com.tekyerema.core.NetworkMonitor.isOnline()) {
                val line = OFFLINE_TWI_LIMIT
                Log.i("Tekyerema", "[TWI] offline and not one of the phone's Twi commands — said so")
                set(VoiceState.SPEAKING, line)
                cues.error()
                try { if (speakAloud()) tts.speak(line, Lang.GH_EN) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                tel.endTurn("failed", byVoice = source == InputSource.ASR)
                _state.update { it.copy(state = VoiceState.IDLE, spoken = line, spokenAt = System.currentTimeMillis()) }
                return
            }
            // Offline, words the meaning matcher mapped to a command only the LocalBrain runs ("read the screen") go as that command.
            val agentT0 = android.os.SystemClock.uptimeMillis()
            var outcome = runAgent(fastRouter?.agentGoal(tr.canonical) ?: tr.canonical) ?: return   // null: already set fail/cancel
            // After a LONG task, a small warm word before the result ("Alright — it's sent.") — the breath a person takes
            // (micro_details.md quick win 8). Never on a question (the "?" tail must stay last) or a reading.
            if (outcome.status == AgentStatus.COMPLETED && android.os.SystemClock.uptimeMillis() - agentT0 > 10_000 &&
                !outcome.spoken.trim().endsWith("?"))
                outcome = outcome.copy(spoken = Closers.warm(outcome.spoken, tr.language == Lang.TWI))
            when (outcome.status) {
                AgentStatus.COMPLETED -> cues.done()
                AgentStatus.COMPLETED_UNVERIFIED -> cues.unverified()   // K-03: never the "done" sound without evidence
                AgentStatus.FAILED, AgentStatus.CANNOT_DO -> cues.error()
                else -> {}
            }

            set(VoiceState.SPEAKING, outcome.spoken)
            var interruption: com.tekyerema.voice.io.BargeIn? = null
            tel.mark("reply_start")
            val spoke = if (!speakAloud() || outcome.spoken.isBlank()) false else try {
                val b = speakInterruptible(Verbalize.apply(outcome.spoken), tr.language)
                // The interruption's own audio (captured stack), or just its onset (the phone's recognizer listens afresh).
                interruption = b.takeIf { it.interrupted && (it.wav != null || !voiceIn.caps.transcribesAudio) }
                b.spokeFully || interruption != null
            } catch (c: CancellationException) { throw c } catch (e: Exception) { false }
            tel.endTurn(com.tekyerema.agent.AgentEngine.gradeOf(outcome.status), byVoice = true)   // before any follow-up turn
            _state.update {
                it.copy(state = VoiceState.IDLE, spoken = outcome.spoken, spokenAt = System.currentTimeMillis(),
                    partialHint = if (spoke || !speakAloud() || outcome.spoken.isBlank()) "" else "(no ${Lang.label(tr.language)} voice yet)")
            }
            // B-07: the user spoke over the reply — what they said is the next turn.
            interruption?.let { b ->
                Log.i("Tekyerema", "[VOICE] barge-in: reply interrupted — handling what the user said")
                val heard = b.wav?.let { wav -> set(VoiceState.TRANSCRIBING, "Transcribing…"); voiceIn.transcribe(wav) }
                    // Onset only: the reply has stopped and the barge-in mic is released — listen afresh (the first word or
                    // two of the interruption are lost; the recognizer never runs during our own speech).
                    ?: listen(com.tekyerema.voice.io.ListenPurpose.REQUEST, events(ready = { listenCue(); set(VoiceState.LISTENING, "") },
                        captured = { cues.captured(); set(VoiceState.TRANSCRIBING, "Transcribing…") }))
                val next = (heard as? com.tekyerema.voice.io.Heard.Speech)?.transcript
                // "Stop" / "that's enough" said over the reply: it has stopped, and that is all (checked before the echo
                // guard — a reading that contains the word "stop" must not swallow the user's own "stop").
                if (next != null && ReadingPace.isEnough(next.canonical)) {
                    Log.i("Tekyerema", "[VOICE] barge-in: the user stopped the reply")
                    cues.captured()
                    idle(""); return
                }
                // Seen on the A20e: the echo canceller let our OWN voice through. That is never a new request —
                // finish the reply from the sentence it was cut in, and don't start a turn from our own words.
                if (next != null && SelfEcho.isEcho(next.canonical, outcome.spoken)) {
                    Log.i("Tekyerema", "[VOICE] barge-in ignored: the mic heard our own reply")
                    SelfEcho.resumeFrom(next.canonical, outcome.spoken).takeIf { it.isNotBlank() && speakAloud() }?.let { rest ->
                        set(VoiceState.SPEAKING, rest)
                        try { tts.speak(Verbalize.apply(rest), tr.language) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                    }
                    idle(""); return
                }
                if (next != null && !next.isBlank) { runTurn(next, InputSource.ASR); return }
                idle("")
            }
            // Conversational tail (§7/§32): when the agent's spoken reply is a QUESTION (e.g. "I need
            // Prince's phone number. Can you give me their number?"), re-open the mic RIGHT HERE, in this same
            // job, so the user can answer. This does NOT depend on Tɛkyerɛma's screen being in front — the
            // agent often hands off to another app (Contacts, the dialer), which is exactly why a follow-up
            // question used to go unanswered and the turn just died in the background. Silence ends it (quiet
            // runOnce → idle, no further tail); a spoken answer becomes the next turn. Capped so mic noise
            // can't spin forever, and only for spoken (ASR) turns, never a pending confirmation (handled
            // separately by confirmByVoice).
            // A reading cut into parts ends "There's more — say continue": listen for it the same way, so going on is one
            // word instead of a new trigger plus "continue" (no card — it isn't a question the task is waiting on).
            val invitesMore = ReadingPace.invitesContinue(outcome.spoken)
            val isQuestion = (outcome.spoken.trim().endsWith("?") || invitesMore) &&
                agent.state.value.status != AgentStatus.WAITING_CONFIRMATION && followUpChain < maxFollowUps
            if (isQuestion) {
                followUpChain++
                if (!invitesMore) {
                    Log.i("Tekyerema", "[VOICE] agent asked a question — showing the ask card ($source)")
                    showAsking(outcome.spoken)         // a VISIBLE card over any app (typed OR spoken), so the user sees it's waiting
                }
                // Reopen the mic only for a SPOKEN turn; a TYPED turn is answered by typing (card stays until then).
                if (source == InputSource.ASR && speakAloud() && hasMicPermission()) {
                    try { runOnce(quiet = true, questionPending = true) } finally { if (!invitesMore) hideAsking() }
                }
            } else if (interruption == null && outcome.status == AgentStatus.COMPLETED) {
                // Habits (agent/habits): only a VERIFIED success is noted — never an unverified or failed one.
                offerHabit(tr, source, null)
            }
        } catch (c: CancellationException) {
            idle("Cancelled.")
        } catch (e: Exception) {
            fail(VoiceStage.NONE, e.message ?: "Voice error", "Sorry, something went wrong. Tap and try again, or type your request.")
        } finally {
            com.tekyerema.core.telemetry.Telemetry.discard(byVoice = true)   // an unfinished turn measured nothing
        }
    }

    /**
     * B-07 — speak [text] while an echo-cancelled mic listens; if the user starts talking the reply stops and their
     * words are returned as the next utterance. Only for replies long enough to be worth interrupting, never for a
     * confirmation read-back (an early "yes" must not count). Returns whether it spoke fully, whether it was interrupted,
     * and — for an input that transcribes audio — the interruption itself.
     */
    private suspend fun speakInterruptible(text: String, lang: String): com.tekyerema.voice.io.BargeIn {
        val cfg = com.tekyerema.core.config.AppConfig
        val detector = barge
        if (detector == null || !bargeInEnabled() || !hasMicPermission() || text.length < cfg.int("speech.bargeIn.minChars", 60))
            return com.tekyerema.voice.io.BargeIn(tts.speak(text, lang), interrupted = false, wav = null)
        // The reply on the voice-communication path, so the echo canceller has our own voice as its reference (the A20e's
        // AEC let media-stream TTS straight through). Entered BEFORE the mic opens and the reply starts: both must be on it.
        val duplex = duplexBegin()
        try {
            // voice/io: keep the interruption's audio only for an input that can transcribe it; the phone's recognizer opens
            // its own mic, so there the onset alone stops the reply and a fresh listen follows (after duplexEnd, below).
            return detector.speakWatching({ tts.speak(text, lang) }, onOnset = { tts.stop() }, keepAudio = voiceIn.caps.transcribesAudio)
        } finally {
            if (duplex) duplexEnd()
        }
    }

    /** Speak a short outcome (when replies are read aloud), then rest. Never throws. */
    private suspend fun say(text: String) {
        if (!speakAloud()) return
        set(VoiceState.SPEAKING, text)
        try { tts.speak(text, forceLanguage() ?: sessionLanguage()) }
        catch (c: CancellationException) { throw c } catch (_: Exception) {}
    }

    private suspend fun sayThenIdle(text: String) { say(text); idle(text) }

    private data class Outcome(val status: AgentStatus, val spoken: String)

    private suspend fun runAgent(goal: String): Outcome? {
        if (agent.busy) {
            // A task is already running (a scheduled one, or one that started while this was being heard). The engine
            // refuses a second goal, and this turn used to wait for its own goal until the whole task budget ran out —
            // then "took too long" and stopped the OTHER task. Stop / pause / status apply to the running task (C-12);
            // a new request is not folded into a task the user may not know about — say so instead of dropping it.
            val twi = (forceLanguage() ?: sessionLanguage()) == Lang.TWI
            val line = if (ControlVerbs.classify(goal) != ControlVerbs.Verb.STEER) applyInterjection(goal, twi)
                else Phrases.get("busyOtherTask", twi, "I'm still working on another task. Say stop to end it, or ask me again when it's done.")
            Log.i("Tekyerema", "[VOICE] a task is already running — request not started as a new task")
            say(line)
            idle("")
            return null
        }
        stopAlreadySaid = false
        agent.submitGoal(goal)
        set(VoiceState.EXECUTING, "Working on it…")
        // Instant, offline "thinking" backchannel while the brain works: a blind user gets nothing from the
        // visual thinking animation, so silence reads as "it didn't hear me". Randomized short fillers bridge
        // the wait (Siri/Alexa's trick). Stopped the moment a real reply or a confirmation prompt is spoken.
        if (speakAloud()) fillers?.start(forceLanguage() ?: sessionLanguage())
        val terminal = setOf(
            AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED,
            AgentStatus.CANNOT_DO, AgentStatus.FAILED, AgentStatus.STOPPED
        )
        val runSeq = agent.state.value.runSeq   // this run (submitGoal numbers it synchronously)
        val progressJob = if (speakAloud()) observerScope.launch {
            launch { appNotes(runSeq) }   // Voice-UX 2026-09-29: "In WhatsApp." (voice/AppNote.kt)
            progressLoop(goal)
        } else null   // K-02
        val finalState: AgentUiState? = try {
            awaitAgent(goal, runSeq, terminal) { st ->
                when (st.status) {
                    AgentStatus.WAITING_CONFIRMATION -> {
                        fillers?.stop()   // the confirmation question is about to be spoken — never talk over it
                        if (_state.value.state != VoiceState.CONFIRMATION_REQUIRED && !voiceConfirmation()) cues.needsOk()
                        _state.update {
                            it.copy(state = VoiceState.CONFIRMATION_REQUIRED,
                                partialHint = st.pendingConfirmation ?: "Waiting for your confirmation")
                        }
                    }
                    AgentStatus.THINKING, AgentStatus.ACTING ->
                        if (_state.value.state != VoiceState.EXECUTING) set(VoiceState.EXECUTING, "Working on it…")
                    AgentStatus.ASKING, AgentStatus.SECURE_INPUT -> fillers?.stop()   // a question is about to be spoken
                    else -> {}
                }
            }
        } finally {
            // Always stop the backchannel once the wait ends — success, timeout, OR cancellation — so it can
            // never overlap the spoken outcome (which runTurn speaks right after this returns).
            progressJob?.cancel()
            fillers?.stop()
        }
        if (finalState == null) {
            // §1.21 — the timeout must actually HALT the agent, not just stop us waiting on it. Otherwise
            // the agent's own coroutine keeps running in the background and could still perform (or speak
            // about) a consequential action after we've told the user it timed out.
            agent.stop()
            fail(VoiceStage.EXECUTION, "The assistant took too long to respond.", "Sorry, that took too long. Try again, or ask for one step at a time."); return null
        }
        val resultText = finalState.log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text
        val reading = com.tekyerema.agent.ScreenReadIntent.isRead(goal) || com.tekyerema.agent.ScreenReadIntent.isContinue(goal) ||
            (resultText != null && ReadingPace.invitesContinue(resultText))
        return Outcome(finalState.status, spokenFor(finalState.status, resultText, exact = reading))
    }

    /**
     * Wait for [goal] to reach a terminal status. The [agentTimeoutMs] budget counts only time the AGENT is working:
     * while it waits on the user (a confirmation, or the user typing a PIN in Secure Input Mode) the clock stops —
     * G-13: a user entering their PIN used to be cut off by the 180 s cap. A separate, longer cap
     * (`voice.agent.maxUserWaitMs`, 10 min) still ends a turn nobody answers. Null = timed out.
     */
    private suspend fun awaitAgent(goal: String, runSeq: Long, terminal: Set<AgentStatus>, onState: suspend (AgentUiState) -> Unit): AgentUiState? =
        kotlinx.coroutines.coroutineScope {
            val tick = 250L
            val maxUserWait = com.tekyerema.core.config.AppConfig.long("voice.agent.maxUserWaitMs", 600_000L)
            val out = kotlinx.coroutines.CompletableDeferred<AgentUiState?>()
            val watcher = launch {
                agent.state.collect { st ->
                    onState(st)
                    // THIS run, by number — two tasks can share their goal words, and an old run unwinding after stop()
                    // must not end the new turn (audit 2026-09-30, half 1 #5). The goal match stays as a fallback for a
                    // resumed task ("carry on") whose run renumbers.
                    if (st.status in terminal && (st.runSeq == runSeq || st.goal == goal || st.request == goal)) out.complete(st)
                }
            }
            // The clock samples the agent's status each tick: time on the user (confirmation, secure hand-off) and
            // time working are budgeted separately.
            val clock = launch {
                var worked = 0L
                var waited = 0L
                while (true) {
                    kotlinx.coroutines.delay(tick)
                    val s = agent.state.value.status
                    if (s == AgentStatus.WAITING_CONFIRMATION || s == AgentStatus.SECURE_INPUT || s == AgentStatus.ASKING ||
                        s == AgentStatus.PAUSED) waited += tick else worked += tick
                    if (worked >= agentTimeoutMs || waited >= maxUserWait) { out.complete(null); break }
                }
            }
            val result = out.await()
            watcher.cancel(); clock.cancel()
            result
        }

    /**
     * K-02 — a blind user gets nothing from the thinking animation, and the two opening fillers end after a few
     * seconds. Past that, speak a short progress line every `ux.progress.intervalMs` while the agent is working
     * (never while it asks or waits on the user), up to `ux.progress.maxLines` times. Never the step number — the
     * owner heard "step 4… step 5…" on every long task (2026-09-28); steps are the agent's bookkeeping, not news.
     */
    private suspend fun progressLoop(goal: String) {
        val cfg = com.tekyerema.core.config.AppConfig
        kotlinx.coroutines.delay(cfg.long("ux.progress.firstAfterMs", 10_000L))
        val interval = cfg.long("ux.progress.intervalMs", 12_000L)
        var lines = 0
        while (lines < cfg.int("ux.progress.maxLines", 8)) {
            val st = agent.state.value
            if (st.goal != goal && st.request != goal) return   // WS-A: a resumed task runs under its original goal
            val working = st.status == AgentStatus.THINKING || st.status == AgentStatus.ACTING
            // Never while the user is interjecting: the answer mic would take "Still working on it." as their words.
            if (working && _state.value.state == VoiceState.EXECUTING && interjectJob?.isActive != true) {
                fillers?.stop()
                val lang = forceLanguage() ?: sessionLanguage()
                val twi = lang == Lang.TWI
                // B-13 "detailed": say what it's doing right now ("I'm opening an app…"), not just "still working".
                val line = if (verbosity() == "detailed" && !twi) agent.statusLine()
                    else Phrases.get("progress", twi, if (twi) "Mereyɛ ho adwuma da." else "Still working on it.")
                cues.progress()
                // B-13: "brief" users get the progress tick only — no spoken line.
                if (verbosity() != "brief")
                    try { tts.speak(Verbalize.apply(line), lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                lines++
            }
            kotlinx.coroutines.delay(interval)
        }
    }

    /**
     * Voice-UX (2026-09-29): when this run's step brings another app to the front, say where the phone is — "In WhatsApp."
     * — once per app; the rules (never over a prompt or a PIN step, not in a quick task) are in [AppNote]. Runs beside
     * [progressLoop] and is cancelled with it when the task ends.
     */
    private suspend fun appNotes(runSeq: Long) {
        val notes = AppNote()
        // A task that is over by now says it all in its result (this job is cancelled with it) — hold every note till then.
        kotlinx.coroutines.delay(AppNote.MIN_TASK_MS)
        val heldUntil = System.currentTimeMillis()
        agent.state.collect { st ->
            val sw = st.appSwitch?.takeIf { it.runSeq == runSeq } ?: return@collect
            val now = agent.state.value
            if (now.appSwitch != sw) return@collect   // it moved on again: the newer app is judged on its own
            val lang = forceLanguage() ?: sessionLanguage()
            val t = System.currentTimeMillis()
            val line = notes.next(AppNote.Moment(sw.pkg, sw.name, taskMs = AppNote.MIN_TASK_MS + (t - heldUntil), nowMs = t,
                agentWorking = now.status == AgentStatus.THINKING || now.status == AgentStatus.ACTING,
                micFree = _state.value.state == VoiceState.EXECUTING && interjectJob?.isActive != true &&
                    askJob?.isActive != true && confirmJob?.isActive != true && secureJob?.isActive != true,
                secureStep = SecureStep.active()), lang == Lang.TWI) ?: return@collect
            fillers?.stop()
            try { tts.speak(Verbalize.apply(line), lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
        }
    }

    /** §35: speech must never imply an action happened unless the agent verified it. */
    private fun spokenFor(status: AgentStatus, resultText: String?, exact: Boolean = false): String {
        // B-13: "brief" keeps the first sentence of the reply; the honesty tail is added AFTER trimming, never dropped.
        // ("detailed" no longer adds "That took N steps." — no spoken step numbers, owner 2026-09-28.)
        // [exact]: a reading the user asked for is never cut — "brief" used to keep only "You're in Messages." of a screen.
        val text = resultText?.let { if (verbosity() == "brief" && !exact) SpeechLint.firstSentence(it) else it }?.let { SpeechLint.apply(it) }
        val twi = sessionLanguage() == Lang.TWI
        return when (status) {
            AgentStatus.COMPLETED -> text ?: Closers.done(twi)   // varied, never the same "Done." twice in a row
            // (Honest results 2026-09-29: a send's evidence line that already says "I can't confirm it went" isn't doubted twice.)
            AgentStatus.COMPLETED_UNVERIFIED -> com.tekyerema.agent.SendReport.unverified(text ?: "I tried that")
            AgentStatus.CANNOT_DO -> text ?: "I can't do that."
            // A dead end always says what to do next (micro_details.md: no naked errors).
            AgentStatus.FAILED -> text ?: "That didn't work. You can try again, or say it another way."
            AgentStatus.STOPPED -> if (stopAlreadySaid) "" else "Stopped."   // a voice "no" was already acknowledged
            else -> text ?: Closers.done(twi)
        }
    }

    private fun set(s: VoiceState, hint: String = "") =
        _state.update {
            it.copy(state = s, partialHint = hint, failedStage = VoiceStage.NONE, error = "",
                speechDetected = if (s == VoiceState.LISTENING) it.speechDetected else false,
                livePartial = if (s == VoiceState.LISTENING) it.livePartial else "")
        }
    private fun idle(hint: String) = _state.update { it.copy(state = VoiceState.IDLE, partialHint = hint, speechDetected = false, livePartial = "") }
    /** A cancelled voice turn rests in IDLE — unless the keyboard replaced it, in which case TYPING stands. */
    private fun cancelledToIdle() { if (_state.value.state != VoiceState.TYPING) idle("Cancelled.") }

    /** Log the honest per-layer failure ([msg]) and speak a human line ([spoken]) before resting in ERROR. */
    private suspend fun fail(stage: VoiceStage, msg: String, spoken: String = "Sorry, something went wrong. Tap and try again, or type your request.") {
        Log.w("Tekyerema", "[VOICE] fail stage=$stage: $msg")
        cues.error()
        if (speakAloud()) {
            try { tts.speak(spoken, forceLanguage() ?: sessionLanguage()) }
            catch (c: CancellationException) { throw c } catch (_: Exception) {}
        }
        _state.update { it.copy(state = VoiceState.ERROR, failedStage = stage, error = spoken, partialHint = spoken) }
    }
}
