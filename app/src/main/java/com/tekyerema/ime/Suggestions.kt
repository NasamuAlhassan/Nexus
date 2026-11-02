package com.tekyerema.ime

import kotlin.math.ln

/**
 * G-05 — word suggestions, entirely on the phone. Two sources (English only since 31 Oct 2026; the Twi list is gone):
 *  - the bundled English list (assets/ime/en_words.tsv, 13 October 2026): the 20,000 most frequent words of the
 *    OpenSubtitles corpus (hermitdave/FrequencyWords, MIT);
 *  - OPTIONAL learned words (off by default): words the user typed that the lists do not have, with a count and
 *    nothing else — no order, no context, no sentence. Never learned from sensitive fields (see [FieldPolicy]),
 *    never anything with a digit (PINs, phone numbers, amounts).
 *
 * Matching folds accents, ɛ→e and ɔ→o, so a learned name like "Kwabɛna" is found by typing "kwabe".
 * Nothing is auto-replaced: a blind user may not notice a silent correction, so suggestions are only offered.
 */
class TwiLexicon(entries: List<Pair<String, Int>>) {
    val words: List<Pair<String, Int>> = entries.distinctBy { it.first }
    private val set = words.mapTo(HashSet()) { it.first }
    operator fun contains(word: String) = word.lowercase() in set
    val size get() = words.size

    /** Each word's matching key ([WordFold]), computed once: the suggester compares 20,000 of them on every key press. */
    val folded: List<String> by lazy { words.map { (w, _) -> if (w.all { it.code < 128 }) w else WordFold.fold(w) } }

    companion object {
        /** `word<TAB>count` lines; `#` comments and blank lines are skipped; a missing count is 1. */
        fun parse(lines: Sequence<String>): TwiLexicon = TwiLexicon(lines.mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val parts = t.split('\t')
            val raw = parts[0].trim()
            // Plain ASCII needs no normalising (and the English list is 20,000 lines of it).
            val w = (if (raw.all { it.code < 128 }) raw else WordFold.canonical(raw)).lowercase()
            if (w.isEmpty()) null else w to (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1)
        }.toList())

        val EMPTY = TwiLexicon(emptyList())
    }
}

/** Words compared without accents: NFC, then accents dropped, lower-case, ɛ→e and ɔ→o. Pure. */
object WordFold {
    fun canonical(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

    fun fold(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .lowercase().replace('ɛ', 'e').replace('ɔ', 'o')
}

/**
 * How the learned words stand next to the English corpus counts (millions for "you", hundreds for the 20,000th word):
 * a learned word is treated like one seen 50,000×.
 */
object WordScales {
    const val LEARNED = 50_000.0
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
        val w = WordFold.canonical(word).lowercase()
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

class WordSuggester(
    private val english: () -> TwiLexicon = { TwiLexicon.EMPTY },
    private val learned: () -> Map<String, Int> = { emptyMap() }
) {


    /**
     * Up to [max] completions or corrections for the word being typed ([typed]). The typed word itself is never
     * offered back unchanged. Case follows the user: "Hel" → "Hello", "HEL" → "HELLO". The English list and the learned
     * words are offered.
     */
    fun suggest(typed: String, max: Int = 3): List<String> {
        if (typed.isEmpty() || max <= 0) return emptyList()
        val lower = typed.lowercase()
        val k = WordFold.fold(typed)
        val scored = HashMap<String, Double>()
        fun consider(word: String, wordKey: String, count: Int, scale: Double, bonus: Double) {
            val exact = word.startsWith(lower)
            if (!exact && !wordKey.startsWith(k)) return
            if (word == lower) return
            var s = ln(count * scale + 1.0) + bonus
            if (exact) s += 2.0                                  // what they typed, letter for letter
            if (wordKey == k) s += 3.0                           // a whole-word correction: "kwabena" → "kwabɛna"
            s -= (word.length - typed.length).coerceAtLeast(0) * 0.05   // shorter completions first, all else equal
            val prev = scored[word]
            if (prev == null || s > prev) scored[word] = s
        }
        english().let { e -> val ws = e.words; val ks = e.folded; for (i in ws.indices) consider(ws[i].first, ks[i], ws[i].second, 1.0, 0.0) }
        for ((w, c) in learned()) consider(w, WordFold.fold(w), c, WordScales.LEARNED, 0.5)
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
