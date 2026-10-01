package com.tekyerema.camera

import android.content.Context
import android.util.Log

/**
 * The Ghana-cedi note and coin reader, on the phone, offline (docs/agent/research/cedi_integration.md).
 *
 * Runs on the ONNX Runtime the app ALREADY ships for the wake word — no third inference runtime on a 2.8 GB phone
 * (the spec's Option B). The model is a MobileNetV3-Small trained on the owner's catalog seeds, heavily augmented;
 * 98% on held-out synthetic frames, and the app never speaks a denomination below the two-frame ≥0.85 gate
 * ([CurrencyAnnouncer]) — a wrong amount is worse than no answer, and no model reads a note perfectly.
 *
 * Contract (onnx_report.md): input float32 [1, 224, 224, 3] RGB 0–255 (normalisation is inside the graph),
 * output 16 softmax scores in `labels.txt` order.
 */
class CediClassifier private constructor(ctx: Context) {

    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val session: ai.onnxruntime.OrtSession
    private val inputName: String
    val labels: List<String>

    init {
        val opts = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(ctx.assets.open("$DIR/cedi.onnx").use { it.readBytes() }, opts)
        inputName = session.inputNames.first()
        labels = ctx.assets.open("$DIR/labels.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        require(labels.size == 16) { "cedi labels: expected 16, got ${labels.size}" }
    }

    /** The best class for one 224×224 RGB frame (row-major, 0–255) and its confidence. */
    fun classify(rgb: FloatArray): Pair<String, Double> {
        require(rgb.size == SIDE * SIDE * 3) { "cedi frame: expected ${SIDE * SIDE * 3} floats, got ${rgb.size}" }
        val tensor = ai.onnxruntime.OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(rgb), longArrayOf(1, SIDE.toLong(), SIDE.toLong(), 3)
        )
        try {
            session.run(mapOf(inputName to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val scores = (out[0].value as Array<FloatArray>)[0]
                var best = 0
                for (i in scores.indices) if (scores[i] > scores[best]) best = i
                return labels[best] to scores[best].toDouble()
            }
        } finally { tensor.close() }
    }

    fun close() = try { session.close() } catch (_: Throwable) {}

    companion object {
        private const val DIR = "cedi"
        const val SIDE = 224

        /** Loaded on first use; null when the model isn't bundled or can't load (then the mode says so honestly). */
        fun create(ctx: Context): CediClassifier? = try {
            CediClassifier(ctx.applicationContext)
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[CEDI] model unavailable: ${t.message}")
            null
        }
    }
}
