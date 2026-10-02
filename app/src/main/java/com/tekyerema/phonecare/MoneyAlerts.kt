package com.tekyerema.phonecare

import com.tekyerema.core.config.AppConfig

/**
 * "Money received" alerts — Mobile Money credit SMS / app notifications read ON THE PHONE (no model, no network).
 *
 * WHY: a blind user can't glance at an SMS to see that a payment landed, and the commonest MoMo fraud in Ghana is the
 * fake credit — "You have received GHS 500…" from an ordinary number, then "I sent it to you by mistake, send it back"
 * (docs/agent/research/assistive_tech_blind.md; openclaw.md §5.2). So:
 *  - a credit is only announced as money received when it comes from a TRUSTED sender: a Mobile Money app, or an SMS
 *    whose sender id is a MoMo one ("MobileMoney", config `alerts.money.senders`) — a phone number can't use that id;
 *  - "sent to you by mistake, send it back" / reversal requests become a WARNING, never a relay of the message;
 *  - only parsed fields are ever spoken (amount, the payer's name, the balance when the user opted in) — never the raw
 *    text, a transaction id, a code or an OTP.
 * Patterns are config-driven (`alerts.money.extraPatterns`, group 1 = amount, optional group 2 = payer) on top of the
 * code defaults below. Pure → unit-tested.
 */
object MoneyAlerts {
    sealed class Event {
        /** A credit from a trusted Mobile Money sender. [amount] as written ("50.00"); [from] cleaned, or null. */
        data class Received(val amount: String, val from: String?, val balance: String?) : Event()
        /** "I sent money to you by mistake — send it back" / a reversal request: the fraud script. */
        object MistakeClaim : Event()
        /** "You have received GHS …" from an ordinary number or an unknown sender: the fake-credit trick. */
        object UntrustedCredit : Event()
    }

    private const val CUR = "(?:GHS|GH\\s?₵|GH\\s?¢|GHC|GH¢|₵|¢|cedis?)"
    private const val AMT = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
    /** The payer: lazily up to a sentence end, a bracket, a line end or the next field ("Current Balance", "Ref"…). */
    private const val NAME = "([^\\n(\\[;]{1,80}?)"
    /** Where the payer's name stops. "on 2024-01-15" / "at 10:30" end it too (Telecel/Vodafone Cash put the date there). */
    private const val NAME_END = "(?=\\s*(?:\\.(?:\\s|$)|[,;(\\[\\n]|$)|\\s+(?:current|your|new|available|avail|balance|bal|ref|reference|" +
        "transaction|trans|txn|trx|id|fee|e-?levy|tax|date|message|msg|reason)\\b|\\s+(?:on|at)\\s+\\d)"
    /** Not money: "GHS 5.00 airtime", "GHS 10 data bundle". The digit guards stop the amount backtracking to "5.0" to
     *  slip past the check. */
    private const val NOT_CASH = "(?!\\d)(?![.,]\\d)(?!\\s+(?:of\\s+)?(?:airtime|data|bundles?|minutes|sms)\\b)"
    /** Not an outgoing payment's receipt ("your payment of GHS 20.00 has been received"). */
    private const val NOT_OUTGOING = "(?<!payment of )(?<!sent )(?<!paid )(?<!request of )(?<!transfer of )(?<!purchase of )"

    /** "Airtime recharge received for GHS 5.00 from 0241234567. Thank you" comes from the MobileMoney sender itself and
     *  is airtime, not cash (real shape, A20e inbox 2026-10-01 — it was announced as "You've received 5 cedis"). */
    private const val NOT_A_RECHARGE = "(?<!recharge\\s)(?<!airtime\\s)(?<!bundle\\s)(?<!data\\s)"

