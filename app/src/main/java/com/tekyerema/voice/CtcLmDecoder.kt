package com.tekyerema.voice

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * A word n-gram language model read from an ARPA file (log10 scores, as KenLM writes them), for [CtcLmDecoder]. Backoff as
 * ARPA defines it; a word it has never seen gets its `<unk>` score. Pure → CtcLmDecoderTest.
 */
class NgramLm private constructor(
    private val prob: HashMap<String, Float>,
    private val backoff: HashMap<String, Float>,
    val order: Int,
    private val unigrams: Array<String>,
) {
    private val known = unigrams.toHashSet()

    /** A word the decoder counts as known: the unigram list pyctcdecode is given, every word of the 1-gram section but
     *  `<s>`, `</s>`, `<unk>` (the models session's `arpa_unigrams`, asr-offline-lm-twi). */
    fun isWord(w: String): Boolean = w in known

    /** Some known word begins with [p] — a half-heard word can still become a real one. */
    fun hasPrefix(p: String): Boolean {
        var lo = 0; var hi = unigrams.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (unigrams[mid] < p) lo = mid + 1 else hi = mid }
        return lo < unigrams.size && unigrams[lo].startsWith(p)
    }

    /** log10 P([word] | the last order−1 words of [context]). */
    fun log10p(context: List<String>, word: String): Float {
        val h = if (context.size > order - 1) context.subList(context.size - (order - 1), context.size) else context
        var bo = 0f
        for (start in 0..h.size) {
            val hist = h.subList(start, h.size)
            val key = if (hist.isEmpty()) word else hist.joinToString(" ") + " " + word
            prob[key]?.let { return bo + it }
            if (hist.isNotEmpty()) bo += backoff[hist.joinToString(" ")] ?: 0f
        }
        return bo + (prob["<unk>"] ?: -100f)
    }

    companion object {
        fun parseArpa(lines: Sequence<String>): NgramLm {
            val prob = HashMap<String, Float>(); val backoff = HashMap<String, Float>()
            var n = 0; var order = 0
            for (raw in lines) {
                val line = raw.trimEnd()
                if (line.isEmpty()) continue
                if (line.startsWith("\\")) {
                    n = Regex("\\\\(\\d+)-grams:").matchEntire(line)?.groupValues?.get(1)?.toInt() ?: 0
                    order = max(order, n); continue
                }
                if (n == 0) continue                       // the \data\ counts
                // KenLM's lines are `prob<TAB>ngram<TAB>backoff`: the n-gram is already space-joined, so take it between the tabs
                // (the phone parsed a 4.5 MB file in 20 s with a regex split and a rejoin per line, A20e, 24 Oct 2026).
                val t1 = line.indexOf('	')
                if (t1 > 0) {
                    val t2 = line.indexOf('	', t1 + 1)
                    val key = if (t2 < 0) line.substring(t1 + 1) else line.substring(t1 + 1, t2)
                    prob[key] = line.substring(0, t1).toFloatOrNull() ?: continue
                    if (t2 >= 0) line.substring(t2 + 1).trim().toFloatOrNull()?.let { backoff[key] = it }
                    continue
                }
                val parts = line.split(WS)               // a hand-written file with spaces instead of tabs
                if (parts.size < n + 1) continue
                val key = parts.subList(1, n + 1).joinToString(" ")
                prob[key] = parts[0].toFloatOrNull() ?: continue
                parts.getOrNull(n + 1)?.toFloatOrNull()?.let { backoff[key] = it }
            }
            val uni = prob.keys.filter { ' ' !in it && it != "<s>" && it != "</s>" && it != "<unk>" }.sorted().toTypedArray()
            return NgramLm(prob, backoff, max(order, 1), uni)
        }
        private val WS = Regex("\\s+")
    }
}

/**
 * CTC prefix beam search with a word language model: pyctcdecode 0.5.0's `decode()` ported step for step (models session,
 * 24 Oct 2026, C-03: KenLM on the deployed Twi recogniser, alpha 0.3, beta 0.5, beam 25, cut WER ~4 points on unseen
 * speakers and money lines). For the phone's own copy of that recogniser ([OrtCtcAsr]), which decoded greedily. Each frame
 * keeps the [beamWidth] best (text, word in progress, last token) beams, scored by the acoustic log-probability plus, per
 * finished word, alpha·ln10·(log10 P(word | history) + [unkOffset] if unknown) + beta; a word in progress that no known word
 * begins with costs [unkOffset] (more when longer than 6 letters). As `decode()` does, beams that agree on the last order−1
 * words are pruned to the best, and at the end the word in progress is scored with `</s>` — an empty one too, which
 * pyctcdecode scores as an unknown "" word. Pure → CtcLmDecoderTest.
 */
