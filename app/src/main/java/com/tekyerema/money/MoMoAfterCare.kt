package com.tekyerema.money

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.NumberSpeech
import com.tekyerema.core.config.AppConfig
import com.tekyerema.phonecare.MoneyAlerts
import java.math.BigDecimal

/**
 * After-care for a Mobile Money send (money safety round 2, 2026-09-29; secure_payment_auth.md recommendation 13: "read
 * the result, local receipt without PIN, guide to 100 / complaint timelines"):
 *  (a) the receipt — when MTN's own "You have sent GHS 5.00 to KOFI MENSAH" / "Payment made for GHS 5.00 to KOFI MENSAH"
 *      arrives from a TRUSTED Mobile Money sender within [receiptWindowMs] of a hand-off at the PIN, and money alerts are
 *      on, a short receipt is read: "MTN confirms 5 cedis sent to Kofi Mensah." (phonecare/PhoneAlerts — the same privacy,
 *      quiet-hours and busy rules as a credit; the balance only when the user switched balances on);
 *  (b) "I sent money to the wrong number" — a spoken guide: call MTN on 100 at once and ask for a reversal; never a number
 *      a stranger gives; the complaint timelines as guidance (the research source is a law firm's explainer, so it is
 *      phrased as "as a guide", not as law). It offers to call 100, and a "yes" becomes "call 100" — a new request that
 *      goes through the normal call confirmation ("Call 1 0 0?"). Never dialled by itself.
 * Pure except the small in-memory state (the hand-off time, the pending offer).
 *
 * Finance audit (2026-10-01): the receipt that is actually SPOKEN is now [TxLedger]'s (PhoneAlerts.outcome) — it knows a
 * failure from a success, is not tied to the "money alerts" switch, survives a restart, and parses MTN's real wordings
 * ([MoMoOutcome]). [receiptFor] / [parseSent] stay for their callers and tests; (b) and [careNumber] are unchanged.
 */
object MoMoAfterCare {
    fun receiptWindowMs(): Long = AppConfig.long("money.afterCare.receiptWindowMs", 5 * 60_000L)
    const val OFFER_MS = 2 * 60_000L

    private data class HandOff(val network: String?, val amount: BigDecimal?, val at: Long)

    @Volatile private var handOff: HandOff? = null
    @Volatile private var offeredAt = 0L
    @Volatile private var lastNetwork: String? = null

    /** A send reached the PIN hand-off (momo_send, or a guided send at Secure Input). [network] = connectors.json key. */
    fun handedOff(network: String?, amount: BigDecimal?, now: Long = System.currentTimeMillis()) {
        handOff = HandOff(network?.lowercase(), amount, now)
        network?.let { lastNetwork = it.lowercase() }
    }

    fun resetForTest() { handOff = null; offeredAt = 0L; lastNetwork = null }

    // ------------------------------------------------------------------ (a) the receipt
    /** What MTN's message says was sent: [amount] as written, [to] cleaned (null when only a number was shown). */
    data class Sent(val amount: String, val to: String?)

    private const val CUR = "(?:GHS|GH\\s?₵|GH\\s?¢|GHC|GH¢|₵|¢)"
    private const val AMT = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
    private const val NAME = "([^\\n(\\[;]{1,80}?)"
    /** Where the recipient's name stops: a sentence end, a bracket, the next field, or "was/has been successful". */
    private const val NAME_END = "(?=\\s*(?:\\.(?:\\s|$)|[,;(\\[\\n]|$)|\\s+(?:current|your|new|available|avail|balance|bal|ref|reference|" +
        "transaction|trans|txn|trx|id|fee|e-?levy|tax|date|message|msg|reason|was|has|is|successful(?:ly)?|with|for)\\b|\\s+(?:on|at)\\s+\\d)"

