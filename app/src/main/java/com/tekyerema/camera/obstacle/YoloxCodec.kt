package com.tekyerema.camera.obstacle

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One thing the detector found in the UPRIGHT camera frame: [cls] is the COCO name, the box is 0..1 of the frame (y grows downward). */
data class Detection(val cls: String, val score: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val w: Float get() = x1 - x0
    val h: Float get() = y1 - y0
    val cx: Float get() = (x0 + x1) / 2f
}

/** Source pixels in upright frame coordinates, packed 0xRRGGBB. The Android glue implements it over a YUV frame (with rotation). */
fun interface PixelSource {
    fun rgb(x: Int, y: Int): Int
}

/**
 * The YOLOX preprocessing, exactly as the official demo does it: the frame shrunk by one scale so its long side fits [side], placed in the
 * TOP-LEFT of a [side]x[side] canvas, the rest filled with 114. (No centring: YOLOX was trained that way.) Nearest-neighbour sampling.
 */
class Letterbox(val srcW: Int, val srcH: Int, val side: Int) {
    val scale: Float = min(side.toFloat() / srcW, side.toFloat() / srcH)
    val newW: Int = max(1, min(side, Math.round(srcW * scale)))
    val newH: Int = max(1, min(side, Math.round(srcH * scale)))

    /** Canvas column -> source column (only for columns < [newW]). */
    val xMap: IntArray = IntArray(newW) { min(srcW - 1, (it / scale).toInt()) }
    val yMap: IntArray = IntArray(newH) { min(srcH - 1, (it / scale).toInt()) }

    companion object { const val PAD = 114f }
}

/**
 * YOLOX (Megvii-BaseDetection/YOLOX, Apache-2.0) pre- and post-processing for the official ONNX export, as pure functions.
 *
 * Model contract (yolox_nano.onnx / yolox_tiny.onnx, COCO, 416x416): input `images` float32 [1,3,S,S], channel order BGR, values 0..255,
 * NO mean/std. Output float32 [1,A,85], A = (S/8)^2 + (S/16)^2 + (S/32)^2 = 3549 for S = 416; per row cx, cy, w, h (undecoded grid offsets
 * unless the model was exported with --decode_in_inference), objectness, then 80 class scores — objectness and classes already sigmoided.
 */
object YoloxCodec {
    val STRIDES = intArrayOf(8, 16, 32)
    const val ROW = 85

    /** The number of anchor rows an S x S YOLOX has: 3549 for 416, 8400 for 640. */
    fun anchorCount(side: Int): Int = STRIDES.sumOf { (side / it) * (side / it) }

    /**
     * Fill [out] (size 3*S*S, planar, channel-first) from [src] (upright, [lb].srcW x [lb].srcH). Channel 0 = B when [bgr] (the official
     * order), else R. Pixels outside the letterboxed image are [Letterbox.PAD] in every channel.
     */
    fun fillInput(src: PixelSource, lb: Letterbox, bgr: Boolean, out: FloatArray) {
        val s = lb.side
        require(out.size == 3 * s * s) { "input tensor: expected ${3 * s * s} floats, got ${out.size}" }
        val plane = s * s
        for (y in 0 until s) {
            val row = y * s
            if (y >= lb.newH) {
                java.util.Arrays.fill(out, row, row + s, Letterbox.PAD)
                java.util.Arrays.fill(out, plane + row, plane + row + s, Letterbox.PAD)
                java.util.Arrays.fill(out, 2 * plane + row, 2 * plane + row + s, Letterbox.PAD)
                continue
            }
            val sy = lb.yMap[y]
            for (x in 0 until lb.newW) {
                val p = src.rgb(lb.xMap[x], sy)
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                out[row + x] = if (bgr) b else r             // channel 0
                out[plane + row + x] = g                     // channel 1
                out[2 * plane + row + x] = if (bgr) r else b // channel 2
            }
            if (lb.newW < s) {
                java.util.Arrays.fill(out, row + lb.newW, row + s, Letterbox.PAD)
                java.util.Arrays.fill(out, plane + row + lb.newW, plane + row + s, Letterbox.PAD)
                java.util.Arrays.fill(out, 2 * plane + row + lb.newW, 2 * plane + row + s, Letterbox.PAD)
            }
        }
    }

