package com.tekyerema.voice

import kotlin.math.sqrt

/**
 * Simplest reliable VAD (§6): frame RMS energy with an adaptive noise floor, a short trigger to
 * avoid cutting the start, and a hangover so natural pauses don't end the utterance early. Pure
 * Kotlin (no Android) so it is unit-testable. The caller feeds fixed-size frames and keeps a
 * pre-roll so the leading phoneme isn't lost.
 */
class EnergyVad(
    private val sampleRate: Int = 16000,
    val frameSamples: Int = 320,             // 20 ms @ 16 kHz
    // §P0/§3.1 — thresholds on the RAW mic signal. MEASURED reality on the A20 (audit gained figures ÷12):
    // quiet room ≈ 10–40 RMS, command-speech peaks ≈ 200–280, but the speech AVERAGE frame is only ≈ 40–150
    // (near-0 dB average SNR). Round 2's 150/80 were TOO HIGH — quiet speech rarely cleared them, so onset
    // never confirmed and capture returned NO_SPEECH on real speech (the P0 bug). Lowered so quiet speech
    // triggers; the adaptive noiseFloor×ratio still rejects a noisy room. (Silero is preferred when loaded.)
    private val absStartRms: Double = 60.0,  // silence never triggers even if noiseFloor≈0
    private val absEndRms: Double = 35.0,
    private val startRatio: Double = 2.5,    // must exceed noiseFloor * this (+8 dB) to count as speech
    private val endRatio: Double = 1.6,
    private val startFrames: Int = 3,        // ~60 ms of speech to confirm onset (debounce)
    hangoverMs: Int = 900,                   // §3.6 silence this long ends the utterance (raised from 700 for pause-heavy/elder speech; caller may override per language)
    minSpeechMs: Int = 250,                  // reject blips shorter than this
    maxUtteranceMs: Int = 15000              // §3.5 hard cap (raised from 12 s: GOLD S-rows reach 12.18 s)
) {
    enum class Event { SILENCE, SPEECH_START, SPEECH, SPEECH_END, TOO_SHORT, TIMEOUT }

    private val frameMs = frameSamples * 1000.0 / sampleRate
    private val hangoverFrames = (hangoverMs / frameMs).toInt().coerceAtLeast(1)
    private val minSpeechFrames = (minSpeechMs / frameMs).toInt().coerceAtLeast(1)
    private val maxFrames = (maxUtteranceMs / frameMs).toInt().coerceAtLeast(1)

    private var noiseFloor = 25.0
    private var speaking = false
    private var onsetRun = 0
    private var silenceRun = 0
    private var speechFrames = 0
    private var totalSpeechFrames = 0

    /** B-19: consecutive non-speech frames inside an utterance (the hangover so far); 0 while talking or idle. */
    val pauseFrames: Int get() = if (speaking) silenceRun else 0

    /** Frames of speech seen so far in the current utterance (for duration reporting). */
    val capturedSpeechMs: Long get() = (totalSpeechFrames * frameMs).toLong()

    /** Diagnostics: the adaptive floor and the onset threshold currently in force. */
    val currentNoiseFloor: Double get() = noiseFloor
    val currentStartThreshold: Double get() = maxOf(absStartRms, noiseFloor * startRatio)

    fun reset() {
        noiseFloor = 25.0; speaking = false; onsetRun = 0; silenceRun = 0
        speechFrames = 0; totalSpeechFrames = 0
    }

    /** Feed one frame; returns the transition event. Frame may be shorter than [frameSamples]. */
    fun process(frame: ShortArray): Event {
        val e = rms(frame)
        val startThresh = maxOf(absStartRms, noiseFloor * startRatio)
        val endThresh = maxOf(absEndRms, noiseFloor * endRatio)

        if (!speaking) {
            if (e >= startThresh) {
                onsetRun++
                if (onsetRun >= startFrames) {
                    speaking = true; silenceRun = 0; speechFrames = onsetRun; totalSpeechFrames += onsetRun
                    return Event.SPEECH_START
                }
            } else {
                onsetRun = 0
                // adapt the noise floor only while genuinely idle
                noiseFloor = 0.95 * noiseFloor + 0.05 * e
            }
            return Event.SILENCE
        }

        // speaking
        speechFrames++
        if (speechFrames >= maxFrames) { finishReset(); return Event.TIMEOUT }
        return if (e >= endThresh) {
            silenceRun = 0; totalSpeechFrames++
            Event.SPEECH
        } else {
            silenceRun++
            if (silenceRun >= hangoverFrames) {
                val enough = totalSpeechFrames >= minSpeechFrames
                finishReset()
                if (enough) Event.SPEECH_END else Event.TOO_SHORT   // §3.4 blip below min-speech → distinct, not silent
            } else {
                Event.SPEECH   // inside a natural pause; keep the utterance open
            }
        }
    }

    private fun finishReset() { speaking = false; onsetRun = 0; silenceRun = 0; speechFrames = 0 }

    companion object {
        /** Root-mean-square amplitude of PCM16 samples (0..32767 scale). */
        fun rms(frame: ShortArray): Double {
            if (frame.isEmpty()) return 0.0
            var sum = 0.0
            for (s in frame) { val v = s.toDouble(); sum += v * v }
            return sqrt(sum / frame.size)
        }
    }
}
