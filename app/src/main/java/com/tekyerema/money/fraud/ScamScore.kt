package com.tekyerema.money.fraud

import org.json.JSONObject

/**
 * Scam-message warning (MASTER_STATUS #13, pipeline item 1; 2 Nov 2026): is this text message likely a scam, and why?
 * Shown as a WARNING, never an action — it never blocks, deletes, replies or touches money.
 *
 * Two parts, because neither is enough alone:
 *  1. [Model] — a logistic regression over hashed word / word-pair / letter n-grams, trained on public SMS phishing data
 *     (campaign/data/fraud/train_baseline.py; feature spec v1 in fraud_features.py, mirrored here exactly and checked
 *     by ScamScoreTest's parity file). AUC 0.98 on public validation data — but none of it is Ghanaian, and it misses
 *     the commonest Ghanaian MoMo scam ("I sent money to you by mistake, send it back") entirely (REPORT.md smoke check).
 *  2. [Cue] — Ghana rules a person can say out loud: a request for the PIN or a code, a "sent by mistake, send it back",
 *     a link to "reverse" or "claim", a prize that needs a fee, an officer who wants money now. Each carries the sentence
 *     the warning speaks, so the user hears WHY ("MTN never asks for your PIN by text").
 * A cue always warns; the model warns on its own only above its threshold (≤ 1 % of genuine messages flagged on
 * validation). Warning lines are short and calm. Pure Kotlin, a few hundred KB of weights, well under a millisecond.
 */
object ScamScore {
    data class Verdict(val warn: Boolean, val score: Double, val cues: List<Cue>) {
        /** What to say: the first cue's reason, or a general line when only the model is sure. Null = no warning. */
        val line: String? get() = when {
            !warn -> null
            cues.isNotEmpty() -> "Careful: this may be a scam. ${cues.first().reason}"
            else -> "Careful: this message looks like a scam. Don't send money or codes until you've checked who it's from."
        }
    }

    enum class Cue(val reason: String) {
        ASKS_PIN("It asks for your PIN or a code. MTN and banks never ask for your PIN or a code by text."),
        WRONG_SEND("Someone says they sent you money by mistake. Check your balance with MTN before sending anything back."),
        // Fraud step 2 (links, LinkRisk): the reason names what is wrong with the link, never the link itself.
        LINK_LOOKALIKE("It has a link that pretends to be a company you trust, but it isn't their real website. Don't open it."),
        LINK_UNSAFE("It has a link that hides where it really goes. Don't open it."),
        LINK_HIDDEN("It has a shortened link asking you to act, so you can't see where it goes. Don't open it."),
        LINK_ACTION("It wants you to open a link to fix or claim something. Don't open it."),
        PRIZE_FEE("It says you won a prize but must pay a fee first. Real prizes don't ask for a fee."),
        AUTHORITY_PAY("Someone says they're police or an official and wants money now. Call the person or the office yourself.")
    }

    /** A trained model file (assets/fraud/sms_baseline.json). */
    class Model(val buckets: Int, val bias: Double, val threshold: Double, private val weights: Map<Int, Double>) {
        fun score(text: String): Double {
            var z = bias
            for (b in bucketsOf(text, buckets)) z += weights[b] ?: 0.0
            return 1.0 / (1.0 + Math.exp(-z))
        }

        companion object {
            fun parse(json: String): Model {
                val o = JSONObject(json)
                require(o.optInt("featureVersion") == FEATURE_VERSION) { "scam model feature version ${o.optInt("featureVersion")}" }
                val w = o.getJSONObject("weights")
                val map = HashMap<Int, Double>(w.length() * 2)
                for (k in w.keys()) map[k.toInt()] = w.getDouble(k)
                return Model(o.getInt("buckets"), o.getDouble("bias"), o.getDouble("threshold"), map)
            }
        }
    }

    @Volatile private var loaded: Model? = null

    /** The model if it was loaded ([model]); null = the cues alone. */
    fun cached(): Model? = loaded

    /** Load once from the app's assets; null (cues only) when the file is missing or unreadable. */
    fun model(context: android.content.Context): Model? = loaded ?: try {
        context.assets.open("fraud/sms_baseline.json").bufferedReader().use { Model.parse(it.readText()) }.also { loaded = it }
    } catch (_: Throwable) { null }

    fun check(text: String, model: Model?): Verdict {
        val cues = cues(text)
        val score = model?.score(text) ?: 0.0
        return Verdict(cues.isNotEmpty() || (model != null && score >= model.threshold), score, cues)
    }

    // ------------------------------------------------------------------ Ghana cues