class CtcLmDecoder(
    private val vocab: Array<String>,
    private val blank: Int,
    private val lm: NgramLm?,
    private val alpha: Float = 0.3f,
    private val beta: Float = 0.5f,
    private val beamWidth: Int = 25,
    private val tokenMinLogp: Float = -5f,
    private val beamPruneLogp: Float = -10f,
    private val unkOffset: Float = -10f,
) {
    private val space = vocab.indexOf("|")
    // The settings as written (0.3f is 0.30000001…; through its text it is 0.3, as pyctcdecode reads it).
    private val alphaD = alpha.toString().toDouble()
    private val betaD = beta.toString().toDouble()
    private val history = max(1, (lm?.order ?: 1) - 1)

    // Double throughout, as pyctcdecode scores in Python floats: near ties flipped in Float (parity 21/30, 24 Oct 2026).
    private class Beam(val words: List<String>, val partial: String, val last: Int, var logit: Double, val lmScore: Double) {
        var score = 0.0
    }

    fun decode(logits: Array<FloatArray>): String {
        var beams = listOf(Beam(emptyList(), "", -1, 0.0, 0.0))
        val lmCache = HashMap<String, Double>()
        val partialCache = HashMap<String, Double>()
        for (row in logits) {
            val logp = logSoftmax(row)
            var best = 0
            for (i in 1 until logp.size) if (logp[i] > logp[best]) best = i
            val merged = LinkedHashMap<String, Beam>()
            fun add(words: List<String>, partial: String, last: Int, logit: Double, lmScore: Double) {
                val key = words.joinToString(" ") + "\u0000" + partial + "\u0000" + last
                val had = merged[key]
                if (had == null) merged[key] = Beam(words, partial, last, logit, lmScore)
                else had.logit = logAddExp(had.logit, logit)
            }
            for (c in logp.indices) {
                if (c != best && logp[c] < tokenMinLogp.toDouble()) continue
                val p = logp[c]
                for (b in beams) {
                    val tok = vocab.getOrElse(c) { "" }
                    when {
                        c == blank -> add(b.words, b.partial, blank, b.logit + p, b.lmScore)
                        c == b.last -> add(b.words, b.partial, c, b.logit + p, b.lmScore)
                        c == space ->
                            if (b.partial.isEmpty()) add(b.words, "", c, b.logit + p, b.lmScore)
                            else add(b.words + b.partial, "", c, b.logit + p, b.lmScore + wordScore(b.words, b.partial, false, lmCache))
                        // pyctcdecode spells [UNK] as "⁇" inside the word (an unknown word to the LM); stripped at the end.
                        tok == "[UNK]" -> add(b.words, b.partial + UNK_MARK, c, b.logit + p, b.lmScore)
                        tok.startsWith("[") || tok.startsWith("<") -> add(b.words, b.partial, c, b.logit + p, b.lmScore)
                        else -> add(b.words, b.partial + tok, c, b.logit + p, b.lmScore)
                    }
                }
            }
            for (b in merged.values) b.score = b.logit + b.lmScore + partialScore(b.partial, partialCache)
            val top = merged.values.maxOfOrNull { it.score } ?: break
            val kept = merged.values.filter { it.score >= top + beamPruneLogp.toDouble() }.sortedByDescending { it.score }.take(beamWidth)
            // decode() runs with prune_history: of beams that agree on all the n-gram can still see, the best one stays.
            val seen = HashSet<String>()
            beams = kept.filter { seen.add(it.words.takeLast(history).joinToString(" ") + "\u0000" + it.partial + "\u0000" + it.last) }
        }
        // The end: the word in progress is the last word (an empty one too), scored with </s>; the same texts merge.
        val done = LinkedHashMap<String, Pair<Double, Double>>()
        for (b in beams) {
            val text = (if (b.partial.isEmpty()) b.words else b.words + b.partial).joinToString(" ")
            val lmScore = b.lmScore + wordScore(b.words, b.partial, true, lmCache)
            val had = done[text]
            done[text] = if (had == null) b.logit to lmScore else logAddExp(had.first, b.logit) to had.second
        }
        val best = done.maxByOrNull { it.value.first + it.value.second }?.key ?: return ""
        return best.replace(UNK_MARK, "").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** pyctcdecode's `LanguageModel.score`: the unknown-word offset and `</s>` sit inside the alpha scaling. */
    private fun wordScore(context: List<String>, word: String, last: Boolean, cache: HashMap<String, Double>): Double {
        val m = lm ?: return 0.0
        val key = context.takeLast(m.order - 1).joinToString(" ") + "\u0000" + word + "\u0000" + last
        return cache.getOrPut(key) {
            val ctx = listOf("<s>") + context
            var raw = m.log10p(ctx, word).toDouble()
            if (!m.isWord(word)) raw += unkOffset
            if (last) raw += m.log10p(ctx + word, "</s>")
            alphaD * raw * LN10 + betaD
        }
    }

    /** pyctcdecode's `score_partial_token`: not scaled by alpha. */
    private fun partialScore(partial: String, cache: HashMap<String, Double>): Double {
        val m = lm ?: return 0.0
        if (partial.isEmpty()) return 0.0
        return cache.getOrPut(partial) {
            val unk = if (m.hasPrefix(partial)) 0.0 else unkOffset.toDouble()
            if (partial.length > AVG_TOKEN_LEN) unk * partial.length / AVG_TOKEN_LEN else unk
        }
    }

    companion object {
        private val LN10 = ln(10.0)
        private const val AVG_TOKEN_LEN = 6
        private const val UNK_MARK = "⁇"
        /** pyctcdecode clips log-probabilities at ln(1e-15). */
        private val MIN_LOGP = ln(1e-15)
        fun logSoftmax(row: FloatArray): DoubleArray {
            var mx = row[0].toDouble(); for (v in row) if (v > mx) mx = v.toDouble()
            var sum = 0.0; for (v in row) sum += exp(v - mx)
            val lse = mx + ln(sum)
            return DoubleArray(row.size) { max(row[it] - lse, MIN_LOGP) }
        }
        fun logAddExp(a: Double, b: Double): Double {
            val m = max(a, b)
            return if (m == Double.NEGATIVE_INFINITY) m else m + ln(exp(a - m) + exp(b - m))
        }
    }
}
