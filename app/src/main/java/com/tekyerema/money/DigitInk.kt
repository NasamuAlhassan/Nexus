package com.tekyerema.money

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A digit DRAWN with a finger on the PIN touch pad → the digit (owner, 28 Oct 2026: "the ability to draw the digits on the touchpad
 * which keys them in"). On the phone, no model: the $P point-cloud recogniser (Vatavu, Anthony & Wobbrock, ICMI 2012) against a few
 * drawn shapes per digit. Point clouds ignore stroke order and direction, so a "4" in one stroke or two, or a "0" started anywhere,
 * match the same. A shape too far from every digit, or too close to two different digits, is refused ([recognize] → null): in a PIN a
 * wrong digit is worse than "draw it again". Pure → DigitInkTest.
 */
object DigitInk {
    data class P(val x: Float, val y: Float, val stroke: Int)

    private const val N = 32

    /** Strokes in the unit box (x right, y down), each a polyline; a few ways people write each digit. */
    private val SHAPES: Map<Char, List<List<List<Pair<Float, Float>>>>> = mapOf(
        '0' to listOf(listOf(ellipse(0.5f, 0.5f, 0.32f, 0.5f)), listOf(ellipse(0.5f, 0.5f, 0.42f, 0.5f))),
        '1' to listOf(listOf(listOf(0.5f to 0f, 0.5f to 1f)), listOf(listOf(0.3f to 0.18f, 0.5f to 0f, 0.5f to 1f))),
        '2' to listOf(listOf(listOf(0.15f to 0.25f, 0.3f to 0.05f, 0.55f to 0f, 0.8f to 0.12f, 0.82f to 0.35f, 0.6f to 0.6f, 0.15f to 1f, 0.9f to 1f))),
        '3' to listOf(listOf(listOf(0.15f to 0.1f, 0.5f to 0f, 0.8f to 0.12f, 0.8f to 0.32f, 0.45f to 0.48f, 0.82f to 0.62f, 0.85f to 0.85f, 0.55f to 1f, 0.15f to 0.9f)),
            listOf(listOf(0.15f to 0f, 0.8f to 0f, 0.45f to 0.42f, 0.8f to 0.6f, 0.82f to 0.85f, 0.55f to 1f, 0.15f to 0.9f)),
            listOf(listOf(0.2f to 0.08f, 0.6f to 0.02f, 0.78f to 0.2f, 0.5f to 0.47f, 0.8f to 0.7f, 0.6f to 0.97f, 0.18f to 0.92f))),
        '4' to listOf(
            listOf(listOf(0.65f to 1f, 0.65f to 0f, 0.12f to 0.65f, 0.9f to 0.65f)),
            listOf(listOf(0.15f to 0f, 0.15f to 0.6f, 0.9f to 0.6f), listOf(0.68f to 0f, 0.68f to 1f))),
        '5' to listOf(
            listOf(listOf(0.82f to 0f, 0.25f to 0f, 0.2f to 0.45f, 0.55f to 0.38f, 0.85f to 0.58f, 0.85f to 0.82f, 0.55f to 1f, 0.15f to 0.9f)),
            listOf(listOf(0.25f to 0f, 0.2f to 0.45f, 0.55f to 0.38f, 0.85f to 0.58f, 0.85f to 0.82f, 0.55f to 1f, 0.15f to 0.9f), listOf(0.25f to 0f, 0.85f to 0f)),
            listOf(listOf(0.8f to 0f, 0.3f to 0f, 0.25f to 0.42f, 0.5f to 0.38f, 0.8f to 0.5f, 0.85f to 0.75f, 0.65f to 0.97f, 0.3f to 0.98f, 0.12f to 0.85f)),
            listOf(listOf(0.8f to 0.02f, 0.28f to 0.03f, 0.22f to 0.46f, 0.6f to 0.4f, 0.84f to 0.65f, 0.6f to 0.97f, 0.18f to 0.9f))),
        '6' to listOf(listOf(listOf(0.78f to 0.03f, 0.4f to 0.25f, 0.2f to 0.6f, 0.25f to 0.88f, 0.5f to 1f, 0.78f to 0.85f, 0.8f to 0.62f, 0.5f to 0.5f, 0.22f to 0.66f))),
        '7' to listOf(listOf(listOf(0.12f to 0f, 0.88f to 0f, 0.42f to 1f)), listOf(listOf(0.12f to 0f, 0.88f to 0f, 0.42f to 1f), listOf(0.38f to 0.5f, 0.78f to 0.5f))),
        '8' to listOf(listOf(listOf(0.72f to 0.12f, 0.5f to 0f, 0.25f to 0.15f, 0.3f to 0.35f, 0.5f to 0.47f, 0.78f to 0.65f, 0.75f to 0.9f, 0.5f to 1f, 0.22f to 0.88f, 0.25f to 0.65f, 0.5f to 0.47f, 0.72f to 0.32f, 0.72f to 0.12f))),
        '9' to listOf(
            listOf(listOf(0.78f to 0.22f, 0.55f to 0f, 0.25f to 0.12f, 0.25f to 0.38f, 0.55f to 0.48f, 0.8f to 0.3f, 0.78f to 0.22f, 0.75f to 1f)),
            listOf(listOf(0.78f to 0.22f, 0.55f to 0f, 0.25f to 0.12f, 0.25f to 0.38f, 0.55f to 0.48f, 0.8f to 0.3f, 0.78f to 0.22f, 0.55f to 1f)))
    )

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int = 24): List<Pair<Float, Float>> =
        (0..n).map { i -> val a = -Math.PI / 2 + 2 * Math.PI * i / n; (cx + rx * Math.cos(a)).toFloat() to (cy + ry * Math.sin(a)).toFloat() }

    private val templates: List<Pair<Char, List<P>>> by lazy {
        SHAPES.flatMap { (d, variants) -> variants.map { strokes ->
            d to normalize(strokes.flatMapIndexed { si, s -> s.map { (x, y) -> P(x, y, si) } }) } }
    }

    /**
     * The digit [strokes] draw (each stroke a list of (x, y) in any pixel units), or null when nothing is close enough or two digits
     * are nearly as close. [maxDist] and [margin] are on the normalised $P distance (28 Oct 2026, 400 synthetic drawings: 1.5 / 1.12 read
     * 91 %, misread none, refused every random scribble; 1.06 misread one).
     */
    fun recognize(strokes: List<List<Pair<Float, Float>>>, maxDist: Float = 1.5f, margin: Float = 1.12f): Char? {
        val pts = strokes.flatMapIndexed { si, s -> s.map { (x, y) -> P(x, y, si) } }
        if (pts.size < 2) return null
        val cand = normalize(pts)
        val best = HashMap<Char, Float>()
        for ((d, t) in templates) {
            val dist = cloudDistance(cand, t)
            if (dist < (best[d] ?: Float.MAX_VALUE)) best[d] = dist
        }
        val sorted = best.entries.sortedBy { it.value }
        val first = sorted.firstOrNull() ?: return null
        if (first.value > maxDist) return null
        val second = sorted.getOrNull(1)?.value ?: Float.MAX_VALUE
        return if (second / max(first.value, 1e-3f) < margin) null else first.key
    }

    // ---------------------------------------------------------------------------------------------------------------- $P
    internal fun normalize(pts: List<P>): List<P> {
        val r = resample(pts, N)
        val minX = r.minOf { it.x }; val maxX = r.maxOf { it.x }; val minY = r.minOf { it.y }; val maxY = r.maxOf { it.y }
        val size = max(max(maxX - minX, maxY - minY), 1e-3f)
        val scaled = r.map { P((it.x - minX) / size, (it.y - minY) / size, it.stroke) }
        val cx = scaled.sumOf { it.x.toDouble() }.toFloat() / scaled.size
        val cy = scaled.sumOf { it.y.toDouble() }.toFloat() / scaled.size
        return scaled.map { P(it.x - cx, it.y - cy, it.stroke) }
    }

    private fun resample(pts: List<P>, n: Int): List<P> {
        var len = 0f
        for (i in 1 until pts.size) if (pts[i].stroke == pts[i - 1].stroke) len += dist(pts[i], pts[i - 1])
        val interval = len / (n - 1)
        if (interval <= 0f) return List(n) { pts[0] }
        val src = pts.toMutableList()
        val out = ArrayList<P>(n).apply { add(src[0]) }
        var acc = 0f
        var i = 1
        while (i < src.size) {
            val a = src[i - 1]; val b = src[i]
            if (a.stroke == b.stroke) {
                val d = dist(a, b)
                if (acc + d >= interval && d > 0f) {
                    val t = (interval - acc) / d
                    val q = P(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.stroke)
                    out.add(q); src.add(i, q); acc = 0f
                } else acc += d
            }
            i++
        }
        while (out.size < n) out.add(src.last())
        return out.take(n)
    }

    private fun cloudDistance(a: List<P>, b: List<P>): Float {
        val step = sqrt(a.size.toFloat()).toInt().coerceAtLeast(1)
        var best = Float.MAX_VALUE
        var i = 0
        while (i < a.size) {
            best = min(best, min(greedy(a, b, i), greedy(b, a, i)))
            i += step
        }
        return best
    }

    private fun greedy(a: List<P>, b: List<P>, start: Int): Float {
        val matched = BooleanArray(a.size)
        var sum = 0f
        var i = start
        do {
            var minD = Float.MAX_VALUE; var idx = -1
            for (j in b.indices) if (!matched[j]) { val d = dist(a[i], b[j]); if (d < minD) { minD = d; idx = j } }
            if (idx >= 0) matched[idx] = true
            val w = 1f - ((i - start + a.size) % a.size) / a.size.toFloat()
            sum += w * minD
            i = (i + 1) % a.size
        } while (i != start)
        return sum
    }

    private fun dist(a: P, b: P): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
}
