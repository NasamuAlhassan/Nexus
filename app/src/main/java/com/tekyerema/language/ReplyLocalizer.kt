package com.tekyerema.language

import android.util.Log
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Lang
import kotlinx.coroutines.CancellationException

/**
 * B-11 — answering a Twi speaker in Twi when the reply text is English (cloud-brain results, errors), through
 * whatever English→Twi translator is configured. Machine translation of Twi is weak (no strong Twi MT exists; see
 * docs/agent/LANGUAGE_LAYER.md) and a blind user cannot see a mistranslation, so this is deliberately narrow:
 *
 *  - Only text that positively reads as English is translated; the app's own Twi lines (ConfirmTemplates, "Yoo.")
 *    are never re-translated, even the short ones with no ɛ/ɔ.
 *  - No quantity is ever translated: amounts, phone numbers and — since 4 October 2026 — every other number (a count,
 *    a size, a time, a percentage, a USSD code) travel as TAGS and come back in English for the English voice, exactly
 *    as the app worded them. (Replies arrive here already verbalised, so "5" is "five"; that's why number WORDS count.)
 *    A quantity word the tagger can't take (a lone "half", "point") still keeps the line in English.
 *  - Names, app names and quoted text must survive verbatim; if the translator drops or alters one, its output is
 *    discarded and the original is spoken.
 *  - An answer that is blank or wildly longer than the source (a hallucinated ramble) is discarded.
 *
 * Returning null always means "speak the original" — the caller's existing behaviour.
 */
