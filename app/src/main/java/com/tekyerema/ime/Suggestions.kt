package com.tekyerema.ime

import com.tekyerema.language.TwiText
import kotlin.math.ln

/**
 * G-05 — word suggestions, entirely on the phone. Two sources:
 *  - the bundled Twi list (assets/ime/twi_words.tsv), harvested from the app's own Twi strings by
 *    scripts/build_twi_wordlist.py and marked for native-speaker review;
 *  - OPTIONAL learned words (off by default): words the user typed that the list does not have, with a count and
 *    nothing else — no order, no context, no sentence. Never learned from sensitive fields (see [FieldPolicy]),
 *    never anything with a digit (PINs, phone numbers, amounts).
 *
 * Matching folds ɛ→e and ɔ→o (and, in Twi mode, the texting stand-ins 3→e, c→o), so typing plain "meye" offers
 * "meyɛ": most of the keyboard's value is getting the Akan letters right without hunting for them.
 * Nothing is auto-replaced: a blind user may not notice a silent correction, so suggestions are only offered.
 */
class TwiLexicon(entries: List<Pair<String, Int>>) {
    val words: List<Pair<String, Int>> = entries.distinctBy { it.first }
    private val set = words.mapTo(HashSet()) { it.first }
    operator fun contains(word: String) = word.lowercase() in set
    val size get() = words.size

    companion object {
        /** `word<TAB>count` lines; `#` comments and blank lines are skipped; a missing count is 1. */
        fun parse(lines: Sequence<String>): TwiLexicon = TwiLexicon(lines.mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val parts = t.split('\t')
            val w = TwiText.canonical(parts[0].trim()).lowercase()
            if (w.isEmpty()) null else w to (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1)
        }.toList())

        val EMPTY = TwiLexicon(emptyList())
    }
}

/** The optional on-device learned words: word → count. Pure; persisted by [LearnedWordStore]. */
class LearnedWords(private val max: Int = 1000) {
    private val counts = LinkedHashMap<String, Int>()
    var dirty = false
        private set

    /** Read-only view (no copy: the suggester reads it on every key). */
    fun view(): Map<String, Int> = counts
    val size get() = counts.size

    /** A word worth remembering: 2–24 characters, letters and inner apostrophes only (no digits ever). */
    fun learnable(word: String): Boolean =
        word.length in 2..24 && word.all { it.isLetter() || it == '\'' || it == '’' } && word.first().isLetter()

    /** Count one use of [word], unless the bundled [lexicon] already knows it (no need to keep it). */
    fun learn(word: String, lexicon: TwiLexicon): Boolean {
        val w = TwiText.canonical(word).lowercase()
        if (!learnable(w) || w in lexicon) return false
        counts[w] = (counts[w] ?: 0) + 1
        if (counts.size > max) {
            // Forget the least-used word; ties go alphabetically so the result is deterministic.
            val victim = counts.entries.minWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).key
            counts.remove(victim)
        }
        dirty = true
        return true
    }

    fun clear() { if (counts.isNotEmpty()) dirty = true; counts.clear() }

    fun serialize(): String { dirty = false; return counts.entries.joinToString("") { "${it.key}\t${it.value}\n" } }

    companion object {
        fun parse(text: String, max: Int = 1000): LearnedWords = LearnedWords(max).also { lw ->
            text.lineSequence().forEach { line ->
                val p = line.split('\t')
                val w = p[0].trim()
                val n = p.getOrNull(1)?.trim()?.toIntOrNull() ?: return@forEach
                if (lw.learnable(w) && n > 0) lw.counts[w] = n
            }
        }
    }
}

class WordSuggester(private val lexicon: TwiLexicon, private val learned: () -> Map<String, Int> = { emptyMap() }) {

    /** Twi fold plus the texting stand-ins: "mey3" and "meye" both find "meyɛ"; "wc" finds "wɔ". */
    private fun key(s: String, twi: Boolean): String {
        val f = TwiText.fold(s)
        return if (twi) f.replace('3', 'e').replace('c', 'o') else f
    }

    /**
     * Up to [max] completions or corrections for the word being typed ([typed]). The typed word itself is never
     * offered back unchanged. Case follows the user: "Me" → "Meyɛ", "ME" → "MEYƐ".
     */
    fun suggest(typed: String, twi: Boolean, max: Int = 3): List<String> {
        if (typed.isEmpty() || max <= 0) return emptyList()
        val lower = typed.lowercase()
        val k = key(typed, twi)
        val scored = HashMap<String, Double>()
        fun consider(word: String, count: Int, learnedWord: Boolean) {
            val exact = word.startsWith(lower)
            if (!exact && !key(word, twi).startsWith(k)) return
            if (word == lower) return
            var s = ln(count.toDouble() + 1.0)
            if (exact) s += 2.0                                  // what they typed, letter for letter
            if (key(word, twi) == k) s += 3.0                    // a whole-word correction: "meye" → "meyɛ"
            if (learnedWord) s += 0.5
            s -= (word.length - typed.length).coerceAtLeast(0) * 0.05   // shorter completions first, all else equal
            scored[word] = maxOf(scored[word] ?: Double.NEGATIVE_INFINITY, s)
        }
        if (twi) lexicon.words.forEach { (w, c) -> consider(w, c, false) }
        learned().forEach { (w, c) -> consider(w, c, true) }
        return scored.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(max).map { matchCase(it.key, typed) }
    }

    companion object {
        fun matchCase(word: String, typed: String): String {
            val letters = typed.filter { it.isLetter() }
            return when {
                letters.length >= 2 && letters.all { it.isUpperCase() } -> word.uppercase()
                typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
                else -> word
            }
        }
    }
}
