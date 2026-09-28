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
    /** A spoken reply to a pending confirmation that is NOT yes/no — a correction or a different
     *  request. The agent re-plans against the pending proposal instead of running it (§8/§9/§13/§14). */
    fun revise(text: String) {}
    /** The user finished entering their MoMo PIN and said "done" — leave Secure Input Mode and resume. */
    fun secureInputDone() {}
}

/** Production bridge → the real [AgentEngine] singleton. */
object AgentEngineBridge : AgentBridge {
    override val state: StateFlow<AgentUiState> get() = AgentEngine.state
    override fun submitGoal(goal: String) = AgentEngine.submitGoal(goal)
    override fun stop() = AgentEngine.stop()
    override fun confirm(approved: Boolean) = AgentEngine.confirm(approved)
    override fun revise(text: String) = AgentEngine.revise(text)
    override fun secureInputDone() = AgentEngine.secureInputDone()
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
    private val systemVoice: TtsProvider? = null
) {
    private val _state = MutableStateFlow(VoiceUiState(language = sessionLanguage()))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var confirmJob: Job? = null
    /** Runs while the agent is in SECURE_INPUT (MoMo PIN): speaks the secure instruction and listens for
     *  "done". Never touches or forwards the PIN. */
    private var secureJob: Job? = null
    /** Set when the user declined by voice, so the turn's closing line isn't spoken twice. */
    @Volatile private var declinedByVoice = false
    /** Consecutive auto follow-up listens in the current exchange, capped so mic noise can't loop forever.
     *  Reset whenever the user deliberately starts a turn (mic tap / typed). */
    private var followUpChain = 0
    private val maxFollowUps = 10

    val isRunning: Boolean get() = job?.isActive == true || confirmJob?.isActive == true

    // The confirmation observer outlives any single turn, so it is not a child of the caller's scope
    // (a test scope would otherwise wait on it forever); shutdown() cancels it.
    private val observerScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    init {
        // Confirmation by voice runs for EVERY pending confirmation — whether the goal came from a
        // voice turn, typed text or ADB — and stops the moment the agent moves on (e.g. a button).
        observerScope.launch {
            var lastPrompt: String? = null
            agent.state.collect { st ->
                when (st.status) {
                    AgentStatus.WAITING_CONFIRMATION -> {
                        if (secureJob?.isActive == true) secureJob?.cancel()
                        val prompt = st.pendingConfirmation ?: "Should I go ahead?"
                        if (confirmJob?.isActive != true && prompt != lastPrompt) {
                            lastPrompt = prompt
                            val twi = st.pendingConfirmationTwi          // §A.1 structured Twi variant, or null
                            confirmJob = observerScope.launch { confirmByVoice(prompt, twi) }
                        }
                    }
                    // Secure Input Mode: the agent went blind for the MoMo PIN. Speak the secure instruction
                    // and listen for "done" — never for the PIN. Runs whatever app is in front (like confirm).
                    AgentStatus.SECURE_INPUT -> {
                        lastPrompt = null
                        if (confirmJob?.isActive == true) confirmJob?.cancel()
                        if (secureJob?.isActive != true) secureJob = observerScope.launch { secureInputByVoice() }
                    }
                    else -> {
                        lastPrompt = null
                        if (confirmJob?.isActive == true) confirmJob?.cancel()
                        if (secureJob?.isActive == true) secureJob?.cancel()
                        if (_state.value.confirmPhase != ConfirmPhase.NONE)
                            _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }
                    }
                }
            }
        }
    }

    /** Mic button: start a fresh utterance, or cancel the running one (§25). */
    fun toggle() { if (isRunning) cancel() else start() }

    fun start() {
        if (isRunning) return
        followUpChain = 0
        job = scope.launch { runOnce() }
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
        if (isRunning) return
        job = scope.launch { runOnce(quiet = true) }
    }

    /**
     * Typed input. The text enters the agent through [runTurn] — the SAME function the ASR
     * transcript goes through — as if ASR had produced exactly this text. It never touches ASR,
     * LID or normalization, which is the point: a typed instruction is the experimental control
     * for the voice pipeline. Returns false (and does nothing) for blank text or while a turn or a
     * confirmation is in progress.
     */
    fun submitText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || isRunning) return false
        followUpChain = 0
        val language = forceLanguage() ?: sessionLanguage()
        job = scope.launch { runTurn(Transcript(raw = t, canonical = t, language = language, reason = "typed"), InputSource.TYPED) }
        return true
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
        val s = agent.state.value.status
        if (s == AgentStatus.THINKING || s == AgentStatus.ACTING || s == AgentStatus.WAITING_CONFIRMATION || s == AgentStatus.SECURE_INPUT) agent.stop()
        tts.stop()
        idle("Cancelled.")
    }

    fun shutdown() { job?.cancel(); confirmJob?.cancel(); secureJob?.cancel(); observerScope.cancel(); tts.shutdown() }

    /**
     * Ask the pending question aloud, listen for a spoken yes/no, and answer the agent.
     * One repeat on an unclear answer; then a safe decline. Buttons remain a parallel path — if
     * the agent moves on for any reason the observer cancels this job.
     */
    private suspend fun confirmByVoice(prompt: String, promptTwi: String? = null) {
        if (!voiceConfirmation() || !speakAloud()) return
        declinedByVoice = false
        cues.needsOk()
        val lang = forceLanguage() ?: sessionLanguage()
        // §A.1 — in a Twi session, ask the confirmation in TWI when we have a structured Twi rendering
        // (name verbatim, number digit-by-digit); otherwise fall back to the English question. The Twi text
        // is spoken in Lang.TWI (Khaya voice if available, else the system voice reads the Twi words — the
        // words are Twi even when the voice is not), never MT'd at runtime.
        val useTwi = lang == Lang.TWI && !promptTwi.isNullOrBlank()
        val askLang = if (useTwi) Lang.TWI else Lang.GH_EN
        try {
            var spokenQuestion = if (useTwi) promptTwi!! else Confirmation.question(prompt)
            repeat(2) { attempt ->
                _state.update { it.copy(confirmPhase = ConfirmPhase.ASKING, speechDetected = false) }
                // English confirmations are spoken in English; a Twi session with a structured Twi line is
                // spoken in Twi (§A.1). The ANSWER may be in either language.
                try { tts.speak(spokenQuestion, askLang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                if (!hasMicPermission()) { _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }; return }

                _state.update { it.copy(confirmPhase = ConfirmPhase.LISTENING, partialHint = prompt) }
                val cap = answerMic.capture(
                    onListening = { cues.listening() },
                    onSpeechStart = { _state.update { it.copy(speechDetected = true) } }
                )
                val answer = when (cap) {
                    is CaptureResult.Success -> {
                        cues.captured()
                        _state.update { it.copy(confirmPhase = ConfirmPhase.HEARD, speechDetected = false) }
                        val tr = try { asr.transcribe(cap.wav, sessionLanguage(), forceLanguage()) }
                        catch (c: CancellationException) { throw c } catch (_: Exception) { null }
                        val heard = tr?.canonical.orEmpty().ifBlank { tr?.raw.orEmpty() }
                        Log.i("Tekyerema", "[VOICE] confirmation heard='${tr?.raw}' → ${Confirmation.interpret(heard)}")
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
                        agent.confirm(true)
                        _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }
                        try { tts.speak(if (lang == Lang.TWI) "Yoo." else "Okay.", lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                        return
                    }
                    Confirmation.Answer.NO -> {
                        declinedByVoice = true
                        agent.confirm(false)
                        _state.update { it.copy(confirmPhase = ConfirmPhase.NONE) }
                        try { tts.speak(if (lang == Lang.TWI) "Yoo, merenyɛ." else "Okay, I won't.", lang) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
                        return
                    }
                    Confirmation.Answer.UNCLEAR -> if (attempt == 0)
                        spokenQuestion = if (useTwi) "Mante no. Ka aane anaa daabi." else Confirmation.REPEAT  // §A.1 Twi repeat
                }
            }
            // Two unclear answers: the safe default is to do nothing, and to say so.
            declinedByVoice = true
            agent.confirm(false)
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

            // A varied, natural acknowledgement ("Go ahead." / "Uh-huh." …) is the listening hint; the
            // earcon plays to completion BEFORE the mic opens so it is never captured as speech.
            val ack = Acknowledgements.next(forceLanguage() ?: sessionLanguage())
            set(VoiceState.LISTENING, ack)
            val cap = mic.capture(
                onListening = { cues.listening(); set(VoiceState.LISTENING, ack) },   // tone once the mic is open
                onSpeechStart = { _state.update { it.copy(state = VoiceState.LISTENING, partialHint = ack, speechDetected = true) } }
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

            set(VoiceState.CAPTURED, "Got it")
            cues.captured()
            set(VoiceState.TRANSCRIBING, "Transcribing…")
            val tr = try {
                asr.transcribe(wav, sessionLanguage(), forceLanguage())
            } catch (c: CancellationException) { throw c
            } catch (e: Exception) { fail(VoiceStage.ASR, e.message ?: "ASR failed", "Sorry, I couldn't understand that. Please try again."); return }
            if (tr.isBlank) { fail(VoiceStage.ASR, "Empty transcript", "Sorry, I didn't catch that. Please try again."); return }
            Log.i("Tekyerema", "[VOICE] raw='${tr.raw}' canonical='${tr.canonical}' lang=${tr.language} " +
                "ASR=${tr.backend}${if (tr.fallbackUsed) "(fallback)" else ""} (${tr.reason})")

            runTurn(tr, InputSource.ASR)
        } catch (c: CancellationException) {
            cancelledToIdle()
        } catch (e: Exception) {
            fail(VoiceStage.NONE, e.message ?: "Voice error", "Sorry, something went wrong.")
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
            fastRouter?.tryHandle(tr.canonical, tr.language)?.let { fast ->
                cues.done()
                set(VoiceState.SPEAKING, fast.spoken)
                // ACTION confirmations ("volume up") speak on the OFFLINE system engine (instant, never blocked
                // by a slow online Twi voice); information QUERIES (time/battery) stay on the normal voice/lang.
                val useSystem = fast.systemVoice && systemVoice != null
                val engine = if (useSystem) systemVoice!! else tts
                val lang = if (useSystem) Lang.GH_EN else tr.language
                val spoke = if (!speakAloud() || fast.spoken.isBlank()) false
                    else try { engine.speak(Verbalize.apply(fast.spoken), lang) }
                    catch (c: CancellationException) { throw c } catch (e: Exception) { false }
                _state.update {
                    it.copy(state = VoiceState.IDLE, spoken = fast.spoken,
                        partialHint = if (spoke || !speakAloud()) "" else "(no ${Lang.label(tr.language)} voice yet)")
                }
                return
            }

            val outcome = runAgent(tr.canonical) ?: return   // null: already set fail/cancel
            when (outcome.status) {
                AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED -> cues.done()
                AgentStatus.FAILED, AgentStatus.CANNOT_DO -> cues.error()
                else -> {}
            }

            set(VoiceState.SPEAKING, outcome.spoken)
            val spoke = if (!speakAloud() || outcome.spoken.isBlank()) false else try { tts.speak(Verbalize.apply(outcome.spoken), tr.language) }
            catch (c: CancellationException) { throw c } catch (e: Exception) { false }
            _state.update {
                it.copy(state = VoiceState.IDLE, spoken = outcome.spoken,
                    partialHint = if (spoke || !speakAloud()) "" else "(no ${Lang.label(tr.language)} voice yet)")
            }
            // Conversational tail (§7/§32): when the agent's spoken reply is a QUESTION (e.g. "I need
            // Prince's phone number. Can you give me their number?"), re-open the mic RIGHT HERE, in this same
            // job, so the user can answer. This does NOT depend on Tɛkyerɛma's screen being in front — the
            // agent often hands off to another app (Contacts, the dialer), which is exactly why a follow-up
            // question used to go unanswered and the turn just died in the background. Silence ends it (quiet
            // runOnce → idle, no further tail); a spoken answer becomes the next turn. Capped so mic noise
            // can't spin forever, and only for spoken (ASR) turns, never a pending confirmation (handled
            // separately by confirmByVoice).
            val isQuestion = outcome.spoken.trim().endsWith("?") &&
                agent.state.value.status != AgentStatus.WAITING_CONFIRMATION && followUpChain < maxFollowUps
            if (isQuestion) {
                followUpChain++
                Log.i("Tekyerema", "[VOICE] agent asked a question — showing the ask card ($source)")
                showAsking(outcome.spoken)         // a VISIBLE card over any app (typed OR spoken), so the user sees it's waiting
                // Reopen the mic only for a SPOKEN turn; a TYPED turn is answered by typing (card stays until then).
                if (source == InputSource.ASR && speakAloud() && hasMicPermission()) {
                    try { runOnce(quiet = true) } finally { hideAsking() }
                }
            }
        } catch (c: CancellationException) {
            idle("Cancelled.")
        } catch (e: Exception) {
            fail(VoiceStage.NONE, e.message ?: "Voice error", "Sorry, something went wrong.")
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
                    else -> {}
                }
            }
        } finally {
            // Always stop the backchannel once the wait ends — success, timeout, OR cancellation — so it can
            // never overlap the spoken outcome (which runTurn speaks right after this returns).
            fillers?.stop()
        }
        if (finalState == null) {
            // §1.21 — the timeout must actually HALT the agent, not just stop us waiting on it. Otherwise
            // the agent's own coroutine keeps running in the background and could still perform (or speak
            // about) a consequential action after we've told the user it timed out.
            agent.stop()
            fail(VoiceStage.EXECUTION, "The assistant took too long to respond.", "Sorry, that took too long. Please try again."); return null
        }
        val resultText = finalState.log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text
        return Outcome(finalState.status, spokenFor(finalState.status, resultText))
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
                    if (s == AgentStatus.WAITING_CONFIRMATION || s == AgentStatus.SECURE_INPUT) waited += tick else worked += tick
                    if (worked >= agentTimeoutMs || waited >= maxUserWait) { out.complete(null); break }
                }
            }
            val result = out.await()
            watcher.cancel(); clock.cancel()
            result
        }

    /** §35: speech must never imply an action happened unless the agent verified it. */
    private fun spokenFor(status: AgentStatus, resultText: String?): String = when (status) {
        AgentStatus.COMPLETED -> resultText ?: "Done."
        AgentStatus.COMPLETED_UNVERIFIED -> (resultText ?: "I tried that") + " — but I could not confirm it."
        AgentStatus.CANNOT_DO -> resultText ?: "I can't do that."
        AgentStatus.FAILED -> resultText ?: "That didn't work."
        AgentStatus.STOPPED -> if (declinedByVoice) "" else "Stopped."   // a voice "no" was already acknowledged
        else -> resultText ?: "Done."
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
