package com.tekyerema.voice

/**
 * The "Nexus" wake word's rules around the keyword spotter (8 Nov 2026, owner: "Hey Nexus", "Nexus", or anything that mentions
 * Nexus). Pure — no audio, no model — so the firing contract is unit-tested (KwsWakeTest).
 *
 * The spotter ([NexusSpotter]) reports a detection on the 80 ms frame where it decodes "nexus" — inside "Hey Nexus", "Okay
 * Nexus", "Nexus, what time is it". [KwsGate] turns that into AT MOST ONE wake per utterance:
 *  - a detection counts only with real sound behind it: the decaying-peak loudness ≥ [WakeGate.minRms] (the same floor the
 *    trained head used: room hum and a distant TV never wake it);
 *  - after a wake, [REFRACTORY_FRAMES] of enforced quiet (~2 s), so "Nexus… Nexus" said in one breath is one wake.
 */
object KwsWake {
    /** speech.json `wake.engine`: "kws" = the Nexus keyword spotter (default); "oww" = the trained "Hey Tɛkyerɛma" head. */
    const val ENGINE_KWS = "kws"
    const val ENGINE_OWW = "oww"
    const val DEFAULT_ENGINE = ENGINE_KWS

    /** The engine to run, from the config value: anything that is not "oww" is the spotter (a typo must not deafen it). */
    fun engine(configured: String?): String =
        if (configured?.trim()?.lowercase() == ENGINE_OWW) ENGINE_OWW else ENGINE_KWS

    /** BPE pieces of the gigaspeech KWS model for the keyword; checked against its tokens.txt when the asset was made. */
    const val NEXUS_TOKENS = "▁NE X US"
    const val PHRASE = "NEXUS"

    /** Spotter defaults (2 Nov spike, kws_v2: threshold 0.05 / boost 2 → 87 % clean, 80 % at 20 dB, 73 % at 10 dB, 0 false
     *  accepts in 2.31 h of Ghanaian speech). `wake.kws.threshold` / `wake.kws.boost` tune them without a rebuild. */
    const val DEFAULT_THRESHOLD = 0.05f
    const val DEFAULT_BOOST = 2.0f

    /** The keywords string for `KeywordSpotter.createStream`: "tokens :boost #threshold @phrase", clamped to sane values. */
    fun keywords(threshold: Float, boost: Float): String {
        val t = threshold.coerceIn(0.01f, 0.9f)
        val b = boost.coerceIn(0.5f, 5f)
        return "$NEXUS_TOKENS :$b #$t @$PHRASE"
    }

    const val REFRACTORY_FRAMES = 25
}

/** One 80 ms frame at a time: the frame's RMS and whether the spotter detected "nexus" in it. True exactly on the wake frame. */
class KwsGate(private val minRms: Float = WakeGate.minRms) {
    private var recentRms = 0f
    private var refractory = 0

    fun reset() { recentRms = 0f; refractory = 0 }

    fun feed(rms: Float, detected: Boolean): Boolean {
        recentRms = maxOf(rms, recentRms * WakeGate.RMS_DECAY)
        if (refractory > 0) { refractory--; return false }
        if (!detected || recentRms < minRms) return false
        refractory = KwsWake.REFRACTORY_FRAMES
        return true
    }
}
