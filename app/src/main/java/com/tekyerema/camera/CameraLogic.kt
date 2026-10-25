package com.tekyerema.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Pure camera-perception logic for P-03 (read text), P-06 (colour and light) and P-09 (photo quality gate).
 * No Android types anywhere in this file, so every threshold is unit-tested on the JVM; CameraReadActivity only
 * moves pixels in and speech out.
 */

// ---------------------------------------------------------------------------------------------------------------- P-03

/**
 * Decides which OCR readings are worth saying aloud. A hand-held camera re-reads the same sign on every frame and OCR
 * jitters (a letter here, a block order there), so speaking every result would be a stream of repeats. A reading is
 * spoken only when it is:
 *  - real text: at least [minLetters] letters (stray marks and lone characters are noise);
 *  - STABLE: the same text in two consecutive frames, so a half-framed sign is not read while the phone moves;
 *  - NEW: not a near-duplicate (similarity ≥ [similarityThreshold]) of what was last spoken.
 * Numbers are never "near": "Room 101" and "Room 102" differ by one character but mean different doors, so texts
 * whose digits differ are always treated as different.
 * The spoken text is capped at [maxChars], cut at a word boundary: a whole page in one breath is not useful.
 */
class TextStabilizer(
    private val similarityThreshold: Double = 0.85,
    private val maxChars: Int = 400,
    private val minLetters: Int = 3
) {
    private var previous: String? = null     // normalised reading from the last frame
    private var lastSpoken: String? = null   // normalised text we last said

    /** Feed one frame's OCR text (blocks separated by newlines). Returns the text to speak now, or null. */
    fun offer(raw: String?): String? {
        val spoken = spokenForm(raw.orEmpty(), maxChars)
        if (spoken.count { it.isLetter() } < minLetters) { previous = null; return null }
        val norm = normalise(spoken)
        val prev = previous
        previous = norm
        if (prev == null || !same(prev, norm)) return null                  // not stable yet
        if (lastSpoken?.let { same(it, norm) } == true) return null          // already said this
        lastSpoken = norm
        return spoken
    }

    /** Forget everything (a mode switch starts a fresh reading). */
    fun reset() { previous = null; lastSpoken = null }

    private fun same(a: String, b: String): Boolean =
        a == b || (digits(a) == digits(b) && similarity(a, b) >= similarityThreshold)

    companion object {
        private val SPACES = Regex("\\s+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private const val SENTENCE_END = ".!?:;,"

        /**
         * Text as it should be spoken: each OCR line/block becomes its own sentence (so the voice pauses between a
         * sign's lines), inner whitespace collapses, and the result is capped at [maxChars] on a word boundary.
         */
        fun spokenForm(raw: String, maxChars: Int = 400): String {
            val sb = StringBuilder()
            for (line in raw.split('\n', '\r')) {
                val l = line.replace(SPACES, " ").trim()
                if (l.isEmpty()) continue
                if (sb.isNotEmpty()) {
                    val last = sb.last()
                    when {
                        // A line wrapped by the page, not ended by the writer: "…at 10 am" / "in the library" is one sentence, and
                        // "regis-" / "tration" is one word (P6, 25 October 2026 — the voice stopped mid-sentence at every wrap).
                        last == '-' && sb.length > 1 && sb[sb.length - 2].isLetter() && l[0].isLowerCase() -> sb.setLength(sb.length - 1)
                        last !in SENTENCE_END && l[0].isLowerCase() -> sb.append(' ')
                        else -> sb.append(if (last in SENTENCE_END) " " else ". ")
                    }
                }
                sb.append(l)
            }
            if (sb.length <= maxChars) return sb.toString()
            val space = sb.lastIndexOf(" ", maxChars)
            val cut = if (space > maxChars / 2) space else maxChars
            return sb.substring(0, cut).trimEnd()
        }

        /** Case-, punctuation- and whitespace-insensitive form used for every comparison. */
        fun normalise(text: String): String = text.lowercase().replace(NON_WORD, " ").trim()

        /**
         * 0..1: the better of edit similarity (catches single-letter OCR jitter) and token overlap (catches the same
         * blocks returned in a different order).
         */
        fun similarity(a: String, b: String): Double = max(editSimilarity(a, b), tokenOverlap(a, b))

        /** 1 − Levenshtein distance / longer length. */
        fun editSimilarity(a: String, b: String): Double {
            if (a == b) return 1.0
            val longest = max(a.length, b.length)
            if (longest == 0) return 1.0
            return 1.0 - com.tekyerema.core.TextDistance.levenshtein(a, b).toDouble() / longest
        }

        /** Jaccard overlap of the space-separated tokens. */
        fun tokenOverlap(a: String, b: String): Double {
            val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
            val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
            if (ta.isEmpty() && tb.isEmpty()) return 1.0
            val union = (ta union tb).size
            return if (union == 0) 0.0 else (ta intersect tb).size.toDouble() / union
        }

        private fun digits(s: String): String = s.filter { it.isDigit() }
    }
}

/**
 * P-06: speaks a label only when it has CHANGED, has held for two consecutive readings (so a boundary colour does not
 * flip-flop "blue"/"dark blue" every frame), and at least [minIntervalMs] after the last announcement — a blind user
 * needs a steady answer, not a running commentary.
 */
class ChangeAnnouncer(private val minIntervalMs: Long = 2000L) {
    private var previous: String? = null
    private var lastSpoken: String? = null
    private var lastSpokenAt: Long? = null

    /** Returns [label] when it should be spoken now, else null. [nowMs] is any monotonic clock. */
    fun offer(label: String, nowMs: Long): String? {
        val prev = previous
        previous = label
        if (label != prev || label == lastSpoken) return null
        val at = lastSpokenAt
        if (at != null && nowMs - at < minIntervalMs) return null
        lastSpoken = label
        lastSpokenAt = nowMs
        return label
    }
}

// ---------------------------------------------------------------------------------------------------------------- P-06

/**
 * Everyday colour names from an averaged RGB sample, via HSV: hue picks the family, value (brightness) and saturation
 * add "dark"/"light". Deliberately coarse — a blind user matching clothes needs "dark blue", not "#1F3A93"; brown is
 * named separately because it is how people say "dark orange", and pink is how they say "light red".
 */
object ColorNames {
    fun name(r: Int, g: Int, b: Int): String {
        val rr = r.coerceIn(0, 255); val gg = g.coerceIn(0, 255); val bb = b.coerceIn(0, 255)
        val hi = max(rr, max(gg, bb))
        val lo = min(rr, min(gg, bb))
        val chroma = hi - lo
        val v = hi / 255.0
        val s = if (hi == 0) 0.0 else chroma.toDouble() / hi
        if (v < 0.16) return "black"
        // Nearly no colour: a grey scale. The absolute chroma floor stops sensor noise in dim frames reading as a hue.
        if (s < 0.15 || chroma < 20) return when {
            v >= 0.85 -> "white"
            v < 0.25 -> "black"
            v < 0.45 -> "dark grey"
            v > 0.70 -> "light grey"
            else -> "grey"
        }
        val h = hue(rr, gg, bb, hi, chroma)
        val dark = v < 0.6
        val light = !dark && s < 0.45 && v > 0.75
        return when {
            h < 15 || h >= 345 -> when { light -> "pink"; dark -> "dark red"; else -> "red" }
            h < 45 -> when {
                v < 0.35 -> "dark brown"
                v < 0.65 -> "brown"
                s < 0.5 && v < 0.9 -> "light brown"
                light -> "light orange"
                else -> "orange"
            }
            h < 70 -> qualify("yellow", dark, light)
            h < 165 -> qualify("green", dark, light)
            h < 255 -> qualify("blue", dark, light)
            h < 290 -> qualify("purple", dark, light)
            else -> when { v < 0.35 -> "dark purple"; v < 0.55 -> "purple"; light -> "light pink"; else -> "pink" }
        }
    }

    private fun qualify(base: String, dark: Boolean, light: Boolean) = when {
        dark -> "dark $base"
        light -> "light $base"
        else -> base
    }

    /** Hue in degrees [0, 360). */
    private fun hue(r: Int, g: Int, b: Int, hi: Int, chroma: Int): Double {
        val c = chroma.toDouble()
        val h = when (hi) {
            r -> 60.0 * (((g - b) / c) % 6.0)
            g -> 60.0 * ((b - r) / c + 2.0)
            else -> 60.0 * ((r - g) / c + 4.0)
        }
        return if (h < 0) h + 360.0 else h
    }
}

/**
 * P-06: how much light the camera sees, from the frame's mean luma (0..255). The camera's auto-exposure lifts dim
 * scenes, so these words are relative ("dim" means even the camera is struggling), never a lux reading.
 */
object LightLevel {
    fun describe(
        meanLuma: Double,
        veryDarkBelow: Double = 40.0,
        dimBelow: Double = 90.0,
        brightFrom: Double = 190.0
    ): String = when {
        meanLuma < veryDarkBelow -> "very dark"
        meanLuma < dimBelow -> "dim"
        meanLuma < brightFrom -> "normal light"
        else -> "bright"
    }
}

/** Full-range BT.601 YUV → RGB: the JFIF matrix Android cameras use for YUV_420_888 frames. */
object YuvColor {
    fun toRgb(y: Double, u: Double, v: Double): IntArray {
        val d = u - 128.0
        val e = v - 128.0
        return intArrayOf(clamp(y + 1.402 * e), clamp(y - 0.344136 * d - 0.714136 * e), clamp(y + 1.772 * d))
    }

    private fun clamp(x: Double): Int = x.roundToInt().coerceIn(0, 255)
}

/** Plane statistics over raw sample bytes (strides as given by the camera). */
object FrameStats {
    /**
     * Mean of the unsigned samples in [left, right) × [top, bottom) (plane coordinates), sampling every [step]-th
     * sample in both directions. Indices past the end of [plane] are skipped: the last row of a camera plane is often
     * shorter than rowStride.
     */
    fun mean(
        plane: ByteArray, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, right: Int, bottom: Int, step: Int = 1
    ): Double {
        val s = max(1, step)
        var sum = 0L
        var n = 0
        var y = max(0, top)
        while (y < bottom) {
            val row = y * rowStride
            var x = max(0, left)
            while (x < right) {
                val i = row + x * pixelStride
                if (i < plane.size) { sum += plane[i].toInt() and 0xFF; n++ }
                x += s
            }
            y += s
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /**
     * The centre box covering [fraction] of each side, as [left, top, right, bottom]. Left/top are even so halving
     * them lands on the matching 2×2-subsampled chroma sample.
     */
    fun centreBox(width: Int, height: Int, fraction: Double = 0.2): IntArray {
        val f = fraction.coerceIn(0.02, 1.0)
        val bw = max(2, (width * f).roundToInt()).coerceAtMost(width)
        val bh = max(2, (height * f).roundToInt()).coerceAtMost(height)
        val left = ((width - bw) / 2) and 1.inv()
        val top = ((height - bh) / 2) and 1.inv()
        return intArrayOf(left, top, min(width, left + bw), min(height, top + bh))
    }
}

// ---------------------------------------------------------------------------------------------------------------- P-09

/** Why a frame is (not) worth describing. */
enum class Verdict { OK, TOO_DARK, TOO_BRIGHT, BLURRY }

/** [blurScore] = variance of the Laplacian on the downsampled grid (higher = sharper); [meanLuma] 0..255. */
data class Quality(val blurScore: Double, val meanLuma: Double, val verdict: Verdict)

/**
 * P-09 — is this frame good enough to send for a description? A blind user cannot see that the photo is black, washed
 * out or smeared, and a cloud description of a bad frame ("a blurry dark image") wastes their time and a request. So:
 *  - exposure: mean luma below [DARK_BELOW] is too dark; above [BRIGHT_ABOVE], or more than [GLARE_FRACTION] of the
 *    frame blown out, is glare;
 *  - blur: variance of a 4-neighbour Laplacian. It is computed on a grid ~[GRID_WIDTH] cells wide (each cell the box
 *    average of its pixels), so the score means the same at 640×480 and 1280×960 and sensor noise is averaged away
 *    instead of passing for detail. Exposure is checked first: a black frame is also "flat", but the useful advice is
 *    the light, not the hand.
 */
object QualityGate {
    const val DARK_BELOW = 40.0
    const val BRIGHT_ABOVE = 215.0
    const val GLARE_FRACTION = 0.3
    const val BLUR_MIN = 50.0
    const val GRID_WIDTH = 320
    private const val CLIPPED = 250.0

    fun assess(
        luma: ByteArray, width: Int, height: Int, rowStride: Int,
        darkBelow: Double = DARK_BELOW,
        brightAbove: Double = BRIGHT_ABOVE,
        blurMin: Double = BLUR_MIN
    ): Quality {
        require(width > 0 && height > 0 && rowStride >= width) { "bad frame geometry ${width}x$height stride $rowStride" }
        require(luma.size >= (height - 1) * rowStride + width) { "luma buffer too small" }
        val step = max(1, width / GRID_WIDTH)
        val gw = width / step
        val gh = height / step
        val grid = DoubleArray(gw * gh)
        val area = (step * step).toDouble()
        var sum = 0.0
        var clipped = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var acc = 0
            for (dy in 0 until step) {
                val row = (gy * step + dy) * rowStride + gx * step
                for (dx in 0 until step) acc += luma[row + dx].toInt() and 0xFF
            }
            val v = acc / area
            grid[gy * gw + gx] = v
            sum += v
            if (v >= CLIPPED) clipped++
        }
        val cells = gw * gh
        val mean = sum / cells
        val blur = laplacianVariance(grid, gw, gh)
        val verdict = when {
            mean < darkBelow -> Verdict.TOO_DARK
            mean > brightAbove || clipped.toDouble() / cells > GLARE_FRACTION -> Verdict.TOO_BRIGHT
            blur < blurMin -> Verdict.BLURRY
            else -> Verdict.OK
        }
        return Quality(blur, mean, verdict)
    }

    /** What to tell the user for a failed [verdict] (empty for OK). Short: it may be repeated every few seconds. */
    fun guidance(verdict: Verdict): String = when (verdict) {
        Verdict.TOO_DARK -> "It's too dark — try turning on a light."
        Verdict.TOO_BRIGHT -> "Too much glare — tilt the phone a little."
        Verdict.BLURRY -> "Hold the phone still for a moment."
        Verdict.OK -> ""
    }

    private fun laplacianVariance(g: DoubleArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
                sum += l
                sumSq += l * l
                n++
            }
        }
        val m = sum / n
        return max(0.0, sumSq / n - m * m)
    }
}