    private val PATTERNS = listOf(
        // MTN: "Payment made for GHS 5.00 to KOFI MENSAH. Current Balance: …"
        "payment\\s+made\\s+for\\s+$CUR\\s*$AMT\\s+to\\s+$NAME$NAME_END",
        // "You have sent GHS 5.00 to KOFI MENSAH …" / "You've sent" / "You have successfully transferred GHS …"
        "you(?:['’]ve|\\s+have)?\\s+(?:successfully\\s+)?(?:sent|transferred|paid)\\s+$CUR\\s*$AMT\\s+to\\s+$NAME$NAME_END",
        // "Your transfer of GHS 5.00 to KOFI MENSAH was successful"
        "(?:transfer|payment)\\s+of\\s+$CUR\\s*$AMT\\s+to\\s+$NAME$NAME_END\\s+(?:was|has\\s+been|is)\\s+successful",
        // "GHS 5.00 sent to KOFI MENSAH"
        "$CUR\\s*$AMT\\s+(?:has\\s+been\\s+)?(?:sent|transferred|paid)\\s+to\\s+$NAME$NAME_END"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** MTN's "you sent" message, or null. Pure. */
    fun parseSent(text: String?): Sent? {
        val t = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        for (re in PATTERNS) {
            val m = re.find(t) ?: continue
            val amount = m.groupValues[1].takeIf { it.isNotBlank() } ?: continue
            return Sent(amount, m.groupValues.getOrNull(2)?.let { MoneyAlerts.cleanName(it) })
        }
        return null
    }

    /**
     * The receipt to read for [body] (from a TRUSTED Mobile Money sender — the caller checks), or null: not a "you sent"
     * message, or no hand-off in the last [receiptWindowMs]. One receipt per hand-off (the MoMo app's push and the SMS
     * for the same send are said once). [withBalance] = the user switched balances on.
     */
    fun receiptFor(body: String?, withBalance: Boolean, now: Long = System.currentTimeMillis()): String? {
        val h = handOff ?: return null
        if (now - h.at !in 0..receiptWindowMs()) return null
        val sent = parseSent(body) ?: return null
        handOff = null
        return receiptLine(sent, h.network, h.amount, body?.takeIf { withBalance }?.let { MoneyAlerts.balanceOf(it) })
    }

    /** "MTN confirms 5 cedis sent to Kofi Mensah." — and when MTN's figure isn't what was handed off, it says so. Pure. */
    fun receiptLine(s: Sent, network: String?, handedOff: BigDecimal?, balance: String?): String {
        val who = network?.let { NumberSpeech.networkName(it) } ?: "Mobile Money"
        val amount = MoneyAlerts.spokenCedis(s.amount)
        val base = "$who confirms $amount sent" + (s.to?.let { " to $it" } ?: "") + "."
        val shown = s.amount.replace(",", "").toBigDecimalOrNull()
        val differs = handedOff != null && shown != null && shown.compareTo(handedOff) != 0
        val warn = if (differs) " That isn't the ${MoneyAlerts.spokenCedis(handedOff!!.toPlainString())} you asked for — call $who on " +
            "${careNumber(network) ?: "its customer service number"} if it's wrong." else ""
        val bal = balance?.let { " Your balance is ${MoneyAlerts.spokenCedis(it)}." }.orEmpty()
        return base + warn + bal
    }

    // ------------------------------------------------------------------ (b) "I sent money to the wrong number"
    private val WRONG = Regex(
        "\\b(?:i|we)\\b.{0,30}\\b(?:sent|send|transferred|paid|pay)\\b.{0,30}\\bto (?:the |a )?wrong (?:number|person|account|wallet|recipient|momo)\\b|" +
            "\\bwrong (?:momo|mobile money) (?:transfer|send|number|payment|transaction)\\b|" +
            "\\b(?:sent|transferred) (?:it |money |the money |momo )?to (?:the |a )?wrong (?:number|person)\\b|" +
            "\\breverse (?:a |my |the )?(?:momo |mobile money )(?:transfer|transaction|payment)\\b|" +
            "\\b(?:momo|mobile money) (?:reversal|sent by mistake)\\b")

    /** "I sent money to the wrong number" / "I sent it to the wrong person" / "reverse a MoMo transfer". Pure. */
    fun isWrongTransfer(goal: String): Boolean = !com.tekyerema.agent.MessageToSomeone.matches(goal) &&   // "tell Kofi I sent it to the wrong number" is a message
        WRONG.containsMatchIn(goal.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " "))

    /** The network's customer-care number from config (`connectors.mobileMoney.networks.<net>.care`); MTN's is 100. */
    fun careNumber(network: String?): String? {
        val key = (network ?: "mtn").lowercase()
        return AppConfig.string("connectors.mobileMoney.networks.$key.care", if (key == "mtn") "100" else "").trim().ifEmpty { null }
    }

    /** The spoken guide (pure). With a care number it ends with the one question: should I call it? */
    fun guideLine(network: String?): String {
        val who = NumberSpeech.networkName(network ?: "mtn")
        val care = careNumber(network)
        val call = if (care != null) "Call $who on $care straight away and ask them to reverse it — the sooner, the better. " +
            "Only use $care: don't call a number someone gives you, and don't send money to anyone who calls about it. "
            else "Call $who's customer service straight away and ask them to reverse it — the sooner, the better. " +
            "Don't call a number someone gives you, and don't send money to anyone who calls about it. "
        val rules = "As a guide, the Mobile Money rules give you 30 days to complain, and $who should acknowledge a complaint within 3 working days."
        return call + rules + (care?.let { " Should I call $who on $it?" } ?: "")
    }

    /**
     * The request, as a step: the guide is the whole answer (a question at the end reopens the microphone); a "yes" to it
     * is turned into "call 100" by [rewrite]. Null = not this request.
     */
    fun decide(goal: String, history: List<String>, now: Long = System.currentTimeMillis()): AgentDecision? {
        if (history.isNotEmpty() || !isWrongTransfer(goal)) return null
        val net = lastNetwork ?: "mtn"
        if (careNumber(net) != null) offeredAt = now
        return AgentDecision.Done(guideLine(net))
    }

    private val YES = Regex("^(?:yes|yeah|yep|yes please|please do|please|ok|okay|sure|go ahead|do it|call (?:them|it|mtn)|aane)(?: please| now)?$")
    private val NO = Regex("^(?:no|nope|no thanks|no thank you|not now|don't|do not|daabi|later|leave it)(?: please)?$")

    private fun norm(t: String) = t.trim().lowercase().replace('’', '\'').replace(Regex("[.!?,]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun callOfferPending(now: Long = System.currentTimeMillis()): Boolean = offeredAt != 0L && now - offeredAt in 0..OFFER_MS

    /** A "yes" to "Should I call MTN on 100?" → "call 100" (a new request, confirmed like any call); used once. */
    fun rewrite(goal: String, now: Long = System.currentTimeMillis()): String? {
        if (!callOfferPending(now) || !YES.matches(norm(goal))) return null
        offeredAt = 0L
        return "call ${careNumber(lastNetwork) ?: return null}"
    }

    /** A "no" to the offer: the line to say, or null (no offer pending / not a no). */
    fun declineLine(text: String, now: Long = System.currentTimeMillis()): String? {
        if (!callOfferPending(now) || !NO.matches(norm(text))) return null
        offeredAt = 0L
        return "Okay. If you need it, say “call ${careNumber(lastNetwork) ?: "100"}”."
    }
}
