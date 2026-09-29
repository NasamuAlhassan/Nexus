package com.tekyerema.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * On-device "Hey Tɛkyerɛma" scorer — a faithful port of the V2 app's WakeScorer (itself a port of the proven
 * Windows PathAScorer, LLM/V2/wake/listener.py). It runs openWakeWord's frozen mel + embedding ONNX features,
 * then OUR trained per-phrase heads, over a rolling 2.7 s buffer. Same constants, same raw-energy gate, same
 * peak normalisation, same per-phrase bars — if any of these drift from the training kernel the heads see
 * off-manifold features and score garbage, so change them only together with a retrain.
 *
 * Models ride in assets/wakeword/ (offline, ~3 MB). One [feed] = one 80 ms frame in, {phrase: prob} out.
 */
class WakeScorer(private val ctx: Context) {
    companion object {
        const val SR = 16000
        const val FRAME = 1280               // 80 ms at 16 kHz
        const val MEL_WIN = 76
        const val MEL_STRIDE = 8
        const val CLF_WIN = 16
        const val RAW_PEAK_FLOOR = 0.012f
        /** openWakeWord mel framing: a 10 ms hop at 16 kHz (each frame spans the model's FFT size). */
        const val HOP = 160
        /** Extra hops of audio on an incremental tail so it yields at least [MEL_WIN] mel frames (FFT-size framing). */
        const val TAIL_MARGIN_FRAMES = 4
        /** Default scale hysteresis (dB) — set from the offline A/B on the wake clip sets. */
        const val DEFAULT_TOLERANCE_DB = 2.0
        val BUF = (SR * 2.7).toInt()          // 2.7 s rolling buffer
        /** Per-phrase bars. Heads retrained 2026-09-13 with the owner's recorded wake clips (and commands as
         *  negatives). Wake matrix 2026-09-14: "hey" head at 0.97 fired 14/16 live-condition wakes, 1/270
         *  command clips, 0 on silence/noise. */
        /*  A-07 (2026-09-28): the "hey" bar is now 0.995 — see [WakeGate.DEFAULT_BAR] for the measurement. */
        val PHRASES = linkedMapOf("hey_tekyerema" to 0.995f, "tekyerema" to 0.90f)
        /** The only head allowed to fire: the bare head scored 0.93–0.94 on plain white/fan noise and fired
         *  2–5×/30 s; the "hey" head stays at 0.66–0.70 on the same noise. The bare head is logged only. */
        const val FIRE_HEAD = "hey_tekyerema"
        private const val DIR = "wakeword"
    }

    private var env: OrtEnvironment? = null
    private var mel: OrtSession? = null
    private var emb: OrtSession? = null
    private val heads = LinkedHashMap<String, OrtSession>()
    private val buf = FloatArray(BUF)

    // ---- Incremental scoring (2026-09-28, Galaxy A20e): the full recompute — a mel over the whole 2.7 s plus 16
    // embedding runs EVERY 80 ms frame — ran ~4-6x slower than real time on that phone, so audio was dropped and the
    // phrase reached the heads chopped ("G.", "Gyaa"). The features only depend on the audio and the normalisation
    // scale (0.5 / peak), so while the scale is unchanged the 15 older embeddings are reused and only the newest is
    // computed from a short, frame-aligned tail. When the peak changes, everything is recomputed as before. A self-check
    // at load compares the two paths; any mismatch keeps the full recompute (features must never drift from training).
    private var streamPos = 0L                      // absolute samples fed so far
    private var cacheScale = Float.NaN
    private val embCache = HashMap<Long, FloatArray>()   // absolute sample position of a window's end → embedding
    @Volatile var incremental = false; private set
    /** `wake.scaleToleranceDb`: how far the normalisation scale may drift before a full recompute (0 = exact). */
    private val toleranceDb: Double by lazy { com.tekyerema.core.config.AppConfig.double("wake.scaleToleranceDb", DEFAULT_TOLERANCE_DB) }
    /** Timing for the heartbeat: frames scored and total ms since the last read. */
    @Volatile private var statFrames = 0
    @Volatile private var statMs = 0L
    @Volatile private var statMaxMs = 0L
    @Volatile private var statFull = 0

