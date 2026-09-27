package com.tekyerema.voice

/**
 * Turns the per-frame wake scores into AT MOST ONE wake per utterance — pure logic (no audio, no models), so
 * the firing contract is unit-tested. Line-for-line the V2 WakeEngine gate that the 2026-09-14 wake matrix
 * measured:
 *  - a frame is a HIT only when the [WakeScorer.FIRE_HEAD] score clears its bar AND there is real sound: a
 *    decaying-peak RMS ≥ [MIN_RMS] (room hum / a distant TV scored 0.98 on the V2 phone while nobody spoke;
 *    the peak decays because the score lags the audio — the frame AT the hit is often the silence after it);
 *  - [TRIGGER_FRAMES] consecutive hits fire once (a one-frame spike never fires; four was too many for a
 *    short phrase), then [REFRACTORY_FRAMES] of enforced silence (~2 s) so one "Hey Tɛkyerɛma" = one wake.
 */
class WakeGate {
    private var above = 0
    private var refractory = 0
    private var recentRms = 0f

    fun reset() { above = 0; refractory = 0; recentRms = 0f }

    /** One 80 ms frame: its raw RMS and the scorer's output. Returns true exactly on the frame that fires. */
    fun feed(rms: Float, scores: Map<String, Float>): Boolean {
        recentRms = maxOf(rms, recentRms * RMS_DECAY)
        if (scores.isEmpty()) return false                 // scorer gated the window (too quiet / not full)
        val bar = WakeScorer.PHRASES[WakeScorer.FIRE_HEAD] ?: 1f
        val hit = (scores[WakeScorer.FIRE_HEAD] ?: 0f) >= bar && recentRms >= MIN_RMS
        if (refractory > 0) { refractory--; above = 0; return false }
        if (!hit) { above = 0; return false }
        if (++above < TRIGGER_FRAMES) return false
        above = 0; refractory = REFRACTORY_FRAMES
        return true
    }

    companion object {
        /** PCM float [-1, 1]; quiet-room noise on the A20e sat well below this. */
        const val MIN_RMS = 0.005f
        const val RMS_DECAY = 0.93f
        const val TRIGGER_FRAMES = 3
        const val REFRACTORY_FRAMES = 25
    }
}
