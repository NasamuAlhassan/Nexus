package com.tekyerema.ime

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * G-05 (2) — swipe typing (13 October 2026): the finger slides across the letters of a word and lifts; the word is
 * decided here, on the phone, from the keys the path crossed and the word lists (English and the user's own). Pure,
 * so every rule is unit-tested: [steps] turns sampled touch points into the keys they passed over and marks the ones
 * the user meant (where the path began, ended or turned a corner); [decode] ranks the words.
 *
 * The ranking is the classic shape-free one (SHARK-style, simplified): a candidate starts on the first key and ideally
 * ends on the last; every letter of the word must be found along the path in order; keys the path merely passed over
 * cost a little (a straight swipe from h to o crosses j, k and l); a letter the path never touched costs a lot and at
 * most two are forgiven; a corner the word does not use counts against it; frequency breaks ties. Nothing is corrected
 * silently: the best word is committed and the runners-up are offered in the strip, where they can be heard and picked.
 */
object GestureDecoder {
    data class Pt(val x: Float, val y: Float)

    /** A key's box on the keyboard, in the same coordinates as the touch points. */
    data class KeyBox(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }

    /** One key the finger crossed. [anchor]: the path began, ended or turned here — a key the user meant, not one on the way. */
    data class Step(val key: String, val anchor: Boolean)

    /** A word list and its standing: [scale] multiplies counts so a small hand-made list compares with a corpus; [bonus] is added to each of its words. */
    data class Source(val words: List<Pair<String, Int>>, val bonus: Double = 0.0, val scale: Double = 1.0)

    fun pathLength(points: List<Pt>): Float {
        var d = 0f
        for (i in 1 until points.size) d += dist(points[i - 1], points[i])
        return d
    }

    /** A touch that began on a letter has become a swipe: it visited a second letter key and travelled [minTravel]. */
    fun isGesture(points: List<Pt>, keys: List<KeyBox>, minTravel: Float): Boolean =
        points.size >= 3 && pathLength(points) >= minTravel && steps(points, keys).size >= 2

    /**
     * The letter keys the path crossed, in order, consecutive repeats folded ("h h e e l l o" → h e l o: the path, not
     * the word). The path is resampled every [spacing] pixels first, so a fast finger cannot jump over a key, and a key
     * is an anchor when the path starts, ends or turns by more than [cornerDeg] degrees on it.
     */
    fun steps(points: List<Pt>, keys: List<KeyBox>, spacing: Float = 8f, cornerDeg: Float = 55f): List<Step> {
        val rs = resample(points, spacing)
        if (rs.isEmpty()) return emptyList()
        val corner = corners(rs, cornerDeg)
        val out = ArrayList<Step>()
        for ((i, p) in rs.withIndex()) {
            val k = keys.firstOrNull { it.contains(p.x, p.y) } ?: continue
            if (k.text.length != 1 || !k.text[0].isLetter()) continue
            val t = k.text.lowercase()
            val meant = corner[i] || i == 0 || i == rs.lastIndex
            if (out.isEmpty() || out.last().key != t) out += Step(t, meant)
            else if (meant && !out.last().anchor) out[out.lastIndex] = Step(t, true)
        }
        return out
    }

    fun keySequence(points: List<Pt>, keys: List<KeyBox>): List<String> = steps(points, keys).map { it.key }

    /** The path as points [spacing] apart (the first and last kept), so direction and coverage do not depend on how often the screen sampled. */
    internal fun resample(points: List<Pt>, spacing: Float): List<Pt> {
        if (points.isEmpty() || spacing <= 0f) return points
        val out = ArrayList<Pt>(points.size)
        out += points[0]
        var carried = 0f
        var prev = points[0]
        for (i in 1 until points.size) {
            val p = points[i]
            var d = dist(prev, p)
            while (d > 0f && carried + d >= spacing) {
                val t = (spacing - carried) / d
                val q = Pt(prev.x + t * (p.x - prev.x), prev.y + t * (p.y - prev.y))
                out += q
                prev = q
                d = dist(prev, p)
                carried = 0f
            }
            carried += d
            prev = p
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }

    /** Which resampled points sit on a turn of at least [minDeg] degrees (arms of [arm] samples either side). */
    internal fun corners(rs: List<Pt>, minDeg: Float, arm: Int = 4): BooleanArray {
        val flags = BooleanArray(rs.size)
        val cosMax = Math.cos(Math.toRadians(minDeg.toDouble()))
        for (i in arm until rs.size - arm) {
            val ax = rs[i].x - rs[i - arm].x; val ay = rs[i].y - rs[i - arm].y
            val bx = rs[i + arm].x - rs[i].x; val by = rs[i + arm].y - rs[i].y
            val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
            if (la == 0f || lb == 0f) continue
            if ((ax * bx + ay * by) / (la * lb) < cosMax) flags[i] = true
        }
        return flags
    }

    /**
     * Up to [limit] words for the key path, best first. Each source's words are (word, count), lower-case and canonical.
     * Keys are compared to letters by [match], so a path over e can still produce "meyɛ" when that is the word.
     */
    fun decode(path: List<Step>, sources: List<Source>, limit: Int = 3): List<String> {
        if (path.isEmpty() || limit <= 0) return emptyList()
        val first = path.first().key
        val last = path.last().key
        val n = path.size
        val best = HashMap<String, Double>()
        for (src in sources) for ((w, count) in src.words) {
            if (w.length < 2 || w.length > n + 2) continue
            if (match(first, w[0]) == 0.0) continue
            val s = align(w, path) ?: continue
            val lastLetter = w.lastOrNull { it.isLetter() } ?: continue
            val endMatch = match(last, lastLetter)
            val end = if (endMatch > 0.0) 1.5 * endMatch else -1.0
            val letters = w.count { it.isLetter() }
            val longer = (letters - n).coerceAtLeast(0) * 0.6          // more letters than keys: unlikely
            val shorter = (n - letters).coerceAtLeast(0) * 0.1         // a long swipe for a short word: less likely per key
            val score = s + end - longer - shorter + 0.25 * ln(count.toDouble() * src.scale + 1.0) + src.bonus
            val prev = best[w]
            if (prev == null || score > prev) best[w] = score
        }
        return best.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }
    }

    /** The plain form: a key sequence (its ends count as meant) against one list. */
    fun decodeKeys(keys: List<String>, words: List<Pair<String, Int>>, limit: Int = 3): List<String> =
        decode(keys.mapIndexed { i, k -> Step(k, i == 0 || i == keys.lastIndex) }, listOf(Source(words)), limit)

    /**
     * How well the key [raw] (what is printed on it, lower-case) stands for the letter [c] of a word: the same letter 1.0;
     * the same after folding 0.75 (the e key for an é or an ɛ, so a learned name like "Kwabɛna" can be swiped); 0 = not
     * this key.
     */
    internal fun match(raw: String, c: Char): Double {
        if (raw.length != 1) return 0.0
        val r = raw[0]
        val lc = c.lowercaseChar()
        if (r == lc) return 1.0
        if (fold(r) == fold(lc)) return 0.75
        return 0.0
    }

    /**
     * In-order alignment of the word's letters along the path: a letter found on a key scores its [match], +0.4 when the
     * key is an anchor; each key skipped on the way costs 0.2 (when the first matching key is not an anchor but a later
     * one is, the better of the two is taken); the first letter must be the first key; a doubled letter ("hello") needs
     * no second key; apostrophes are free; a letter the path never touched costs 1.2 and at most two are forgiven; every
     * anchor no letter used costs 0.6. Null = the word cannot have been swiped.
     */
    internal fun align(word: String, path: List<Step>): Double? {
        var i = 0
        var score = 0.0
        var missing = 0
        val used = BooleanArray(path.size)
        var prev = ' '
        for ((li, c) in word.withIndex()) {
            if (c == '\'' || c == '’') continue
            if (li > 0 && c == prev) { score += 0.8; continue }
            prev = c
            var j1 = -1; var m1 = 0.0
            var j2 = -1; var m2 = 0.0
            var j = i
            while (j < path.size) {
                val m = match(path[j].key, c)
                if (m > 0.0) {
                    if (j1 < 0) { j1 = j; m1 = m }
                    if (path[j].anchor) { j2 = j; m2 = m; break }
                    if (j - j1 > 6) break
                }
                j++
            }
            if (j1 < 0) {
                if (li == 0) return null
                if (++missing > 2) return null
                score -= 1.2
                continue
            }
            if (li == 0 && j1 != 0) return null
            val s1 = m1 - 0.2 * (j1 - i) + (if (path[j1].anchor) 0.4 else 0.0)
            val s2 = if (j2 >= 0) m2 + 0.4 - 0.2 * (j2 - i) else Double.NEGATIVE_INFINITY
            if (s2 > s1) { score += s2; used[j2] = true; i = j2 + 1 } else { score += s1; used[j1] = true; i = j1 + 1 }
        }
        for (k in path.indices) if (path[k].anchor && !used[k]) score -= 0.6
        return score
    }

    private val accentless = HashMap<Char, Char>()

    /** ɛ/e and ɔ/o are one letter here; an accented vowel is its base letter; everything else is itself, lower-case. */
    private fun fold(c: Char): Char = when {
        c == 'ɛ' || c == 'Ɛ' -> 'e'
        c == 'ɔ' || c == 'Ɔ' -> 'o'
        c.code < 128 -> c.lowercaseChar()
        else -> synchronized(accentless) {
            accentless.getOrPut(c) {
                val d = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
                (if (d.isNotEmpty()) d[0] else c).lowercaseChar()
            }
        }
    }

    private fun dist(a: Pt, b: Pt): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
