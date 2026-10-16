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
    "sidii" to "cedi", "paswa" to "pesewa", "pasewa" to "pesewa",
    // the HCI Lab recogniser's plural (2 October 2026: "…tensidisma" = "ten sidis ma")
    "sidis" to "cedis", "sedis" to "cedis",
    // the HCI Lab recogniser's pesewa (audit/lab-asr-2026-10-02: "pasea aduonum", "pɛ soa aduonum")
    "pasea" to "pesewa", "pɛsoa" to "pesewa", "pesoa" to "pesewa", "pɛsewa" to "pesewa",
    // the on-device omni CTC model's cedi (3 October 2026, GOLD K06: "mano scedi me no")
    "scedi" to "cedi"
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
    "ziro" to 0, "anom" to 5, "mwɔtwe" to 8, "nkoron" to 9, "mmɔkye" to 8,
    // X-06 — the other standard spellings of 2–5 and the larger units, from the Harvard Twi course's counting lesson
    // (elias.fas.harvard.edu/languages/twi/beginning/2/counting): mienu, miɛnsa, ɛnan, enum; ahaanu = 200,
    // apem = 1000. Recognition only — never spoken. [owner: verify with a native speaker]
    "mienu" to 2, "miɛnsa" to 3, "ɛnan" to 4, "enum" to 5, "ahaanu" to 200, "apem" to 1000,
    // the HCI Lab recogniser's spelling of 3 (audit/lab-asr-2026-10-02: "mmeɛnsa" every time)
    "mmeɛnsa" to 3, "mmeensa" to 3,
    // …and of 4 on the rest of the GOLD clips (3 October 2026: "du nnan" = 14). Its 9, "nkuro", is also a word ("towns"):
    // read as 9 only beside another numeral, in normalizeTwi.
    "nnan" to 4
)

/** The same tables keyed by [com.tekyerema.language.TwiText.fold], so plain-Latin and tone-marked spellings
 *  ("mmiensa", "oha", "nwotwe") find their numeral. Exact spellings are always tried first. */
private val TWI_NUMBERS_FOLDED: Map<String, Int> =
    TWI_NUMBERS.entries.associate { com.tekyerema.language.TwiText.fold(it.key) to it.value }
private val TWI_CURRENCY_FOLDED: Map<String, String> =
    TWI_CURRENCY_VARIANTS.entries.associate { com.tekyerema.language.TwiText.fold(it.key) to it.value }

/**
 * The words a glued-together transcript is split back into ([Normalizer.unglue]): the Twi words of a phone request, the
 * numerals, the currency words, English number words. The HCI Lab recogniser runs code-mixed speech together — "Soma ten
 * sidis ma…" came back as "somatensidisma" (owner's test, 2 October 2026). [owner: verify the Twi with a native speaker]
 */
private val GLUE_WORDS: Set<String> = setOf(
    "soma", "ma", "kɔ", "kɔma", "frɛ", "tɔ", "bue", "me", "wo", "no", "na", "ne", "sika", "kredit", "data", "nkra",
    "mepɛ", "sɛ", "momo", "mu", "aka", "sɛn", "bɛn", "fa", "de", "kɔ", "nnɔn", "dɔn"
) +TWI_NUMBERS.keys + TWI_CURRENCY_VARIANTS.keys + setOf("cedi", "cedis", "pesewa", "pesewas") +
    EN_UNITS.keys + EN_TENS.keys + setOf("hundred", "thousand")

