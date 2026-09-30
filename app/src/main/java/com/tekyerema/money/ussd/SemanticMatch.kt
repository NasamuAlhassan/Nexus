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
            "transfer", "momo user", "mobile money user", "other networks", "non mtn", "to wallet"),
        TxIntent.BUY_AIRTIME to listOf("buy airtime", "airtime", "recharge", "top up", "topup", "credit", "airtime & bundles",
            "airtime and bundles", "for self", "my number", "self"),
        TxIntent.BUY_DATA to listOf("buy data", "data bundle", "data bundles", "internet bundle", "internet", "data",
            "browse", "bundles", "buy bundle", "proceed to buy bundle"),
        TxIntent.SEND_AIRTIME to listOf("airtime for others", "buy for others", "other number", "another number",
            "airtime gift", "for others"),
        TxIntent.SEND_DATA to listOf("data for others", "buy for others", "gift data", "share data", "for others",
            "mashup for others"),
        TxIntent.CHECK_BALANCE to listOf("check balance", "balance", "my wallet", "wallet balance", "account balance",
            "bundle balance", "my account"),
        TxIntent.PAY_BILL to listOf("pay bill", "paybill", "momopay", "pay bills", "make payment", "merchant", "utilities")
    )

    /** Rows that must NEVER auto-match a money-moving intent, whatever the similarity says. */
    private val NEVER_MONEY = Regex("(?i)cash ?out|withdraw|reversal|loan|borrow|savings|insurance|allow cash|agent|bank")

    /** Anchor phrases for the embedding level — the intent said the canonical way. */
    private fun anchors(intent: TxIntent): List<String> = listOf(intent.spoken) + VOCAB.getValue(intent).take(4)

    private fun vocab(intent: TxIntent): List<String> =
        VOCAB.getValue(intent) + AppConfig.stringList("ussd.vocab.${intent.name.lowercase()}", emptyList()).map { it.lowercase() }

    /**
     * The best row for [intent] on this menu. [embed] is the on-device sentence embedder (null when unavailable —
     * levels 1–2 still work). Navigation rows ("Back", "More", "Exit") and the money-guarded rows never win.
     */
    fun best(intent: TxIntent, rows: List<Pair<String, String>>, embed: ((String) -> FloatArray)? = null): Verdict? {
        val scores = rows.mapNotNull { (n, words) ->
            val w = words.lowercase().trim()
            if (NAV_ROW.matches(w)) return@mapNotNull null
            if (intent.movesMoney && NEVER_MONEY.containsMatchIn(w)) return@mapNotNull null
            Triple(n, words, score(intent, w, embed))
        }.filter { it.third > 0.0 }.sortedByDescending { it.third }
        val top = scores.firstOrNull() ?: return null
        val second = scores.getOrNull(1)?.third ?: 0.0
        return Verdict(top.first, top.second, top.third, top.third - second)
    }

    /** One row's score for one intent: exact wording 0.95; keywords ~0.6–0.85; embedding cosine mapped to ~0–0.8. */
    internal fun score(intent: TxIntent, row: String, embed: ((String) -> FloatArray)? = null): Double {
        val words = vocab(intent)
        if (words.any { row == it || row.startsWith("$it ") || row.endsWith(" $it") || row.contains(" $it ") }) return 0.95
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

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s
    }

    private val NAV_ROW = Regex("(?i)^(?:back|more|next|exit|quit|main menu|home)\\W*$")

    private val TxIntent.movesMoney: Boolean
        get() = this == TxIntent.SEND_MONEY || this == TxIntent.PAY_BILL

    // Thresholds (config-tunable; brief §6): act on a clear winner, fall back on a murky one, never guess.
    fun actBar(): Double = AppConfig.double("ussd.navigator.act", 0.75)
    fun marginBar(): Double = AppConfig.double("ussd.navigator.margin", 0.25)
    fun fallbackBar(): Double = AppConfig.double("ussd.navigator.fallback", 0.40)
}
