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
import kotlinx.coroutines.async
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
    private val healthNotice: () -> String? = { null }
) {
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
    /** Consecutive auto follow-up listens in the current exchange, capped so mic noise can't loop forever.
     *  Reset whenever the user deliberately starts a turn (mic tap / typed). */
    private var followUpChain = 0
    private val maxFollowUps = 10

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
                            askJob = launchOnAnswerMic { askByVoice(q) }
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
                val cap = answerMic.captureWith(VadStrategy.QUICK, onListening = { cues.listening() },
                    onSpeechStart = { _state.update { it.copy(speechDetected = true) } })
                val wav = (cap as? CaptureResult.Success)?.wav ?: return@launch
                interjectionHeard = true
                cues.captured()
                val text = try { asr.transcribe(wav, sessionLanguage(), forceLanguage()).canonical } catch (c: CancellationException) { throw c } catch (_: Exception) { "" }
                if (text.isBlank()) return@launch
                val line = applyInterjection(text, lang == Lang.TWI)
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
    private suspend fun askByVoice(question: String) {
        val lang = forceLanguage() ?: sessionLanguage()
        try {
            fillers?.stop()
            if (speakAloud()) try { tts.speak(Verbalize.apply(question), lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
            if (!hasMicPermission()) return   // typed answers still work (submitText)
            repeat(2) { attempt ->
                _state.update { it.copy(state = VoiceState.CONFIRMATION_REQUIRED, partialHint = question) }
                val cap = answerMic.captureWith(VadStrategy.PATIENT, onListening = { cues.listening() },
                    onSpeechStart = { _state.update { it.copy(speechDetected = true) } })
                if (cap is CaptureResult.Cancelled) return
                val wav = (cap as? CaptureResult.Success)?.wav
                if (wav != null) {
                    cues.captured()
                    val text = try { asr.transcribe(wav, sessionLanguage(), forceLanguage()).canonical } catch (c: CancellationException) { throw c } catch (_: Exception) { "" }
                    if (text.isNotBlank()) { agent.answer(text); return }
                }
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
        val st = agent.state.value
        when (st.status) {
            AgentStatus.ASKING -> st.pendingQuestion?.let { q -> if (askJob?.isActive != true) askJob = launchOnAnswerMic { askByVoice(q) } }
            AgentStatus.WAITING_CONFIRMATION -> if (confirmJob?.isActive != true) st.pendingConfirmation?.let { p ->
                confirmJob = launchOnAnswerMic { confirmByVoice(p, st.pendingConfirmationTwi, st.confirmationSeq) } }
            AgentStatus.SECURE_INPUT -> if (secureJob?.isActive != true) secureJob = launchOnAnswerMic { secureInputByVoice() }
            else -> {}
        }
    }

    /**
     * The user asked to talk (mic button, opening Tɛkyerɛma, a trigger). Routed by [NewRequest]: the answer to a waiting
     * prompt, an interjection while a task works, or a fresh turn — cutting our own reply short if it is still being read
     * (it used to do nothing until a 30-second reading ended). Already listening, or working out what was just said: the
     * request is already being heard, so nothing more is started.
     */
    fun start() {
        when (route()) {
            NewRequest.Route.ANSWER -> respond()
            NewRequest.Route.INTERJECT -> interject()
            NewRequest.Route.START -> { followUpChain = 0; launchTurn { runOnce() } }
            NewRequest.Route.PREEMPT -> if (_state.value.state in NewRequest.TALKING_OR_RESTING) {
                Log.i("Tekyerema", "[VOICE] new request while ${_state.value.state} — stopping the reply to listen")
                followUpChain = 0
                launchTurn { runOnce() }
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
        launchTurn { runOnce(quiet = true) }
    }

    /**
     * A conversational follow-up: after Tɛkyerɛma has answered, listen once more so the user can
     * simply keep talking ("okay, now call him", "what about tomorrow?") instead of re-triggering —
     * the cross-turn memory in the agent carries the context. Unlike [start], silence here is NOT an
     * error: if the user says nothing we rest quietly so the screen can close, with no "I didn't hear
     * anything" nag. This is what makes a sequence of turns feel like one conversation (§32), not a
     * string of isolated commands.
     */
    fun followUp() {
        // Never over a running task or a waiting prompt: a new goal would be refused by the busy engine and this turn
        // would then wait on it for the whole task budget.
        if (isRunning || agent.busy || agent.state.value.status in PROMPTS) return
        launchTurn { runOnce(quiet = true) }
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
            AgentStatus.ASKING -> { askJob?.cancel(); hideAsking(); cues.captured(); agent.answer(t); TypedResult(route, accepted = true) }
            AgentStatus.WAITING_CONFIRMATION -> when {
                Confirmation.looksLikeRevision(t) -> { confirmJob?.cancel(); cues.captured(); agent.revise(t); TypedResult(route, accepted = true) }
                else -> when (Confirmation.interpret(t)) {
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

    /**
     * Ask the pending question aloud, listen for a spoken yes/no, and answer the agent.
     * One repeat on an unclear answer; then a safe decline. Buttons remain a parallel path — if
     * the agent moves on for any reason the observer cancels this job.
     */
    private suspend fun confirmByVoice(prompt: String, promptTwi: String? = null, seq: Long = 0L) {
        if (!voiceConfirmation() || !speakAloud()) return
        stopAlreadySaid = false
        cues.needsOk()
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
                try { tts.speak(spokenQuestion, askLang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                if (!hasMicPermission()) { _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }; return }

                _state.update { it.copy(confirmPhase = ConfirmPhase.LISTENING, partialHint = prompt) }
                val onSpeech = { _state.update { it.copy(speechDetected = true) } }
                val cap = if (patient) answerMic.captureWith(VadStrategy.PATIENT, onListening = { cues.listening() }, onSpeechStart = onSpeech)
                    else answerMic.capture(onListening = { cues.listening() }, onSpeechStart = onSpeech)
                val answer = when (cap) {
                    is CaptureResult.Success -> {
                        cues.captured()
                        _state.update { it.copy(confirmPhase = ConfirmPhase.HEARD, speechDetected = false) }
                        val tr = try { asr.transcribe(cap.wav, sessionLanguage(), forceLanguage()) }
                        catch (c: CancellationException) { throw c } catch (_: Exception) { null }
                        val heard = tr?.canonical.orEmpty().ifBlank { tr?.raw.orEmpty() }
                        Log.i("Tekyerema", "[VOICE] confirmation heard='${com.tekyerema.core.Privacy.content(tr?.raw)}' → ${Confirmation.interpret(heard)}")   // J-15
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
                        Confirmation.interpret(heard)
                    }
                    CaptureResult.Cancelled -> throw CancellationException("confirmation cancelled")
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
        try {
            var attempt = 0
            while (attempt < 8) {
                _state.update { it.copy(state = VoiceState.CONFIRMATION_REQUIRED, partialHint = if (lang == Lang.TWI) "Hyɛ mu, na ka 'awie'" else "Finish it yourself, then say 'done'") }
                try { tts.speak(if (attempt == 0) instruction else remind, lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                if (!hasMicPermission()) return    // can't listen; stay secure (user completes on the phone, taps to cancel/retry)
                val cap = answerMic.capture(onListening = { cues.listening() }, onSpeechStart = { _state.update { it.copy(speechDetected = true) } })
                when (cap) {
                    is CaptureResult.Success -> {
                        val tr = try { asr.transcribe(cap.wav, sessionLanguage(), forceLanguage()) }
                        catch (c: CancellationException) { throw c } catch (_: Exception) { null }
                        val heard = tr?.canonical.orEmpty().ifBlank { tr?.raw.orEmpty() }
                        // SECURITY: never log `heard` (it is user speech during a secure step). Log only the decision.
                        if (Confirmation.isCompletion(heard)) {
                            Log.i("Tekyerema", "[VOICE] secure step complete (user said done) → asking agent to resume")
                            _state.update { it.copy(state = VoiceState.IDLE, speechDetected = false) }
                            agent.secureInputDone()
                            return
                        }
                        // Not a completion word → gently re-prompt (do NOT treat anything else as the PIN).
                    }
                    CaptureResult.Cancelled -> throw CancellationException("secure input cancelled")
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
        val cap = mic.captureWith(strategy, onListening = { cues.listening() },
            onSpeechStart = { _state.update { it.copy(speechDetected = true) } })
        val wav = (cap as? CaptureResult.Success)?.wav ?: run {
            if (cap is CaptureResult.Cancelled) throw CancellationException("cancelled while listening")
            return null
        }
        cues.captured()
        val t = try { asr.transcribe(wav, sessionLanguage(), forceLanguage()) }
            catch (c: CancellationException) { throw c } catch (_: Exception) { null }
        return t?.takeUnless { it.isBlank }
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

    private suspend fun runOnce(quiet: Boolean = false) {
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
            val cap = mic.capture(
                onListening = { cues.listening(); set(VoiceState.LISTENING, ack) },   // tone once the mic is open
                onSpeechStart = {
                    com.tekyerema.core.telemetry.Telemetry.mark("speech_start")   // B-19: speech → end_of_turn is the endpoint wait
                    _state.update { it.copy(state = VoiceState.LISTENING, partialHint = ack, speechDetected = true) }
                }
            )
            val wav = when (cap) {
                is CaptureResult.Success -> cap.wav
                // In a follow-up, silence just ends the conversation quietly (the screen then closes);
                // on a deliberate tap-to-talk it's worth telling the user we heard nothing.
                CaptureResult.NoSpeech -> { if (quiet) idle("") else sayThenIdle("I didn't hear anything. Tap and try again."); return }
                CaptureResult.TooShort -> { if (quiet) idle("") else sayThenIdle("That was too short. Tap and try again."); return }
                CaptureResult.Cancelled -> { cancelledToIdle(); return }
                is CaptureResult.Error -> { if (quiet) { idle(""); return }; fail(VoiceStage.MIC, cap.message, "I can't use the microphone right now."); return }
            }

            com.tekyerema.core.telemetry.Telemetry.mark("end_of_turn")
            set(VoiceState.CAPTURED, "Got it")
            cues.captured()
            set(VoiceState.TRANSCRIBING, "Transcribing…")
            var tr = try {
                asr.transcribe(wav, sessionLanguage(), forceLanguage()).also { com.tekyerema.core.telemetry.Telemetry.mark("asr_final") }
            } catch (c: CancellationException) { throw c
            } catch (e: Exception) { fail(VoiceStage.ASR, e.message ?: "ASR failed", "Sorry, I couldn't understand that. Try again, a little closer to the phone."); return }
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
            // J-15: what the user said stays out of logcat in release builds.
            if (com.tekyerema.core.Privacy.logContent())
                Log.i("Tekyerema", "[VOICE] raw='${tr.raw}' canonical='${tr.canonical}' lang=${tr.language} " +
                    "ASR=${tr.backend}${if (tr.fallbackUsed) "(fallback)" else ""} (${tr.reason})")
            else Log.i("Tekyerema", "[VOICE] len=${tr.canonical.length} lang=${tr.language} ASR=${tr.backend}${if (tr.fallbackUsed) "(fallback)" else ""}")

            runTurn(tr, InputSource.ASR)
        } catch (c: CancellationException) {
            cancelledToIdle()
        } catch (e: Exception) {
            fail(VoiceStage.NONE, e.message ?: "Voice error", "Sorry, something went wrong.")
        } finally {
            // A turn that never reached an answer (no speech, cancelled, an error) is not a measurement — drop it.
            com.tekyerema.core.telemetry.Telemetry.discard(byVoice = true)
        }
    }

    /**
     * THE canonical agent input. Recognized text — from ASR or the keyboard — enters the agent
     * here and nowhere else: session language, UNDERSTANDING, the agent run (planner → tools →
     * confirmation gate), then the spoken outcome. [source] is logged so a trace can tell the two
     * apart; it changes nothing about what the agent receives.
     */
    private suspend fun runTurn(tr: Transcript, source: InputSource) {
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

            // LOCAL FAST PATH (demo-critical): deterministic device ops execute HERE, before the agent/LLM —
            // no reasoning, no screen read, no app launch merely to answer (never open Clock to tell the time).
            // Returns null for anything it can't confidently handle → the agent path below runs unchanged.
            val tel = com.tekyerema.core.telemetry.Telemetry
            tel.ensureVoiceTurn(if (source == InputSource.ASR) "voice" else "typed")
            tel.update { goal = tr.canonical }
            fastRouter?.tryHandle(tr.canonical, tr.language)?.let { fast ->
                tel.mark("route"); tel.update { tier = com.tekyerema.agent.RouterTiers.FAST_PATH }   // Q-01 / Q-02
                if (fast.ok) cues.done() else cues.error()   // K-03: a failure line never plays the "done" sound
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
                return
            }

            tel.mark("route")
            val outcome = runAgent(tr.canonical) ?: return   // null: already set fail/cancel
            when (outcome.status) {
                AgentStatus.COMPLETED -> cues.done()
                AgentStatus.COMPLETED_UNVERIFIED -> cues.unverified()   // K-03: never the "done" sound without evidence
                AgentStatus.FAILED, AgentStatus.CANNOT_DO -> cues.error()
                else -> {}
            }

            set(VoiceState.SPEAKING, outcome.spoken)
            var interruption: ByteArray? = null
            tel.mark("reply_start")
            val spoke = if (!speakAloud() || outcome.spoken.isBlank()) false else try {
                val (ok, barged) = speakInterruptible(Verbalize.apply(outcome.spoken), tr.language)
                interruption = barged
                ok || barged != null
            } catch (c: CancellationException) { throw c } catch (e: Exception) { false }
            tel.endTurn(com.tekyerema.agent.AgentEngine.gradeOf(outcome.status), byVoice = true)   // before any follow-up turn
            _state.update {
                it.copy(state = VoiceState.IDLE, spoken = outcome.spoken, spokenAt = System.currentTimeMillis(),
                    partialHint = if (spoke || !speakAloud()) "" else "(no ${Lang.label(tr.language)} voice yet)")
            }
            // B-07: the user spoke over the reply — what they said is the next turn.
            interruption?.let { wav ->
                Log.i("Tekyerema", "[VOICE] barge-in: reply interrupted — handling what the user said")
                set(VoiceState.TRANSCRIBING, "Transcribing…")
                val next = try { asr.transcribe(wav, sessionLanguage(), forceLanguage()) }
                    catch (c: CancellationException) { throw c } catch (_: Exception) { null }
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
                    try { runOnce(quiet = true) } finally { if (!invitesMore) hideAsking() }
                }
            }
        } catch (c: CancellationException) {
            idle("Cancelled.")
        } catch (e: Exception) {
            fail(VoiceStage.NONE, e.message ?: "Voice error", "Sorry, something went wrong.")
        } finally {
            com.tekyerema.core.telemetry.Telemetry.discard(byVoice = true)   // an unfinished turn measured nothing
        }
    }

    /**
     * B-07 — speak [text] while an echo-cancelled mic listens; if the user starts talking the reply stops and their
     * words are returned as the next utterance. Only for replies long enough to be worth interrupting, never for a
     * confirmation read-back (an early "yes" must not count). Returns (spoke fully, interrupting audio or null).
     */
    private suspend fun speakInterruptible(text: String, lang: String): Pair<Boolean, ByteArray?> {
        val cfg = com.tekyerema.core.config.AppConfig
        val barge = bargeMic
        if (barge == null || !bargeInEnabled() || !hasMicPermission() || text.length < cfg.int("speech.bargeIn.minChars", 60))
            return tts.speak(text, lang) to null
        // The reply on the voice-communication path, so the echo canceller has our own voice as its reference (the A20e's
        // AEC let media-stream TTS straight through). Entered BEFORE the mic opens and the reply starts: both must be on it.
        val duplex = duplexBegin()
        try {
            return kotlinx.coroutines.coroutineScope {
                val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
                val listen = async {
                    barge.captureWith(VadStrategy.NORMAL, onListening = {}, onSpeechStart = { interrupted.set(true); tts.stop() })
                }
                val ok = try { tts.speak(text, lang) } catch (c: CancellationException) { listen.cancel(); throw c }
                if (!interrupted.get()) { listen.cancel(); return@coroutineScope ok to null }
                val cap = listen.await()
                false to (cap as? CaptureResult.Success)?.wav
            }
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
        val progressJob = if (speakAloud()) observerScope.launch { progressLoop(goal) } else null   // K-02
        val finalState: AgentUiState? = try {
            awaitAgent(goal, terminal) { st ->
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
    private suspend fun awaitAgent(goal: String, terminal: Set<AgentStatus>, onState: suspend (AgentUiState) -> Unit): AgentUiState? =
        kotlinx.coroutines.coroutineScope {
            val tick = 250L
            val maxUserWait = com.tekyerema.core.config.AppConfig.long("voice.agent.maxUserWaitMs", 600_000L)
            val out = kotlinx.coroutines.CompletableDeferred<AgentUiState?>()
            val watcher = launch {
                agent.state.collect { st ->
                    onState(st)
                    if (st.status in terminal && st.goal == goal) out.complete(st)
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
            if (st.goal != goal) return
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

    /** §35: speech must never imply an action happened unless the agent verified it. */
    private fun spokenFor(status: AgentStatus, resultText: String?, exact: Boolean = false): String {
        // B-13: "brief" keeps the first sentence of the reply; the honesty tail is added AFTER trimming, never dropped.
        // ("detailed" no longer adds "That took N steps." — no spoken step numbers, owner 2026-09-28.)
        // [exact]: a reading the user asked for is never cut — "brief" used to keep only "You're in Messages." of a screen.
        val text = resultText?.let { if (verbosity() == "brief" && !exact) SpeechLint.firstSentence(it) else it }?.let { SpeechLint.apply(it) }
        return when (status) {
            AgentStatus.COMPLETED -> text ?: "Done."
            AgentStatus.COMPLETED_UNVERIFIED -> (text ?: "I tried that") + " — but I could not confirm it."
            AgentStatus.CANNOT_DO -> text ?: "I can't do that."
            AgentStatus.FAILED -> text ?: "That didn't work."
            AgentStatus.STOPPED -> if (stopAlreadySaid) "" else "Stopped."   // a voice "no" was already acknowledged
            else -> text ?: "Done."
        }
    }

    private fun set(s: VoiceState, hint: String = "") =
        _state.update {
            it.copy(state = s, partialHint = hint, failedStage = VoiceStage.NONE, error = "",
                speechDetected = if (s == VoiceState.LISTENING) it.speechDetected else false)
        }
    private fun idle(hint: String) = _state.update { it.copy(state = VoiceState.IDLE, partialHint = hint, speechDetected = false) }
    /** A cancelled voice turn rests in IDLE — unless the keyboard replaced it, in which case TYPING stands. */
    private fun cancelledToIdle() { if (_state.value.state != VoiceState.TYPING) idle("Cancelled.") }

    /** Log the honest per-layer failure ([msg]) and speak a human line ([spoken]) before resting in ERROR. */
    private suspend fun fail(stage: VoiceStage, msg: String, spoken: String = "Sorry, something went wrong.") {
        Log.w("Tekyerema", "[VOICE] fail stage=$stage: $msg")
        cues.error()
        if (speakAloud()) {
            try { tts.speak(spoken, forceLanguage() ?: sessionLanguage()) }
            catch (c: CancellationException) { throw c } catch (_: Exception) {}
        }
        _state.update { it.copy(state = VoiceState.ERROR, failedStage = stage, error = spoken, partialHint = spoken) }
    }
}