    private val DEFAULT_PATTERNS = listOf(
        // MTN: "Payment received for GHS 50.00 from KOFI MENSAH 0244123456 Current Balance: GHS 123.45 …"; "Cash In received for …"
        "(?:payment|cash\\s?in|transfer|money|funds?|deposit)?\\s*${NOT_A_RECHARGE}received\\s+(?:for|of)\\s+$CUR\\s*$AMT$NOT_CASH(?:\\s+from\\s+$NAME$NAME_END)?",
        // "You have received GHS 50.00 from KOFI MENSAH (0244123456)." / "You've received …" / "You received GH₵20.00 …"
        "you(?:['’]ve|\\s+have)?\\s+(?:just\\s+)?received\\s+(?:an?\\s+)?(?:amount\\s+of\\s+|payment\\s+of\\s+|transfer\\s+of\\s+)?$CUR\\s*$AMT$NOT_CASH(?:\\s+from\\s+$NAME$NAME_END)?",
        // "You've received 50.00 GHS from …" / "You received 20 cedis from …"
        "you(?:['’]ve|\\s+have)?\\s+(?:just\\s+)?received\\s+$AMT\\s*$CUR$NOT_CASH(?:\\s+from\\s+$NAME$NAME_END)?",
        // "Your wallet has been credited with GHS 50.00 from …"
        "credited\\s+with\\s+$CUR\\s*$AMT$NOT_CASH(?:\\s+from\\s+$NAME$NAME_END)?",
        // "GHS 50.00 has been credited to your wallet (from …)" / "GHS 50.00 received from …" — a direction is required.
        "$NOT_OUTGOING$CUR\\s*$AMT\\s+(?:has\\s+been\\s+)?(?:received|credited|deposited)\\s+(?:(?:to|into|in)\\s+your\\s+" +
            "(?:mobile\\s+money\\s+|momo\\s+)?(?:wallet|account)(?:\\s+from\\s+$NAME$NAME_END)?|from\\s+$NAME$NAME_END)"
    )

    /** "Current Balance: GHS 123.45", "Your new balance is GHS 123.45", "Available Balance GHS 1,234.00". */
    private val BALANCE = Regex("(?i)\\bbal(?:ance)?\\b(?:\\s+is)?\\s*:?\\s*$CUR\\s*$AMT")

    /** The fraud script. Case-insensitive; "reversal" is only suspicious from an untrusted sender (MTN itself sends
     *  genuine reversal notices, and those need no warning). */
    private val DEFAULT_MISTAKE = listOf(
        "\\bby\\s+mistake\\b", "\\bmistaken(?:ly)?\\b", "\\bin\\s+error\\b", "\\berroneous(?:ly)?\\b",
        "\\bwrong(?:ly)?\\s+(?:sent|number|person|account|transfer|transaction|wallet)\\b", "\\bsent\\s+(?:it\\s+|the\\s+money\\s+)?wrongly\\b",
        "\\bsend\\s+(?:it|the\\s+money|my\\s+money|the\\s+cash|them)\\s+back\\b", "\\b(?:pay|give|return)\\s+(?:it|the\\s+money|my\\s+money)\\s+back\\b",
        "\\breturn\\s+(?:the|my)\\s+money\\b", "\\brefund\\s+(?:me|it|the\\s+money|my\\s+money)\\b"
    )
    private val UNTRUSTED_ONLY = listOf("\\brevers(?:al|e)\\b")
    /** The message is about money at all ("sorry, I sent that text by mistake" is not). */
    private val MONEY_CUE = Regex("(?i)\\b(?:money|momo|mobile\\s+money|cash|cedis?|ghs|ghc|transferred|transfer|transaction|payment|paid|amount|funds?)\\b|₵|¢")
    /** It claims money came TO the user ("I sent GHS 200 to your number", "the money I sent you") — without this, "please
     *  return my money" between friends would set off the fraud warning on the loudspeaker. */
    private val CLAIM_TO_YOU = Regex("(?i)\\b(?:sent|transferred|deposited|credited)\\b(?:\\s+\\S+){0,6}?\\s+(?:to\\s+|into\\s+)?(?:you|your)\\b")
    private val NUMBER_LIKE = Regex("^[+\\d\\s().-]{5,}$")

