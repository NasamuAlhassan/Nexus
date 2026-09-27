package com.tekyerema.voice

/**
 * On-device language pipeline (§10/§13/§25): LID + entity-first normalization + entity extraction in
 * pure Kotlin — a faithful port of phase2/{lid,asr,eval_set}.py. This runs with NO network, so once a
 * local ASR model supplies a raw transcript, everything from LID → canonical goal → entities is
 * genuinely local. The raw transcript is preserved separately (never overwritten). Unit-tested.
 *
 * Not Ghanaian-language invention: English number words are standard; Twi numerals come from the
 * speaker-verified table (mirrors phase2/lang/twi_numbers.csv incl. observed ASR variants).
 */

private val EN_UNITS = mapOf(
    "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
    "eighteen" to 18, "nineteen" to 19
)
private val EN_TENS = mapOf(
    "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
    "seventy" to 70, "eighty" to 80, "ninety" to 90
)
private val EN_SCALES = mapOf("hundred" to 100, "thousand" to 1000)
// §5.2 — dropped "city"/"cities": they are ordinary English words, so "I have one city" was being
// rewritten to "1 cedis". Kept only the acoustic mishearings that are not real words on their own.
private val CURRENCY_MISHEAR = mapOf(
    "cds" to "cedis", "cd" to "cedis",
    "seedies" to "cedis", "seedis" to "cedis", "sedis" to "cedis", "cedes" to "cedis", "cd's" to "cedis"
)
// Twi colloquial currency words the ASR emits (observed on clean-v1 output): "sidi/sedi/sidim" = cedi,
// "paswa" = pesewa. Standard everyday Twi pronunciations → map to the canonical currency token so the
// currency ENTITY is recovered (mirrors phase2/asr.py _TWI_CURRENCY_VARIANTS; deterministic, not training).
private val TWI_CURRENCY_VARIANTS = mapOf(
    "sidi" to "cedi", "sedi" to "cedi", "sidim" to "cedi", "sɛdi" to "cedi",
    "sidii" to "cedi", "paswa" to "pesewa", "pasewa" to "pesewa"
)

/** Speaker-verified Twi numerals + observed ASR-output variants (w2v-bert + Khaya). */
private val TWI_NUMBERS: Map<String, Int> = mapOf(
    "zero" to 0, "baako" to 1, "mmienu" to 2, "mmiɛnsa" to 3, "anan" to 4, "anum" to 5,
    "nsia" to 6, "nson" to 7, "nwɔtwe" to 8, "awɔtwe" to 8, "nkron" to 9, "du" to 10,
    "dubaako" to 11, "dumienu" to 12, "dumiɛnsa" to 13, "duanan" to 14, "duanum" to 15,
    "dunsia" to 16, "dunson" to 17, "dunwɔtwe" to 18, "dunkron" to 19, "aduonu" to 20,
    "aduasa" to 30, "aduanan" to 40, "aduonum" to 50, "aduosia" to 60, "aduɔson" to 70,
    "aduɔwɔtwe" to 80, "aduɔkron" to 90, "ɔha" to 100,
    // observed ASR-output spellings (do not appear in gold; only help score real output)
    "ziro" to 0, "anom" to 5, "mwɔtwe" to 8, "nkoron" to 9, "mmɔkye" to 8
)

/** Entity-first normalization — spoken numbers → digits; names/words untouched (§13). */
object Normalizer {
    fun normalize(text: String?, lang: String): String {
        val s = (text ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (s.isEmpty()) return s
        if (lang == Lang.TWI) return normalizeTwi(s)
        return normalizeEnglish(s)
    }

    private val PUNCT = ".,;:?!“”\"'"
    private val TWI_CURRENCY_TOKENS = setOf("cedi", "cedis", "pesewa", "pesewas")
    private fun isTwiNumeric(c: String): Boolean =
        TWI_NUMBERS.containsKey(c) || TWI_CURRENCY_VARIANTS.containsKey(c) || c in TWI_CURRENCY_TOKENS || Regex("\\d+").matches(c)

    private fun normalizeTwi(s: String): String {
        val toks = s.split(" ")
        val cores = toks.map { it.trim(*PUNCT.toCharArray()).lowercase() }
        return toks.mapIndexed { i, w ->
            val core = cores[i]
            val lead = w.takeWhile { it in PUNCT }
            val trail = w.takeLastWhile { it in PUNCT }
            // §5.3 — bare "du" is "ten" ONLY in a numeric context (a neighbouring numeral/currency word);
            // otherwise it's the ordinary Twi word and must be left alone. Compounds (dubaako=11 …) are
            // separate keys and unaffected.
            if (core == "du") {
                val numericNeighbour = (i > 0 && isTwiNumeric(cores[i - 1])) || (i < cores.size - 1 && isTwiNumeric(cores[i + 1]))
                return@mapIndexed if (numericNeighbour) "${lead}10$trail" else w
            }
            // number table first (mirrors phase2/asr.py _twi_tok order), then colloquial currency words.
            TWI_NUMBERS[core]?.let { return@mapIndexed "$lead$it$trail" }
            TWI_CURRENCY_VARIANTS[core]?.let { return@mapIndexed "$lead$it$trail" }
            w
        }.joinToString(" ")
    }

    private fun expand(tok: String): List<String> {
        if ("-" in tok) {
            val parts = tok.split("-").filter { it.isNotEmpty() }
            val known = { p: String -> val l = p.lowercase(); EN_UNITS.containsKey(l) || EN_TENS.containsKey(l) || EN_SCALES.containsKey(l) }
            if (parts.isNotEmpty() && parts.all(known)) return parts
        }
        return listOf(tok)
    }

    private fun normalizeEnglish(s: String): String {
        val toks = s.split(" ").flatMap { expand(it) }
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            val (span, used) = englishNumberSpan(toks.subList(i, toks.size))
            if (used > 0 && span != null) { out.add(span); i += used } else { out.add(toks[i]); i += 1 }
        }
        // currency-mishearing repair: only rewrite a known mishearing directly after a digit
        for (j in 1 until out.size) {
            val base = out[j].lowercase().trim('.', ',', ';')
            if (CURRENCY_MISHEAR.containsKey(base) && Regex("\\d[\\d,]*").matches(out[j - 1]))
                out[j] = CURRENCY_MISHEAR[base]!!
        }
        return out.joinToString(" ")
    }

