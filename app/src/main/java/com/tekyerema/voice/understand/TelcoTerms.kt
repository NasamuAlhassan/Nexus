package com.tekyerema.voice.understand

/**
 * Network names and spelled-out letters, as recognisers write them, put right — only where a phone network or money is
 * being talked about (the [Frame]). Rules, not a list of every mishearing:
 *
 *  - SPELLED LETTERS: a run of letter names or single letters ("em ti en", "m t n", "M.T.N.", "gee aitch ess") becomes the
 *    acronym, when it is one the app uses (MTN, GHS, GHC, PIN, SMS, USSD, ATM, AT);
 *  - ONE-WORD FORMS of a network: a word one edit away from "mtn" that starts like it ("mtm", "mtns", "emtn", "m10" read
 *    as letters), "empty n" / "empty en" (how "M-T-N" is often written), "airtel tigo" / "air tel tigo" → AirtelTigo;
 *  - a network name in possessive or plural ("MTN's", "mtns") keeps its 's.
 *
 * The words as heard stay in the transcript's raw text; only the request acted on changes. Pure → SpeechRobustnessTest.
 */
object TelcoTerms {
    private val LETTER_NAMES = mapOf(
        "em" to 'm', "emm" to 'm', "m" to 'm', "tee" to 't', "ti" to 't', "t" to 't', "en" to 'n', "enn" to 'n', "n" to 'n',
        "gee" to 'g', "g" to 'g', "ji" to 'g', "aitch" to 'h', "eych" to 'h', "h" to 'h', "ess" to 's', "es" to 's', "s" to 's',
        "see" to 'c', "c" to 'c', "si" to 'c', "pee" to 'p', "p" to 'p', "eye" to 'i', "i" to 'i', "ay" to 'a', "a" to 'a',
        "you" to 'u', "u" to 'u', "dee" to 'd', "d" to 'd')
    /** The acronyms a spelled run may become — never any other letters (a sentence of "a"s and "I"s stays a sentence). */
    private val ACRONYMS = mapOf("mtn" to "MTN", "ghs" to "GHS", "ghc" to "GHC", "pin" to "PIN", "sms" to "SMS", "ussd" to "USSD",
        "atm" to "ATM", "at" to "AT")
    /** Acronyms whose letters are ALSO everyday words ("a t", "i", "pee") — accepted only in the money/network frame. */
    private val ONLY_IN_FRAME = setOf("at", "pin", "atm")

    private const val PUNCT = ".,;:!?\"'"
    private fun core(t: String) = t.lowercase().trim(*PUNCT.toCharArray()).replace(".", "")

    /** [text] with spelled acronyms and network names put right; [frame] = money or a network is being talked about. */
    fun repair(text: String, frame: Boolean = Frame.of(text), protect: Set<String> = emptySet()): String {
        // never inside a message: "saying em ti en office is closed" is the user's own words (MessageBody)
        if (text.isBlank() || MessageBody.isMessaging(text)) return text
        var s = spelledRuns(text, frame)
        if (!frame) return s
        s = EMPTY_N.replace(s) { m -> "MTN" + m.groupValues[1] }
        // "GH 20" / "G H 5" — how an amount in cedis is said here — is the currency before its figure
        s = GH_AMOUNT.replace(s) { "GHS " }
        s = AIRTELTIGO.replace(s, "AirtelTigo")
        // a word that is part of a saved contact's name ("MTM Ventures") is the user's contact, not a network
        s = s.split(" ").joinToString(" ") { tok ->
            if (protect.isNotEmpty() && com.tekyerema.voice.PhoneticKey.compact(tok) in protect) tok else mtnWord(tok) ?: tok }
        return s
    }

