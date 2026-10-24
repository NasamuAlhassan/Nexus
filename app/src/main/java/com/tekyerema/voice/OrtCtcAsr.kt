package com.tekyerema.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Our Twi hearing model on the phone (owner, 20 Oct 2026: "make the Twi offline too, our current model on the Space is good"):
 * tekyerema-asr-ctc-twi-v2 (w2v-BERT 2.0 + CTC) exported to ONNX, run by ONNX Runtime — sherpa-onnx has no runner for it.
 * Features come from [W2vBertFeatures]; the graph takes `input_features` [1, T, 160] (and `attention_mask` [1, T] when it
 * asks for one) and returns logits [1, T', vocab]. Greedy CTC: best token per frame, repeats merged, the blank ("[PAD]")
 * dropped, "|" between words. The vocabulary is the model's own vocab.json ({"a": 2, "|": 0, …}).
 */
class OrtCtcAsr(modelFile: File, vocabFile: File, threads: Int, preoptimized: Boolean = false) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val vocab: Array<String>
    private val blank: Int
    /** The beam search with the word language model, when the model folder has one and it is switched on; else greedy. */
    private val beam: CtcLmDecoder?

    init {
        val t0 = SystemClock.uptimeMillis()
        // The word LM (when on) is read on a thread of its own while the session loads: the two used to run one after the other
        // (A20e, 24 Oct 2026: 20 s for the LM file, after 5 s for the model).
        val lmRead = startLmRead(modelFile.parentFile)
        // Lean on a 3 GB phone (reliability audit, 20 Oct 2026): EXTENDED fusions (attention, GELU) but not ALL (x86 layout
        // transforms), no CPU arena and no memory pattern, so the peak stays near the model's own size.
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            // A file already optimised at EXTENDED by ORT 1.17.1 (model.json "preoptimized") skips the rewrite: seconds saved at load.
            setOptimizationLevel(if (preoptimized) OrtSession.SessionOptions.OptLevel.NO_OPT else OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            // No second, prepacked copy of the weights (Kaggle 1.17.1 measurements, 21 Oct 2026: load RAM is mostly copied
            // initializers). `speech.asr.ort.prepacking` true = ORT's default (a little faster, more RAM).
            if (!com.tekyerema.core.config.AppConfig.bool("speech.asr.ort.prepacking", false)) addConfigEntry("session.disable_prepacking", "1")
        }
        // DEBUG builds: `speech.asr.ort.profile` writes ORT's per-operator timings (the first decode), for the models session.
        if (com.tekyerema.BuildConfig.DEV_HOOKS && com.tekyerema.core.config.AppConfig.bool("speech.asr.ort.profile", false))
            opts.enableProfiling(File(modelFile.parentFile, "ort_profile").absolutePath)
        session = env.createSession(modelFile.absolutePath, opts)
        val (v, b) = readVocab(vocabFile.readText())
        vocab = v; blank = b
        beam = lmRead?.let { f -> try { f.get()?.let { decoderFor(it, v, b) } } catch (t: Throwable) { Log.w("Tekyerema", "[ASR] onnx LM not loaded: ${t.message}"); null } }
        Log.i("Tekyerema", "[ASR] onnx ${modelFile.name} ready (${threads} threads, inputs ${session.inputNames}) in ${SystemClock.uptimeMillis() - t0} ms")
    }

    /** The words heard in [samples] at [sampleRate] ("" when nothing). */
    fun decode(samples: FloatArray, sampleRate: Int): String {
        val full = if (sampleRate == W2vBertFeatures.SAMPLE_RATE) samples else resample(samples, sampleRate, W2vBertFeatures.SAMPLE_RATE)
        // Attention grows with the square of the length: a command is a few seconds, so at most MAX_SECONDS are heard.
        // 8 s by default (`speech.asr.ort.maxSeconds`): a command is short, and the peak RAM grows faster than the clip (ef, 21 Oct
        // 2026: 587 MB at 8 s for the 353 MB model; a 15 s clip could reach ~700 MB on a phone with ~600 MB free).
        val maxS = com.tekyerema.core.config.AppConfig.int("speech.asr.ort.maxSeconds", 8).coerceIn(2, MAX_SECONDS)
        val pcm = if (full.size > maxS * W2vBertFeatures.SAMPLE_RATE) full.copyOf(maxS * W2vBertFeatures.SAMPLE_RATE) else full
        val feats = W2vBertFeatures.compute(pcm)
        if (feats.isEmpty()) return ""
        val t = feats.size; val d = feats[0].size
        val flat = FloatBuffer.allocate(t * d).apply { feats.forEach { put(it) }; rewind() }
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs["input_features"] = OnnxTensor.createTensor(env, flat, longArrayOf(1, t.toLong(), d.toLong()))
            if ("attention_mask" in session.inputNames)
                inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(t) { 1L }), longArrayOf(1, t.toLong()))
            session.run(inputs).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<Array<FloatArray>>)[0]
                beam?.let { d ->
                    try {
                        val t0 = SystemClock.uptimeMillis()
                        return d.decode(logits).also { Log.i("Tekyerema", "[ASR] onnx LM beam in ${SystemClock.uptimeMillis() - t0} ms") }
                    } catch (e: Exception) { Log.w("Tekyerema", "[ASR] onnx LM beam failed, greedy instead: ${e.message}") }
                }
                return greedy(logits, vocab, blank)
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    /** DEBUG: the profile file ORT wrote for the decodes so far (`speech.asr.ort.profile`), logged; null when off. */
    fun endProfile(): String? = try { session.endProfiling().also { Log.i("Tekyerema", "[ASR] ORT profile written: $it") } } catch (_: Throwable) { null }

    override fun close() { try { session.close() } catch (_: Throwable) {} }

    companion object {
        const val MAX_SECONDS = 15
        /** The word language model beside the model ([dir]/lm.arpa), for the beam search. OFF until it matches pyctcdecode
         *  on the models session's parity clips: `speech.asr.ort.lm` true, with `.alpha`, `.beta`, `.beam` (C-03 settings). */
        private fun startLmRead(dir: File?): java.util.concurrent.FutureTask<NgramLm?>? {
            if (!com.tekyerema.core.config.AppConfig.bool("speech.asr.ort.lm", false)) return null
            val f = dir?.let { File(it, "lm.arpa") }?.takeIf { it.isFile } ?: return null
            val task = java.util.concurrent.FutureTask<NgramLm?> {
                val t0 = SystemClock.uptimeMillis()
                val lm = f.bufferedReader(Charsets.UTF_8, 1 shl 16).useLines { NgramLm.parseArpa(it) }
                Log.i("Tekyerema", "[ASR] onnx LM ${f.length() / 1024} KB, order ${lm.order}, read in ${SystemClock.uptimeMillis() - t0} ms")
                lm
            }
            Thread(task, "ort-lm-read").start()
            return task
        }
        private fun decoderFor(lm: NgramLm, vocab: Array<String>, blank: Int) = CtcLmDecoder(vocab, blank, lm,
            alpha = com.tekyerema.core.config.AppConfig.float("speech.asr.ort.lm.alpha", 0.3f),
            beta = com.tekyerema.core.config.AppConfig.float("speech.asr.ort.lm.beta", 0.5f),
            beamWidth = com.tekyerema.core.config.AppConfig.int("speech.asr.ort.lm.beam", 25).coerceIn(1, 100))
        /** id → token, and the blank's id ("[PAD]", else "<pad>", else 0). Pure. */
        fun readVocab(json: String): Pair<Array<String>, Int> {
            val o = JSONObject(json)
            val size = o.keys().asSequence().maxOf { o.getInt(it) } + 1
            val v = Array(size) { "" }
            o.keys().forEach { k -> v[o.getInt(k)] = k }
            val blank = v.indexOf("[PAD]").takeIf { it >= 0 } ?: v.indexOf("<pad>").takeIf { it >= 0 } ?: 0
            return v to blank
        }

        /** Greedy CTC over [logits] [T'][vocab]. Pure. */
        fun greedy(logits: Array<FloatArray>, vocab: Array<String>, blank: Int): String {
            val sb = StringBuilder()
            var prev = -1
            for (row in logits) {
                var best = 0
                for (i in 1 until row.size) if (row[i] > row[best]) best = i
                if (best != prev && best != blank) {
                    val tok = vocab.getOrElse(best) { "" }
                    when {
                        tok == "|" -> sb.append(' ')
                        tok.startsWith("[") || tok.startsWith("<") -> {}
                        else -> sb.append(tok)
                    }
                }
                prev = best
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }

        /** Linear resampling (the phone's microphone is 16 kHz already; this only covers other clips). Pure. */
        fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
            if (x.isEmpty() || from == to) return x
            val n = (x.size.toLong() * to / from).toInt()
            return FloatArray(n) { i ->
                val pos = i.toDouble() * from / to
                val a = pos.toInt().coerceAtMost(x.size - 1); val b = (a + 1).coerceAtMost(x.size - 1)
                val f = (pos - a).toFloat()
                x[a] * (1 - f) + x[b] * f
            }
        }
    }
}