class ReplyLocalizer(
    private val translator: TranslationProvider?,
    private val enabled: () -> Boolean = { AppConfig.bool("providers.speech.mt.localizeReplies", true) },
    private val maxChars: () -> Int = { AppConfig.int("providers.speech.mt.maxChars", 400) },
    /** How long a reply may wait for the translator before it is spoken in English: a read-back waited 6.8 s on the
     *  Space (ZeroGPU, cold) and then discarded the answer (QA R_TW05, A20e, 3 October 2026). `providers.speech.mt.replyWaitMs`. */
    private val waitMs: () -> Long = { AppConfig.long("providers.speech.mt.replyWaitMs", 3_500L) }
) {
    /**
     * The Twi line for an English [text], or null (speak the original). Since 2 October 2026 (owner: names as tags, amounts
     * and numbers in English) names, app names, quoted words, amounts and phone numbers travel through the translator as
     * TAGS (EntityTags) and come back verbatim — amounts and numbers marked to be spoken by the ENGLISH voice
     * (`⟦en:5 Ghana cedis⟧`, see [EntityTags.segments]). A tag lost or doubled → the original is spoken. A quantity that
     * can't be tagged (a percentage, a code) still keeps the line in English.
     */
    suspend fun toTwi(text: String, wait: Long = waitMs()): String? {
        val src = text.trim()
        if (translator == null || !enabled() || src.isEmpty() || src.length > maxChars()) return null
        if (!looksEnglish(src)) return null
        val tagged = EntityTags.protectEnglish(src, protectedTerms(src))
        if (hasQuantity(tagged.text.replace(Regex("\\[(?:P|A|N|Q)\\d+\\]"), " "))) return null
        val t0 = System.currentTimeMillis()
        val out = try { kotlinx.coroutines.withTimeoutOrNull(wait) { translator.translate(tagged.text, Lang.GH_EN, Lang.TWI) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (out == null) {
            if (System.currentTimeMillis() - t0 >= wait) Log.i("Tekyerema", "[MT] reply translation took longer than $wait ms — speaking the English")
            return null
        }
        if (!tagged.isEmpty && !EntityTags.intact(tagged.text, out, tagged.entities)) {
            Log.w("Tekyerema", "[MT] the translation lost a name/amount tag — speaking the original"); return null
        }
        if (out.length > tagged.text.length * 3 + 40) { Log.w("Tekyerema", "[MT] translation implausibly long — speaking the original"); return null }
        return EntityTags.restore(out, tagged.entities, EntityTags::inTwi)
    }

    companion object {
        private val NUMBER_WORDS = setOf(
            // no "one": runs of number words are tagged before this check, so a "one" left over is a word ("which one").
            "zero", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
            "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion",
            // no "cedi"/"pesewa": an amount's figure or number word is what makes a quantity — "How much cedi should I send to
            // Prince?" has none, and was read by the English voice in a Twi conversation (A20e, 5 October 2026)
            "point", "percent", "half", "quarter", "star", "hash", "o'clock"
        )
        private val WORD = Regex("[\\p{L}\\p{M}']+")
        private val DIGIT = Regex("\\p{Nd}")

        /** Frequent English words. Short app-authored Twi lines ("Mante. San ka bio.", "Yoo.") carry neither ɛ/ɔ nor
         *  a Twi lexicon word, so "not Twi" is not enough — the text must positively look English. */
        private val ENGLISH_COMMON = setOf(
            "a", "an", "the", "to", "of", "in", "on", "at", "for", "with", "from", "by", "about", "and", "or", "but",
            "not", "no", "yes", "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "done", "have", "has",
            "had", "will", "would", "can", "could", "should", "may", "might", "must", "i", "you", "your", "yours", "me",
            "my", "we", "our", "they", "them", "their", "he", "she", "him", "her", "his", "it", "its", "this", "that",
            "these", "those", "there", "here", "what", "which", "who", "when", "where", "why", "how", "now", "then",
            "again", "please", "sorry", "okay", "ok", "all", "any", "some", "more", "just", "still", "already", "up",
            "down", "out", "off", "open", "opened", "opening", "call", "calling", "send", "sent", "sending", "message",
            "messages", "read", "reading", "found", "find", "couldn't", "can't", "don't", "didn't", "isn't", "it's",
            "i'm", "i'll", "you're", "let", "let's", "need", "want", "say", "said", "tell", "try", "new", "phone"
        )

        /** Positively English: no Akan letters, no more Twi than English lexicon evidence, and at least a third of the
         *  words are common English words. */
        fun looksEnglish(s: String): Boolean {
            if (TwiText.hasAkanLetters(s)) return false
            val d = com.tekyerema.voice.Lid.detect(s)
            if (d.twiTok > 0 && d.twi >= d.eng) return false
            val words = WORD.findAll(s.lowercase()).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()
            if (words.isEmpty()) return false
            val hits = words.count { it in ENGLISH_COMMON }
            // A longer answer is mostly content words: "Usain Bolt is the fastest man, holding the one hundred‑metre world
            // record." has 3 common words in 13, so it was "not English" and the English voice read it in Twi mode (A20e,
            // 4 October 2026). Six words or more, two common English words and not one Twi word is English too.
            return hits >= 1 && (hits * 3 >= words.size || (words.size >= 6 && hits >= 2 && d.twiTok == 0))
        }
        /** For choosing the VOICE (not for translating): [looksEnglish], or — a line made mostly of names, like a list of
         *  contacts — no Akan letter, no Twi word, and at least one common English word. Pure. */
        fun englishForVoice(s: String): Boolean {
            if (looksEnglish(s)) return true
            if (TwiText.hasAkanLetters(s) || com.tekyerema.voice.Lid.detect(s).twiTok > 0) return false
            return WORD.findAll(s.lowercase()).any { it.value.trim('\'') in ENGLISH_COMMON }
        }

        private val MONEY_SIGN =Regex("GH₵|GH¢|₵|¢|\\bGHS\\b|\\bGHC\\b", RegexOption.IGNORE_CASE)

        /** Digits, currency signs or spelled quantities anywhere in the text. */
        fun hasQuantity(s: String): Boolean =
            DIGIT.containsMatchIn(s) || MONEY_SIGN.containsMatchIn(s) ||
                WORD.findAll(s.lowercase()).any { it.value in NUMBER_WORDS }

        private val DOUBLE_QUOTED = Regex("[\"“”]([^\"“”]{1,60})[\"“”]")
        // Single quotes only when they open after a non-letter and close before one, so "can't … 'Mum'" finds Mum,
        // not "t … ".
        private val SINGLE_QUOTED = Regex("(?<![\\p{L}])['‘]([^'‘’]{1,60})['’](?![\\p{L}])")
        /** A sentence starts at the beginning, after . ! ? and a space, or on a new line (a read-back's "\nYou didn't name…"
         *  was tagged as a NAME "You" and stayed English in the Twi — A20e, 5 October 2026). */
        private val SENTENCE_START = Regex("(^|[.!?]\\s+|\\n[\\s“”\"']*)$")
        /** "I", "I've", "I'm", "I'll", "I'd" — never a name ("Okay, I've left it." was tagged "[P1] left it"). */
        private val FIRST_PERSON = Regex("^I(?:['’](?:ve|m|ll|d|s))?$")

        /**
         * Protected spans: quoted text, and capitalised words that are not the first word of a sentence (names, app
         * names), plus mixed-case words anywhere (WhatsApp, MoMo). "I" is not a name.
         */
        fun protectedTerms(s: String): Set<String> {
            val out = LinkedHashSet<String>()
            DOUBLE_QUOTED.findAll(s).forEach { out.add(it.groupValues[1].trim()) }
            SINGLE_QUOTED.findAll(s).forEach { out.add(it.groupValues[1].trim()) }
            val words = WORD.findAll(s).toList()
            for ((i, m) in words.withIndex()) {
                val w = m.value.trim('\'')
                if (w.length < 2 || FIRST_PERSON.matches(w)) continue
                val mixed = w.drop(1).any { it.isUpperCase() } && w.any { it.isLowerCase() }
                val capital = w[0].isUpperCase()
                val atStart = SENTENCE_START.containsMatchIn(s.substring(0, m.range.first))
                // A capital at a sentence start is a name when the next word is a capitalised word right after it —
                // "Usain Bolt is…", "Kwame Nkrumah was…" — so the whole name is kept, not just "Bolt" (4 October 2026).
                val next = words.getOrNull(i + 1)
                val nameStart = atStart && capital && next != null && next.value[0].isUpperCase() &&
                    s.substring(m.range.last + 1, next.range.first) == " " && w.lowercase() !in ENGLISH_COMMON
                if (nameStart) { out.add("$w ${next!!.value.trim('\'')}"); continue }
                if (mixed || (capital && !atStart)) out.add(w)
            }
            return out
        }

        /** Every protected term of [src] appears verbatim in [out]. */
        fun preserved(src: String, out: String): Boolean = protectedTerms(src).all { out.contains(it) }
    }
}
