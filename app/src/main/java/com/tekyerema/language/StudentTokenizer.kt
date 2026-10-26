package com.tekyerema.language

import java.text.Normalizer

/**
 * The student translator's tokenizer: the NLLB sentencepiece BPE model with its unused pieces blocked, ported so the
 * app needs neither sentencepiece nor the protobuf. Built from `tokenizer_pieces.tsv` (id, piece, score; ids 6..30666).
 * Encoder ids = [ENG] + up to 126 piece ids + [EOS]; decode drops ids < 6 and joins the pieces.
 * Golden pairs from the real sentencepiece live in src/test/resources/mt/golden_tokenizer.json.
 */
class StudentTokenizer(rows: List<Triple<String, Double, Int>>) {
    private val scores = HashMap<String, Double>(rows.size * 2)
    private val ids = HashMap<String, Int>(40_000)       // usable pieces only
    private val pieceOf = HashMap<Int, String>(40_000)

    init {
        for ((piece, score, id) in rows) {
            scores[piece] = score
            if (id >= 0) { ids[piece] = id; pieceOf[id] = piece }
        }
    }

    /** Normalise like the model's nmt_nfkc: NFKC, whitespace controls to a space, runs collapsed, trimmed, then "▁" prefix. */
    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val sb = StringBuilder()
        var pendingSpace = false
        for (ch in nfkc) {
            val c = ch.code
            // tab, newline, vertical tab, form feed, carriage return, space, the zero-width characters U+200B-200D and the
            // line/paragraph separators are all read as a space by the real normaliser
            val blank = c == 32 || c in 9..13 || c in 0x200b..0x200d || c == 0x2028 || c == 0x2029
            if (blank) { pendingSpace = sb.isNotEmpty() }
            else if (c == 0xfeff) { /* byte-order mark: dropped */ }
            else { if (pendingSpace) sb.append(0x2581.toChar()); pendingSpace = false; sb.append(ch) }
        }
        return if (sb.isEmpty()) "" else "▁$sb"
    }

    private class Sym(val s: String, val left: Sym? = null, val right: Sym? = null)

    /** BPE (the model is sentencepiece type 2): start from characters, repeatedly merge the adjacent pair whose joined
     *  piece scores highest (leftmost on a tie). Blocked ("unused") pieces still serve as merge steps; a final symbol that is
     *  blocked is split back into the two it was made from. A character outside the vocabulary becomes the unknown piece (3). */
    fun pieces(text: String): List<Int> {
        val s = normalize(text)
        if (s.isEmpty()) return emptyList()
        val syms = ArrayList<Sym>()
        var i = 0
        while (i < s.length) { val n = Character.charCount(s.codePointAt(i)); syms.add(Sym(s.substring(i, i + n))); i += n }
        while (syms.size > 1) {
            var bestAt = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (k in 0 until syms.size - 1) {
                val sc = scores[syms[k].s + syms[k + 1].s] ?: continue
                if (sc > bestScore) { bestScore = sc; bestAt = k }
            }
            if (bestAt < 0) break
            syms[bestAt] = Sym(syms[bestAt].s + syms[bestAt + 1].s, syms[bestAt], syms[bestAt + 1])
            syms.removeAt(bestAt + 1)
        }
        val out = ArrayList<Int>()
        fun emit(y: Sym) {
            val id = ids[y.s]
            when {
                id != null -> out.add(id)
                y.left != null && y.right != null -> { emit(y.left); emit(y.right) }
                else -> out.add(UNK)
            }
        }
        syms.forEach { emit(it) }
        return out
    }

    fun encode(text: String): LongArray {
        val body = pieces(text).take(MAX_PIECES)
        return (listOf(ENG) + body + listOf(EOS)).map { it.toLong() }.toLongArray()
    }

    fun decode(outIds: List<Int>): String =
        outIds.filter { it >= FIRST_PIECE }.joinToString("") { pieceOf[it] ?: "" }.replace('▁', ' ').trim()

    companion object {
        const val EOS = 2
        const val UNK = 3
        const val ENG = 4
        const val TWI = 5
        const val FIRST_PIECE = 6
        const val MAX_PIECES = 126

        /** Rows of `bpe_merges.tsv`: piece, score, id (-1 for a blocked piece). */
        fun parseTsv(lines: Sequence<String>): List<Triple<String, Double, Int>> =
            lines.filter { it.isNotEmpty() && !it.startsWith("#") }.map {
                val f = it.split('	'); Triple(f[0], f[1].toDouble(), f[2].toInt())
            }.toList()
    }
}
