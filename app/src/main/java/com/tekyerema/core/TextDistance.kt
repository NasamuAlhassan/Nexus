package com.tekyerema.core

/**
 * Edit distance between spellings, for every fuzzy match in the app: contacts and apps by name, a heard Twi command, the
 * network name, a dictated message after a polish, a camera label that flickers between frames. One implementation; the
 * callers keep their own thresholds and keys. (Seven identical copies were merged here in the 24 Oct 2026 cleanup.) Pure.
 */
object TextDistance {
    /** Levenshtein distance: insertions, deletions and substitutions, each costing 1. */
    fun levenshtein(a: CharSequence, b: CharSequence): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** 1 − Levenshtein / the longer length, in [0, 1]; 1.0 = identical, and 0.0 when either is empty. */
    fun similarity(a: CharSequence, b: CharSequence): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }
}
