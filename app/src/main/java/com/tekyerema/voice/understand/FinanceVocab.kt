package com.tekyerema.voice.understand

import com.tekyerema.voice.PhoneticKey

/**
 * The money words of a request, put right by SOUND when a recogniser misspells them — only inside a money/network
 * request ([Frame]), only in the command part (never the words of a message being sent), and never a word that names
 * one of the user's contacts. One small vocabulary and two reusable tests, instead of a list of mishearings:
 *
 *  - same sound key ([PhoneticKey]: "mumu"/"moomoo" = MoMo, "pen" = PIN, "balanse" = balance), or
 *  - spelling ≥ 0.8 alike with the same first letter ("reverence" = reference, "bundel" = bundle);
 *  - two words heard for one ("air time", "mo mo", "mash up", "cash out", "mobile many") are joined first.
 *
 * Pure → SpeechRobustnessTest. [protect] = words that must stay as they are (contact names on this phone).
 */
object FinanceVocab {
    private class Term(val canonical: String) {
        val compact = PhoneticKey.compact(canonical)
        val key = PhoneticKey.of(canonical)
    }

    private val TERMS = listOf("MoMo", "Mobile Money", "airtime", "data", "bundle", "mashup", "balance", "reference", "PIN",
        "recipient", "amount", "transfer", "withdraw", "cash out", "wallet", "cedis", "pesewas", "Telecel", "AirtelTigo",
        "top up", "recharge").map(::Term)

    /** Everyday words that sound like a money word but are words in their own right — never rewritten. */
    private val PROTECT = setOf("date", "dates", "pan", "pain", "bin", "been", "pine", "ballad", "valance", "wallets",
        "preference", "difference", "inference", "deference", "mama", "mummy", "mum", "mom", "moms", "air", "time", "mobile",
        "money", "many", "cash", "out", "top", "up", "mash", "dater", "dated", "datum", "send", "sent", "the", "and", "with",
        "for", "to", "of", "on", "in", "a", "an", "me", "my", "his", "her", "please", "now", "who", "what", "how", "is",
        "pinned", "pins", "pinch", "spin", "tin", "sim", "number", "account", "momo", "data", "pin", "airtime", "balance",
        "reference", "amount", "wallet", "bundle", "mashup", "cedis", "pesewas", "cedi", "pesewa", "transfer", "withdraw")

    /** Where the command ends and the words of a message begin ("… saying I'm coming", "… that the data is ready"). */
    private val BODY = Regex("(?i)\\b(?:saying|that says|which says|telling (?:him|her|them)|tell (?:him|her|them)|with the message|" +
        "the message is|message:|text:|that)\\b|[:\"“]")

    private const val PUNCT = ".,;:!?\"'“”"

    data class Result(val text: String, val fixes: List<Pair<String, String>>)

    fun repair(text: String, protect: Set<String> = emptySet(), frame: Boolean = Frame.of(text)): Result {
        if (text.isBlank() || !frame) return Result(text, emptyList())
        val bodyAt = BODY.find(text)?.range?.first ?: text.length
        val head = text.substring(0, bodyAt)
        val tail = text.substring(bodyAt)
        val toks = head.split(" ").filter { it.isNotEmpty() }.toMutableList()
        val fixes = ArrayList<Pair<String, String>>()
        var i = 0
        val out = ArrayList<String>(toks.size)
        while (i < toks.size) {
            val c1 = core(toks[i])
            // two words for one term ("air time", "mo mo", "mobile many")
            val c2 = toks.getOrNull(i + 1)?.let(::core)
            if (c2 != null && c1.isNotEmpty() && c2.isNotEmpty() && c1.none { it.isDigit() } && c2.none { it.isDigit() }) {
                val joined = PhoneticKey.compact(c1 + c2)
                val term = bestTerm(joined, twoWords = true) ?: wordByWord(c1, c2)
                if (term != null && (c1 + " " + c2) !in protect && !(term.compact == joined && toks[i] + " " + toks[i + 1] == term.canonical)) {
                    val said = toks[i] + " " + toks[i + 1]
                    if (!said.equals(term.canonical, ignoreCase = true)) fixes.add(said to term.canonical)
                    out.add(term.canonical + toks[i + 1].takeLastWhile { it in PUNCT }); i += 2; continue
                }
            }
            if (c1.length >= 3 && c1 !in PROTECT && c1 !in protect && c1.none { it.isDigit() }) {
                val term = bestTerm(PhoneticKey.compact(c1), twoWords = false)
                if (term != null && !c1.equals(term.compact, ignoreCase = true)) {
                    fixes.add(toks[i] to term.canonical)
                    out.add(toks[i].takeWhile { it in PUNCT } + term.canonical + toks[i].takeLastWhile { it in PUNCT }); i++; continue
                }
            }
            out.add(toks[i]); i++
        }
        if (fixes.isEmpty()) return Result(text, emptyList())
        val sep = if (tail.isEmpty() || tail.startsWith(" ") || head.endsWith(" ")) "" else " "
        return Result((out.joinToString(" ") + (if (head.endsWith(" ")) " " else "") + sep + tail).replace(Regex(" {2,}"), " "), fixes)
    }

    private fun core(t: String) = t.lowercase().trim(*PUNCT.toCharArray())

    /** A two-word term said with one word right and the other close ("mobile many" → Mobile Money, "tap up" → top up):
     *  one word exact, the other alike by sound or spelling (≥ 0.6, same first letter). Only one term may fit. */
    private fun wordByWord(a: String, b: String): Term? {
        fun near(x: String, y: String) = x == y || (x.isNotEmpty() && y.isNotEmpty() && x[0] == y[0] &&
            (PhoneticKey.of(x) == PhoneticKey.of(y) || PhoneticKey.similarity(x, y) >= 0.6))
        val fits = TERMS.filter { t ->
            val w = t.canonical.lowercase().split(" ")
            w.size == 2 && ((a == w[0] && near(b, w[1])) || (b == w[1] && near(a, w[0]))) && !(a == w[0] && b == w[1])
        }
        return fits.singleOrNull()
    }

    /** The term [compact] is, by sound or spelling — the closest, and only when no other term is as close. */
    private fun bestTerm(compact: String, twoWords: Boolean): Term? {
        if (compact.length < 3) return null
        val key = PhoneticKey.of(compact)
        val scored = TERMS.mapNotNull { t ->
            // a two-word join only becomes a ONE-word term or a two-word one it spells closely
            val sim = PhoneticKey.similarity(compact, t.compact)
            val sameStart = compact[0] == t.compact[0]
            val score = when {
                compact == t.compact -> 2.0
                key == t.key && key.length >= 3 && sim >= 0.5 -> 1.0 + sim / 10
                sim >= 0.8 && sameStart -> sim
                else -> null
            } ?: return@mapNotNull null
            t to score
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (scored.size > 1 && best.second - scored[1].second < 0.05) return null
        if (twoWords && best.second < 0.85) return null
        return best.first
    }
}
