package com.tekyerema.voice.io

import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider

/**
 * The replaceable voice boundary (docs/agent/research/agenticness_voice_ambiguity.md §A).
 *
 * On API 30 the phone's own recognizer opens the microphone itself and cannot be handed a WAV, so the input seam sits
 * one level up from "capture audio, then transcribe it": [VoiceInput.listen] = "listen once and give me normalized
 * text". The agent core never changes — it only ever receives strings. Two implementations:
 *  - [SystemSpeechInput]: Android's SpeechRecognizer, English (profile `builtin_en`, the testing default);
 *  - [CapturedAudioInput]: today's MicCapture + HybridAsr (on-device Whisper-tiny + the UG HCI Lab ASR), profile `ghana`.
 * Which one runs is decided once, in VoiceEngine.init, by `speech.voiceLayer.profile` ([VoiceLayer]).
 */

/** Why the controller is listening. Each implementation maps it to its own timing: a VadStrategy on the captured path,
 *  recognizer extras plus re-listen loops for the system recognizer. */
enum class ListenPurpose { REQUEST, FOLLOW_UP, CONTINUATION, PATIENT, ANSWER, YES_NO, SECURE_DONE, INTERJECTION }

data class ListenSpec(
    val purpose: ListenPurpose,
    /** The builtin_en profile pins Ghanaian English. */
    val language: String = Lang.GH_EN,
    /** The secure step: no partials, no content logs, and the audio must never leave the phone. */
    val sensitive: Boolean = purpose == ListenPurpose.SECURE_DONE,
    /** n-best wanted (yes/no answers and name slots). */
    val maxAlternatives: Int = if (purpose == ListenPurpose.YES_NO) 5 else 1,
    /** B-09: the user asked for a moment ("wait") — listen patiently. Captured path: the PATIENT VAD; system recognizer:
     *  one more listen when the first hears nothing. (Not in the brief's signature: yes/no needs it after a hold.) */
    val patient: Boolean = purpose == ListenPurpose.PATIENT
)

/** One recognizer hypothesis. [confidence] null = the recognizer gave none (-1 or missing) — unknown, never "low". */
data class Hypothesis(val text: String, val confidence: Float?)

/** The outcome of one [VoiceInput.listen]. */
sealed interface Heard {
    /** [transcript] = LanguagePipeline output (raw kept as heard, canonical normalized + name-corrected).
     *  [alternatives] = the recognizer's n-best, best first (empty on the captured path). */
    data class Speech(val transcript: Transcript, val alternatives: List<Hypothesis> = emptyList()) : Heard
    data object NoSpeech : Heard
    data object TooShort : Heard
    data object Cancelled : Heard
    data class Unavailable(val cause: Cause, val detail: String) : Heard
    enum class Cause { NO_RECOGNIZER, PERMISSION, OFFLINE, BUSY, AUDIO, LANGUAGE, NOT_ALLOWED_HERE, OTHER }
}

/** What happens while listening. UI + cues only: nothing here is ever acted on as the user's words. */
interface ListenEvents {
    /** Listening now — the moment for the listening tone (the mic is open, or about to be for the system recognizer). */
    fun onReady() {}
    /** VAD onset / onBeginningOfSpeech. */
    fun onSpeechStart() {}
    /** Drives the living visual. */
    fun onLevel(level01: Float) {}
    /** A partial transcript: UI only, never acted on, never logged; never called for a sensitive spec. */
    fun onPartial(text: String) {}
    /** The user stopped talking and the words are being recognised (capture ended / onEndOfSpeech). */
    fun onCaptured() {}
}

data class InputCaps(
    /** Audio may be sent off the phone (the system recognizer on API 30: assume true — PREFER_OFFLINE is only a hint). */
    val mayLeavePhone: Boolean,
    val partials: Boolean,
    /** Can transcribe a WAV captured elsewhere (a barge-in interruption). False for SpeechRecognizer on API 30. */
    val transcribesAudio: Boolean,
    val languages: Set<String>,
    /** Can hear "done" at a secure step with the audio kept on the phone (J-15). False ⇒ the controller never listens
     *  there: the user taps Done (or types it). */
    val secureListen: Boolean,
    /** Ends a turn on its own silence strategy (VAD). False ⇒ the recognizer's endpointer decides (its silence extras
     *  are ignored in the field), so patience is built by listening again and joining the parts. */
    val patientEndpointing: Boolean,
    /** The recognizer plays its own start tone: ours would make two. */
    val playsOwnCue: Boolean = false
)

interface VoiceInput {
    val id: String
    val caps: InputCaps
    suspend fun listen(spec: ListenSpec, events: ListenEvents = object : ListenEvents {}): Heard
    /** Transcribe audio captured elsewhere (a barge-in interruption); only when [InputCaps.transcribesAudio]. */
    suspend fun transcribe(wav: ByteArray): Heard =
        Heard.Unavailable(Heard.Cause.OTHER, "$id can't transcribe captured audio")
    /** Stop now and drop the result. */
    fun cancel() {}
    fun release() {}
}

/** Output stays the existing [TtsProvider] (speak / stop / shutdown); aliased so both sides read alike. */
typealias VoiceOutput = TtsProvider

/** B-07 barge-in, split out: noticing that the user started talking is separate from recognising what they said. */
interface BargeInDetector {
    /**
     * Play [speak] while watching the microphone. [onOnset] runs the moment the user starts talking (the caller stops
     * the reply there). With [keepAudio] the whole interruption comes back as a WAV (for an input that transcribes
     * audio); without it the microphone is released at the onset, before this returns, so a recognizer that opens its
     * own microphone can take over — a privacy-sensitive VOICE_COMMUNICATION capture left open would silence it.
     */
    suspend fun speakWatching(speak: suspend () -> Boolean, onOnset: () -> Unit, keepAudio: Boolean): BargeIn
}

/** What happened to one reply spoken with barge-in on. */
class BargeIn(val spokeFully: Boolean, val interrupted: Boolean, val wav: ByteArray?)

/**
 * J-02 yes/no on n-best results: only the TOP hypothesis can answer, and any alternative that means the opposite makes
 * the answer unclear (the question is asked again, then safely declined). An alternative can never turn an unclear
 * answer into a yes. Pure → unit-tested.
 */
object YesNo {
    fun decide(best: String, alternatives: List<Hypothesis>): Confirmation.Answer {
        val top = Confirmation.interpret(best)
        if (top != Confirmation.Answer.YES && top != Confirmation.Answer.NO) return top
        val opposite = if (top == Confirmation.Answer.YES) Confirmation.Answer.NO else Confirmation.Answer.YES
        return if (alternatives.any { Confirmation.interpret(it.text) == opposite }) Confirmation.Answer.UNCLEAR else top
    }
}
