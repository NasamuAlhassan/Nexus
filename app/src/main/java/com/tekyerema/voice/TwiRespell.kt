package com.tekyerema.voice

/**
 * English words inside a Twi line, respelled so the Twi voice can say them (owner, 21 Oct 2026: "it can't handle a lot of the
 * pronunciation like 'wife', mobile money, phone… a lot of the English words"). The Twi voices read Twi letters only (the
 * phone's MMS voice has no c, j, q, v, x, z and SKIPS them: "cedis" came out "edis", "Zelia" "elia"); English digraphs are
 * read letter by letter ("ph-one").
 *
 * Two layers, both data-driven:
 *  1. a pick-by-ear lexicon (`speech.twi.respell`, word or phrase → Twi spelling) for common loanwords whose letters are all
 *     Twi letters but whose English sound is not ("mobile money", "wife");
 *  2. general letter rules for any other word that is plainly not Twi (it holds a letter or a digraph Twi never writes:
 *     c, j, q, v, x, z, ph, th, sh, ch, ck): ph→f, th→t, sh→hy, ch→ky, ck→k, soft c→s, c→k, q→k, x→ks, z→s, v→f, j→dw.
 * A word made only of Twi letters and not in the lexicon is left exactly as it is, so Twi words are never touched. Pure.
 */
object TwiRespell {
    private val NOT_TWI = Regex("(?i)[cjqvxz]|ph|th|sh|ch|ck")
    private val WORD = Regex("[A-Za-zƐɛƆɔ'’]+")

    /** [line] with the lexicon's phrases and the not-Twi words respelled. */
    fun apply(line: String, lexicon: Map<String, String> = configured()): String {
        var s = kinNames(endings(line))
        // Longest phrases first, whole words only, case-insensitive ("Mobile Money" and "mobile money").
        for ((k, v) in lexicon.entries.sortedByDescending { it.key.length }) {
            if (k.isBlank() || k.startsWith("_")) continue
            s = Regex("(?i)(?<![\\p{L}])" + Regex.escape(k) + "(?![\\p{L}])").replace(s) { v }
        }
        return WORD.replace(s) { m -> if (NOT_TWI.containsMatchIn(m.value)) letters(m.value) else m.value }
    }

    /**
     * "ending 3 6 9 0" (a number's last digits) inside a Twi line, as the Twi read-back says it: "nɔma a n'awieɛ yɛ mmiɛnsa nsia
     * nkron zero" (owner, 21 Oct 2026: "the ending number like 3 6 9 0, the ending isn't translated"). The translator keeps the
     * English word and the figures (or English digit words); this puts the Twi frame and Twi digits back, whatever came out.
     */
    fun endings(line: String): String = ENDING.replace(line) { m ->
        val digits = DIGIT_TOKEN.findAll(m.groupValues[1]).mapNotNull { t -> digitOf(t.value) }.joinToString("")
        if (digits.length < 3) m.value
        else Phrases.get("twiNumberEnding", true, ", nɔma a n'awieɛ yɛ {digits}", "digits" to ConfirmTemplates.twiDigits(digits))
            .trimStart(',', ' ') + m.value.takeLastWhile { it.isWhitespace() || it == ',' }
    }

    /**
     * A contact saved as "My wife", "My mum"… said inside a Twi line as the Twi for it, from the listener's side: "wo yere" (your
     * wife). The name is kept as saved everywhere else (the screen, the read-back's English); the translator half-translated it
     * ("Ɛfiri My yere hɔ") — judge's review of take A1, 21 Oct 2026.
     */
    fun kinNames(line: String): String = KIN_NAME.replace(line) { m ->
        val twi = KIN_TWI[m.groupValues[1].lowercase()] ?: return@replace m.value
        if (m.value.first().isUpperCase() && m.range.first == 0) "Wo $twi" else "wo $twi"
    }
    private val KIN_TWI = mapOf("wife" to "yere", "yere" to "yere", "husband" to "kunu", "kunu" to "kunu",
        "mum" to "maame", "mom" to "maame", "mother" to "maame", "maame" to "maame", "dad" to "papa", "father" to "papa",
        "papa" to "papa", "brother" to "nuabarima", "sister" to "nuabaa")
    private val KIN_NAME = Regex("(?i)(?<![\\p{L}])my\\s+(wife|yere|husband|kunu|mum|mom|mother|maame|dad|father|papa|brother|sister)(?![\\p{L}])")

    private const val DIGIT_WORDS = "zero|oh|one|two|three|four|five|six|seven|eight|nine|baako|mmienu|abien|mmiɛnsa|abiɛsa|anan|" +
        "anum|nsia|nson|nwɔtwe|nkron"
    private val DIGIT_TOKEN = Regex("(?i)\\d|\\b(?:$DIGIT_WORDS)\\b")
    private val ENDING = Regex("(?i)\\bending(?:\\s+(?:in|with))?[\\s:,]+((?:(?:\\d|\\b(?:$DIGIT_WORDS)\\b)[\\s,]*){3,6})")
    private fun digitOf(t: String): Char? = when (t.lowercase()) {
        "zero", "oh" -> '0'; "one", "baako" -> '1'; "two", "mmienu", "abien" -> '2'; "three", "mmiɛnsa", "abiɛsa" -> '3'
        "four", "anan" -> '4'; "five", "anum" -> '5'; "six", "nsia" -> '6'; "seven", "nson" -> '7'; "eight", "nwɔtwe" -> '8'
        "nine", "nkron" -> '9'
        else -> t.singleOrNull()?.takeIf { it.isDigit() }
    }

    /** The letter rules for one word (lower case out; the Twi voice lower-cases anyway). */
    fun letters(word: String): String {
        var w = word.lowercase()
        w = w.replace("ph", "f").replace("th", "t").replace("sh", "hy").replace("ch", "ky").replace("ck", "k")
        w = w.replace(Regex("c(?=[eiy])"), "s").replace("c", "k")
        w = w.replace("q", "k").replace("x", "ks").replace("z", "s").replace("v", "f").replace("j", "dw")
        return w
    }

    private fun configured(): Map<String, String> = try {
        val o = com.tekyerema.core.config.AppConfig.obj("speech.twi.respell")
        o?.keys()?.asSequence()?.associateWith { o.optString(it) }?.filterValues { it.isNotBlank() } ?: emptyMap()
    } catch (_: Throwable) { emptyMap() }
}

/** Every Twi voice behind one door: the line is respelled ([TwiRespell]) before any Twi voice (lab, Space, phone) says it. */
class RespellTts(private val inner: TtsProvider) : TtsProvider, VoiceAvailability {
    override val name get() = inner.name
    override fun whyUnavailable(): String? = (inner as? VoiceAvailability)?.whyUnavailable()
    override suspend fun speak(text: String, language: String): Boolean = inner.speak(TwiRespell.apply(text), language)
    override fun stop() = inner.stop()
    override fun shutdown() = inner.shutdown()
}
