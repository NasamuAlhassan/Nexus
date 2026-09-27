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
        val BUF = (SR * 2.7).toInt()          // 2.7 s rolling buffer
        /** Per-phrase bars. Heads retrained 2026-09-13 with the owner's recorded wake clips (and commands as
         *  negatives). Wake matrix 2026-09-14: "hey" head at 0.97 fired 14/16 live-condition wakes, 1/270
         *  command clips, 0 on silence/noise. */
        val PHRASES = linkedMapOf("hey_tekyerema" to 0.97f, "tekyerema" to 0.90f)
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

    fun load(): Boolean = try {
        val e = OrtEnvironment.getEnvironment()
        env = e
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)
        mel = e.createSession(asset("$DIR/melspectrogram.onnx"), opts)
        emb = e.createSession(asset("$DIR/embedding_model.onnx"), opts)
        for (k in PHRASES.keys) heads[k] = e.createSession(asset("$DIR/$k.onnx"), opts)
        true
    } catch (t: Throwable) { close(); false }

    private fun asset(path: String): ByteArray = ctx.assets.open(path).use { it.readBytes() }

    /** Clear the rolling buffer (a fresh listen must not score the audio from before a pause). */
    @Synchronized fun reset() = buf.fill(0f)

    /** Copy of the last 2.7 s of raw audio — exactly the window the heads just scored (monitor mode only). */
    @Synchronized fun snapshot(): FloatArray = buf.copyOf()

    /** Feed one 80 ms frame; returns {phrase: prob}, or empty when the window is too quiet or not yet full. */
    @Synchronized
    fun feed(frame: FloatArray): Map<String, Float> {
        val e = env ?: return emptyMap()
        val m = mel ?: return emptyMap()
        val eb = emb ?: return emptyMap()
        return try {
            val n = frame.size
            if (n in 1 until BUF) {
                System.arraycopy(buf, n, buf, 0, BUF - n)
                System.arraycopy(frame, 0, buf, BUF - n, n)
            }
            var peak = 0f
            for (v in buf) { val a = if (v < 0f) -v else v; if (a > peak) peak = a }
            if (peak < RAW_PEAK_FLOOR) return emptyMap()   // too quiet: not the phrase
            val scale = 0.5f / peak
            val norm = FloatArray(BUF) { buf[it] * scale }

            val (melData, melShape) = run(e, m, norm, longArrayOf(1, BUF.toLong()))
            if (melShape.size < 2) return emptyMap()
            val t = melShape[melShape.size - 2].toInt()
            val nMel = melShape[melShape.size - 1].toInt()
            if (t < MEL_WIN) return emptyMap()
            for (i in melData.indices) melData[i] = melData[i] / 10f + 2f

            val embs = ArrayList<FloatArray>()
            var i = maxOf(0, t - MEL_WIN - (CLF_WIN - 1) * MEL_STRIDE)
            while (i + MEL_WIN <= t) {
                val win = FloatArray(MEL_WIN * nMel)
                System.arraycopy(melData, i * nMel, win, 0, MEL_WIN * nMel)
                val (ev, _) = run(e, eb, win, longArrayOf(1, MEL_WIN.toLong(), nMel.toLong(), 1))
                embs.add(ev)
                i += MEL_STRIDE
            }
            if (embs.size < CLF_WIN) return emptyMap()
            val dim = embs[0].size            // 96
            val x = FloatArray(CLF_WIN * dim)
            val start = embs.size - CLF_WIN
            for (j in 0 until CLF_WIN) System.arraycopy(embs[start + j], 0, x, j * dim, dim)

            val out = LinkedHashMap<String, Float>()
            for ((k, s) in heads) {
                val (pv, _) = run(e, s, x, longArrayOf(1, CLF_WIN.toLong(), dim.toLong()))
                out[k] = if (pv.isNotEmpty()) pv[0] else 0f
            }
            out
        } catch (t: Throwable) { emptyMap() }
    }

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
