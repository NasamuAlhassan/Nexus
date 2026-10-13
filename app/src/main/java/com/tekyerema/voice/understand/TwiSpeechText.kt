package com.tekyerema.voice.understand

import com.tekyerema.voice.ConfirmTemplates

/**
 * A Twi line made ready for the HCI Lab's Twi voice (robustness layer, 5 October 2026). That voice SKIPS digits and "GH₵"
 * (audit/demo-2026-10-04/probe: "Frɛ 0552093690" came back as "Frɛ" and silence), and the owner wants ONE voice for the
 * whole line (4 October 2026) — so before the lab is called, every figure becomes Twi words:
 *
 *  - an amount ("GH₵ 10.50", "GHS 5", "5 cedis", "50 pesewas", "five Ghana cedis") → "sidi du ne pesewa aduonum";
 *  - a phone number (10 digits from 0, "+233…", or the read-back's "0 5 5, 2 0 9, 3 6 9 0") → Twi digits in 3-3-4 groups;
 *  - any other whole number → its Twi numeral (to 999,999), else digit by digit;
 *  - a stretch marked for the English voice (⟦en:…⟧) is put into Twi the same way and unmarked — one voice;
 *  - "GHS"/"GHC"/"GH₵" on their own → "sidi".
 * A line with no figure and no marker is returned exactly as it is (the recorded lab clips are looked up by their text).
 * The numerals are the owner's speaker-verified table; hundreds and thousands follow the Harvard counting lesson
 * [owner: verify]. Pure → SpeechRobustnessTest.
 */
object TwiSpeechText {
    private const val EN_OPEN = "⟦en:"
    private const val EN_CLOSE = "⟧"
    private val SPAN = Regex("⟦en:(.*?)⟧")

    fun prepare(text: String): String {
        if (text.isBlank() || (text.none { it.isDigit() } && !text.contains(EN_OPEN) && !CUR_ALONE.containsMatchIn(text) &&
                !EN_NUMBER_RUN.containsMatchIn(text))) return text
        var s = SPAN.replace(text) { m -> spanToTwi(m.groupValues[1]) }
        // a USSD code is said one digit at a time, "star" and "hash" as Ghanaians say them in Twi too — never as a number
        s = EN_USSD.replace(s) { m -> "*" + m.groupValues[1].trim().split(" ").joinToString("") { w -> USSD_WORD[w.lowercase()] ?: "" } + "#" }
        s = USSD.replace(s) { m -> m.value.map { c -> when (c) { '*' -> "star"; '#' -> "hash"; else -> ConfirmTemplates.twiDigits(c.toString()) } }.joinToString(" ") }
        // Lines reach the voice through the English verbaliser (VoiceController: Verbalize.apply), which has already made
        // "GH₵ 10.50" into "ten cedis fifty pesewas" and 0552093690 into "zero five five …" — English words the Twi voice
        // would read as English. Those runs go back to figures first, then into Twi like any other figure.
        s = EN_NUMBER_RUN.replace(s) { m -> englishRun(m.value) }
        s = figures(s)
        return s.replace(Regex(" {2,}"), " ").trim()
    }

    private const val EN_NUM = "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
        "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million)"
    /** A run of English number words ("ten", "twenty-five", "one thousand two hundred fifty", "zero five five two …",
     *  "two point five"), a phone number's "plus" with it. "and" and "point" only between number words. */
    private val EN_NUMBER_RUN = Regex("(?i)\\b(?:plus )?$EN_NUM(?:-$EN_NUM)?(?:[ ,]+(?:(?:and|point) )?$EN_NUM(?:-$EN_NUM)?)*\\b")
    /** The English verbaliser's USSD code ("star one seven zero hash"): kept as one code. */
    private val EN_USSD = Regex("(?i)\\bstar((?: (?:star|zero|one|two|three|four|five|six|seven|eight|nine))+) hash\\b")

