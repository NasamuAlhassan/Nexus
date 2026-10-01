package com.tekyerema.voice.io

import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.VadStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Profile `ghana` — today's stack behind the [VoiceInput] boundary, with NO behaviour change: our own microphone +
 * VAD ([Recorder] = MicCapture) produce a WAV, and [AsrProvider] (HybridAsr: on-device Whisper-tiny, the UG HCI Lab
 * ASR online) turns it into a normalized [com.tekyerema.voice.Transcript]. Each purpose maps to exactly the mic and
 * silence strategy the controller used before the boundary existed:
 *
 *  REQUEST / FOLLOW_UP → mic (its default strategy)    CONTINUATION → mic CONTINUE     PATIENT → mic PATIENT
 *  ANSWER → answerMic PATIENT    YES_NO → answerMic (PATIENT after a hold)    SECURE_DONE → answerMic
 *  INTERJECTION → answerMic QUICK
 *
 * The secure step stays on the phone: HybridAsr recognises only on-device while Privacy.secureStep is set.
 */
class CapturedAudioInput(
    private val mic: Recorder,
    private val answerMic: Recorder,
    private val asr: AsrProvider,
    private val sessionLanguage: () -> String,
    private val forceLanguage: () -> String?
) : VoiceInput {
    override val id = "captured(${asr.name})"
    override val caps = InputCaps(
        mayLeavePhone = true,          // an online ASR may be configured — but never at the secure step (HybridAsr)
        partials = false,
        transcribesAudio = true,
        languages = setOf(Lang.GH_EN, Lang.TWI),
        secureListen = true,
        patientEndpointing = true
    )

    /** Which recorder and silence strategy [spec] uses (null strategy = the recorder's own default). */
    internal fun route(spec: ListenSpec): Pair<Recorder, VadStrategy?> = when (spec.purpose) {
        ListenPurpose.REQUEST, ListenPurpose.FOLLOW_UP -> mic to null
        ListenPurpose.CONTINUATION -> mic to VadStrategy.CONTINUE
        ListenPurpose.PATIENT -> mic to VadStrategy.PATIENT
        ListenPurpose.ANSWER -> answerMic to VadStrategy.PATIENT
        ListenPurpose.YES_NO -> answerMic to (if (spec.patient) VadStrategy.PATIENT else null)
        ListenPurpose.SECURE_DONE -> answerMic to null
        ListenPurpose.INTERJECTION -> answerMic to VadStrategy.QUICK
    }

    override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard {
        val (recorder, strategy) = route(spec)
        val onReady = { events.onReady() }
        val onSpeech = { events.onSpeechStart() }
        val cap = if (strategy == null) recorder.capture(onReady, onSpeech) else recorder.captureWith(strategy, onReady, onSpeech)
        return when (cap) {
            is CaptureResult.Success -> { events.onCaptured(); transcribe(cap.wav) }
            CaptureResult.NoSpeech -> Heard.NoSpeech
            CaptureResult.TooShort -> Heard.TooShort
            CaptureResult.Cancelled -> Heard.Cancelled
            is CaptureResult.Error -> Heard.Unavailable(Heard.Cause.AUDIO, cap.message)
        }
    }

    override suspend fun transcribe(wav: ByteArray): Heard = try {
        Heard.Speech(asr.transcribe(wav, sessionLanguage(), forceLanguage()))
    } catch (c: CancellationException) { throw c
    } catch (e: com.tekyerema.voice.AsrLowMemoryException) {
        // The offline model was NOT loaded (loading it would have got the app killed): a typed, speakable outcome.
        Heard.Unavailable(Heard.Cause.LOW_MEMORY, e.message ?: com.tekyerema.voice.AsrMemory.SPOKEN)
    } catch (e: Exception) { Heard.Unavailable(Heard.Cause.OTHER, e.message ?: "ASR failed") }
}

/**
 * B-07 onset detection on our own echo-cancelled microphone (MicCapture on VOICE_COMMUNICATION + AEC, 200 ms onset).
 * Both profiles use it; only what follows the onset differs ([BargeInDetector.speakWatching] keepAudio).
 */
class RecorderBargeIn(private val recorder: Recorder) : BargeInDetector {
    override suspend fun speakWatching(speak: suspend () -> Boolean, onOnset: () -> Unit, keepAudio: Boolean): BargeIn = coroutineScope {
        val interrupted = AtomicBoolean(false)
        val listen = async {
            recorder.captureWith(VadStrategy.NORMAL, onListening = {}, onSpeechStart = { interrupted.set(true); onOnset() })
        }
        val ok = try { speak() } catch (c: CancellationException) { listen.cancel(); throw c }
        if (!interrupted.get()) { listen.cancel(); return@coroutineScope BargeIn(ok, interrupted = false, wav = null) }
        if (!keepAudio) {
            // The recognizer opens its own microphone next: this capture must have let go of the AudioRecord first.
            listen.cancelAndJoin()
            return@coroutineScope BargeIn(false, interrupted = true, wav = null)
        }
        val cap = listen.await()
        BargeIn(false, interrupted = true, wav = (cap as? CaptureResult.Success)?.wav)
    }
}