    @Volatile private var statNoScale = 0
    @Volatile private var statJump = 0
    @Volatile private var statMaxJumpDb = 0.0
    @Volatile private var statQuiet = 0

    /** (frames, avg ms, max ms, full recomputes) since the last call. */
    @Synchronized fun takeStats(): IntArray {
        val r = intArrayOf(statFrames, if (statFrames == 0) 0 else (statMs / statFrames).toInt(), statMaxMs.toInt(), statFull)
        statFrames = 0; statMs = 0; statMaxMs = 0; statFull = 0
        return r
    }

    /** Why full recomputes happened since the last call: "quiet=… noScale=… jump=… maxJumpDb=…". */
    @Synchronized fun takeReasons(): String {
        val r = "quiet=$statQuiet noScale=$statNoScale jump=$statJump maxJumpDb=${"%.1f".format(statMaxJumpDb)}"
        statQuiet = 0; statNoScale = 0; statJump = 0; statMaxJumpDb = 0.0
        return r
    }

    fun load(): Boolean = try {
        val e = OrtEnvironment.getEnvironment()
        env = e
        val opts = OrtSession.SessionOptions()
        // Up to 2 threads: the A20e has 2 fast cores; more threads only add contention with the mic and the UI.
        opts.setIntraOpNumThreads(minOf(2, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
        opts.setInterOpNumThreads(1)
        mel = e.createSession(asset("$DIR/melspectrogram.onnx"), opts)
        emb = e.createSession(asset("$DIR/embedding_model.onnx"), opts)
        for (k in PHRASES.keys) heads[k] = e.createSession(asset("$DIR/$k.onnx"), opts)
        incremental = selfCheck()
        android.util.Log.i("Tekyerema", "[WAKE] scorer loaded: incremental=$incremental threads=${minOf(2, Runtime.getRuntime().availableProcessors())}")
        true
    } catch (t: Throwable) { close(); false }

    private fun asset(path: String): ByteArray = ctx.assets.open(path).use { it.readBytes() }

    /** Clear the rolling buffer (a fresh listen must not score the audio from before a pause). */
    @Synchronized fun reset() { buf.fill(0f); embCache.clear(); cacheScale = Float.NaN }

    /** Copy of the last 2.7 s of raw audio — exactly the window the heads just scored (monitor mode only). */
    @Synchronized fun snapshot(): FloatArray = buf.copyOf()

    /** Feed one 80 ms frame; returns {phrase: prob}, or empty when the window is too quiet or not yet full. */
    @Synchronized
    fun feed(frame: FloatArray): Map<String, Float> {
        val t0 = android.os.SystemClock.uptimeMillis()
        try {
            val n = frame.size
            if (n in 1 until BUF) {
                System.arraycopy(buf, n, buf, 0, BUF - n)
                System.arraycopy(frame, 0, buf, BUF - n, n)
                streamPos += n
            }
            var peak = 0f
            for (v in buf) { val a = if (v < 0f) -v else v; if (a > peak) peak = a }
            if (peak < RAW_PEAK_FLOOR) { statQuiet++; embCache.clear(); cacheScale = Float.NaN; return emptyMap() }   // too quiet: not the phrase
            val exact = 0.5f / peak
            // Scale hysteresis: in a room with sound the loudest sample changes almost every frame, which forced a full
            // recompute every time (~270 ms on the A20e). Hold the scale until it drifts more than the tolerance —
            // a sub-2 dB level offset, far smaller than the level variation the heads already see between utterances
            // (checked offline against the exact path; see RUN_REPORT). 0 = the exact, training-kernel behaviour.
            val tolDb = toleranceDb
            val driftDb = if (cacheScale.isNaN()) Double.NaN else Math.abs(20.0 * Math.log10((exact / cacheScale).toDouble()))
            val hold = incremental && tolDb > 0 && !driftDb.isNaN() && driftDb <= tolDb
            if (!hold) { if (driftDb.isNaN()) statNoScale++ else { statJump++; if (driftDb > statMaxJumpDb) statMaxJumpDb = driftDb } }
            val scale = if (hold) cacheScale else exact
            val embs = embeddings(scale, useCache = incremental) ?: return emptyMap()
            return scoreHeads(embs)
        } catch (t: Throwable) { return emptyMap() }
        finally {
            val dt = android.os.SystemClock.uptimeMillis() - t0
            statFrames++; statMs += dt; if (dt > statMaxMs) statMaxMs = dt
        }
    }

    /** Set when the last [embeddings] call used the cache (self-check bookkeeping). */
    private var lastIncremental = false

    /** Mel frame geometry of the full buffer (openWakeWord: 400-sample window, 160 hop), learned on the first run. */
    private var melFrames = -1
    private var melBins = -1

    /** The last [CLF_WIN] embeddings for the buffer at [scale] — from cache where possible, else recomputed. */
    private fun embeddings(scale: Float, useCache: Boolean): List<FloatArray>? {
        val e = env ?: return null
        val m = mel ?: return null
        val eb = emb ?: return null
        val bufStart = streamPos - BUF
        if (!useCache || scale != cacheScale || melFrames < 0) {
            // Full recompute — the original, training-exact path.
            statFull++
            embCache.clear(); cacheScale = scale
            val norm = FloatArray(BUF) { buf[it] * scale }
            val (melData, melShape) = run(e, m, norm, longArrayOf(1, BUF.toLong()))
            if (melShape.size < 2) return null
            val t = melShape[melShape.size - 2].toInt()
            val nMel = melShape[melShape.size - 1].toInt()
            melFrames = t; melBins = nMel
            if (t < MEL_WIN) return null
            for (i in melData.indices) melData[i] = melData[i] / 10f + 2f
            val out = ArrayList<FloatArray>()
            var i = maxOf(0, t - MEL_WIN - (CLF_WIN - 1) * MEL_STRIDE)
            while (i + MEL_WIN <= t) {
                val win = FloatArray(MEL_WIN * nMel)
                System.arraycopy(melData, i * nMel, win, 0, MEL_WIN * nMel)
                val (ev, _) = run(e, eb, win, longArrayOf(1, MEL_WIN.toLong(), nMel.toLong(), 1))
                embCache[bufStart + HOP.toLong() * (i + MEL_WIN)] = ev
                out.add(ev)
                i += MEL_STRIDE
            }
            return if (out.size < CLF_WIN) null else out.takeLast(CLF_WIN)
        }
        // Incremental: every embedding window but the newest is already cached at this scale.
        val t = melFrames; val nMel = melBins
        val first = maxOf(0, t - MEL_WIN - (CLF_WIN - 1) * MEL_STRIDE)
        val out = ArrayList<FloatArray>()
        var i = first
        while (i + MEL_WIN <= t) {
            val key = bufStart + HOP.toLong() * (i + MEL_WIN)
            val cached = embCache[key] ?: newestEmbedding(i, scale, nMel) ?: return embeddings(scale, useCache = false)
            embCache[key] = cached
            out.add(cached)
            i += MEL_STRIDE
        }
        // Drop embeddings that slid out of the window.
        val oldest = bufStart + HOP.toLong() * (first + MEL_WIN)
        embCache.keys.removeAll { it < oldest }
        lastIncremental = true   // only now: the fast path really produced every embedding (no fallback)
        return if (out.size < CLF_WIN) null else out.takeLast(CLF_WIN)
    }

    /**
     * One embedding for the window starting at mel frame [i], from a short frame-aligned tail of the buffer. The mel
     * model's frame spans its FFT size (512 samples, not the 400-sample window) — measured on the A20e: a 400-based tail
     * came out one frame short and every frame silently fell back to the full recompute. So the tail gets a margin of
     * [TAIL_MARGIN_FRAMES] hops and the FIRST [MEL_WIN] frames (aligned to [tailStart]) are used.
     */
    private fun newestEmbedding(i: Int, scale: Float, nMel: Int): FloatArray? {
        val e = env ?: return null
        val m = mel ?: return null
        val eb = emb ?: return null
        val tailStart = HOP * i                          // aligned to the full buffer's mel frames
        val tailLen = minOf(BUF - tailStart, HOP * (MEL_WIN + TAIL_MARGIN_FRAMES))
        if (tailLen <= 0) return null
        val tail = FloatArray(tailLen) { buf[tailStart + it] * scale }
        val (melData, melShape) = run(e, m, tail, longArrayOf(1, tailLen.toLong()))
        if (melShape.size < 2 || melShape[melShape.size - 2].toInt() < MEL_WIN) return null
        val win = FloatArray(MEL_WIN * nMel)
        for (k in 0 until MEL_WIN * nMel) win[k] = melData[k] / 10f + 2f
        val (ev, _) = run(e, eb, win, longArrayOf(1, MEL_WIN.toLong(), nMel.toLong(), 1))
        return ev
    }

    private fun scoreHeads(embs: List<FloatArray>): Map<String, Float> {
        val e = env ?: return emptyMap()
        val dim = embs[0].size            // 96
        val x = FloatArray(CLF_WIN * dim)
        for (j in 0 until CLF_WIN) System.arraycopy(embs[j], 0, x, j * dim, dim)
        val out = LinkedHashMap<String, Float>()
        for ((k, s) in heads) {
            val (pv, _) = run(e, s, x, longArrayOf(1, CLF_WIN.toLong(), dim.toLong()))
            out[k] = if (pv.isNotEmpty()) pv[0] else 0f
        }
        return out
    }

    /**
     * On load: feed a few seconds of a synthetic, speech-like signal through both paths and compare the heads' scores.
     * The incremental path is used only when it matches the full recompute (|Δ| ≤ 0.01 on every frame).
     */
    @Synchronized
    private fun selfCheck(): Boolean = try {
        val rnd = java.util.Random(7)
        var worst = 0f
        var compared = 0
        reset(); streamPos = 0
        for (f in 0 until 60) {
            val frame = FloatArray(FRAME) { k ->
                val s = (f * FRAME + k).toDouble() / SR
                (0.3 * Math.sin(2 * Math.PI * (180 + 40 * Math.sin(s * 3)) * s) * (0.6 + 0.4 * Math.sin(s * 5)) +
                    0.02 * rnd.nextGaussian()).toFloat()
            }
            if (f == 35) frame[100] = 0.9f   // one fixed spike pins the peak (and the scale) for the frames compared
            System.arraycopy(buf, FRAME, buf, 0, BUF - FRAME); System.arraycopy(frame, 0, buf, BUF - FRAME, FRAME); streamPos += FRAME
            var peak = 0f
            for (v in buf) { val a = if (v < 0f) -v else v; if (a > peak) peak = a }
            if (peak < RAW_PEAK_FLOOR || f < 36) continue
            val scale = 0.5f / peak
            lastIncremental = false
            val inc = embeddings(scale, useCache = true)
            val usedCache = lastIncremental
            val full = embeddings(scale, useCache = false)
            if (usedCache && inc != null && full != null && inc.size == full.size) {   // only frames where the fast path ran
                compared++
                for (j in inc.indices) for (d in inc[j].indices) worst = maxOf(worst, Math.abs(inc[j][d] - full[j][d]))
            }
        }
        reset(); streamPos = 0; takeStats()
        android.util.Log.i("Tekyerema", "[WAKE] incremental self-check: compared=$compared maxEmbeddingDiff=${"%.5f".format(worst)}")
        compared > 5 && worst <= 1e-3f
    } catch (t: Throwable) { reset(); false }

    /** Run a single-input session; return (flat floats, output shape). */
    private fun run(env: OrtEnvironment, s: OrtSession, data: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val name = s.inputNames.iterator().next()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tin ->
            s.run(mapOf(name to tin)).use { res ->
                val out = res[0] as OnnxTensor
                val fb = out.floatBuffer
                val arr = FloatArray(fb.remaining())
                fb.get(arr)
                return arr to out.info.shape
            }
        }
    }

    fun close() {
        try { mel?.close(); emb?.close(); heads.values.forEach { it.close() } } catch (_: Exception) {}
        mel = null; emb = null; heads.clear(); env = null
    }
}