    /** Parse a leading run of English number words → (text, tokensConsumed). Distinguishes a spoken
     *  DIGIT SEQUENCE (phone/code: all single digits, len≥2 → kept apart) from a QUANTITY (composed). */
    private fun englishNumberSpan(tokens: List<String>): Pair<String?, Int> {
        val words = ArrayList<String>()
        var end = 0
        for ((k, tok) in tokens.withIndex()) {
            val t = tok.lowercase().trim('.', ',', ';', '?', '!')
            val breaker = tok.endsWith(",") || tok.endsWith(";") || tok.endsWith(".")
            when {
                t in EN_UNITS || t in EN_TENS || t in EN_SCALES -> {
                    // §5.1 — a TENS or TEEN following a non-scale number starts a NEW number ("six thirty" is
                    // a time, 6:30, not 36; "fifteen fifty" is 15:50, not 65). Only a scale ("one thousand
                    // thirty") legitimately carries a tens/teen after it. Stop the span before such a word.
                    if (words.isNotEmpty()) {
                        val nextIsTensOrTeen = t in EN_TENS || (EN_UNITS[t]?.let { it in 10..19 } == true)
                        val prevIsScale = words.last() in EN_SCALES
                        if (nextIsTensOrTeen && !prevIsScale) break
                    }
                    words.add(t); end = k + 1; if (breaker) break
                }
                t == "and" && words.isNotEmpty() -> continue
                else -> break
            }
        }
        if (words.isEmpty()) return null to 0
        val singleDigits = words.all { EN_UNITS[it]?.let { v -> v <= 9 } == true }
        if (singleDigits && words.size >= 2) return words.joinToString(" ") { EN_UNITS[it].toString() } to end
        var total = 0; var cur = 0
        for (w in words) when {
            w in EN_UNITS -> cur += EN_UNITS[w]!!
            w in EN_TENS -> cur += EN_TENS[w]!!
            else -> { val sc = EN_SCALES[w]!!; cur = (if (cur == 0) 1 else cur) * sc; if (sc >= 1000) { total += cur; cur = 0 } }
        }
        return (total + cur).toString() to end
    }
}

/** Confidence-aware text LID (§11) — faithful port of phase2/lid.py; never a hard flip on bare entities. */
object Lid {
    // §4.2 — English homographs REMOVED from the Twi lexicon: "no"/"so"/"me"/"ne" flipped short English
    // commands to Twi (e.g. "No, cancel" scored Twi 1.0). Twi detection now leans on distinctive tokens +
    // diacritics, which is enough (see the multi-token/diacritic tests). Kept "na"/"ma"/"wo" (not standalone
    // English words and useful signals).
    private val TWI_WORDS = setOf(
        "wo","na","sɛ","ma","yɛ","mesoma","soma","frɛ","merefrɛ","kenkan","bue","mabue",
        "hwɛ","nkra","sika","bere","mia","kɔ","aane","daabi","obi","hena","dɛn","wopɛ","meyɛ","mewɔ","wɔ",
        "firi","hɔ","seesei","ara","yɛsrɛ","toa","nua","barima","yere","ɛno","ɛyɛ","meretie","wʋm"
    )
    private val ENG_WORDS = setOf(
        "the","to","a","is","it","my","me","you","your","on","of","and","i","in","what","who","how","do",
        "want","send","call","read","open","check","play","stop","message","have","much","money","time",
        "battery","please","enter",
        // §4.2 — common English command/confirmation words, so a spoken "no"/"yes"/"cancel" scores English.
        "no","yes","so","cancel","wait","okay","ok","sure","back","home","now","up","down"
    )
    private val TWI_DIACRITICS = "ɛɔŋʋɩɣ".toSet()

    data class Detection(val lang: String, val confidence: Double, val twi: Double, val eng: Double,
                         val twiTok: Int = 0, val engTok: Int = 0, val diac: Int = 0)

