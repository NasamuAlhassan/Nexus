package com.tekyerema.money.ussd

import com.tekyerema.core.config.AppConfig

/**
 * Which menu row means the user's intent — decided ON THE PHONE (navigator brief §4–6). Three local levels, one
 * verdict with a confidence and a margin; the caller (the navigator) decides act / online fallback / ask from them:
 *
 *  1. EXACT — the row IS a known wording of the intent ("Send Money", "Transfer Funds"): confidence ≈ 0.95.
 *  2. KEYWORD — the row carries the intent's words and none of its rivals': scaled by how cleanly it wins.
 *  3. EMBEDDING — the meaning matcher's own on-device model (agent/IntentMatcher, already in the APK) compares the
 *     row with the intent's anchor phrases by MEANING — "Recharge" lands near "buy airtime" without either word.
 *
 * Money rule (brief §26): a row that mentions cash-out, reversal, loans or agent services NEVER auto-matches a send —
 * those are the expensive near-misses. The vocabulary lives here in one place and is config-extendable
 * (`ussd.vocab.<intent>` adds wordings without a rebuild). Pure but for the injected embedder.
 */
object SemanticMatch {
    data class Verdict(val option: String, val row: String, val confidence: Double, val margin: Double)

    /** Wordings per intent — the research's cross-network table (ghana_ussd_flows.md) plus the live A20e menus. */
    private val VOCAB: Map<TxIntent, List<String>> = mapOf(
        TxIntent.SEND_MONEY to listOf("send money", "transfer money", "money transfer", "transfer funds", "send funds",
            "transfer", "to wallet"),
        TxIntent.BUY_AIRTIME to listOf("buy airtime", "airtime", "recharge", "top up", "topup", "credit", "airtime & bundles",
            "airtime and bundles", "for self", "my number", "self", "myself", "own number"),
        TxIntent.BUY_DATA to listOf("buy data", "data bundle", "data bundles", "internet bundle", "internet", "data",
            "browse", "bundles", "buy bundle", "proceed to buy bundle"),
        // Airtime for someone else goes through the SAME top menus as airtime for oneself ("Airtime & Bundles",
        // "Airtime") — without those words it stalled at the very first menu — and differs only at "Self / Others".
        TxIntent.SEND_AIRTIME to listOf("buy airtime", "airtime", "recharge", "top up", "topup", "airtime & bundles",
            "airtime and bundles"),
        TxIntent.SEND_DATA to listOf("data for others", "buy for others", "gift data", "share data", "for others",
            "mashup for others"),
        TxIntent.CHECK_BALANCE to listOf("check balance", "balance", "my wallet", "wallet balance", "account balance",
            "bundle balance", "my account"),
        TxIntent.PAY_BILL to listOf("pay bill", "paybill", "momopay", "pay bills", "make payment", "merchant", "utilities")
    )

    /**
     * The second level of a send: WHO it goes to. On the owner's first live walk (A20e, 2 October 2026) MTN's menu after
     * "Transfer Money" was "1) MoMo User 2) Non MoMo User 3) Send with Care 4) Favorite 5) Other Networks 6) Bank
     * Account", and the navigator stopped to ask: "MoMo User", "Non MoMo User" (it ends with the same words) and "Other
     * Networks" were all exact wordings of a send, a three-way tie. The answer was in what the user had already
     * confirmed — the recipient's number is on the SIM's own network — so the recipient's network now decides:
     * same network → [SEND_SAME] rows only; another network → [SEND_OTHER] rows only; unknown → both (and a tie asks).
     */
    private val SEND_SAME = listOf("momo user", "mobile money user", "mtn user", "mtn momo user", "telecel cash user", "at money user")
    private val SEND_OTHER = listOf("other networks", "other network", "non mtn", "another network", "other momo", "other mobile money")
    /** "Non MoMo User" is not "MoMo User": a send to someone with no wallet is never chosen for the user. */
    private val NEGATED = Regex("(?i)^(?:non|not|no)[- ](?!mtn\\b)")

    /** Whose phone the airtime is for decides between "Self" and "Others": the wrong one is not a candidate. */
    private val FOR_OTHERS = Regex("(?i)\\bothers?\\b|another|different number|gift")
    private val FOR_SELF = Regex("(?i)\\bself\\b|myself|my number|own number")