// ---------------------------------------------------------------------------------------------------------------- cedi

/**
 * Ghana banknotes and coins read by camera (docs/agent/research/cedi_integration.md). Seeing AI and Lookout both
 * skip the cedi, so this is the first of its kind — which is exactly why it must be HONEST: a wrong amount is worse
 * than no answer. A denomination is spoken only when the SAME class holds two consecutive frames and both clear
 * [minConfidence]; anything else coaches instead. Pure → unit-tested.
 */
class CurrencyAnnouncer(
    private val minConfidence: Double = 0.85,
    private val agreeFrames: Int = 2,
    /** The same note, having gone away, isn't re-announced sooner than this. */
    private val repeatMs: Long = 4_000L
) {
    private var runClass: String? = null
    private var runLength = 0
    private var spoken: String? = null
    private var spokenAt = 0L

    /** One frame's verdict. Returns the line to speak, or null (sub-gate, unchanged, or still settling). */
    fun offer(topClass: String, confidence: Double, nowMs: Long): String? {
        // Not money, or not sure enough: the run breaks — two AGREEING, CONFIDENT frames are required.
        if (topClass == NOT_MONEY || confidence < minConfidence) {
            runClass = null; runLength = 0
            return null
        }
        if (topClass == runClass) runLength++ else { runClass = topClass; runLength = 1 }
        if (runLength < agreeFrames) return null
        // Stable. Speak when it CHANGED, or when the same note comes back after a while (counting cash).
        if (topClass == spoken && nowMs - spokenAt < repeatMs) return null
        spoken = topClass
        spokenAt = nowMs
        return spokenFor(topClass)
    }

    /** True while nothing has been announced — the caller's "I can't tell" coaching applies. */
    val quiet: Boolean get() = spoken == null

    companion object {
        const val NOT_MONEY = "not_money"

        /** The class as a person says it. Verbalize runs on the output as everywhere else. */
        fun spokenFor(cls: String): String = when (cls) {
            "note_1" -> "One cedi."
            "note_2" -> "Two cedis."
            "note_5" -> "Five cedis."
            "note_10" -> "Ten cedis."
            "note_20" -> "Twenty cedis."
            "note_50" -> "Fifty cedis."
            "note_100" -> "One hundred cedis."
            "note_200" -> "Two hundred cedis."
            "coin_1_pesewa" -> "One pesewa — a coin."
            "coin_5_pesewas" -> "Five pesewas — a coin."
            "coin_10_pesewas" -> "Ten pesewas — a coin."
            "coin_20_pesewas" -> "Twenty pesewas — a coin."
            "coin_50_pesewas" -> "Fifty pesewas — a coin."
            "coin_1_cedi" -> "One cedi — a coin."
            "coin_2_cedis" -> "Two cedis — a coin."
            else -> cls
        }

        /** A note or coin class (as opposed to [NOT_MONEY]) — "money is there, I just can't read it". */
        fun isMoney(cls: String): Boolean = cls != NOT_MONEY
    }
}

/** Timing for lines the camera screen hands to another voice (its own engine failed). Pure. */
object CameraSpeech {
    /**
     * How long [line] needs to be heard at a normal speaking pace before the screen may close: about 70 ms a
     * character, never under 1.5 s (a short line still needs the voice to start) and never over 8 s.
     */
    fun hearingMs(line: String): Long = (line.trim().length * 70L).coerceIn(1_500L, 8_000L)
}
