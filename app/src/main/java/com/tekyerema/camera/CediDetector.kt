package com.tekyerema.camera

import android.content.Context
import android.util.Log

/**
 * Finds every note and coin in a camera frame, on the phone (28 Oct 2026; campaign/kaggle/cedi-det-v1). The first stage of offline
 * counting: each box it finds is then named by [CediClassifier] from the camera's own pixels. It replaces [ObjectCrop]'s
 * hand-written box finder for piles when `cedi/cedi_det.onnx` is in the build; without the file the app counts as before.
 *
 * Contract (det_metrics.json): input float32 [1, 320, 320, 3] RGB 0–255, the whole frame stretched to a square (as the model was
 * trained); outputs `boxes` [N, 4] (x0, y0, x1, y1 in 0..1 of the frame) and `scores` [N, 3] (background, note, coin). Thresholding
 * and overlap removal are here ([post], pure → CediDetectorTest).
 */
class CediDetector private constructor(ctx: Context) {
    data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val coin: Boolean, val score: Float) {
        val w: Float get() = x1 - x0
        val h: Float get() = y1 - y0
    }

    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val session: ai.onnxruntime.OrtSession

    init {
        val opts = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(ctx.assets.open("$DIR/$FILE").use { it.readBytes() }, opts)
    }

    /** The pieces in one stretched [SIDE]×[SIDE] frame, most confident first. */
    fun detect(rgb: FloatArray, scoreMin: Float = SCORE_MIN): List<Box> {
        require(rgb.size == SIDE * SIDE * 3) { "detector frame: expected ${SIDE * SIDE * 3} floats, got ${rgb.size}" }
        val tensor = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(rgb), longArrayOf(1, SIDE.toLong(), SIDE.toLong(), 3))
        try {
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST") val boxes = out[0].value as Array<FloatArray>
                @Suppress("UNCHECKED_CAST") val scores = out[1].value as Array<FloatArray>
                return post(boxes, scores, scoreMin)
            }
        } finally { tensor.close() }
    }

    fun close() = try { session.close() } catch (_: Throwable) {}

    companion object {
        private const val DIR = "cedi"
        private const val FILE = "cedi_det.onnx"
        const val SIDE = 320
        /** Below this a box is not a piece of money. Tuned on Kaggle (det_metrics.json) — config `camera.currency.detScore`. */
        const val SCORE_MIN = 0.5f

        fun installed(ctx: Context): Boolean = try { ctx.assets.list(DIR)?.contains(FILE) == true } catch (_: Throwable) { false }

        fun create(ctx: Context): CediDetector? = try { CediDetector(ctx.applicationContext) } catch (t: Throwable) {
            Log.w("Tekyerema", "[CEDI] detector unavailable: ${t.message}"); null
        }

        /** Per class: score ≥ [scoreMin], then overlap removal (IoU > [nms]); then one box per place across the two classes. */
        fun post(boxes: Array<FloatArray>, scores: Array<FloatArray>, scoreMin: Float, nms: Float = 0.5f, cross: Float = 0.7f, max: Int = 12): List<Box> {
            val all = ArrayList<Box>()
            for (c in 1..2) {
                val cand = boxes.indices.filter { scores[it][c] >= scoreMin }.sortedByDescending { scores[it][c] }
                val kept = ArrayList<Box>()
                for (i in cand) {
                    val b = boxes[i]
                    val box = Box(b[0], b[1], b[2], b[3], c == 2, scores[i][c])
                    if (box.w <= 0f || box.h <= 0f) continue
                    if (kept.all { iou(it, box) <= nms }) kept.add(box)
                }
                all.addAll(kept)
            }
            all.sortByDescending { it.score }
            val out = ArrayList<Box>()
            for (b in all) if (out.size < max && out.all { iou(it, b) < cross }) out.add(b)
            return out
        }

        fun iou(a: Box, b: Box): Float {
            val iw = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)
            val ih = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)
            if (iw <= 0f || ih <= 0f) return 0f
            val inter = iw * ih
            return inter / (a.w * a.h + b.w * b.h - inter)
        }

        /** The square crop (frame pixels) the classifier reads for [b]: its longer side × [margin], kept inside the frame. */
        fun window(b: Box, frameW: Int, frameH: Int, margin: Float): ObjectCrop.Window {
            val side = (maxOf(b.w * frameW, b.h * frameH) * margin).toInt().coerceIn(16, minOf(frameW, frameH))
            val cx = (b.x0 + b.x1) / 2f * frameW
            val cy = (b.y0 + b.y1) / 2f * frameH
            val x = (cx - side / 2f).toInt().coerceIn(0, frameW - side)
            val y = (cy - side / 2f).toInt().coerceIn(0, frameH - side)
            return ObjectCrop.Window(x, y, side)
        }
    }
}
