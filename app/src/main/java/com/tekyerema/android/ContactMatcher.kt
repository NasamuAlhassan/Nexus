package com.tekyerema.android

import com.tekyerema.voice.PhoneticKey

/**
 * How well a name as HEARD fits a contact as SAVED — a score per contact, so the resolver can tell a sure match from a
 * guess (robustness layer, 5 October 2026). The users keep their contacts as they are; the hearing adapts:
 *
 *  - 1.00 the whole name ("Prince Melanin")              0.95 its first word(s) ("Prince")
 *  - 0.90 the whole name by sound ("Prinse Melanin")      0.85 the first word by sound ("Kwami" → Kwame)
 *  - 0.80 a later word exactly ("Melanin", "press" in "Abuakwa Printing Press")
 *  - 0.70 a later word by sound; the start of the first word ("Princ" → Prince/Princess)
 *  - 0.60 the consonant skeleton of the first word with one consonant dropped ("press" ~ "Prince": p-r-s / p-r-n-s), or the start of a
 *         later word; spelling ≥ 0.8 alike (Levenshtein)
 *  - 0.50 inside a word, or a later word's skeleton
 *
 * [confidence] turns the scores into a decision: SURE (one clear best ≥ 0.85), CHOOSE (several fit about as well — ask),
 * WEAK (one, but only loosely — the read-back names it in full), NONE. Pure → SpeechRobustnessTest.
 */
object ContactMatcher {
    enum class Confidence { SURE, CHOOSE, WEAK, NONE }

    const val STRONG = 0.85
    /** The name as said — whole, or its first word(s). Anything less is an inference, said back once (HeardAs). */
    const val AS_SAID = 0.95
    const val FLOOR = 0.55

    /** Words, lower-case; a possessive stays one word ("Richmond's" → "richmonds", not "richmond" + "s"). */
    private fun tokens(s: String): List<String> =
        s.split(Regex("[^\\p{L}\\p{N}'’]+")).map { PhoneticKey.compact(it) }.filter { it.isNotEmpty() }

    private val POSSESSIVE = Regex("(?i)\\p{L}['’]s\\b")

    /**
     * Two consonant skeletons are one DROPPED consonant apart ("press" p-r-s / "Prince" p-r-n-s — the n before an s is
     * what recognisers lose), or the same. A changed consonant is a different name: Kwame k-w-m is not Kwesi k-w-s or
     * Qweku k-w-k (A20e, 5 October 2026: "Kwame" offered Kwesi Dain and Qweku Marvin).
     */
    private fun skeletonNear(a: String, b: String): Boolean =
        a == b || (kotlin.math.abs(a.length - b.length) == 1 && lev(a, b) == 1)

    /** Consonants only, after the sound folding ("prince" → p-r-n-s, "press" → p-r-s). */
    internal fun skeleton(word: String): String {
        val k = PhoneticKey.of(word)
        if (k.isEmpty()) return k
        val sb = StringBuilder().append(k[0])
        for (c in k.drop(1)) if (c !in "aiu" && sb.last() != c) sb.append(c)
        return sb.toString()
    }

    private fun lev(a: String, b: String): Int = com.tekyerema.core.TextDistance.levenshtein(a, b)