    /** A sender shown as a phone number (not a sender id like "MobileMoney", not a bank's name). Pure. */
    fun looksLikeNumber(sender: String?): Boolean = sender != null && NUMBER_LIKE.matches(sender.trim())

    /** Does the SMS inbox body [smsBody] hold the notification text [shown] (which may be cut short or sanitized)? Pure. */
    fun sameMessage(smsBody: String?, shown: String?): Boolean {
        fun n(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        val a = n(smsBody); val b = n(shown).take(40)
        return b.length >= 12 && a.contains(b)
    }

    private fun compiled(key: String, defaults: List<String>): List<Regex> =
        (defaults + AppConfig.stringList(key, emptyList())).mapNotNull { p ->
            try { Regex(p, RegexOption.IGNORE_CASE) } catch (_: Throwable) { null }   // a bad config pattern is skipped, not fatal
        }

    private fun received() = compiled("alerts.money.extraPatterns", DEFAULT_PATTERNS)
    private fun mistake(trusted: Boolean) = compiled("alerts.money.extraMistakePatterns", DEFAULT_MISTAKE + if (trusted) emptyList() else UNTRUSTED_ONLY)

    /**
     * What a message means for a money alert, or null when it is none of our business.
     * @param trusted it came from a Mobile Money app or a MoMo sender id ([isTrustedSender]).
     * @param fromNumber the sender is an ordinary phone number ([looksLikeNumber]) — the fake-credit trick's source. A
     *   credit from any other untrusted sender (a bank, a remittance service) is simply not ours to announce.
     */
    fun classify(text: String?, trusted: Boolean, fromNumber: Boolean = false): Event? {
        val t = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        var amount: String? = null
        var payer: String? = null
        for (re in received()) {
            val m = re.find(t) ?: continue
            amount = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            // A pattern may name the payer in more than one place (alternatives): the first one found.
            payer = (2 until m.groupValues.size).map { m.groupValues[it] }.firstOrNull { it.isNotBlank() }
            break
        }
        // The fraud script first: a (fake) credit, or a claim that money was sent to the user, AND "by mistake / send it
        // back". A trusted sender's own notices ("beware of people who say they sent money by mistake") need a credit.
        val claim = amount != null || (!trusted && CLAIM_TO_YOU.containsMatchIn(t))
        if (claim && MONEY_CUE.containsMatchIn(t) && mistake(trusted).any { it.containsMatchIn(t) }) return Event.MistakeClaim
        if (amount == null) return null
        // A login code / OTP is never a credit and never becomes a spoken line, whatever else the message says.
        if (com.tekyerema.money.MoMoOutcome.isSecret(t)) return null
        if (!trusted) return if (fromNumber) Event.UntrustedCredit else null
        // MTN's own ledger labels in the payer's place ("from Credit.Inv") are not a person: the amount, and no name.
        return Event.Received(amount, payer?.takeUnless { SERVICE_LABEL.matches(it.trim()) }?.let { cleanName(it) }, balanceOf(t))
    }

    /** "Credit.Inv" / "Debit. Inv1" / "Bills.INV" — what MTN writes where a payer's name would be (real shapes, 2026-10-01). */
    private val SERVICE_LABEL = Regex("(?i)^(?:credit|debit|bills?)\\s*\\.\\s*inv\\w*\\.?$")

    /** The balance figure in [text], or null. */
    fun balanceOf(text: String): String? = BALANCE.find(text)?.groupValues?.get(1)

    private val PHONE = Regex("\\+?\\d[\\d ]{7,14}\\d")

    /** "KOFI MENSAH 0244123456" → "Kofi Mensah"; "233244123456 - AMA SERWAA" → "Ama Serwaa"; digits only → null. Pure. */
    fun cleanName(raw: String): String? {
        if (raw.contains(com.tekyerema.core.Redact.HIDDEN)) return null
        val s = raw.replace(PHONE, " ").replace(Regex("[\\-–—:/|*#]+"), " ").replace(Regex("\\s+"), " ").trim().trim('.', ',', ' ')
        if (s.isEmpty() || s.none { it.isLetter() } || s.any { it.isDigit() }) return null
        val words = s.split(' ')
        if (words.size > 5 || s.length > 40) return null   // not a name: never read out a sentence as one
        val allCaps = s.none { it.isLowerCase() }
        return if (!allCaps) s else words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
    }

    /** A MoMo sender id, compared without case, spaces or punctuation ("MobileMoney" = "Mobile Money" = "MOBILE-MONEY"). */
    fun isTrustedSender(sender: String?, trusted: List<String> = senders()): Boolean {
        val n = norm(sender)
        return n.isNotEmpty() && trusted.any { norm(it) == n }
    }

    /** A Mobile Money app's own notifications are trusted (a trailing `*` = a package prefix). */
    fun isMoneyApp(pkg: String, apps: List<String> = moneyApps()): Boolean =
        apps.any { a -> if (a.endsWith("*")) pkg.startsWith(a.dropLast(1)) else pkg == a }

    private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    fun senders(): List<String> = AppConfig.stringList("alerts.money.senders", DEFAULT_SENDERS)
    fun moneyApps(): List<String> = AppConfig.stringList("alerts.money.apps", DEFAULT_APPS)
    fun smsApps(): List<String> = AppConfig.stringList("alerts.smsApps", DEFAULT_SMS_APPS)

    /** No bare "MoMo": people save their MoMo agent's number under that name, and a saved name is what a notification shows.
     *  (Telecel Cash's real sender id is unverified — add it in config `alerts.money.senders` once seen on a phone.) */
    val DEFAULT_SENDERS = listOf("MobileMoney", "MTN MoMo", "MTNMoMo", "Vodafone Cash", "Telecel Cash", "AirtelTigo Money", "AT Money")
    val DEFAULT_APPS = listOf("com.mtn.momo*", "com.mtn.mobilemoney*", "com.vodafone.cash*", "com.telecel.cash*")
    val DEFAULT_SMS_APPS = listOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms",
        "com.android.messaging")

