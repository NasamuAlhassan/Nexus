package com.tekyerema.voice.understand

import com.tekyerema.android.ContactMatcher
import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.GhanaWords
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer

/**
 * A spoken ANSWER read as what the question asked for (robustness layer, 5 October 2026) — the recogniser hears free
 * English, but "How much?" wants an amount, "What's the number?" wants digits, "Who did you mean: A, B or C?" wants one of
 * A, B, C. Context, not guessing:
 *
 *  - AMOUNT: money words repaired with the money context known ("20 cities" → 20 cedis, "10 pesos" → 10 pesewas);
 *  - PHONE: the digits only, in one run, "+233"/"233" folded to 0 ("oh five five, two oh nine…" → "0552093…");
 *  - CHOICE: "the first one" / "the last one", or the option the answer sounds like ([ContactMatcher] scores) — only
 *    when one option clearly fits;
 *  - NAME: a person's name checked against the contacts as a name slot ("coffee" → Kofi);
 *  - REFERENCE / anything else: left exactly as said (a reference is the user's own words).
 *
 * Pure (the contact vocabulary is passed in) → SpeechRobustnessTest.
 */
object AnswerContext {
    enum class Expect { AMOUNT, PHONE, CHOICE, NAME, REFERENCE, FREE }

    private val REFERENCE = Regex("(?i)\\breference\\b|\\bnarration\\b")
    private val AMOUNT = Regex("(?i)\\bhow much\\b|\\bamount\\b|\\bhow many cedis\\b|\\bwhat (?:amount|price)\\b")
    private val PHONE = Regex("(?i)\\b(?:phone number|mobile number|what(?:'s| is) the number|which number|the number|their number|" +
        "his number|her number|number should|number to|digits)\\b")
    private val CHOICE = Regex("(?i)^(?:which\\b[^:?]*|who did you mean|who do you mean|did you mean)\\s*:\\s*(.+?)\\?")
    /** The planner's form: "Which chat should I send the message to? Richmond, or Richmond Apenensu?" */
    private val CHOICE_AFTER = Regex("(?i)^(?:which|who)\\b[^?]*\\?\\s+([^?]+?\\bor\\b[^?]+?)\\?$")
    private val NAME = Regex("(?i)^(?:who\\b|to whom\\b|which contact\\b|what(?:'s| is) (?:their|his|her|the person's) name)")

    fun expect(question: String): Expect = when {
        REFERENCE.containsMatchIn(question) -> Expect.REFERENCE
        CHOICE.containsMatchIn(question.trim()) || CHOICE_AFTER.containsMatchIn(question.trim()) -> Expect.CHOICE
        AMOUNT.containsMatchIn(question) -> Expect.AMOUNT
        PHONE.containsMatchIn(question) -> Expect.PHONE
        NAME.containsMatchIn(question.trim()) -> Expect.NAME
        else -> Expect.FREE
    }

    /** The options a "Which X: A, B, or C?" question offers ("ending 323" kept with its name). */
    fun options(question: String): List<String> {
        val list = (CHOICE.find(question.trim()) ?: CHOICE_AFTER.find(question.trim()))?.groupValues?.get(1) ?: return emptyList()
        return list.split(Regex(",\\s*(?:or\\s+)?|\\s+or\\s+")).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
    }

    /** [answer] read for [question]; unchanged when nothing applies. [vocab] = the contacts' names (NAME answers). */
    fun read(answer: String, question: String?, vocab: EntityCorrector.Vocab = EntityCorrector.Vocab.EMPTY): String {
        val a = answer.trim()
        if (a.isEmpty() || question.isNullOrBlank()) return answer
        return when (expect(question)) {
            Expect.AMOUNT -> amount(a)
            // English digits ("oh five five", "double", "+233"), then Twi ones ("zero anum anum …"), then the words as said
            Expect.PHONE -> phone(numbers(a)) ?: phone(twiDigits(a)) ?: phone(a) ?: a
            Expect.CHOICE -> choice(a, options(question)) ?: a
            Expect.NAME -> name(a, vocab)
            Expect.REFERENCE, Expect.FREE -> a
        }
    }

