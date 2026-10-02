package com.tekyerema.money

import com.tekyerema.phonecare.MoneyAlerts

/**
 * What one of the network's own Mobile Money messages says happened — written against the REAL wording on the test
 * phone (finance audit 2026-10-01: 922 "MobileMoney" messages, 45 shapes, pulled masked into
 * audit/finance-2026-10-01/sms_shapes.txt). Until then every MoMo SMS in the tests was invented, and the receipt parser
 * knew only "Payment made for …": a merchant payment ("Payment for GHS5.00 to X .Current Balance"), an app payment
 * ("Your payment of GHS 5.00 to X has been completed at …"), a FAILED payment, a cash-out and an airtime purchase were
 * all unknown to it.
 *
 * The real texts are untidy, and the parser follows them as they are: a name runs straight into "Current Balance" with
 * no full stop, a full stop sits after a space ("to NAME .Current Balance"), a number follows the name ("to NAME
 * 0241234567."), a service label has a full stop inside it ("Debit. Inv1", "Bills.INV"), "GHS5.00,Tax Charged 0.Download".
 *
 * Pure. It does NOT know who sent the message: the caller acts on a result only when the sender is a trusted Mobile
 * Money one (MoneyAlerts.isTrustedSender / the SMS's real address) — a stranger's "your transaction failed, send it
 * again" parses too.
 */
object MoMoOutcome {
    enum class Kind { SENT, PAID, FAILED, RECEIVED, CASH_IN, CASH_OUT, AIRTIME, BUNDLE, OTP }

    data class Msg(
        val kind: Kind,
        /** As written, commas removed: "5.00", "1250.00". Null when the message has none. */
        val amount: String?,
        /** The other party as the network names it, cleaned for speech input ("KOFI MENSAH"); null when absent or only a number. */
        val party: String?,
        /** A phone number shown for the other party, 0-form, or null. */
        val partyNumber: String?,
        /** The balance after ("Current Balance" / "Your new balance"); null when absent. */
        val balance: String?,
        val fee: String?,
        val txnId: String?,
        /** The message's reference; for a [Kind.BUNDLE] the product as written ("Data Bundle", "Midnight Bundle", "Mashup"). */
        val reference: String?
    ) {
        /** Money left the wallet and the network says it completed. */
        val outgoingDone: Boolean get() = kind == Kind.SENT || kind == Kind.PAID || kind == Kind.AIRTIME || kind == Kind.BUNDLE || kind == Kind.CASH_OUT
    }

    private const val CUR = "(?:GHS|GH\\s?₵|GH\\s?¢|GHC|₵|¢)"
    private const val AMT = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
    /** The other party between "to" and the words that decide the kind ("… has failed", "… has been completed"). */
    private const val MID = "(.{1,80}?)"
    /** "GHS 5.00 airtime" / "GHS 10 data bundle" is not cash (the digit guards stop the amount backtracking past it). */
    private const val NOT_CASH = "(?!\\d)(?![.,]\\d)(?!\\s+(?:of\\s+)?(?:airtime|data|bundles?|minutes|sms)\\b)"

    /** Where the other party's name is: nowhere, in group 2, in the words after the match, or after it only when the
     *  optional " from " (group 2) was there; PRODUCT = group 2 is what was bought. */
    private enum class Who { NONE, GROUP, AFTER, AFTER_IF, PRODUCT }

