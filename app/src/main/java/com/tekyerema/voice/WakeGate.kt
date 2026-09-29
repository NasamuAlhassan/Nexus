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
 *
 * A-07 (2026-09-28): the bar is [bar] — `wake.bar`, default [DEFAULT_BAR] = 0.995 (was 0.97). The shipped head's
 * sigmoid saturates: the owner's own commands, Ghanaian-English speakers and a close TV all reached 0.98-1.00, so
 * 0.97 let ordinary speech through. Re-scoring the whole eval_accuracy.py set with the phone's exact scorer
 * (phase2/wakeword/score_heads.py, 3.37 h of non-wake audio + 56 wake clips): 0.97 → 32 false wakes (9.5/h), 40/56
 * woke; 0.995 → 5 false wakes (1.5/h), 38/56 woke (the two lost were the owner's clip at -12 dB and one at 5 dB SNR;
 * unseen voices unchanged at 12/24). Picked on one half of the items, it held on the other (15 → 2 false wakes,
 * no wake lost).
 */
class WakeGate(private val bar: Float = bar()) {
    private var above = 0
    private var refractory = 0
    private var recentRms = 0f
    /** Highest score of the hits in the current run of hits. */
    private var runPeak = 0f
    /** Addressedness (2026-09-29): the peak score of the run that last fired — how clearly it was "Hey Tɛkyerɛma"
     *  ([WakeSignal]: a wake that only just cleared the bar gets its turn gated like a follow-up). */
    var lastFirePeak = 0f
        private set

    fun reset() { above = 0; refractory = 0; recentRms = 0f; runPeak = 0f }

    /** One 80 ms frame: its raw RMS and the scorer's output. Returns true exactly on the frame that fires. */
    fun feed(rms: Float, scores: Map<String, Float>): Boolean {
        recentRms = maxOf(rms, recentRms * RMS_DECAY)
        if (scores.isEmpty()) return false                 // scorer gated the window (too quiet / not full)
        val score = scores[WakeScorer.FIRE_HEAD] ?: 0f
        val hit = score >= bar && recentRms >= MIN_RMS
        if (refractory > 0) { refractory--; above = 0; runPeak = 0f; return false }
        if (!hit) { above = 0; runPeak = 0f; return false }
        runPeak = maxOf(runPeak, score)
        if (++above < TRIGGER_FRAMES) return false
        above = 0; refractory = REFRACTORY_FRAMES
        lastFirePeak = runPeak; runPeak = 0f
        return true
    }

    companion object {
        /** PCM float [-1, 1]; quiet-room noise on the A20e sat well below this. */
        const val MIN_RMS = 0.005f
        const val RMS_DECAY = 0.93f
        const val TRIGGER_FRAMES = 3
        const val REFRACTORY_FRAMES = 25
        /** See the class comment. Tied to the shipped head: a retrained head needs its own bar. */
        val DEFAULT_BAR: Float get() = WakeScorer.PHRASES[WakeScorer.FIRE_HEAD] ?: 0.995f

        /** `wake.bar` (config) or [DEFAULT_BAR]; clamped so a typo can neither disable nor jam the wake word. */
        fun bar(): Float = try {
            com.tekyerema.core.config.AppConfig.double("wake.bar", DEFAULT_BAR.toDouble()).toFloat().coerceIn(0.5f, 0.9999f)
        } catch (_: Throwable) { DEFAULT_BAR }
    }
}