    /** Rows that must NEVER auto-match a money-moving intent, whatever the similarity says. */
    private val NEVER_MONEY = Regex("(?i)cash ?out|withdraw|reversal|loan|borrow|savings|insurance|allow cash|agent|bank")

    /** Anchor phrases for the embedding level — the intent said the canonical way. */
    private fun anchors(intent: TxIntent): List<String> = listOf(intent.spoken) + VOCAB.getValue(intent).take(4)

    /** Airtime for someone on the SAME network ("Others") — MTN's real menu after Airtime is "1) Self 2) Others
     *  3) Welcome Pack 4) Other Networks" (seen live 2 October 2026), so the recipient's network decides here too. */
    private val AIRTIME_OTHERS = listOf("others", "airtime for others", "buy for others", "for others", "other number",
        "another number", "airtime gift")

    private fun vocab(intent: TxIntent, crossNetwork: Boolean? = null): List<String> =
        VOCAB.getValue(intent) + AppConfig.stringList("ussd.vocab.${intent.name.lowercase()}", emptyList()).map { it.lowercase() } +
            when (intent) {
                TxIntent.SEND_MONEY -> (if (crossNetwork != true) SEND_SAME else emptyList()) + (if (crossNetwork != false) SEND_OTHER else emptyList())
                TxIntent.SEND_AIRTIME -> (if (crossNetwork != true) AIRTIME_OTHERS else emptyList()) + (if (crossNetwork != false) SEND_OTHER else emptyList())
                else -> emptyList()
            }

    /**
     * A send to another network asks WHICH network next: MTN's "Transfer Money to Other Network 1) AT 2) Telecel
     * 3) E-zwich 4) G-Money 5) Zeepay 6) Ghana Pay" (A20e, 2 October 2026) — and the walk stopped to ask the user, though
     * the read-back had just said "a Telecel number". What each network is called on a menu, by its key.
     */
    private val NETWORK_ROWS = mapOf(
        "mtn" to listOf("mtn", "mtn momo", "mtn mobile money", "mtn mobile money user"),
        "telecel" to listOf("telecel", "telecel cash", "vodafone", "vodafone cash"),
        "airteltigo" to listOf("at", "at money", "airteltigo", "airtel tigo", "airteltigo money", "airtel", "tigo")
    )

    private fun rowWords(words: String): String =
        words.lowercase().replace("&", " & ").replace(Regex("\\s+"), " ").trim().trimEnd('.')

    /**
     * The row that IS the recipient's network on a "which network?" menu, or null. Only a menu that lists two or more
     * networks by name is one (a lone "MTN" row in a main menu is not), and only when exactly one row is the
     * recipient's — otherwise the usual matching (and its question to the user) stands.
     */
    internal fun networkRow(rows: List<Pair<String, String>>, recipientNetwork: String?): Pair<String, String>? {
        val mine = NETWORK_ROWS[recipientNetwork?.lowercase() ?: return null] ?: return null
        val all = NETWORK_ROWS.values.flatten()
        if (rows.count { rowWords(it.second) in all } < 2) return null
        return rows.filter { rowWords(it.second) in mine }.singleOrNull()
    }

    private fun names(row: String, wordings: List<String>): Boolean =
        wordings.any { row == it || row.startsWith("$it ") || row.endsWith(" $it") || row.contains(" $it ") }