    private class Rule(val kind: Kind, pattern: String, val who: Who, val product: String? = null) {
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /** Tried in order; the first that matches decides. Group 1 is always the amount. */
    private val RULES = listOf(
        // "Your payment of GHS 20.00 to NAME has failed at 2026-09-12 10:11:12. Reference: …" — before any success wording.
        Rule(Kind.FAILED, "\\byour\\s+(?:payment|transfer)\\s+of\\s+$CUR\\s*$AMT\\s+to\\s+$MID\\s+" +
            "(?:has\\s+failed|(?:was|is)\\s+(?:unsuccessful|not\\s+successful|declined|rejected))\\b", Who.GROUP),
        // "Your payment of GHS 20.00 to NAME has been completed at 2026-09-12 10:11:12. Your new balance: GHS 95.00. Fee was …"
        Rule(Kind.PAID, "\\byour\\s+payment\\s+of\\s+$CUR\\s*$AMT\\s+to\\s+$MID\\s+has\\s+been\\s+completed\\b", Who.GROUP),
        // (older wording, kept) "Your transfer of GHS 0.10 to AMA MENSAH was successful."
        Rule(Kind.SENT, "\\b(?:transfer|payment)\\s+of\\s+$CUR\\s*$AMT\\s+to\\s+$MID\\s+(?:was|has\\s+been|is)\\s+successful", Who.GROUP),
        // "Payment made for GHS 5.00 to NAME Current Balance: GHS 120.00 . Available Balance: …" — person to person.
        Rule(Kind.SENT, "\\bpayment\\s+made\\s+for\\s+$CUR\\s*$AMT\\s+to\\s+", Who.AFTER),
        // "Payment for GHS5.00 to NAME .Current Balance: GHS 115.00. Transaction Id: …" — a merchant, a bank, a debit.
        Rule(Kind.PAID, "\\bpayment\\s+for\\s+$CUR\\s*$AMT\\s+to\\s+", Who.AFTER),
        // "Cash Out made for GHS50.00 to NAME. Current Balance: GHS250.00 Financial Transaction Id: …"
        Rule(Kind.CASH_OUT, "\\bcash\\s?-?\\s?out\\s+made\\s+for\\s+$CUR\\s*$AMT\\s+to\\s+", Who.AFTER),
        // "Cash In received for GHS 100.00 from NAME. Current Balance GHS 300.00 Available Balance GHS 300.00. …"
        Rule(Kind.CASH_IN, "\\bcash\\s?-?\\s?in\\s+received\\s+for\\s+$CUR\\s*$AMT(\\s+from\\s+)?", Who.AFTER_IF),
        // "Airtime recharge received for GHS 5.00 from 0241234567. Thank you" — airtime, not cash.
        Rule(Kind.AIRTIME, "\\bairtime\\s+recharge\\s+received\\s+for\\s+$CUR\\s*$AMT(\\s+from\\s+)?", Who.AFTER_IF),
        // "Payment received for GHS 50.00 from NAME Current Balance: GHS 123.45 . Available Balance: …"
        Rule(Kind.RECEIVED, "\\bpayment\\s+received\\s+for\\s+$CUR\\s*$AMT(\\s+from\\s+)?", Who.AFTER_IF),
        // "You have successfully purchased GHS 5.00 Airtime from MTNONLINEAIRTIMEVENDOR on 2026-09-12.Ref: …."
        Rule(Kind.AIRTIME, "\\byou\\s+have\\s+successfully\\s+purchased\\s+$CUR\\s*$AMT\\s+airtime\\b(\\s+from\\s+)?", Who.AFTER_IF),
        // "You have recharged GHC5.00."
        Rule(Kind.AIRTIME, "\\byou\\s+have\\s+recharged\\s+$CUR\\s*$AMT", Who.NONE),
        // "Y'ello! you have successfully purchased GHC3 Data Bundle and received 471.86 MB." / "… GHC1.50 Midnight Bundle …"
        Rule(Kind.BUNDLE, "\\byou\\s+have\\s+successfully\\s+purchased\\s+$CUR\\s*$AMT\\s+((?:[A-Za-z0-9]+\\s+){0,3}?bundle)\\b", Who.PRODUCT),
        // "Congrats! you have purchased a Mashup of Ghc2.00 to be used for all networks."
        Rule(Kind.BUNDLE, "\\byou\\s+have\\s+purchased\\s+a\\s+mashup\\s+of\\s+$CUR\\s*$AMT", Who.NONE, product = "Mashup"),
        // Older / other networks' wordings (not seen on the test phone; kept from MoMoAfterCare and MoneyAlerts).
        Rule(Kind.SENT, "\\byou(?:['’]ve|\\s+have)?\\s+(?:successfully\\s+)?(?:sent|transferred|paid)\\s+$CUR\\s*$AMT\\s+to\\s+", Who.AFTER),
        Rule(Kind.SENT, "$CUR\\s*$AMT\\s+(?:has\\s+been\\s+)?(?:sent|transferred|paid)\\s+to\\s+", Who.AFTER),
        Rule(Kind.RECEIVED, "\\byou(?:['’]ve|\\s+have)?\\s+(?:just\\s+)?received\\s+$CUR\\s*$AMT$NOT_CASH(\\s+from\\s+)?", Who.AFTER_IF)
    )