    /** How well [heard] fits the saved [name], 0 when it doesn't. Pure. */
    fun score(heard: String, name: String): Double {
        val h = tokens(heard); val n = tokens(name)
        if (h.isEmpty() || n.isEmpty()) return 0.0
        val hc = h.joinToString(""); val nc = n.joinToString("")
        if (hc == nc) return 1.0
        // "Richmond's Dad" is someone Richmond knows, not Richmond: a possessive name fits only when the possessive is said
        // (A20e, 5 October 2026: "tell Richmond …" was turned towards the contact "Richmond's Dad").
        if (POSSESSIVE.containsMatchIn(name) && !POSSESSIVE.containsMatchIn(heard) && !(h.size > 1 && PhoneticKey.similarity(hc, nc) >= 0.85))
            return 0.0
        if (h.size <= n.size && n.subList(0, h.size) == h) return 0.95
        var best = 0.0
        fun up(v: Double) { if (v > best) best = v }
        if (PhoneticKey.of(hc) == PhoneticKey.of(nc) && hc.length >= 4) up(0.90)
        val first = n[0]
        if (h.size == 1) {
            val w = h[0]
            val wk = PhoneticKey.of(w)
            if (wk.length >= 3 && wk == PhoneticKey.of(first)) up(0.85)
            if (akanVowelVariant(w, first)) up(0.85)
            for ((i, t) in n.withIndex()) {
                if (i > 0 && w == t) up(0.80)
                if (i > 0 && wk.length >= 3 && wk == PhoneticKey.of(t)) up(0.70)
                if (i > 0 && akanVowelVariant(w, t)) up(0.70)
                // the start of a word: of the first, from 3 letters ("Kwa" → Kwame, Kwabena); of a later word, from 4 —
                // "men" is not Mensah (safety audit 2026-10-05)
                if (w.length >= (if (i == 0) 3 else 4) && t.startsWith(w) && t != w) up(if (i == 0) 0.70 else 0.60)
                if (w.length >= 3 && t.contains(w) && !t.startsWith(w)) up(0.50)
            }
            val ws = skeleton(w)
            if (ws.length >= 3) {
                if (skeletonNear(ws, skeleton(first))) up(0.60)
                if (n.drop(1).any { skeleton(it).length >= 3 && skeletonNear(ws, skeleton(it)) }) up(0.50)
            }
            val sim = maxOf(PhoneticKey.similarity(w, first), PhoneticKey.similarity(w, nc))
            if (sim >= 0.8) up(0.55 + (sim - 0.8))
            // the whole name glued into one word by the recogniser, by sound ("prinsmalane" ~ Prince Melanin — the lab ASR,
            // A20e 5 October 2026): only loosely, so it is said back and confirmed
            if (n.size > 1 && w.length >= 8 && w[0] == nc[0] && PhoneticKey.similarity(wk, PhoneticKey.of(nc)) >= 0.75) up(0.60)
        } else {
            // several words heard: a run of the saved words, in order ("Printing Press", "Prince Alhassan")
            for (i in 1..n.size - h.size) if (n.subList(i, i + h.size) == h) up(0.80)
            val sim = PhoneticKey.similarity(hc, nc)
            if (sim >= 0.8) up(0.60 + (sim - 0.8))
            // one saved word heard as two ("rich man" ~ Richmond): only loosely — the read-back names the contact in full
            for (t in n) if (t.length >= 5 && hc[0] == t[0] && PhoneticKey.similarity(hc, t) >= 0.75) up(0.60)
            // the first name exactly, then words that fit nothing ("Prince maai" — "ma me", for me, glued on): every Prince
            // fits loosely, so "which Prince?" is asked rather than "I couldn't find Prince maai" (A20e, 5 October 2026)
            if (h[0] == first && h[0].length >= 3) up(0.60)
        }
        return best
    }

    /**
     * Akan day names start with a vowel the recogniser (and the spelling) varies: Efua / Afua, Ekua / Akua, Esi / Ɛsi,
     * Abena / Ebena. Two words that differ ONLY in that first vowel are the same name. Not for short words.
     */
    internal fun akanVowelVariant(a: String, b: String): Boolean =
        a.length >= 3 && a.length == b.length && a != b && a[0] in "aeiouɛɔ" && b[0] in "aeiouɛɔ" && a.substring(1) == b.substring(1)

    data class Scored(val contact: ContactResolver.Contact, val score: Double)

    /** [contacts] that fit [heard], best first (one per number), with the floor applied. Pure. */
    fun rank(heard: String, contacts: List<ContactResolver.Contact>): List<Scored> =
        contacts.map { Scored(it, score(heard, it.name)) }
            .filter { it.score >= FLOOR }
            .sortedByDescending { it.score }
            .distinctBy { it.contact.number.filter(Char::isDigit).takeLast(9) }

    /** The decision for a ranked list ([rank]). */
    fun confidence(ranked: List<Scored>): Confidence {
        val best = ranked.firstOrNull() ?: return Confidence.NONE
        val rivals = ranked.drop(1).count { best.score - it.score < (if (best.score >= STRONG) 0.1 else 0.3) }
        return when {
            rivals > 0 -> Confidence.CHOOSE
            best.score >= STRONG -> Confidence.SURE
            else -> Confidence.WEAK
        }
    }

    /**
     * The contacts to hand back for [heard]: the strong ones when there is one (so "Prince" is every Prince but not
     * "Princess"); otherwise the loose matches TOGETHER with the sound-alikes within 0.3 of the best — a guess is never
     * returned alone when another person fits too (the caller then asks "who did you mean?"). Pure.
     */
    fun pick(heard: String, ranked: List<Scored>): List<ContactResolver.Contact> {
        val best = ranked.firstOrNull() ?: return emptyList()
        // The whole saved name, said in full: that contact. A20e 6 Nov 2026: the owner saved his other number as just "Prince"
        // and "text Prince" was still asked "Which Prince: Prince, Prince Tesano…, Prince Ice P?" — then no answer could end it.
        if (best.score >= 1.0) return ranked.filter { it.score >= 1.0 }.map { it.contact }
        return if (best.score >= STRONG) ranked.filter { it.score >= STRONG - 0.001 && best.score - it.score <= 0.1 }.map { it.contact }
        else ranked.filter { best.score - it.score <= 0.3 }.map { it.contact }
    }
}
