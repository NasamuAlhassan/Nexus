package com.tekyerema.voice

/**
 * Phase 2 voice layer — shared types + provider abstractions.
 *
 * Design (see PHASE2_VOICE_DEVICE_REPORT.md): Android owns the real-time native path
 * (mic capture → VAD → state machine → TTS playback + UI). The heavy ASR + LID + entity-first
 * normalization run in the online voice provider (the same proxy that serves the LLM), reusing the
 * VERIFIED phase2/asr.py + lid.py — the 2.8 GB test phone cannot host whisper-large-v3 / w2v-bert.
 * The provider interfaces keep the controller ignorant of Whisper/w2v-bert/Groq/sherpa details, so a
 * future on-device tiny model can slot in behind [AsrProvider] with no controller change.
 */

/** The two — and only two — supported languages (Phase-2 scope). */
object Lang {
    const val GH_EN = "gh_en"
    const val TWI = "twi"
    const val AUTO = "auto"
    fun label(code: String) = when (code) { GH_EN -> "Ghanaian English"; TWI -> "Twi"; else -> code }
}

/**
 * ASR result. [raw] is the untouched transcript and is NEVER overwritten (§12); [canonical] is the
 * entity-first normalized goal that feeds the agent.
 *
 * §5.5 — [entities] are DIAGNOSTIC / EVALUATION ONLY. Neither LocalBrain nor AgentEngine consumes them,
 * and the confirmation prompt does not render them, so they provide NO runtime protection for a
 * mis-heard name or number — do not treat their presence as a safety guarantee. (Wiring them into
 * BrainContext + confirmation rendering is deferred; until then they exist only for the eval harness.)
 */
data class Transcript(
    val raw: String,
    val canonical: String,
    val language: String,
    val reason: String = "",
    val entities: List<Pair<String, String>> = emptyList(),
    val backend: String = "",
    val fallbackUsed: Boolean = false,
    val asrMs: Long = 0
) {
    val isBlank: Boolean get() = canonical.isBlank()
}

/** Distinguishes an ASR-layer failure so the controller can label the failed stage honestly (§29). */
class AsrException(message: String) : Exception(message)

/** Transcribe captured audio to a canonical goal: the on-device model, or an online provider (the UG HCI Lab API). */
interface AsrProvider {
    /**
     * @param wav a complete 16 kHz mono PCM16 WAV (header + data).
     * @param sessionLanguage the current session language context; used for low-signal LID fallback.
     * @param forceLanguage when non-null, skip LID and transcribe in this language.
     */
    suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String? = null): Transcript
    val name: String
}

/** Captures one microphone utterance (mic + VAD). Abstracted so the controller is unit-testable. */
interface Recorder {
    suspend fun capture(onListening: () -> Unit = {}, onSpeechStart: () -> Unit = {}): CaptureResult
    /** B-02/B-03/B-09: capture with a specific silence strategy (a continuation, patient listening). */
    suspend fun captureWith(strategy: VadStrategy, onListening: () -> Unit = {}, onSpeechStart: () -> Unit = {}): CaptureResult =
        capture(onListening, onSpeechStart)
}

/** Speak text through the phone speaker. Implementations own their own runtime (Android TTS, etc.). */
interface TtsProvider {
    /** Suspends until playback finishes (or fails). Returns true if it actually spoke. */
    suspend fun speak(text: String, language: String): Boolean
    fun stop()
    fun shutdown()
    val name: String
}

/**
 * The voice lifecycle (§26). Transitions are driven by [VoiceController]; ERROR/CANCELLED/
 * CONFIRMATION_REQUIRED are reachable from the active states. No concurrent utterance may run.
 */
enum class VoiceState {
    IDLE, LISTENING, CAPTURED, TRANSCRIBING, UNDERSTANDING, EXECUTING, SPEAKING,
    CONFIRMATION_REQUIRED, ERROR, CANCELLED,
    /** The keyboard is open on the command field; the microphone is not listening. */
    TYPING
}

/** How the text that reached the agent was produced — the diagnostic control for the voice pipeline. */
enum class InputSource { NONE, ASR, TYPED }

/** Sub-phase of CONFIRMATION_REQUIRED while the confirmation runs by voice. */
enum class ConfirmPhase { NONE, ASKING, LISTENING, HEARD }

/** Where a voice attempt failed — for honest per-layer failure reporting (§29). */
enum class VoiceStage {
    MIC, VAD, ASR, LID, NORMALIZATION, AGENT, EXECUTION, VERIFICATION, TTS, NONE
}

/** Observable voice UI state (mirrors the pattern of AgentUiState, kept separate from it). */
data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    val partialHint: String = "",       // e.g. "Listening…", a captured duration, or the transcript
    val lastRaw: String = "",
    val lastCanonical: String = "",
    val language: String = Lang.GH_EN,
    val spoken: String = "",
    val failedStage: VoiceStage = VoiceStage.NONE,
    val error: String = "",
    /** True from the moment the VAD hears the user start talking until the turn moves on. */
    val speechDetected: Boolean = false,
    /** While a confirmation is being asked and answered by voice. */
    val confirmPhase: ConfirmPhase = ConfirmPhase.NONE,
    /** Whether the last text that entered the agent came from ASR or the keyboard. */
    val inputSource: InputSource = InputSource.NONE
)
