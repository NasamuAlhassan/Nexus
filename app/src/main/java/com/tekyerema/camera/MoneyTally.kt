package com.tekyerema.camera

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * The phone's own count of the money a vision model saw (owner, 24 Oct 2026: one 10 cedi note was counted as TWO, 20 cedis,
 * an hour before the live demo — after the 22 Oct prompt fix for the same fault on a 50; judges asked about mixed piles,
 * "three 10s and five 5s"). The model is asked for ONE entry per physical note or coin with the box around its edges
 * ([VisionPrompts.MONEY]); the count is decided here, never taken from the model:
 *  - two entries whose boxes overlap are one piece of paper (a note listed twice, front and back, a numeral and its note);
 *  - two same-value boxes that are each small and squarish and sit together like the corner numerals of one note are one
 *    note (the 24 Oct failure shape);
 *  - overlapping boxes of DIFFERENT values cannot be told apart: the count is unstable and no total is said.
 * The old answer shape (`items` with `count`, no boxes) is still read, as counts it cannot check. Pure → MoneyTallyTest.
 */
object MoneyTally {
    /** One piece of money: its value in cedis, note or coin, the model's certainty, and its outline [ymin, xmin, ymax, xmax]
     *  on a 0–1000 scale when given. */
    data class Piece(val value: Double, val note: Boolean, val sure: Boolean, val box: FloatArray? = null)

    /** What the phone counted: pieces per (value, note), whether any were doubtful, merged as duplicates, or unstable. */
    data class Tally(
        val counts: Map<Pair<Double, Boolean>, Int>,
        val doubtful: Set<Pair<Double, Boolean>>,
        val merged: Int,
        val unstable: Boolean,
    ) {
        val total: Double get() = counts.entries.sumOf { (k, n) -> k.first * n }
        val isEmpty: Boolean get() = counts.isEmpty()
    }

    /** The pieces in a model's JSON answer (`notes`, or the older `items` with a count), or null when it is not one. */
    fun parse(json: String): List<Piece>? {
        val o = obj(json) ?: return null
        o.optJSONArray("notes")?.let { arr ->
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { piece(it) } }
        }
        val items = o.optJSONArray("items") ?: return null
        return (0 until items.length()).flatMap { i ->
            val it = items.optJSONObject(i) ?: return@flatMap emptyList()
            val p = piece(it) ?: return@flatMap emptyList()
            val n = it.optInt("count", 1).coerceIn(1, 200)
            if (p.box != null) listOf(p) else List(n) { p }   // a box is one piece, whatever its count says
        }
    }

    /** The phone's count of [pieces]. */
    fun reconcile(pieces: List<Piece>): Tally {
        val keep = BooleanArray(pieces.size) { true }
        val doubt = HashSet<Pair<Double, Boolean>>()
        var merged = 0
        var unstable = false
        for (i in pieces.indices) for (j in i + 1 until pieces.size) {
            if (!keep[i] || !keep[j]) continue
            val a = pieces[i]; val b = pieces[j]
            val ba = a.box ?: continue; val bb = b.box ?: continue
            val same = a.value == b.value && a.note == b.note
            when {
                samePiece(ba, bb) -> if (same) { keep[j] = false; merged++ } else unstable = true
                same && a.note && numeralsOfOneNote(ba, bb) -> { keep[j] = false; merged++; doubt += a.value to a.note }
            }
        }
        val counts = LinkedHashMap<Pair<Double, Boolean>, Int>()
        pieces.forEachIndexed { i, p ->
            if (!keep[i]) return@forEachIndexed
            val k = p.value to p.note
            counts[k] = (counts[k] ?: 0) + 1
            if (!p.sure) doubt += k
        }
        return Tally(counts, doubt, merged, unstable)
    }

    /** Two answers for two frames of the same notes count exactly the same pieces per denomination. False when either is not
     *  a money answer, or its notes overlap (no stable count) — the caller then asks the user to spread the notes out. */
    fun sameCounts(a: Tally?, b: Tally?): Boolean =
        a != null && b != null && !a.unstable && !b.unstable && a.counts == b.counts
    fun sameCounts(a: String, b: String): Boolean = sameCounts(of(a), of(b))

    /** The tally of a model's answer, or null when it is not a money answer. */
    fun of(json: String): Tally? = parse(json)?.let { reconcile(it) }

    // ---------------------------------------------------------------------------------------------------------------- boxes

    private fun area(b: FloatArray) = max(0f, b[2] - b[0]) * max(0f, b[3] - b[1])
    private fun inter(a: FloatArray, b: FloatArray) =
        max(0f, min(a[2], b[2]) - max(a[0], b[0])) * max(0f, min(a[3], b[3]) - max(a[1], b[1]))

    /** The same piece listed twice: the boxes overlap by a third (IoU), or most of the smaller lies inside the larger. */
    internal fun samePiece(a: FloatArray, b: FloatArray): Boolean {
        val i = inter(a, b); if (i <= 0f) return false
        val small = min(area(a), area(b)); val union = area(a) + area(b) - i
        return (union > 0f && i / union >= 0.3f) || (small > 0f && i / small >= 0.6f)
    }

    /** Two boxes that are the printed numerals of ONE note, not two notes: each squarish (a note is about 2:1) and small
     *  beside the box around both, and that box shaped like a note. */
    internal fun numeralsOfOneNote(a: FloatArray, b: FloatArray): Boolean {
        fun aspect(x: FloatArray): Float { val h = x[2] - x[0]; val w = x[3] - x[1]; return if (min(h, w) <= 0f) 99f else max(h, w) / min(h, w) }
        val u = floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
        val ua = area(u); if (ua <= 0f) return false
        return aspect(a) < 1.5f && aspect(b) < 1.5f && area(a) < 0.3f * ua && area(b) < 0.3f * ua && aspect(u) in 1.5f..3.2f
    }

    // ----------------------------------------------------------------------------------------------------------------- json

    private fun piece(o: JSONObject): Piece? {
        val v = o.optDouble("value_ghs", Double.NaN)
        if (v.isNaN() || v <= 0) return null
        val box = o.optJSONArray("box")?.takeIf { it.length() == 4 }?.let { a ->
            FloatArray(4) { a.optDouble(it, Double.NaN).toFloat() }.takeIf { b -> b.none { it.isNaN() } && b[2] > b[0] && b[3] > b[1] }
        }
        return Piece(v, o.optString("kind", "note") != "coin", o.optBoolean("sure", true), box)
    }

    internal fun obj(json: String): JSONObject? =
        try { JSONObject(json.substring(json.indexOf('{').coerceAtLeast(0), json.lastIndexOf('}') + 1)) } catch (_: Throwable) { null }
}