    private val NEG = "(?<!never )(?<!not )(?<!n't )(?<!do not )(?<!don't )"
    private val ASKS_PIN = Regex("(?i)\\b$NEG(?:send|reply with|enter|give|share|tell|confirm|type)\\b[^.?!]{0,30}\\b(?:pin|otp|password|secret code|verification code|the code|your code)\\b")
    private val WRONG = Regex("(?i)\\b(?:by mistake|mistakenly|wrongly|wrong number|in error|wrong account)\\b")
    private val SEND_BACK = Regex("(?i)\\b(?:send it back|return|refund|reverse|reversal|pay (?:it )?back|send back)\\b")
    private val LINK = Regex("(?i)https?://\\S+|www\\.\\S+|\\b[a-z0-9-]+\\.(?:com|co|net|org|gh|ly|link|xyz|info|biz|top|me|online|site)(?:/\\S*)?")
    private val LINK_VERB = Regex("(?i)\\b(?:verify|reverse|reversal|claim|unlock|activate|update|confirm|blocked|suspended)\\b")
    private val PRIZE = Regex("(?i)\\b(?:won|winner|prize|promo|draw|lottery|jackpot|selected)\\b")
    private val FEE = Regex("(?i)\\b(?:fee|charge|pay|send ghs|send gh₵|send \\d)")
    private val AUTHORITY = Regex("(?i)\\b(?:police|cid|officer|court|custody|arrest(?:ed)?|bail|immigration)\\b")
    private val PAY = Regex("(?i)\\b(?:pay|send|transfer)\\b")

    fun cues(text: String): List<Cue> = buildList {
        // "Never share your PIN" (MTN's own advice) is not a request: a verb right after a negation doesn't count ([NEG]).
        if (ASKS_PIN.containsMatchIn(text)) add(Cue.ASKS_PIN)
        if (WRONG.containsMatchIn(text) && SEND_BACK.containsMatchIn(text)) add(Cue.WRONG_SEND)
        // Links (fraud step 2): a look-alike or a disguised address warns on its own; a shortened, http or odd-ending link
        // only with words that ask the reader to act; otherwise the old link + "verify/claim" cue.
        val links = LinkRisk.links(text)
        val acts = LinkRisk.asksToAct(text) || LINK_VERB.containsMatchIn(text)
        when {
            links.any { LinkRisk.Signal.LOOKALIKE in it.signals } -> add(Cue.LINK_LOOKALIKE)
            links.any { LinkRisk.Signal.UNSAFE in it.signals } -> add(Cue.LINK_UNSAFE)
            acts && links.any { LinkRisk.Signal.HIDDEN in it.signals } -> add(Cue.LINK_HIDDEN)
            // as before step 2, except a link to the brand's own site ("verify your SIM at mtn.com.gh") is not a scam cue
            LINK.containsMatchIn(text) && LINK_VERB.containsMatchIn(text) && !(links.isNotEmpty() && links.all { it.official }) ->
                add(Cue.LINK_ACTION)
            acts && links.any { LinkRisk.Signal.WEAK in it.signals } -> add(Cue.LINK_ACTION)
        }
        if (PRIZE.containsMatchIn(text) && FEE.containsMatchIn(text)) add(Cue.PRIZE_FEE)
        if (AUTHORITY.containsMatchIn(text) && PAY.containsMatchIn(text)) add(Cue.AUTHORITY_PAY)
    }

    // ------------------------------------------------------------------ feature spec v1 (= fraud_features.py)

    const val FEATURE_VERSION = 1
    private val CUR = Regex("[₵¢£$€]")
    private val URL = Regex("https?://\\S+|www\\.\\S+|\\b[a-z0-9-]+\\.(?:com|co|net|org|gh|ly|link|xyz|info|biz|top|me|online|site)(?:/\\S*)?")
    private val PHONE = Regex("\\+?[0-9][0-9 -]{7,}[0-9]")
    private val NUM = Regex("[0-9]+")
    private val TOK = Regex("[a-z]+")

    fun tokens(text: String): List<String> {
        var t = text.lowercase()
        t = CUR.replace(t, " zzcur ")
        t = URL.replace(t, " zzurl ")
        t = PHONE.replace(t, " zzphone ")
        t = NUM.replace(t, " zznum ")
        return TOK.findAll(t).map { it.value }.toList()
    }

    fun features(text: String): Set<String> {
        val toks = tokens(text)
        val out = HashSet<String>()
        for (i in toks.indices) {
            val tok = toks[i]
            out.add("w:$tok")
            if (i + 1 < toks.size) out.add("b:${tok}_${toks[i + 1]}")
            if (tok.length >= 3 && !tok.startsWith("zz")) {
                val p = "<$tok>"
                for (n in 3..4) for (j in 0..p.length - n) out.add("c:" + p.substring(j, j + n))
            }
        }
        return out
    }

    fun fnv1a(s: String): Long {
        var h = 0x811C9DC5L
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h
    }

    fun bucketsOf(text: String, buckets: Int): Set<Int> = features(text).mapTo(HashSet()) { (fnv1a(it) % buckets).toInt() }
}