/** Entity-first normalization — spoken numbers → digits; names/words untouched (§13). */
object Normalizer {
    fun normalize(text: String?, lang: String): String {
        val s = (text ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (s.isEmpty()) return s
        if (lang == Lang.TWI) return normalizeTwi(s)
        return normalizeEnglish(s)
    }

    /**
     * A Twi request made plain for a translator: every model scored on 2 October 2026 turned "sidi" into cents, dimes or
     * pennies ("Soma sidi du ma Ama" → "Send a dime to Ama"; "Tɔ data sidi mmiɛnsa" → "three data centers"). The Twi
     * numerals become digits and the amount goes before its currency ("Soma 10 cedis ma Ama"), which translators keep.
     */
    fun forTranslation(goal: String): String =
        normalize(goal, Lang.TWI).replace(Regex("(?i)\\b(cedi|cedis|pesewa|pesewas)\\s+(\\d+(?:\\.\\d+)?)\\b")) { m ->
            val unit = m.groupValues[1].lowercase().removeSuffix("s")
            "${m.groupValues[2]} $unit${if (m.groupValues[2] == "1") "" else "s"}"
        }

    private val PUNCT = ".,;:?!“”\"'"
    private val TWI_CURRENCY_TOKENS = setOf("cedi", "cedis", "pesewa", "pesewas")
    private fun twiNumber(core: String): Int? =
        TWI_NUMBERS[core] ?: TWI_NUMBERS_FOLDED[com.tekyerema.language.TwiText.fold(core)]
    private fun twiCurrency(core: String): String? =
        TWI_CURRENCY_VARIANTS[core] ?: TWI_CURRENCY_FOLDED[com.tekyerema.language.TwiText.fold(core)]
    private fun isTwiNumeric(c: String): Boolean =
        twiNumber(c) != null || twiCurrency(c) != null || c in TWI_CURRENCY_TOKENS || Regex("\\d+").matches(c) ||
            EN_UNITS.containsKey(c) || EN_TENS.containsKey(c)

    /** One Twi token: its text, the numeral it stands for (if any), and the punctuation around it. */
    private class TwiTok(val text: String, val value: Long?, val lead: String, val trail: String, val core: String)

    /**
     * A word the recogniser glued together, split back — ONLY when every piece is a known word ([GLUE_WORDS], each ≥ 2
     * letters) AND a piece is a number or currency word, so an ordinary Twi word is never cut up. Fewest pieces wins.
     * "somatensidisma" → "soma ten sidis ma"; "mesomaa" (no number in it) stays as it is. Pure.
     */
    fun unglue(word: String): String {
        val w = word.lowercase()
        if (w.length < 6 || w in GLUE_WORDS || !w.all { it.isLetter() }) return word
        // best[i] = fewest pieces covering w[0, i)
        val best = arrayOfNulls<List<String>>(w.length + 1).also { it[0] = emptyList() }
        for (i in 0 until w.length) {
            val prefix = best[i] ?: continue
            for (j in i + 2..minOf(w.length, i + 12)) {
                val piece = w.substring(i, j)
                if (piece in GLUE_WORDS && (best[j] == null || best[j]!!.size > prefix.size + 1)) best[j] = prefix + piece
            }
        }
        val pieces = best[w.length] ?: return word
        val numeric = pieces.any { it in TWI_NUMBERS || it in TWI_CURRENCY_VARIANTS || it in EN_UNITS || it in EN_TENS ||
            it in setOf("cedi", "cedis", "pesewa", "pesewas") }
        return if (pieces.size >= 2 && numeric) pieces.joinToString(" ") else word
    }

    /**
     * What the UG HCI Lab recogniser writes for words it can't spell, measured on the owner's own GOLD recordings
     * (audit/lab-asr-2026-10-02, 34 sentences): split money words joined back ("si di" → sidi, "pɛ soa" → pɛsoa), and the
     * loanwords it respells ("ɛtam" = airtime — the owner's first Twi test, "me ɛtam no akasa", was "me airtime no aka
     * sɛn"; "mesege" = message). Plain respellings only; the numerals that depend on context are in [normalizeTwi].
     */
    /** A cedi as the recognisers write it, for the joins below that only apply right after an amount's currency. */
    private const val CURRENCY_HEARD = "sidis|sedis|sidi|sedi|scedi|sɛdi|cedis|cedi"
    private val LAB_JOINS = listOf(
        // Khaya's recogniser writes "sidi" the English way and glues the amount to it: "Soma 5seed is ma 0552093690."
        // for "Soma sidi anum ma 0552093690" (the owner's A20e, 16 Oct 2026) — the money check then found no amount.
        Regex("(\\d)((?:seed|sidi|sedi|cedi|pesewa|pɛsoa|pesoa)\\p{L}*)") to { m: MatchResult -> "${m.groupValues[1]} ${m.groupValues[2]}" },
        Regex("(?<![\\p{L}])seed(?:\\s?is|s|i)(?![\\p{L}])") to { _: MatchResult -> "sidis" },
        // (…"se di" too: the on-device Twi model on the A20e, 3 October 2026 — "to mase di anoma ama")
        Regex("(?<![\\p{L}])(ma|so|to)?s[ie]\\s+di(?![\\p{L}])") to { m: MatchResult -> (m.groupValues[1].let { if (it.isEmpty()) "" else "$it " }) + "sidi" },
        Regex("(?<![\\p{L}])pɛ\\s+soa(?![\\p{L}])") to { _: MatchResult -> "pɛsoa" },
        // "soma" (send) heard as "so ma" / "to ma" — only right before money ("so masi di anua ama" = "soma sidi anum ma Ama")
        // (…and before pesewas: "so ma pɛ soa du ama prince" = "soma pesewa du ma Prince" — the demo script, 4 October 2026)
        Regex("(?<![\\p{L}])(?:so|to)\\s+ma(?=\\s+(?:sidi|sedi|cedi|cedis|sika|pesewas?|pɛsoa|pesoa|pasea|pɛsewa|\\d))") to { _: MatchResult -> "soma" },
        // "how much is LEFT" — "aka sɛn" heard as one word, only after airtime / credit / money (alone, "akasa" = "has spoken")
        Regex("(?<![\\p{L}])(airtime|ɛtam|etam|kredit|krɛdit|sika)(\\s+no)?\\s+aka\\s?(?:sa|sɛn|sen)(?![\\p{L}])") to
            { m: MatchResult -> "${m.groupValues[1]}${m.groupValues[2]} aka sɛn" },
        // The rest of the GOLD clips through the HCI Lab (3 October 2026): pesewa in three pieces before its amount
        Regex("(?<![\\p{L}])pɛ\\s+so\\s+a(?=\\s+(?:adu|du|ɔha|\\d))") to { _: MatchResult -> "pɛsoa" },
        // …and the on-device omni CTC model's (campaign/kaggle/twi-asr-diag): the "ma" of "soma" glued to the cedi
        // ("so masedi", "to masedi" = "soma sedi"), and the numbers after a cedi it splits or glues to the next "ma":
        // "anoma ama" = "anum ma Ama", "me no" / "mi noma" = "mmienu (ma)", "aduo no(m)" = 20 (50), "dome no" = 12.
        Regex("(?<![\\p{L}])(?:so|to)?\\s?ma(s[ei]di|sidi)(?![\\p{L}])") to { m: MatchResult -> "soma ${m.groupValues[1]}" },
        Regex("(?<![\\p{L}])($CURRENCY_HEARD)\\s+anoma(?=\\s+\\p{L})") to { m: MatchResult -> "${m.groupValues[1]} anum ma" },
        Regex("(?<![\\p{L}])($CURRENCY_HEARD)\\s+m[ei]\\s+no(ma)?(?![\\p{L}])") to
            { m: MatchResult -> "${m.groupValues[1]} mmienu${if (m.groupValues[2].isNotEmpty()) " ma" else ""}" },
        Regex("(?<![\\p{L}])($CURRENCY_HEARD)\\s+(?:aduo|ado|adu)\\s?no(m?)(?![\\p{L}])") to
            { m: MatchResult -> "${m.groupValues[1]} aduonu${if (m.groupValues[2].isNotEmpty()) "m" else ""}" },
        Regex("(?<![\\p{L}])($CURRENCY_HEARD)\\s+adnom(?![\\p{L}])") to { m: MatchResult -> "${m.groupValues[1]} aduonum" },
        Regex("(?<![\\p{L}])($CURRENCY_HEARD)\\s+do\\s?m[ei]\\s?no(?![\\p{L}])") to { m: MatchResult -> "${m.groupValues[1]} dumienu" },
        // "sika a aka" (the money that's left) and "mewɔ sika sɛn" (how much do I have), heard run together / apart
        Regex("(?<![\\p{L}])sikaa\\s+aka(?![\\p{L}])") to { _: MatchResult -> "sika a aka" },
        Regex("(?<![\\p{L}])me\\s+wo\\s+sika(?=\\s+sɛn)") to { _: MatchResult -> "mewɔ sika" },
        Regex("(?<![\\p{L}])kɔ\\s+momu(?![\\p{L}])") to { _: MatchResult -> "kɔ momo" }
    )
    private val LAB_WORDS = mapOf("ɛtam" to "airtime", "etam" to "airtime", "ɛtaem" to "airtime", "etaem" to "airtime",
        "mesege" to "message", "mɛsege" to "message", "mesegye" to "message", "mɛsegye" to "message",
        // 3 October 2026 — the HCI Lab and the on-device omni CTC model on the rest of the GOLD clips
        "volum" to "volume", "artim" to "airtime", "ertim" to "airtime", "airtim" to "airtime",
        "mesage" to "message", "mɛsage" to "message", "twam" to "twa mu", "bɔnwom" to "bɔ nnwom", "nwom" to "nnwom")
    /** "zero" as the recogniser hears it — never right on the GOLD clips (sro, sio, sere) — read as 0 only beside a numeral. */
    private val ZERO_HEARD = setOf("zero", "ziro", "sro", "sio", "sero", "siro", "zro", "zio", "sere", "zeero")

    private fun normalizeTwi(input: String): String {
        // X-06 — standard spelling first (look-alike ɛ/ɔ, tone marks), so every lookup below sees one form. The raw
        // transcript is kept separately by the pipeline; only the canonical goal is respelled.
        // Then the recogniser's own spellings are mended, and a glued run ("somatensidisma") is split back into its
        // words, before any numeral is read.
        var c = com.tekyerema.language.TwiText.canonical(input)
        for ((re, fix) in LAB_JOINS) c = re.replace(c) { fix(it) }
        // "tempɛsos" — an English amount said inside Twi and glued by the lab recogniser ("ten pesos", 5 October 2026)
        c = com.tekyerema.voice.understand.SpokenNumbers.unglueAmounts(c)
        val s = c.split(" ").joinToString(" ") { tok ->
            val core = tok.trim(*PUNCT.toCharArray())
            val mended = LAB_WORDS[core.lowercase()] ?: unglue(core)
            if (core.length == tok.length) mended else tok.replace(core, mended)
        }
        val words = s.split(" ")
        val cores = words.map { it.trim(*PUNCT.toCharArray()).lowercase() }
        val toks = words.mapIndexed { i, w ->
            val core = cores[i]
            val lead = w.takeWhile { it in PUNCT }
            val trail = w.takeLastWhile { it in PUNCT }
            // §5.3 — bare "du" is "ten" ONLY in a numeric context (a neighbouring numeral/currency word);
            // otherwise it's the ordinary Twi word and must be left alone. Compounds (dubaako=11 …) are
            // separate keys and unaffected.
            if (core == "du") {
                val numericNeighbour = (i > 0 && isTwiNumeric(cores[i - 1])) ||
                    (i < cores.size - 1 && (isTwiNumeric(cores[i + 1]) || cores[i + 1] == "nnum" || cores[i + 1] == "anua"))
                return@mapIndexed TwiTok(w, if (numericNeighbour) 10L else null, lead, trail, core)
            }
            // The recogniser's "zero" (sro / sio / sere…) — a digit only beside another numeral (a phone number, "zero
            // mmienu anan"); "sere" alone is an ordinary word.
            if (core in ZERO_HEARD && core != "zero" && core != "ziro") {
                val numericNeighbour = (i > 0 && isTwiNumeric(cores[i - 1])) || (i < cores.size - 1 && isTwiNumeric(cores[i + 1]))
                return@mapIndexed TwiTok(w, if (numericNeighbour) 0L else null, lead, trail, core)
            }
            // "nkron" (9) as the HCI Lab wrote it, "nkuro" — also the word for towns, so 9 only beside another numeral
            if (core == "nkuro" || core == "nkoro") {
                val numericNeighbour = (i > 0 && isTwiNumeric(cores[i - 1])) || (i < cores.size - 1 && isTwiNumeric(cores[i + 1]))
                return@mapIndexed TwiTok(w, if (numericNeighbour) 9L else null, lead, trail, core)
            }
            // "anum" (5) written "anua" — 3 of 4 times on the GOLD clips — read as 5 only right after a currency word.
            // (…and "nnum", the owner's own test on the phone, 2 October 2026: "soma sidi nnum ma prince".)
            // (…and after "du": "du nnum" = 15, the HCI Lab on GOLD N03, 3 October 2026.)
            if ((core == "anua" || core == "nnum") && i > 0 &&
                (twiCurrency(cores[i - 1]) != null || cores[i - 1] in TWI_CURRENCY_TOKENS || cores[i - 1] == "du"))
                return@mapIndexed TwiTok(w, 5L, lead, trail, core)
            // number table first (mirrors phase2/asr.py _twi_tok order), then colloquial currency words.
            twiNumber(core)?.let { return@mapIndexed TwiTok(w, it.toLong(), lead, trail, core) }
            twiCurrency(core)?.let { return@mapIndexed TwiTok("$lead$it$trail", null, lead, trail, core) }
            TwiTok(w, null, lead, trail, core)
        }
        val twi = composeTwi(toks).joinToString(" ") { t -> t.value?.let { "${t.lead}$it${t.trail}" } ?: t.text }
        // Code-switching: Twi speakers very often say amounts in English ("Soma five cedis kɔ Kofi"). English number
        // words are not Twi words, so the English span parser can run over the result without touching the Twi.
        return normalizeEnglish(twi)
    }

    /**
     * Compound Twi numerals, following the Harvard counting lesson ("aduonu baako" 21, "aduonu enum" 25, "ɔha ne
     * baako" 101): a tens word followed by a unit adds; a hundreds/thousand word, "ne" ("and"), then a smaller number
     * adds. Only across words with no punctuation between them, so a list read out ("aduonu, anum") and a spoken
     * digit string ("baako mmienu mmiɛnsa" → 1 2 3) stay separate. [owner: verify with a native speaker]
     */
    private fun composeTwi(toks: List<TwiTok>): List<TwiTok> {
        fun joinable(a: TwiTok, b: TwiTok) = a.trail.isEmpty() && b.lead.isEmpty()
        val tens = ArrayList<TwiTok>(toks.size)
        var i = 0
        while (i < toks.size) {
            val a = toks[i]; val b = toks.getOrNull(i + 1)
            val av = a.value; val bv = b?.value
            // …and the teens the HCI Lab recogniser writes in two words: "dumienu" (12) came back as "du mmienu"
            // (audit/lab-asr-2026-10-02, "ɛno yɛ cedi dumienu" → "…sidi du mmienu" = "10 2"). Only the bare word "du".
            val teen = a.core == "du" && av == 10L
            if (b != null && av != null && bv != null && ((av in 20L..90L && av % 10L == 0L) || teen) && bv in 1L..9L && joinable(a, b)) {
                tens.add(TwiTok(a.text + " " + b.text, av + bv, a.lead, b.trail, a.core)); i += 2
            } else { tens.add(a); i++ }
        }
        // Right to left, so "apem ne ɔha ne aduonu" folds the inner "ɔha ne aduonu" (120) before the outer (1120).
        val out = ArrayList(tens)
        var j = out.size - 3
        while (j >= 0) {
            if (j + 2 < out.size) {
                val a = out[j]; val ne = out[j + 1]; val b = out[j + 2]
                val av = a.value; val bv = b.value
                val fits = av != null && bv != null && ne.value == null && ne.core == "ne" &&
                    ((av in 100L..900L && av % 100L == 0L && bv in 1L..99L) || (av == 1000L && bv in 1L..999L))
                if (fits && joinable(a, ne) && joinable(ne, b)) {
                    out[j] = TwiTok(a.text + " " + ne.text + " " + b.text, av!! + bv!!, a.lead, b.trail, a.core)
                    out.removeAt(j + 1); out.removeAt(j + 1)
                }
            }
            j--
        }
        return out
    }

    private fun expand(tok: String): List<String> {
        if ("-" in tok) {
            val parts = tok.split("-").filter { it.isNotEmpty() }
            val known = { p: String -> val l = p.lowercase(); EN_UNITS.containsKey(l) || EN_TENS.containsKey(l) || EN_SCALES.containsKey(l) }
            if (parts.isNotEmpty() && parts.all(known)) return parts
        }
        return listOf(tok)
    }

    private fun normalizeEnglish(input: String): String {
        // "double five", "oh five five", "a cedi" — said the Ghanaian way, put in the shape the parser below reads
        // (voice/understand/SpokenNumbers); "2 point 5" → "2.5" after it.
        // A MESSAGE keeps its own words (safety audit 2026-10-05): the body after "saying" / "that" / ":" is passed on as
        // heard, and the rest gets the digit-run rules only (a recipient's number said aloud) — no money rules.
        if (com.tekyerema.voice.understand.MessageBody.isMessaging(input)) {
            val (head, body) = com.tekyerema.voice.understand.MessageBody.split(input)
            return parseEnglish(com.tekyerema.voice.understand.SpokenNumbers.digitRuns(head)).trimEnd() +
                (if (body.isNotEmpty() && head.endsWith(" ")) " " else "") + body
        }
        val s = com.tekyerema.voice.understand.SpokenNumbers.beforeParse(input)
        return com.tekyerema.voice.understand.SpokenNumbers.afterParse(parseEnglish(s))
    }

    private fun parseEnglish(s: String): String {
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
        "firi","hɔ","seesei","ara","yɛsrɛ","toa","nua","barima","yere","ɛno","ɛyɛ","meretie","wʋm",
        // B-05 — everyday request words that code-switched speech keeps in Twi ("Mepɛ sɛ me call Kofi"): want, show,
        // thanks, please, where, or, so, today, tomorrow, speak, stop, wait, done. Recognition cues only, never
        // spoken. [owner: verify with a native speaker]
        "mepɛ","pɛ","kyerɛ","medaase","mesrɛ","ɛhe","anaa","nti","ɛnnɛ","ɔkyena","kasa","gyae","twɛn","awie"
    )
    /** Plain-Latin spellings of the Twi cues ("mepe", "fre") that are not also English words — ASR output and
     *  typed Twi often drop ɛ/ɔ. "ye", "den", "eye", "ho", "ko" fold from Twi words but are English (or too
     *  close), so they only count when written with the Twi letter. */
    private val TWI_FOLDED: Set<String> by lazy {
        TWI_WORDS.map { com.tekyerema.language.TwiText.fold(it) }.toSet() -
            setOf("ye", "den", "eye", "ho", "ko", "pe") - ENG_WORDS
    }
    private fun isTwiWord(t: String): Boolean =
        t in TWI_WORDS || (t !in ENG_WORDS && com.tekyerema.language.TwiText.fold(t) in TWI_FOLDED)
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
        // Unicode letters + combining marks, so Twi tokens with diacritics (e.g. "sɛ", "frɛ") stay whole. NOT "(?U)":
        // Android's ICU regex rejects that flag ("Syntax error… near index 3") — it broke every spoken request on the
        // A20e (2026-09-28) while the JVM unit tests, on Java's engine, passed.
        // Standard spelling first: a Greek ε or a tone-marked "frɛ́" is the same Twi evidence as "frɛ".
        val canon = com.tekyerema.language.TwiText.canonical(text ?: "")
        val toks = Regex("[\\p{L}\\p{M}]+").findAll(canon.lowercase()).map { it.value }.toList()
        if (toks.isEmpty()) return Detection(Lang.GH_EN, 0.0, 0.0, 0.0)
        val twi = toks.count { isTwiWord(it) }
        val eng = toks.count { it in ENG_WORDS }
        val diac = canon.count { it in TWI_DIACRITICS }
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
        val numbers = Normalizer.normalize(raw, lang)
        // The robustness layer around the recognisers (voice/understand): in a request about money or a network, the
        // network's name ("em ti en", "MTM" → MTN) and the money words ("mumu" → MoMo, "reverence" → reference) by
        // sound — never a word that names a contact, never the words of a message.
        val vocab = vocabulary()
        val messaging = com.tekyerema.voice.understand.MessageBody.isMessaging(numbers)
        val normalized = if (messaging) { HeardAs.noteWords(numbers, numbers); numbers } else try {
            val frame = com.tekyerema.voice.understand.Frame.of(numbers)
            val protect = vocab.entries.filter { it.kind == EntityCorrector.Kind.CONTACT }.map { it.compact }.toHashSet()
            val telco = com.tekyerema.voice.understand.TelcoTerms.repair(numbers, frame, protect)
            val fin = com.tekyerema.voice.understand.FinanceVocab.repair(telco, protect, frame || com.tekyerema.voice.understand.Frame.of(telco))
            HeardAs.noteWords(numbers, fin.text, fin.fixes)
            fin.text
        } catch (t: Throwable) { numbers }
        // B-19: names in a name slot are checked against this phone's contacts and apps ("message coffee" → "message
        // Kofi"). Only the canonical goal changes; the raw transcript stays as heard.
        // …and never inside a message's own words: "saying call coffee now" stays as said (safety audit 2026-10-05).
        val (head, body) = if (messaging) com.tekyerema.voice.understand.MessageBody.split(normalized) else normalized to ""
        val fixed = try { EntityCorrector.correct(head, vocab).let { r -> if (body.isEmpty()) r else
                EntityCorrector.Result(r.text.trimEnd() + (if (head.endsWith(" ")) " " else "") + body, r.fixes) } }
            catch (t: Throwable) { EntityCorrector.Result(normalized, emptyList()) }
        val canonical = fixed.text
        if (fixed.fixes.isNotEmpty()) try { com.tekyerema.core.telemetry.Telemetry.update { voice["nameFixes"] = fixed.fixes.size } } catch (_: Throwable) {}
        // Spoken doubt (2026-09-29): a corrected name that turns out to be a call's / message's / payment's recipient is said
        // once in the read-back ("I heard 'prints' — taking it as Prince.") — voice/HeardAs.
        HeardAs.note(canonical, fixed.fixes)
        val entities = try { Entities.derive(canonical, lang) } catch (t: Throwable) { emptyList() }
        return Transcript(raw = raw, canonical = canonical, language = lang, reason = reason, entities = entities, backend = backend,
            asrMs = asrMs, corrections = fixed.fixes.map { "${it.kind.name.lowercase()}:${it.from}→${it.to}" })
    }

    /** The names to correct against — [SpeechVocabulary]'s in-memory snapshot (empty in unit tests unless set). */
    @Volatile var vocabulary: () -> EntityCorrector.Vocab = { SpeechVocabulary.current() }

    /** The request part of the layer on its own (numbers, network names, money words, contact names) — for text that did
     *  not come through [process]: a debug hook's "heard" text, tests. Same steps, same order. */
    fun understand(raw: String, lang: String = Lang.GH_EN): String = process(raw, lang, lang).canonical
}
