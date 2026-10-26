package com.tekyerema.camera

/**
 * Where the note or coin is in the frame, so the classifier can look at IT and not at the whole centre square.
 *
 * The cedi model sees a 224 px square. A coin held in the middle of the camera's square fills ~15 % of it (35 px) and a
 * 10 pesewas looks like a 1 cedi or a 20 pesewas at that size: 49 of the 75 misses on the held-out real photos (campaign/
 * evals/cedi/V3_PLAN_2026-10-26.md). Cropped to the object (with a margin) the same model read 97.8 % of them (was 93.0 %),
 * with 1 wrong denomination spoken instead of 15; 98 % of the 10 pesewas coins instead of 56 %.
 *
 * Method: take the background colour from the frame's border ring (median), measure every pixel's colour distance from it,
 * threshold by Otsu (never below 18), keep what lies in the central 90 %, and take its 1st–99th percentile box. Pure, no
 * model, a few thousand operations on a 96×96 grid. A frame with no clear object (plain, or the object fills everything)
 * has no box and the caller uses the whole square, as before. Pure → ObjectCropTest.
 */
object ObjectCrop {
    /** Box in grid pixels; [frac] is its share of the grid's area. */
    data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val frac: Float)

    /** A square window of the camera frame, in frame pixels. */
    data class Window(val x: Int, val y: Int, val size: Int)

    const val GRID = 96
    private const val BINS = 64
    private const val MIN_DIST = 18f

    /** A box worth trusting: 1–85 % of the grid (smaller is noise, larger is the whole picture). */
    fun plausible(b: Box?): Boolean = b != null && b.frac in 0.01f..0.85f

    /** The object in [rgb] (side×side×3 floats 0–255, row-major), or null when none stands out from the background. */
    fun find(rgb: FloatArray, side: Int): Box? {
        val n = side * side
        if (rgb.size < n * 3) return null
        val ring = maxOf(2, (0.06f * side).toInt())
        val inner = side - 2 * ring
        val bc = n - inner * inner
        val br = FloatArray(bc); val bg = FloatArray(bc); val bb = FloatArray(bc)
        var k = 0
        for (y in 0 until side) for (x in 0 until side) {
            if (y < ring || y >= side - ring || x < ring || x >= side - ring) {
                val i = (y * side + x) * 3
                br[k] = rgb[i]; bg[k] = rgb[i + 1]; bb[k] = rgb[i + 2]; k++
            }
        }
        br.sort(); bg.sort(); bb.sort()
        val mr = br[bc / 2]; val mg = bg[bc / 2]; val mb = bb[bc / 2]
        val d = FloatArray(n)
        var maxD = 0f
        for (i in 0 until n) {
            val dr = rgb[i * 3] - mr; val dg = rgb[i * 3 + 1] - mg; val db = rgb[i * 3 + 2] - mb
            val v = Math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
            d[i] = v
            if (v > maxD) maxD = v
        }
        if (maxD <= 0f) return null
        val span = maxD + 1e-6f
        val hist = IntArray(BINS)
        for (v in d) hist[minOf(BINS - 1, (v / span * BINS).toInt())]++
        // Otsu on the distance histogram
        var cum = 0.0; var mu = 0.0
        val om = DoubleArray(BINS); val muA = DoubleArray(BINS)
        for (i in 0 until BINS) {
            val p = hist[i].toDouble() / n
            cum += p; mu += p * (i * span / BINS)
            om[i] = cum; muA[i] = mu
        }
        val mt = muA[BINS - 1]
        var best = 0; var bestV = -1.0
        for (i in 0 until BINS) {
            val s = (mt * om[i] - muA[i]).let { it * it } / maxOf(om[i] * (1 - om[i]), 1e-9)
            if (s > bestV) { bestV = s; best = i }
        }
        val t = maxOf(MIN_DIST, best * span / BINS)
        val half = 0.45f * side
        val c = side / 2f
        val xs = IntArray(n); val ys = IntArray(n)
        var m = 0
        for (y in 0 until side) for (x in 0 until side) {
            if (d[y * side + x] > t && Math.abs(y - c) < half && Math.abs(x - c) < half) { xs[m] = x; ys[m] = y; m++ }
        }
        if (m < 0.002f * n) return null
        val sx = xs.copyOf(m).also { it.sort() }; val sy = ys.copyOf(m).also { it.sort() }
        val x0 = percentile(sx, 0.01f); val x1 = percentile(sx, 0.99f)
        val y0 = percentile(sy, 0.01f); val y1 = percentile(sy, 0.99f)
        return Box(x0, y0, x1, y1, ((x1 - x0) * (y1 - y0)) / n)
    }

    /** numpy-style linear-interpolated percentile of a sorted array. */
    private fun percentile(sorted: IntArray, q: Float): Float {
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt(); val hi = minOf(sorted.size - 1, lo + 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** The camera-frame window for [box] (grid coordinates of the frame's centre square): the object plus [margin], square. */
    fun window(box: Box, frameW: Int, frameH: Int, margin: Float = 1.25f): Window {
        val crop = minOf(frameW, frameH)
        val ox = (frameW - crop) / 2; val oy = (frameH - crop) / 2
        val k = crop / GRID.toFloat()
        val cx = (box.x0 + box.x1) / 2f * k; val cy = (box.y0 + box.y1) / 2f * k
        val s = (maxOf(box.x1 - box.x0, box.y1 - box.y0) * margin * k).coerceIn(minOf(24f, crop.toFloat()), crop.toFloat())
        val xa = (cx - s / 2f).coerceIn(0f, crop - s); val ya = (cy - s / 2f).coerceIn(0f, crop - s)
        return Window(ox + xa.toInt(), oy + ya.toInt(), s.toInt())
    }

    /**
     * The reading from the whole centre square and from the object crop, as one answer. "Sure" means reaching that class's own
     * gate ([gateOf], the announcer's per-class table). Both sure and naming different things: silence (confidence 0, the
     * crop's class so the "I can see money" coaching still works). Only the crop sure: the crop (it has the detail). Only the
     * whole square sure: the whole square, exactly as before — the crop never costs an answer the old way gave. Neither: the
     * more confident one. No crop found → the whole square. On the 1,074 held-out real photos with the v2 model: answered
     * 78.7 % → 88.7 %, 100 % correct when answered, none wrong (10 pesewas 16 % → 60 %, 1 cedi coin 49 % → 90 %).
     */
    fun decide(fullLabel: String, fullConf: Double, tightLabel: String?, tightConf: Double, gateOf: (String) -> Double): Pair<String, Double> {
        if (tightLabel == null) return fullLabel to fullConf
        val fullSure = fullConf >= gateOf(fullLabel)
        val tightSure = tightConf >= gateOf(tightLabel)
        return when {
            fullSure && tightSure && fullLabel != tightLabel -> tightLabel to 0.0
            tightSure -> tightLabel to tightConf
            fullSure -> fullLabel to fullConf
            tightConf >= fullConf -> tightLabel to tightConf
            else -> fullLabel to fullConf
        }
    }
}
