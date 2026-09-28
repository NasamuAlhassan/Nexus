package com.tekyerema.language

import java.text.Normalizer as JNormalizer

/**
 * X-06/B-20 — Twi (Akan) spelling at the edges of the voice pipeline. Pure Kotlin, unit-tested, no Twi invented:
 * this file only changes HOW letters are written, never which words are said.
 *
 * Why it exists: the same Twi word reaches us in several spellings.
 *  - Look-alike code points. Keyboards, web text and some ASR vocabularies write the Akan letters ɛ/ɔ with Greek ε,
 *    Cyrillic ԑ or the reversed-c ͻ/ↄ. They look identical and match nothing in our lexicons.
 *  - Tone marks. Standard Akan orthography does not write tone, but datasets and some recognisers emit accents
 *    (ɛ́, à); our lexicons and the agent's rules are written without them.
 *  - Plain-Latin spellings. People and models drop the special letters (ɛ→e, ɔ→o: "mepe", "oha"), so lookups
 *    compare a FOLDED key rather than the text itself.
 *  - The phone's English voice cannot say ɛ/ɔ (it spells them out or skips them — the reason the B-17 lexicon
 *    already respells "Tɛkyerɛma"), so text for that voice gets the nearest plain vowel.
 */
object TwiText {
    /** Look-alike → the Akan letter. Greek capital Ε (U+0395) is left alone: it is equally a Latin E look-alike. */
    private val CONFUSABLES = mapOf(
        'ε' to 'ɛ',   // Greek small epsilon ε
        'ԑ' to 'ɛ',   // Cyrillic small reversed ze ԑ
        'Ԑ' to 'Ɛ',   // Cyrillic capital reversed ze Ԑ
        'ͻ' to 'ɔ',   // Greek small reversed lunate sigma ͻ
        'ↄ' to 'ɔ',   // Latin small reversed c ↄ
        'Ↄ' to 'Ɔ'    // Roman numeral reversed one hundred Ↄ
    )

    /** Combining tone marks: grave, acute, circumflex, macron, double acute, caron, double grave. The tilde (nasal)
     *  and cedilla are kept — they are not tone and do appear in names. */
    private val TONE_MARKS = setOf('̀', '́', '̂', '̄', '̋', '̌', '̏')

    /** The Akan letters that are not in plain Latin (plus their capitals). */
    private val SPECIAL = setOf('ɛ', 'Ɛ', 'ɔ', 'Ɔ', 'ŋ', 'Ŋ')

    /** True when [s] contains an Akan letter (ɛ/ɔ/ŋ) or one of its look-alikes — evidence the text is written Twi. */
    fun hasAkanLetters(s: String): Boolean = s.any { it in SPECIAL || it in CONFUSABLES }

    /**
     * The standard written form: look-alikes mapped to ɛ/ɔ, tone marks removed, everything else untouched (case,
     * punctuation, digits, English words, names). Idempotent.
     */
    fun canonical(s: String): String {
        if (s.isEmpty()) return s
        val decomposed = JNormalizer.normalize(s, JNormalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (c in TONE_MARKS) continue
            sb.append(CONFUSABLES[c] ?: c)
        }
        return JNormalizer.normalize(sb, JNormalizer.Form.NFC)
    }

    /** A matching key: canonical, lower-case, ɛ→e and ɔ→o. Never shown or spoken — only compared. */
    fun fold(s: String): String =
        canonical(s).lowercase().replace('ɛ', 'e').replace('ɔ', 'o')

    /**
     * Text for a voice that only knows English letters (the phone's engine): ɛ→e, ɔ→o, ŋ→ng, in either case, after
     * [canonical]. "Kwabɛna" becomes "Kwabena", which is also how the name is commonly written. Text without any of
     * these letters comes back unchanged (the same instance), so English replies — and accents in English or French
     * names like "café" — are untouched.
     */
    fun forEnglishVoice(s: String): String {
        if (!hasAkanLetters(s)) return s
        return canonical(s)
            .replace('ɛ', 'e').replace('Ɛ', 'E')
            .replace('ɔ', 'o').replace('Ɔ', 'O')
            .replace("ŋ", "ng").replace("Ŋ", "Ng")
    }
}