    // ---------------------------------------------------------------- spoken (pure)

    /** "50.00" → "50 cedis"; "1.00" → "1 cedi"; "12.50" → "12 cedis 50 pesewas"; "0.50" → "50 pesewas"; "1,250.00" → "1250 cedis". */
    fun spokenCedis(amount: String): String {
        val a = amount.replace(",", "").trim()
        val whole = a.substringBefore('.').trimStart('0').ifEmpty { "0" }
        val frac = a.substringAfter('.', "").let { if (it.length == 1) it + "0" else it.take(2) }.trimStart('0')
        val cedis = if (whole == "1") "1 cedi" else "$whole cedis"
        val pesewas = if (frac.isEmpty()) "" else if (frac == "1") "1 pesewa" else "$frac pesewas"
        return when {
            whole == "0" && pesewas.isNotEmpty() -> pesewas
            pesewas.isEmpty() -> cedis
            else -> "$cedis $pesewas"
        }
    }

    /** "You've received 50 cedis from Kofi Mensah." (+ the balance only when [withBalance]). */
    fun receivedLine(e: Event.Received, withBalance: Boolean): String {
        val base = if (e.from != null) AppConfig.phrase("alerts.phrases.moneyFrom", "You've received {amount} from {from}.",
            "amount" to spokenCedis(e.amount), "from" to e.from)
        else AppConfig.phrase("alerts.phrases.money", "You've received {amount}.", "amount" to spokenCedis(e.amount))
        val bal = e.balance?.takeIf { withBalance }?.let {
            " " + AppConfig.phrase("alerts.phrases.balance", "Your balance is {balance}.", "balance" to spokenCedis(it))
        }.orEmpty()
        return base + bal
    }

    fun mistakeLine(): String = AppConfig.phrase("alerts.phrases.moneyMistake",
        "A message claims money was sent to you by mistake. Don't send anything back before checking your balance.")

    fun untrustedLine(): String = AppConfig.phrase("alerts.phrases.moneyUntrusted",
        "A text from an ordinary number says you received money. Check your balance before you believe it.")
}