    /** Number words as figures, with the spoken-digit rules ("oh five five", "double five", "two point five"). */
    private fun numbers(a: String): String = try { Normalizer.normalize(a, Lang.GH_EN) } catch (_: Throwable) { a }

    private val TWI_DIGIT = mapOf("zero" to "0", "hwee" to "0", "baako" to "1", "biako" to "1", "mmienu" to "2", "abien" to "2",
        "mmiɛnsa" to "3", "mmiensa" to "3", "abiɛsa" to "3", "anan" to "4", "anum" to "5", "nsia" to "6", "nson" to "7",
        "nnson" to "7", "nwɔtwe" to "8", "nwotwe" to "8", "awɔtwe" to "8", "nkron" to "9", "akron" to "9")

    /** A phone number said one Twi digit at a time ("zero anum anum, mmienu zero nkron …") as digits; other words kept. */
    private fun twiDigits(a: String): String =
        a.split(Regex("[\\s,.]+")).filter { it.isNotEmpty() }.joinToString(" ") { w -> TWI_DIGIT[w.lowercase()] ?: w }

    /** Two bare figures side by side ("2 50" from "two fifty") — 2.50 or 250? Not decided here: the words stay as said
     *  and the flow that asked reads them back or asks again (never a silent guess at an amount). */
    private val TWO_FIGURES = Regex("(?<![\\d.])\\d+\\s+\\d+(?![\\d.])")

    /** An amount answer: figures, currency spelled out ("GH 20" → GHS 20), money words repaired ("10 pesos" → pesewas). */
    internal fun amount(a: String): String {
        val n = numbers(a)
        if (TWO_FIGURES.containsMatchIn(n)) return GhanaWords.fix(a, aboutMoney = true)
        return GhanaWords.fix(TelcoTerms.repair(n, frame = true), aboutMoney = true)
    }

    private val FILLER = Regex("(?i)\\b(?:it'?s|it is|the number is|number is|is|my number is|their number is|please|okay|ok)\\b")

    /** The digits of a spoken number, when the answer is (nearly) only a number. Null otherwise. */
    internal fun phone(a: String): String? {
        val rest = FILLER.replace(a, " ").replace(Regex("[\\s,.\\-()]+"), " ").trim()
        if (!rest.all { it.isDigit() || it == ' ' || it == '+' }) return null
        var d = rest.filter { it.isDigit() || it == '+' }
        if (d.count { it.isDigit() } < 7) return null
        d = d.removePrefix("+")
        if (d.startsWith("233") && d.length == 12) d = "0" + d.substring(3)
        if (d.length == 9 && d[0] in "2345") d = "0$d"   // the leading 0 dropped ("five five two…")
        return d
    }

    /** The one option [a] names — by its words (a name, "ending 323") or its sound — when no other option is close. */
    internal fun choice(a: String, options: List<String>): String? {
        if (options.size < 2) return null
        ordinal(a, options.size)?.let { return options[it] }
        val digits = a.filter { it.isDigit() }
        val scored = options.map { o ->
            val name = o.replace(Regex("(?i)\\s+ending\\s+[\\d ]+$"), "").trim()
            var s = ContactMatcher.score(a.replace(Regex("(?i)\\bending\\b.*$"), "").trim(), name)
            // "the one ending 323": the digits decide between same-named options
            val tail = Regex("(?i)ending\\s+([\\d ]+)$").find(o)?.groupValues?.get(1)?.filter { it.isDigit() }
            if (tail != null && digits.isNotEmpty()) s = if (digits.endsWith(tail) || tail.endsWith(digits)) s + 0.2 else s - 0.3
            o to s
        }.sortedByDescending { it.second }
        val best = scored[0]
        if (best.second >= 0.7 && scored[1].second <= best.second - 0.1) return best.first
        if (digits.isNotEmpty()) return null
        return bySound(a, options)
    }