    /**
     * Raw model output -> detections in the upright frame, 0..1.
     *
     * @param out flat [rows * 85]; [rows] must equal [anchorCount] of [lb].side (else IllegalArgumentException).
     * @param decoded true when the export already turned cx,cy,w,h into canvas pixels (--decode_in_inference).
     * @param keep which COCO class names may be reported (null = all); applied AFTER the arg-max so a "cup" is never turned into a "chair".
     */
    fun decode(out: FloatArray, rows: Int, lb: Letterbox, decoded: Boolean, scoreMin: Float, keep: ((String) -> Boolean)? = null): List<Detection> {
        require(rows == anchorCount(lb.side)) { "YOLOX output has $rows rows; a ${lb.side} px model has ${anchorCount(lb.side)}" }
        require(out.size >= rows * ROW) { "YOLOX output: expected ${rows * ROW} floats, got ${out.size}" }
        val found = ArrayList<Detection>()
        var anchor = 0
        for (stride in STRIDES) {
            val g = lb.side / stride
            for (gy in 0 until g) for (gx in 0 until g) {
                val o = anchor * ROW
                anchor++
                val obj = out[o + 4]
                if (obj < scoreMin) continue          // class score is <= 1, so the product can't pass either
                var best = 0
                var bestP = out[o + 5]
                for (c in 1 until Coco.NAMES.size) { val p = out[o + 5 + c]; if (p > bestP) { bestP = p; best = c } }
                val score = obj * bestP
                if (score < scoreMin) continue
                val name = Coco.NAMES[best]
                if (keep != null && !keep(name)) continue
                val cx: Float; val cy: Float; val w: Float; val h: Float
                if (decoded) {
                    cx = out[o]; cy = out[o + 1]; w = out[o + 2]; h = out[o + 3]
                } else {
                    cx = (out[o] + gx) * stride; cy = (out[o + 1] + gy) * stride
                    w = exp(min(out[o + 2], 10f)) * stride; h = exp(min(out[o + 3], 10f)) * stride
                }
                // Canvas pixels -> source pixels (/scale) -> 0..1 of the frame.
                val fx0 = ((cx - w / 2f) / lb.scale / lb.srcW).coerceIn(0f, 1f)
                val fy0 = ((cy - h / 2f) / lb.scale / lb.srcH).coerceIn(0f, 1f)
                val fx1 = ((cx + w / 2f) / lb.scale / lb.srcW).coerceIn(0f, 1f)
                val fy1 = ((cy + h / 2f) / lb.scale / lb.srcH).coerceIn(0f, 1f)
                if (fx1 - fx0 <= 0f || fy1 - fy0 <= 0f) continue
                found.add(Detection(name, score, fx0, fy0, fx1, fy1))
            }
        }
        return found
    }

    /** Per-class greedy overlap removal (YOLOX's multiclass NMS), highest score first; at most [max] kept. */
    fun nms(dets: List<Detection>, iouMax: Float, max: Int = Int.MAX_VALUE): List<Detection> {
        val kept = ArrayList<Detection>()
        for (d in dets.sortedByDescending { it.score }) {
            if (kept.size >= max) break
            if (kept.none { it.cls == d.cls && iou(it, d) > iouMax }) kept.add(d)
        }
        return kept
    }

    fun iou(a: Detection, b: Detection): Float {
        val iw = min(a.x1, b.x1) - max(a.x0, b.x0)
        val ih = min(a.y1, b.y1) - max(a.y0, b.y0)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / (a.w * a.h + b.w * b.h - inter)
    }

    /** decode + nms in one call. */
    fun postprocess(out: FloatArray, rows: Int, lb: Letterbox, decoded: Boolean, scoreMin: Float, nmsIou: Float, maxDetections: Int, keep: ((String) -> Boolean)? = null): List<Detection> =
        nms(decode(out, rows, lb, decoded, scoreMin, keep), nmsIou, maxDetections)
}

/** Upright pixel -> sensor pixel for a CameraX frame that must be turned [rotation] degrees clockwise to be upright. */
object FrameRotation {
    /**
     * The sensor pixel under upright pixel ([ux], [uy]), written to [out] (out[0] = x, out[1] = y) so nothing is allocated per pixel.
     * [uprightW] x [uprightH] is the upright size; the sensor frame is its transpose for 90 and 270.
     */
    fun toSensor(ux: Int, uy: Int, uprightW: Int, uprightH: Int, rotation: Int, out: IntArray) {
        when (((rotation % 360) + 360) % 360) {
            90 -> { out[0] = uy; out[1] = uprightW - 1 - ux }        // sensorH = uprightW
            180 -> { out[0] = uprightW - 1 - ux; out[1] = uprightH - 1 - uy }
            270 -> { out[0] = uprightH - 1 - uy; out[1] = ux }       // sensorW = uprightH
            else -> { out[0] = ux; out[1] = uy }
        }
    }

    /** The upright frame's (width, height) for a sensor frame of [sensorW] x [sensorH]. */
    fun uprightSize(sensorW: Int, sensorH: Int, rotation: Int): Pair<Int, Int> =
        if (((rotation % 360) + 360) % 360 % 180 == 90) sensorH to sensorW else sensorW to sensorH
}

/** The 80 COCO class names in the order the YOLOX head emits them. */
object Coco {
    val NAMES: Array<String> = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed",
        "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
        "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
    )
}
