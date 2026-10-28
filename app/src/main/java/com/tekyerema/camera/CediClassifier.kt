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

    /** The reading from the whole centre square [full] and, when the object was found, from its crop [tight] — see [ObjectCrop.decide]. */
    fun classifyBoth(full: FloatArray, tight: FloatArray?): Pair<String, Double> {
        val (fl, fc) = classify(full)
        if (tight == null) return fl to fc
        val (tl, tc) = classify(tight)
        val base = com.tekyerema.core.config.AppConfig.double("camera.currency.gate", 0.85)
        val (cls, conf) = ObjectCrop.decide(fl, fc, tl, tc) { c -> CurrencyAnnouncer.gateFor(c, base) }
        // A sure reading is checked in the other three quarter-turns of the same pixels; a held note may be any way up.
        if (!com.tekyerema.core.config.AppConfig.bool("camera.currency.turnCheck", true) || MoneyItems.itemOf(cls) == null || conf < base) return cls to conf
        val src = tight ?: full
        val others = (1..3).map { k -> classify(CediCrops.turned(src, SIDE, k)).let { (c, p) -> MoneyItems.Reading(c, p) } }
        val r = MoneyItems.crossChecked(MoneyItems.Reading(cls, conf), others, base)
        return r.cls to r.conf
    }

    fun close() = try { session.close() } catch (_: Throwable) {}

    companion object {
        private const val DIR = "cedi"
        const val SIDE = 224
        /** What the user is told when the model isn't in this build (audit 2026-10-01, CAM04: the screen opened and
         *  closed and the log held no spoken line at all). */
        const val NOT_INSTALLED = "The money reader isn't installed on this phone yet."

        /** A money question that cannot go to the online vision models, with the note model in the build: use the phone's own. */
        fun useOffline(describeMode: Boolean, moneyQuestion: Boolean, onlineVisionOk: Boolean, modelInstalled: Boolean): Boolean =
            describeMode && moneyQuestion && !onlineVisionOk && modelInstalled

        /** Is the model file in the APK? A cheap look (no load) so the money mode can say so BEFORE opening the camera. */
        fun installed(ctx: Context): Boolean = try {
            ctx.assets.list(DIR)?.contains("cedi.onnx") == true
        } catch (_: Throwable) { false }

        /** Loaded on first use; null when the model isn't bundled or can't load (then the mode says so honestly). */
        fun create(ctx: Context): CediClassifier? = try {
            CediClassifier(ctx.applicationContext)
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[CEDI] model unavailable: ${t.message}")
            null
        }
    }
}