    /** Failure in the network's general words, for a message none of the shapes above fits ("Purchase of the requested
     *  pack was unsuccessful"). An advert's "no more failed transactions" is not one. */
    private val GENERAL_FAIL = Regex("(?i)\\b(?:has|have)\\s+failed\\b|\\b(?:transaction|payment|transfer|purchase|request)\\s+(?:has\\s+)?failed\\b|" +
        "\\b(?:was|is|were)\\s+(?:unsuccessful|not\\s+successful|declined|rejected)\\b|\\binsufficient\\s+(?:funds?|balance)\\b|" +
        "\\bcould\\s+not\\s+be\\s+(?:completed|processed)\\b|\\bunable\\s+to\\s+(?:complete|process)\\b")
    /** The amount a general failure is about ("… of GHS 5.00 has failed") — never a balance figure. */
    private val FAILED_AMOUNT = Regex("(?i)\\b(?:of|for)\\s+$CUR\\s*$AMT")

    /** Where the other party's name stops: the next field of the message, with whatever full stops and spaces MTN put
     *  before it (" Current Balance", " .Current Balance", ".Current Balance", " ..Current Balance", ". Fee:"), or a date. */
    private val FIELD = Regex("(?i)\\s*\\.*\\s*(?:\\b(?:current|available|avail|new)\\s+bal(?:ance)?\\b|\\byour\\s+(?:\\w+\\s+){0,3}?bal(?:ance)?\\b|" +
        "\\bbal(?:ance)?\\s*:|\\b(?:reference|ref)\\s*[:\\-]|\\b(?:financial\\s+|external\\s+)?trans(?:action)?\\.?\\s*id\\b|\\b(?:fee|tax)\\s*(?:charged\\b|was\\b|is\\b|:)|" +
        "\\be-?levy\\b|\\bthank\\s+you\\b|\\bdownload\\b)|\\s+(?:on|at)\\s+\\d")
    private val PHONE = Regex("\\+?\\d[\\d ]{7,14}\\d")
    private val BREAK = Regex("[,;|\\n]")
    private val SENTENCE_END = Regex("\\.\\s+")

    private val FEE = Regex("(?i)\\bfee\\b(?:\\s+(?:charged|was|is))?\\s*:?\\s*(?:$CUR\\s*)?$AMT")
    private val TXN = Regex("(?i)\\b(?:financial\\s+)?trans(?:action)?\\.?\\s*id\\s*[:\\-]?\\s*(\\d{4,})")
    private val REFERENCE = Regex("(?i)\\b(?:reference|ref)\\s*[:\\-]\\s*([^.|\\n]*)")

    /** A login code / OTP / "confidential code": the app-bound "<#>" prefix, the words themselves, or a code word
     *  followed by a token of 4+ characters that holds a digit ("Enter Code:4821", "Your code is : 123456"). An advert's
     *  "never share your Password, OTP or PIN" and "QR Code and get 500MB" carry no code and are not secrets. */
    private val SECRET = Regex("(?i)^\\s*<#>|\\bthis\\s+otp\\b|\\bconfidential\\s+code\\b|" +
        "\\b(?:one[- ]?time|verification|log-?in|sign-?in|security|authenti[cs]ation|auth)\\s+(?:code|password|passcode|pin)\\b|" +
        "\\b(?:code|otp|pin|password|passcode)\\s*(?:is)?\\s*[:=]?\\s*\\|?\\s*(?=[A-Za-z0-9+/=]*\\d)[A-Za-z0-9+/=]{4,}")

