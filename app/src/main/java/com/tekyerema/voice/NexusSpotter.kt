package com.tekyerema.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * The "Nexus" wake word (owner, 8 Nov 2026): sherpa-onnx's open-vocabulary keyword spotter, already in the app's AAR, with the
 * keyword given as text — no training. Model: k2-fsa's sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 (Apache-2.0),
 * int8, in assets/wakeword/kws/. It listens to the same 16 kHz float frames as the trained head ([WakeScorer.FRAME]) and spots
 * "nexus" anywhere in them, so "Nexus", "Hey Nexus" and "Okay Nexus, …" all wake it. Rules around it: [KwsGate].
 */
class NexusSpotter(private val ctx: Context) {
    private var kws: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var keywords = ""
    private var frames = 0L; private var msSum = 0L; private var msMax = 0L

    fun load(): Boolean = try {
        val cfg = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = WakeScorer.SR, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = "$DIR/$ENCODER", decoder = "$DIR/$DECODER", joiner = "$DIR/$JOINER"),
                tokens = "$DIR/tokens.txt", numThreads = 1, provider = "cpu", modelType = "zipformer2"),
            maxActivePaths = 4,
            keywordsFile = "$DIR/keywords.txt",
            keywordsScore = boost(), keywordsThreshold = threshold(),
            numTrailingBlanks = 1)
        kws = KeywordSpotter(ctx.assets, cfg)
        keywords = KwsWake.keywords(threshold(), boost())
        reset()
        Log.i(TAG, "[WAKE] keyword spotter ready — '$keywords'")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "[WAKE] keyword spotter failed to load: ${t.message}")
        close(); false
    }

    /** A fresh stream: no audio from before a pause (the caller resets when it reopens the mic). */
    fun reset() {
        val k = kws ?: return
        try { stream?.release() } catch (_: Throwable) {}
        stream = k.createStream(keywords)
    }

    /** One frame of 16 kHz float PCM. True when "nexus" was decoded in it. */
    fun feed(frame: FloatArray): Boolean {
        val k = kws ?: return false
        val s = stream ?: return false
        val t0 = android.os.SystemClock.uptimeMillis()
        s.acceptWaveform(frame, WakeScorer.SR)
        var hit = false
        while (k.isReady(s)) {
            k.decode(s)
            if (k.getResult(s).keyword.isNotBlank()) { hit = true; k.reset(s) }
        }
        val ms = android.os.SystemClock.uptimeMillis() - t0
        frames++; msSum += ms; if (ms > msMax) msMax = ms
        return hit
    }

    /** frames fed, ms per frame avg/max since the last call (heartbeat; must keep up with 80 ms frames). */
    fun takeStats(): LongArray {
        val out = longArrayOf(frames, if (frames == 0L) 0 else msSum / frames, msMax)
        frames = 0; msSum = 0; msMax = 0
        return out
    }

    fun close() {
        try { stream?.release() } catch (_: Throwable) {}
        try { kws?.release() } catch (_: Throwable) {}
        stream = null; kws = null
    }

    companion object {
        private const val TAG = "Tekyerema"
        const val DIR = "wakeword/kws"
        const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"

        fun threshold(): Float = try {
            com.tekyerema.core.config.AppConfig.double("wake.kws.threshold", KwsWake.DEFAULT_THRESHOLD.toDouble()).toFloat()
        } catch (_: Throwable) { KwsWake.DEFAULT_THRESHOLD }

        fun boost(): Float = try {
            com.tekyerema.core.config.AppConfig.double("wake.kws.boost", KwsWake.DEFAULT_BOOST.toDouble()).toFloat()
        } catch (_: Throwable) { KwsWake.DEFAULT_BOOST }

        /** speech.json wake.engine → [KwsWake.engine]. */
        fun engine(): String = try {
            KwsWake.engine(com.tekyerema.core.config.AppConfig.string("wake.engine", KwsWake.DEFAULT_ENGINE))
        } catch (_: Throwable) { KwsWake.DEFAULT_ENGINE }
    }
}