    /**
     * The best row for [intent] on this menu. [embed] is the on-device sentence embedder (null when unavailable —
     * levels 1–2 still work). Navigation rows ("Back", "More", "Exit") and the money-guarded rows never win.
     */
    fun best(intent: TxIntent, rows: List<Pair<String, String>>, embed: ((String) -> FloatArray)? = null,
             /** A send: is the recipient on ANOTHER network than the SIM? null = not known (or not a send). */
             crossNetwork: Boolean? = null,
             /** The recipient's network key ("telecel"), when the number says so — picks its row on a "which network?" menu. */
             recipientNetwork: String? = null): Verdict? {
        if (crossNetwork == true && (intent == TxIntent.SEND_MONEY || intent == TxIntent.SEND_AIRTIME))
            networkRow(rows, recipientNetwork)?.let { (n, words) -> return Verdict(n, words, IS, IS) }
        val scores = rows.mapNotNull { (n, words) ->
            // "Airtime&Bundles", "MoMoPay&Pay Bill" (MTN's real main menu) are read as "airtime & bundles": the known
            // wording then matches exactly instead of scoring as a weak keyword hit and stopping to ask.
            val w = rowWords(words)
            if (NAV_ROW.matches(w)) return@mapNotNull null
            if (intent == TxIntent.BUY_AIRTIME && FOR_OTHERS.containsMatchIn(w)) return@mapNotNull null
            if (intent == TxIntent.SEND_AIRTIME) {
                if (FOR_SELF.containsMatchIn(w)) return@mapNotNull null
                if (crossNetwork == false && names(w, SEND_OTHER)) return@mapNotNull null
                if (crossNetwork == true && names(w, AIRTIME_OTHERS)) return@mapNotNull null
            }
            if (intent.movesMoney && NEVER_MONEY.containsMatchIn(w)) return@mapNotNull null
            if (intent == TxIntent.SEND_MONEY) {
                if (NEGATED.containsMatchIn(w)) return@mapNotNull null
                // The row for the OTHER kind of recipient is not a candidate at all — not even by similarity.
                if (crossNetwork == false && names(w, SEND_OTHER)) return@mapNotNull null
                if (crossNetwork == true && names(w, SEND_SAME)) return@mapNotNull null
            }
            Triple(n, words, score(intent, w, embed, crossNetwork))
        }.filter { it.third > 0.0 }.sortedByDescending { it.third }
        val top = scores.firstOrNull() ?: return null
        val second = scores.getOrNull(1)?.third ?: 0.0
        // The ONLY row that is a known wording of the intent wins outright: a row that merely sounds close to the
        // embedder ("Send with Care" next to "MoMo User") must not turn a clear menu into a question.
        // …and a row that IS the wording ("Airtime") beats one that only contains it ("Schedule Airtime") — MTN's real
        // airtime menu, where the walk stopped to ask on 2 October 2026.
        val margin = if ((top.third >= 0.95 && second < 0.95) || (top.third >= IS && second < IS)) maxOf(top.third - second, marginBar())
            else top.third - second
        return Verdict(top.first, top.second, top.third, margin)
    }

    /** One row's score for one intent: exact wording 0.95; keywords ~0.6–0.85; embedding cosine mapped to ~0–0.8. */
    internal fun score(intent: TxIntent, row: String, embed: ((String) -> FloatArray)? = null, crossNetwork: Boolean? = null): Double {
        val words = vocab(intent, crossNetwork)
        if (row in words) return IS
        if (names(row, words)) return 0.95
        val hits = words.count { it.split(' ').all { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(row) } }
        if (hits > 0) return (0.6 + 0.1 * (hits - 1)).coerceAtMost(0.85)
        val e = embed ?: return 0.0
        return try {
            val r = e(row)
            val best = anchors(intent).maxOf { cosine(r, e(it)) }
            // Cosine ≈0.45 is unrelated, ≈0.8 is a synonym on this model: map [0.45, 0.85] → [0, 0.8].
            (((best - 0.45) / 0.40).coerceIn(0.0, 1.0) * 0.8)
        } catch (_: Throwable) { 0.0 }
    }

    /** The row's words are exactly a known wording of the intent. */
    private const val IS = 0.98

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s
    }

    private val NAV_ROW = Regex("(?i)^(?:back|more|next|previous|exit|quit|main menu|home)\\W*$|^#")

    private val TxIntent.movesMoney: Boolean
        get() = this == TxIntent.SEND_MONEY || this == TxIntent.PAY_BILL

    // Thresholds (config-tunable; brief §6): act on a clear winner, fall back on a murky one, never guess.
    fun actBar(): Double = AppConfig.double("ussd.navigator.act", 0.75)
    fun marginBar(): Double = AppConfig.double("ussd.navigator.margin", 0.25)
    fun fallbackBar(): Double = AppConfig.double("ussd.navigator.fallback", 0.40)
}