    private fun englishRun(run: String): String {
        val plus = run.startsWith("plus ", ignoreCase = true)
        val words = if (plus) run.substring(5) else run
        val n = try { com.tekyerema.voice.Normalizer.normalize(words.replace("-", " ").replace(",", ""), com.tekyerema.voice.Lang.GH_EN) }
            catch (_: Throwable) { return run }
        if (!n.any { it.isDigit() }) return run
        // "plus two three three, five five, …" is a +233 number: its digits together, for the phone rule below
        if (plus) return "+" + n.filter { it.isDigit() }
        // the parser stops at thousands: "1 million" / "2 million 500000" → one figure
        return MILLION.replace(n) { m -> ((m.groupValues[1].toLong() * 1_000_000) + (m.groupValues[2].toLongOrNull() ?: 0)).toString() }
    }

    /** An English stretch: its number words become figures first ("five Ghana cedis" → "5 Ghana cedis"), then Twi. */
    private fun spanToTwi(en: String): String {
        val numeric = com.tekyerema.voice.Normalizer.normalize(en, com.tekyerema.voice.Lang.GH_EN)
        return figures(numeric)
    }

    private val CUR = "(?:GH₵|GH¢|GHS|GHC|₵|¢)"
    private val AMOUNT_BEFORE = Regex("(?i)$CUR\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?")
    private val AMOUNT_AFTER = Regex("(?i)\\b(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?\\s?(?:ghana\\s+)?(cedis?|ghs|ghc|pesewas?)\\b")
    /** A phone number: 10 digits from 0 (together or in groups), or +233 / 233 and nine digits. */
    private val PHONE = Regex("(?<![\\d])(?:\\+?233|0)[\\s,-]*(?:\\d[\\s,-]*){8}\\d(?![\\d])")
    private val NUMBER = Regex("(?<![\\d.])\\d{1,3}(?:,\\d{3})+(?![\\d])|(?<![\\d.])\\d+(?:\\.\\d+)?(?![\\d])")
    private val CUR_ALONE = Regex("(?i)(?<![\\p{L}])(?:GH₵|GH¢|GHS|GHC)(?![\\p{L}\\d])")

    private val ENDING = Regex("(?i)\\b(ending(?:\\s+in)?)\\s+(\\d+)")
    /** Cedis and pesewas said together ("10 cedis 50 pesewas", "5 cedis and 20 pesewas") — one amount. */
    private val AMOUNT_PAIR = Regex("(?i)\\b(\\d+)\\s?(?:ghana\\s+)?cedis?,?\\s+(?:and\\s+)?(\\d{1,2})\\s?pesewas?\\b")
    /** Data sizes: "2GB", "500 MB", the verbaliser's "2 gigabytes" — the unit first, then its number, as an amount is said
     *  ("sidi du") [owner: verify]. */
    private val DATA_SIZE = Regex("(?i)\\b(\\d+(?:\\.\\d+)?)\\s?(GB|MB|gigabytes?|megabytes?)\\b")
    private val USSD = Regex("\\*[0-9*]+#")
    private val MILLION = Regex("(?i)\\b(\\d{1,3})\\s+million(?:\\s+(\\d{1,6}))?\\b")
    private val USSD_WORD = mapOf("star" to "*", "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9")

    private fun figures(input: String): String {
        var s = input
        s = PHONE.replace(s) { m -> ConfirmTemplates.twiNumber(m.value.filter { it.isDigit() || it == '+' }) }
        // "Prince Alhassan ending 323": the last digits of a number are said one by one, not as three hundred and twenty-three
        s = ENDING.replace(s) { m -> m.groupValues[1] + " " + ConfirmTemplates.twiDigits(m.groupValues[2]) }
        s = AMOUNT_PAIR.replace(s) { m -> amount(m.groupValues[1], m.groupValues[2].padStart(2, '0'), cedis = true) ?: m.value }
        s = DATA_SIZE.replace(s) { m -> (if (m.groupValues[2].startsWith("g", true)) "gigabyte " else "megabyte ") + number(m.groupValues[1]) }
        s = AMOUNT_BEFORE.replace(s) { m -> amount(m.groupValues[1], m.groupValues[2], cedis = true) ?: m.value }
        s = AMOUNT_AFTER.replace(s) { m ->
            amount(m.groupValues[1], m.groupValues[2], cedis = !m.groupValues[3].lowercase().startsWith("pesewa")) ?: m.value
        }
        s = NUMBER.replace(s) { m -> number(m.value) }
        s = CUR_ALONE.replace(s, "sidi")
        return s
    }