    /** "em ti en" → "MTN": a run of 2–4 letter names that spells a known acronym. */
    private fun spelledRuns(text: String, frame: Boolean): String {
        val toks = text.split(" ").filter { it.isNotEmpty() }
        val out = ArrayList<String>(toks.size)
        var i = 0
        while (i < toks.size) {
            var best: Pair<Int, String>? = null
            for (n in minOf(4, toks.size - i) downTo 2) {
                val run = toks.subList(i, i + n)
                // a letter name only — and no sentence punctuation inside the run ("a. I" is two sentences)
                if (run.dropLast(1).any { it.last() in ",;:!?" }) continue
                val sb = StringBuilder()
                for (t in run) { val l = LETTER_NAMES[core(t)] ?: break; sb.append(l) }
                val letters = sb.toString()
                if (letters.length != n) continue
                val acr = ACRONYMS[letters] ?: continue
                if (letters in ONLY_IN_FRAME && !frame) continue
                // single plain letters "a"/"i"/"u" are words — a run with one of them needs the money/network frame
                if (run.any { core(it) in setOf("a", "i", "u") } && !frame) continue
                best = n to acr; break
            }
            if (best != null) {
                val (n, acr) = best
                out.add(acr + toks[i + n - 1].takeLastWhile { it in ",;:!?" }); i += n
            } else { out.add(toks[i]); i++ }
        }
        return out.joinToString(" ")
    }

    /** One word that is "mtn" give or take one letter, starting like it: "mtm", "mtns", "emtn", "mt" + "n" glued. */
    private fun mtnWord(tok: String): String? {
        val lead = tok.takeWhile { !it.isLetterOrDigit() }
        val trail = tok.takeLastWhile { !it.isLetterOrDigit() }
        val body = tok.substring(lead.length, tok.length - trail.length)
        if (body.isEmpty()) return null
        val poss = Regex("(?i)['’]s$").find(body)?.value.orEmpty()
        // "M.T.N" → "mtn"; "M10" is "M-T-N" read as a figure
        val w = body.removeSuffix(poss).lowercase().replace(".", "").replace("10", "tn")
        if (w == "mtn") return if (body.removeSuffix(poss) == "MTN") null else lead + "MTN" + poss + trail
        if (w.length !in 2..5 || !(w.startsWith("m") || w.startsWith("em"))) return null
        if (w in NOT_MTN) return null
        val d = levenshtein(w, "mtn")
        val plural = w == "mtns"
        // one letter added ("mttn", "mtnn") or the last one heard as the other nasal ("mtm") — never another consonant:
        // "MTV", "mts", "mtb" are other things (safety audit 2026-10-05: "play MTV on my data" became MTN)
        val near = d == 1 && (w.length == 4 || (w.length == 3 && w.startsWith("mt") && w[2] == 'm'))
        return if (near || plural || w == "emtn" || w == "emtien") lead + "MTN" + (if (plural) "'s" else poss) + trail else null
    }

    /** Real words one letter from "mtn" ("men", "man", "mat", "met", "min", "mt" as in Mt. Afadjato…). */
    private val NOT_MTN = setOf("men", "man", "mat", "met", "min", "mon", "mtg", "mt", "mn", "mean", "mint", "meant", "mutton", "mention")

    private val EMPTY_N = Regex("(?i)\\bempty\\s+(?:n|en|end|and)\\b('s)?")
    private val GH_AMOUNT = Regex("(?i)\\bg\\.?\\s?h\\.?\\s+(?=\\d)")
    private val AIRTELTIGO = Regex("(?i)\\b(?:air\\s?tel+\\s*[- ]?\\s*tigo|airtell?\\s?tigo|airtel-tigo|air tell tigo)\\b")

    internal fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

/**
 * Is money or a phone network being talked about? Judged on anchor words and figures — the gate every contextual repair
 * here waits for, so an ordinary sentence is never "corrected" into finance words. Pure.
 */
object Frame {
    private val ANCHORS = Regex("(?i)\\b(?:send|sent|pay|paid|buy|bought|transfer|withdraw|cash ?out|deposit|top ?up|recharge|" +
        "momo|mobile money|wallet|airtime|credit|data|bundles?|mash ?up|balance|cedis?|pesewas?|ghs|ghc|gh₵|₵|mtn|telecel|" +
        "airteltigo|vodafone|network|sim|amount|reference|recipient|pin|ussd|loan|fee|charges?|account|number)\\b")
    private val FIGURE = Regex("\\d")

    fun of(text: String): Boolean = ANCHORS.containsMatchIn(text) || (FIGURE.containsMatchIn(text) && MONEYISH.containsMatchIn(text))
    private val MONEYISH = Regex("(?i)\\b(?:give|to|for)\\b")
}
