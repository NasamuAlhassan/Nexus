package com.tekyerema.camera

import android.graphics.Bitmap

/**
 * A boxed piece of an upright photo, as the 224 px RGB floats the cedi model reads — so the phone can check what the cloud
 * model says each note or coin is ([MoneyTally.withOnDevice]). The window is the object's box plus a margin, made square, the
 * way [ObjectCrop.window] frames the live camera's object crop. Touches Bitmap, so it is not unit-tested.
 */
object CediCrops {
    /** [rgb] (side×side×3, row-major) turned [k] quarter-turns clockwise. */
    fun turned(rgb: FloatArray, side: Int, k: Int): FloatArray {
        val n = ((k % 4) + 4) % 4
        if (n == 0) return rgb
        val out = FloatArray(rgb.size)
        for (y in 0 until side) for (x in 0 until side) {
            val (nx, ny) = when (n) { 1 -> (side - 1 - y) to x; 2 -> (side - 1 - x) to (side - 1 - y); else -> y to (side - 1 - x) }
            val from = (y * side + x) * 3; val to = (ny * side + nx) * 3
            out[to] = rgb[from]; out[to + 1] = rgb[from + 1]; out[to + 2] = rgb[from + 2]
        }
        return out
    }

    /** [box] = [ymin, xmin, ymax, xmax] on 0–1000, as the vision prompt asks for. Null when the window is unusable. */
    fun rgb(bmp: Bitmap, box: FloatArray, margin: Float = 1.25f): FloatArray? {
        val w = bmp.width; val h = bmp.height
        if (w < 16 || h < 16 || box.size != 4) return null
        val cx = (box[1] + box[3]) / 2000f * w
        val cy = (box[0] + box[2]) / 2000f * h
        val bw = (box[3] - box[1]) / 1000f * w
        val bh = (box[2] - box[0]) / 1000f * h
        val s = (maxOf(bw, bh) * margin).coerceIn(16f, minOf(w, h).toFloat()).toInt()
        val x = (cx - s / 2f).toInt().coerceIn(0, w - s)
        val y = (cy - s / 2f).toInt().coerceIn(0, h - s)
        return try {
            val crop = Bitmap.createBitmap(bmp, x, y, s, s)
            val scaled = Bitmap.createScaledBitmap(crop, CediClassifier.SIDE, CediClassifier.SIDE, true)
            val px = IntArray(CediClassifier.SIDE * CediClassifier.SIDE)
            scaled.getPixels(px, 0, CediClassifier.SIDE, 0, 0, CediClassifier.SIDE, CediClassifier.SIDE)
            val out = FloatArray(px.size * 3)
            for (i in px.indices) {
                val p = px[i]
                out[i * 3] = ((p shr 16) and 0xFF).toFloat()
                out[i * 3 + 1] = ((p shr 8) and 0xFF).toFloat()
                out[i * 3 + 2] = (p and 0xFF).toFloat()
            }
            if (scaled !== crop) scaled.recycle()
            if (crop !== bmp) crop.recycle()
            out
        } catch (_: Throwable) { null }
    }
}
