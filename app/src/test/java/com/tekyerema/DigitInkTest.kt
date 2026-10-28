package com.tekyerema

import com.tekyerema.money.DigitInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DigitInkTest {
    // How people actually draw them, in a 0..1 box (y down) — independent of the recogniser's own shapes where it matters.
    private val drawn: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        '0' to listOf((0..30).map { i -> val a = 2 * Math.PI * i / 30; (0.5 + 0.3 * cos(a)).toFloat() to (0.5 + 0.48 * sin(a)).toFloat() }),
        '1' to listOf(listOf(0.52f to 0.02f, 0.5f to 0.5f, 0.49f to 0.98f)),
        '2' to listOf(listOf(0.18f to 0.22f, 0.4f to 0.02f, 0.75f to 0.08f, 0.78f to 0.35f, 0.5f to 0.65f, 0.15f to 0.98f, 0.88f to 0.97f)),
        '3' to listOf(listOf(0.2f to 0.08f, 0.6f to 0.02f, 0.78f to 0.2f, 0.5f to 0.47f, 0.8f to 0.7f, 0.6f to 0.97f, 0.18f to 0.92f)),
        '4' to listOf(listOf(0.2f to 0.02f, 0.18f to 0.6f, 0.88f to 0.6f), listOf(0.7f to 0.05f, 0.7f to 0.98f)),
        '5' to listOf(listOf(0.8f to 0.02f, 0.28f to 0.03f, 0.22f to 0.46f, 0.6f to 0.4f, 0.84f to 0.65f, 0.6f to 0.97f, 0.18f to 0.9f)),
        '6' to listOf(listOf(0.75f to 0.04f, 0.35f to 0.3f, 0.22f to 0.7f, 0.5f to 0.98f, 0.78f to 0.75f, 0.5f to 0.52f, 0.24f to 0.7f)),
        '7' to listOf(listOf(0.15f to 0.02f, 0.85f to 0.03f, 0.45f to 0.98f)),
        '8' to listOf(listOf(0.7f to 0.1f, 0.5f to 0.02f, 0.28f to 0.18f, 0.5f to 0.48f, 0.75f to 0.75f, 0.5f to 0.98f, 0.25f to 0.75f, 0.5f to 0.48f, 0.7f to 0.25f, 0.7f to 0.1f)),
        '9' to listOf(listOf(0.75f to 0.25f, 0.5f to 0.02f, 0.27f to 0.2f, 0.4f to 0.45f, 0.75f to 0.3f, 0.73f to 0.98f))
    )

    /** A drawing in pixels: densified, scaled, slanted a little, jittered — what the touch pad would collect. */
    private fun render(strokes: List<List<Pair<Float, Float>>>, seed: Int, reverse: Boolean = false, startShift: Int = 0): List<List<Pair<Float, Float>>> {
        val r = java.util.Random(seed.toLong())
        val scale = 150f + r.nextFloat() * 250f
        val slant = (r.nextFloat() - 0.5f) * 0.3f
        val ox = r.nextFloat() * 400f; val oy = r.nextFloat() * 600f
        return strokes.map { s ->
            val dense = s.zipWithNext().flatMap { (a, b) -> (0 until 8).map { t -> val f = t / 8f; (a.first + f * (b.first - a.first)) to (a.second + f * (b.second - a.second)) } } + s.last()
            var pts = dense.map { (x, y) -> (ox + scale * (x + slant * (y - 0.5f)) + r.nextGaussian().toFloat() * 3f) to (oy + scale * 1.4f * y + r.nextGaussian().toFloat() * 3f) }
            if (startShift > 0 && pts.size > startShift) pts = pts.drop(startShift) + pts.take(startShift)
            if (reverse) pts.reversed() else pts
        }
    }

    @Test fun drawnDigitsAreReadAndWrongOnesAreRare() {
        var right = 0; var wrong = 0; var refused = 0; val confusions = mutableListOf<String>()
        for ((d, strokes) in drawn) for (seed in 0 until 40) {
            val got = DigitInk.recognize(render(strokes, seed * 31 + d.code, reverse = seed % 3 == 0, startShift = if (d == '0') seed else 0))
            when (got) { d -> right++; null -> { refused++; confusions.add("$d→none") }; else -> { wrong++; confusions.add("$d→$got") } }
        }
        val n = right + wrong + refused
        println("DigitInk: right=$right wrong=$wrong refused=$refused of $n; confusions=${confusions.groupingBy { it }.eachCount()}")
        assertTrue("right ${right * 100 / n}%", right >= n * 0.85)
        assertTrue("wrong $wrong of $n", wrong <= n * 0.02)
    }

    @Test fun aScribbleIsRefused() {
        val r = java.util.Random(7)
        for (k in 0 until 20) {
            val rr = java.util.Random(k.toLong())
            assertNull("scribble $k", DigitInk.recognize(listOf((0 until 60).map { (rr.nextFloat() * 300f) to (rr.nextFloat() * 300f) })))
        }
        assertNull(DigitInk.recognize(listOf(listOf(10f to 10f))))
    }

    @Test fun aPlainVerticalLineIsOne() {
        assertEquals('1', DigitInk.recognize(listOf((0..20).map { 200f to (100f + it * 15f) })))
    }
}