    /** A message carrying a login code / OTP / "confidential code" — never to be read aloud or logged. */
    fun isSecret(body: String?): Boolean = body != null && SECRET.containsMatchIn(body)

    /** The message as a transaction event; null for anything else (promotions, usage warnings, service notices). */
    fun parse(body: String?): Msg? {
        val t = body?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        for (r in RULES) {
            val m = r.re.find(t) ?: continue
            val amount = m.groupValues[1].replace(",", "").takeIf { it.isNotBlank() }
            val (party, number) = when (r.who) {
                Who.GROUP -> partyOf(m.groupValues[2])
                Who.AFTER -> partyOf(t.substring(m.range.last + 1))
                Who.AFTER_IF -> if (m.groupValues[2].isNotBlank()) partyOf(t.substring(m.range.last + 1)) else null to null
                Who.NONE, Who.PRODUCT -> null to null
            }
            val reference = when {
                r.who == Who.PRODUCT -> m.groupValues[2].replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
                r.product != null -> r.product
                else -> REFERENCE.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            }
            return Msg(r.kind, amount, party, number, balanceOf(t), figure(FEE, t), TXN.find(t)?.groupValues?.get(1), reference)
        }
        if (isSecret(t)) return Msg(Kind.OTP, null, null, null, null, null, null, null)
        if (GENERAL_FAIL.containsMatchIn(t))
            return Msg(Kind.FAILED, figure(FAILED_AMOUNT, t), null, null, balanceOf(t), null, TXN.find(t)?.groupValues?.get(1), null)
        return null
    }

    private fun figure(re: Regex, t: String): String? = re.find(t)?.groupValues?.get(1)?.replace(",", "")?.takeIf { it.isNotBlank() }

    private fun balanceOf(t: String): String? = MoneyAlerts.balanceOf(t)?.replace(",", "")?.takeIf { it.isNotBlank() }

    /**
     * (name as written, number in 0-form) from the words that follow "to" / "from": cut at the next field, the number
     * taken out, brackets and stray full stops dropped. A full stop INSIDE a short label stays ("Debit. Inv1",
     * "Bills.INV"); a sentence after the name does not. Never a sentence: more than 8 words or 60 characters is no party.
     */
    private fun partyOf(raw: String): Pair<String?, String?> {
        var s = raw.take(160)
        FIELD.find(s)?.let { s = s.substring(0, it.range.first) }
        s = s.take(80)
        val number = phone(s)
        var name = PHONE.replace(s, " ").replace(Regex("[()\\[\\]]"), " ")
        BREAK.find(name)?.let { name = name.substring(0, it.range.first) }
        SENTENCE_END.find(name)?.let { end ->
            val rest = name.substring(end.range.last + 1).trim().trim('.')
            // "Debit. Inv1" is one label; "KOFI MENSAH. Thank you for using MoMo" is a name and a sentence.
            if (rest.isEmpty() || rest.contains(' ') || rest.length > 8) name = name.substring(0, end.range.first)
        }
        name = name.replace(Regex("\\s+"), " ").trim(' ', '.', ',', '-', ':', ';')
        val ok = name.count { it.isLetter() } >= 2 && name.length <= 60 && name.split(' ').size <= 8
        return (if (ok) name else null) to number
    }

    /** A Ghana mobile number in [s] as 0XXXXXXXXX ("+233…" / "233…" fold to 0…); anything else is not offered. */
    private fun phone(s: String): String? {
        val d = PHONE.find(s)?.value?.filter { it.isDigit() } ?: return null
        return when {
            d.length == 12 && d.startsWith("233") -> "0" + d.substring(3)
            d.length == 10 && d.startsWith("0") -> d
            else -> null
        }
    }
}