    /** A cedi figure as the network writes it ("12.50", "1,234.00", "0.00") in Twi words — "sidi dumienu ne pesewa
     *  aduonum", "sidi zero"; null when it isn't a figure or can't be said. */
    fun cedis(figure: String): String? {
        val a = figure.replace(",", "").trim()
        if (!Regex("\\d+(?:\\.\\d{1,2})?").matches(a)) return null
        return amount(a.substringBefore('.'), a.substringAfter('.', ""), cedis = true)
    }

    /** "10", "50" → "sidi du ne pesewa aduonum"; pesewas when [cedis] is false. Null when it can't be said. */
    private fun amount(whole: String, frac: String, cedis: Boolean): String? {
        val w = whole.replace(",", "").toBigDecimalOrNull() ?: return null
        val v = if (frac.isNotEmpty()) "$w.$frac".toBigDecimal() else w
        val c = if (cedis) v else v.movePointLeft(2)
        val cents = c.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        val ced = cents / 100; val pes = cents % 100
        val parts = listOfNotNull(
            if (ced > 0) "sidi " + (words(ced) ?: return null) else null,
            if (pes > 0) "pesewa " + (words(pes) ?: return null) else null)
        return parts.joinToString(" ne ").ifEmpty { "sidi zero" }
    }

    /** A plain figure in Twi: a numeral to 999,999, a decimal or a longer run digit by digit. */
    private fun number(fig: String): String {
        val plain = fig.replace(",", "")
        if ("." in plain) return plain.split(".").joinToString(" pɔint ") { ConfirmTemplates.twiDigits(it) }
        val n = plain.toLongOrNull() ?: return ConfirmTemplates.twiDigits(plain)
        if (plain.length > 1 && plain.startsWith("0")) return ConfirmTemplates.twiDigits(plain)
        return words(n) ?: ConfirmTemplates.twiDigits(plain)
    }

    private val HUNDREDS = arrayOf("", "ɔha", "ahaanu", "ahasa", "ahanan", "ahanum", "ahansia", "ahanson", "ahanwɔtwe", "ahankron")

    /** 0–999,999,999 as a Twi numeral, or null. 1–199 from [ConfirmTemplates.twiWords] (the verified range). */
    fun words(n: Long): String? {
        if (n == 0L) return "zero"
        if (n in 1..199) return ConfirmTemplates.twiWords(n.toInt())
        if (n in 200..999) {
            val h = HUNDREDS[(n / 100).toInt()]; val r = (n % 100).toInt()
            return if (r == 0) h else "$h ne " + (ConfirmTemplates.twiWords(r) ?: return null)
        }
        if (n in 1000..999_999) {
            val k = n / 1000; val r = n % 1000
            val head = if (k == 1L) "apem" else "mpem " + (words(k) ?: return null)
            return if (r == 0L) head else "$head ne " + (words(r) ?: return null)
        }
        // a million and more (a balance can be): "ɔpepem" [owner: verify] — not "one zero zero zero zero zero zero"
        if (n in 1_000_000..999_999_999) {
            val m = n / 1_000_000; val r = n % 1_000_000
            val head = if (m == 1L) "ɔpepem" else "ɔpepem " + (words(m) ?: return null)
            return if (r == 0L) head else "$head ne " + (words(r) ?: return null)
        }
        return null
    }
}
