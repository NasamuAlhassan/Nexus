package com.tekyerema.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * §P0 — neural voice-activity detection (Silero, bundled `assets/vad/silero_vad.onnx`, MIT). The A20's raw
 * mic has a near-0 dB average SNR (audit: speech avg ≈ noise avg; the distinction is in the peaks), which a
 * mean-energy threshold cannot separate reliably — that is why the energy VAD kept firing on noise OR
 * missing quiet speech. Silero is trained for exactly this and works on the RAW signal.
 *
 * Emits the SAME [EnergyVad.Event] sequence as the energy VAD, so [MicCapture] can swap segmenters with no
 * other change. Onset = Silero reports speech; end = Silero reports non-speech for the hangover (Silero's own
 * minSilenceDuration already debounces, so we keep the extra hangover short). Falls back cleanly: if the model
 * can't load, [ready] is false and MicCapture uses the energy VAD.
 */
class SileroVad(
    appContext: Context,
    private val sampleRate: Int = 16000,
    val frameSamples: Int = 320,
    startFrames: Int = 2,        // ~40 ms of detected speech to confirm onset
    hangoverMs: Int = 400,       // end after this much non-speech (Silero already did 300 ms internally)
    minSpeechMs: Int = 200,
    maxUtteranceMs: Int = 15000
) {
    private val frameMs = frameSamples * 1000.0 / sampleRate
    private var hangoverFrames = (hangoverMs / frameMs).toInt().coerceAtLeast(1)
    private val minSpeechFrames = (minSpeechMs / frameMs).toInt().coerceAtLeast(1)
    private var maxFrames = (maxUtteranceMs / frameMs).toInt().coerceAtLeast(1)

    /** B-03: apply a [VadStrategy]'s end-of-turn silence and utterance cap before a capture. */
    fun configure(hangoverMs: Int, maxUtteranceMs: Int) {
        hangoverFrames = (hangoverMs / frameMs).toInt().coerceAtLeast(1)
        maxFrames = (maxUtteranceMs / frameMs).toInt().coerceAtLeast(1)
    }
    private val startFramesN = startFrames

    private var vad: Vad? = null
    val ready: Boolean get() = vad != null

    private var speaking = false
    private var onsetRun = 0
    private var silenceRun = 0
    private var frames = 0
    private var totalSpeech = 0

    init {
        vad = try {
            val cfg = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "vad/silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.20f,
                    windowSize = 512,
                    maxSpeechDuration = maxOf(maxUtteranceMs, 30_000) / 1000f   // our own cap (configure) ends turns
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            Vad(assetManager = appContext.assets, config = cfg).also {
                Log.i("Tekyerema", "[VAD] Silero VAD loaded (assets/vad/silero_vad.onnx)")
            }
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[VAD] Silero unavailable, falling back to energy VAD: ${t.message}"); null
        }
    }

    fun reset() {
        try { vad?.reset() } catch (_: Throwable) {}
        speaking = false; onsetRun = 0; silenceRun = 0; frames = 0; totalSpeech = 0
    }

    val capturedSpeechMs: Long get() = (totalSpeech * frameMs).toLong()

    /** B-19: consecutive non-speech frames inside an utterance (our hangover so far — Silero's own 300 ms of
     *  minSilence has already passed by the time it counts); 0 while talking or idle. */
    val pauseFrames: Int get() = if (speaking) silenceRun else 0

    /** Feed one raw PCM16 frame; return the transition event (mirrors EnergyVad). */
    fun process(frame: ShortArray): EnergyVad.Event {
        val v = vad ?: return EnergyVad.Event.SILENCE
        val f = FloatArray(frame.size) { frame[it] / 32768f }
        try { v.acceptWaveform(f) } catch (_: Throwable) {}
        val isSpeech = try { v.isSpeechDetected() } catch (_: Throwable) { false }
        // drain any completed segments so the queue doesn't grow (we time onset/offset ourselves)
        try { while (!v.empty()) v.pop() } catch (_: Throwable) {}
        frames++

        if (!speaking) {
            return if (isSpeech) {
                onsetRun++
                if (onsetRun >= startFramesN) { speaking = true; silenceRun = 0; totalSpeech = onsetRun; EnergyVad.Event.SPEECH_START }
                else EnergyVad.Event.SILENCE
            } else { onsetRun = 0; EnergyVad.Event.SILENCE }
        }
        if (frames >= maxFrames) { finish(); return EnergyVad.Event.TIMEOUT }
        return if (isSpeech) {
            silenceRun = 0; totalSpeech++; EnergyVad.Event.SPEECH
        } else {
            silenceRun++
            if (silenceRun >= hangoverFrames) {
                val enough = totalSpeech >= minSpeechFrames
                finish()
                if (enough) EnergyVad.Event.SPEECH_END else EnergyVad.Event.TOO_SHORT
            } else EnergyVad.Event.SPEECH
        }
    }

    private fun finish() { speaking = false; onsetRun = 0; silenceRun = 0; try { vad?.flush() } catch (_: Throwable) {} }

    fun release() { try { vad?.release() } catch (_: Throwable) {}; vad = null }
}