    fun detect(text: String?): Detection {
        // (?U) → Unicode-aware \w so Twi tokens with diacritics (e.g. "sɛ", "frɛ") stay whole.
        val toks = Regex("(?U)[^\\W\\d_]+").findAll((text ?: "").lowercase()).map { it.value }.toList()
        if (toks.isEmpty()) return Detection(Lang.GH_EN, 0.0, 0.0, 0.0)
        val twi = toks.count { it in TWI_WORDS }
        val eng = toks.count { it in ENG_WORDS }
        val diac = (text ?: "").count { it in TWI_DIACRITICS }
        val twiScore = twi + 1.5 * minOf(diac, 4)
        val engScore = eng.toDouble()
        val total = twiScore + engScore
        if (total == 0.0) return Detection(Lang.GH_EN, 0.0, twiScore, engScore, twi, eng, diac)
        val lang = if (twiScore > engScore) Lang.TWI else Lang.GH_EN
        val conf = Math.round(Math.abs(twiScore - engScore) / total * 1000.0) / 1000.0
        return Detection(lang, conf, twiScore, engScore, twi, eng, diac)
    }

    /** Production decision: confidence-aware + session/previous fallback (§11). Returns lang + reason. */
    fun resolve(text: String?, sessionDefault: String = Lang.GH_EN, prevLang: String? = null, minConf: Double = 0.25): Pair<String, String> {
        val d = detect(text)
        val both = d.twi > 0 && d.eng > 0
        val fb = prevLang ?: sessionDefault
        if (d.confidence < minConf) {
            return fb to "low-confidence (${d.confidence}) → ${if (prevLang != null) "previous-turn" else "session-default"} $fb"
        }
        // §4.3 — never flip on a single token. Require sustained evidence (≥2 lexicon hits, or a Twi token
        // backed by diacritics) before overriding the current language; the user's mode is the primary signal.
        val winnerTok = if (d.lang == Lang.TWI) d.twiTok else d.engTok
        val sustained = winnerTok >= 2 || (d.lang == Lang.TWI && d.diac > 0)
        if (d.lang != fb && !sustained)
            return fb to "single-token ${d.lang} ($winnerTok tok) not enough to flip from $fb"
        if (both && d.confidence < 0.5) return d.lang to "code-switch (twi+eng) → dominant ${d.lang} (conf ${d.confidence})"
        return d.lang to "confident ${d.lang} (conf ${d.confidence})"
    }
}

/** Typed entity extraction (§11/§13) — numbers/currency/app/name/command. Port of eval_set.derive_entities. */
object Entities {
    private val VERBS = listOf("send","call","read","open","buy","check","play","stop","message","turn","go","point","count")
    private val APPS = listOf("whatsapp","youtube","gmail","maps","music","camera","clock","settings","messages","telegram","chrome","momo")
    private val CURRENCY = listOf("cedis","cedi","pesewas","pesewa","airtime")
    // Recognition inventory (NOT a command list): the current test identity Prince + attested names.
    private val NAMES = setOf("Prince","Ama","Kwame","Kofi","Adjoa","Yaw","Akosua","Kwabena","Abena","Lydia","Mansa")

    fun derive(gold: String, lang: String): List<Pair<String, String>> {
        val norm = Normalizer.normalize(gold, lang)
        val lowNorm = norm.lowercase(); val lowGold = gold.lowercase()
        val seen = HashSet<Pair<String, String>>(); val out = ArrayList<Pair<String, String>>()
        fun add(e: String, c: String) { val k = e.lowercase() to c; if (seen.add(k)) out.add(e to c) }
        Regex("\\d+").findAll(norm).forEach { add(it.value, "number") }
        for (cur in CURRENCY) if (Regex("\\b$cur\\b").containsMatchIn(lowGold)) add(cur, "currency")
        val goldApp = lowGold.replace("whats app", "whatsapp")
        for (app in APPS) if (Regex("\\b$app\\b").containsMatchIn(goldApp)) add(app, "app")
        for (n in NAMES) if (n.lowercase() in lowGold) add(n, "name")
        if (lang == Lang.GH_EN) for (v in VERBS) if (Regex("\\b$v\\b").containsMatchIn(lowNorm)) add(v, "command")
        return out
    }
}

/**
 * Facade the ASR providers/controller use: given a RAW transcript from any model (local or cloud) +
 * session context, produce the full [Transcript] locally. This is what makes LID/normalize/entities
 * offline regardless of where the raw text came from.
 */
object LanguagePipeline {
    fun process(raw: String, sessionLanguage: String, forceLanguage: String? = null, backend: String = "", asrMs: Long = 0): Transcript {
        val (lang, reason) = when (forceLanguage) {
            Lang.GH_EN, Lang.TWI -> forceLanguage to "explicit"
            else -> Lid.resolve(raw, sessionDefault = sessionLanguage)
        }
        val canonical = Normalizer.normalize(raw, lang)
        val entities = try { Entities.derive(canonical, lang) } catch (t: Throwable) { emptyList() }
        return Transcript(raw = raw, canonical = canonical, language = lang, reason = reason, entities = entities, backend = backend, asrMs = asrMs)
    }
}