    private val FILLER_WORDS = Regex("(?i)\\b(?:the|one|please|it'?s|is|nso|yes|ok(?:ay)?|that|this)\\b")

    /**
     * A CLOSED choice heard badly: the lab recogniser writes English names its own way ("Prince Melanin" → "press malam",
     * "Richmond Apenensu" → "rekyemɔndapanu" — A20e, 5 October 2026). Among the few options offered, the one the WHOLE
     * answer sounds closest to (sound keys) — only when it is clearly closer than every other (0.1, as for names) and alike enough
     * (0.5). Taken as an inference: said back once in the read-back of the action that uses it (HeardAs via
     * [noteInference]), never acted on unread.
     */
    internal fun bySound(a: String, options: List<String>): String? {
        val heard = com.tekyerema.voice.PhoneticKey.of(FILLER_WORDS.replace(a, " "))
        if (heard.length < 4) return null
        val scored = options.map { o ->
            o to com.tekyerema.voice.PhoneticKey.similarity(heard, com.tekyerema.voice.PhoneticKey.of(o.replace(Regex("(?i)\\s+ending\\s+[\\d ]+$"), "")))
        }.sortedByDescending { it.second }
        val best = scored[0]
        if (best.second < 0.5 || (scored.size > 1 && scored[1].second > best.second - 0.1)) return null
        return best.first
    }

    private val ORDINALS = listOf("first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2, "fourth" to 3,
        "4th" to 3, "last" to -1, "final" to -1)
    private val ORDINAL_ONLY = Regex("(?i)^(?:(?:the|that|this)\\s+)?(\\w+)(?:\\s+(?:one|person|name|option|contact))?[.!]?$")
    private val NUMBER_ONLY = Regex("(?i)^(?:(?:option|number|no\\.?)\\s+)(one|two|three|four|1|2|3|4)[.!]?$")

    /** "the first one", "second", "the last one", "number two" → the option's index, when the answer is only that. */
    internal fun ordinal(a: String, count: Int): Int? {
        val t = a.trim()
        ORDINAL_ONLY.find(t)?.let { m ->
            val w = m.groupValues[1].lowercase()
            val idx = ORDINALS.firstOrNull { it.first == w }?.second ?: return@let
            val i = if (idx < 0) count - 1 else idx
            return i.takeIf { it < count }
        }
        NUMBER_ONLY.find(t)?.let { m ->
            val i = when (m.groupValues[1].lowercase()) { "one", "1" -> 0; "two", "2" -> 1; "three", "3" -> 2; else -> 3 }
            return i.takeIf { it < count }
        }
        return null
    }

    /**
     * An answer that was READ as something else is said back once in the read-back that uses it (HeardAs): an option
     * chosen by its sound ("afua" → Efua Mensah — not "the first one", not the name as said), or an amount whose unit was
     * repaired ("10 pesos" → 10 pesewas). Safety audit 2026-10-05.
     */
    fun noteInference(answer: String, read: String, question: String?) {
        if (question.isNullOrBlank() || answer.trim().equals(read.trim(), ignoreCase = true)) return
        when (expect(question)) {
            Expect.CHOICE -> if (ordinal(answer, options(question).size) == null && !read.contains(answer.trim(), ignoreCase = true))
                com.tekyerema.voice.HeardAs.noteGuess(answer.trim(), read.trim())
            Expect.AMOUNT -> com.tekyerema.voice.HeardAs.noteWords(answer.trim(), read.trim())
            else -> {}
        }
    }

    /** A name said alone, corrected against the contacts as if after "call" ("coffee" → Kofi). */
    private fun name(a: String, vocab: EntityCorrector.Vocab): String {
        if (vocab.isEmpty || a.split(" ").size > 4) return a
        val fixed = EntityCorrector.correct("call $a", vocab).text
        return fixed.removePrefix("call ").ifBlank { a }
    }
}
