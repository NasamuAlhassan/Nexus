package com.tekyerema.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * §A.2 — pure Ghana Mobile-Money knowledge, unit-tested. We deliberately do NOT auto-dial a constructed
 * money-TRANSFER string: the exact per-network one-shot formats vary, change, and a wrong recipient/amount
 * means real lost money — the highest-harm error for a user who cannot see the screen. Instead we know each
 * network's MoMo ROOT menu code and open it interactively, so the network itself prompts for the amount
 * confirmation and the PIN. We NEVER auto-submit a PIN.
 *
 * [owner: verify these root codes against current network specs before relying on them.]
 */
object GhanaMobileMoney {
    /** Code defaults; `connectors.mobileMoney.networks` (connectors.json) is the source of truth when present. */
    private data class Net(val key: String, val names: List<String>, val mccmnc: List<String>, val root: String, val sendSequence: List<String>?)

    private val DEFAULTS = listOf(
        // Owner-confirmed live MTN sequence: 1, 1, number, number (re-entered to confirm), amount, reference, PIN.
        Net("mtn", listOf("mtn"), listOf("62001"), "*170#", listOf("1", "1", "{number}", "{number}", "{amount}", "{reference}")),
        Net("airteltigo", listOf("airteltigo", "airtel", "tigo"), listOf("62003", "62006"), "*110#", null),
        Net("telecel", listOf("vodafone", "telecel"), listOf("62002"), "*110#", null)
    )

    private fun networks(): List<Net> {
        val o = com.tekyerema.core.config.AppConfig.obj("connectors.mobileMoney.networks") ?: return DEFAULTS
        val out = ArrayList<Net>()
        for (k in o.keys()) {
            val n = o.optJSONObject(k) ?: continue
            fun list(name: String) = n.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } }
            val root = n.optString("root").takeIf { it.isNotBlank() } ?: continue
            out.add(Net(k, list("names").orEmpty(), list("mccmnc").orEmpty(), root, list("sendSequence")))
        }
        return out.ifEmpty { DEFAULTS }
    }

    private fun match(operator: String?): Net? {
        val o = operator?.lowercase()?.trim().orEmpty()
        if (o.isEmpty()) return null
        return networks().firstOrNull { n -> o in n.mccmnc || n.names.any { it.isNotBlank() && o.contains(it) } }
    }

    /** "mtn" | "airteltigo" | "telecel" (config keys), or null when the SIM's network is unknown. */
    fun network(operator: String?): String? = match(operator)?.key

    /** Root Mobile-Money USSD menu per network (interactive; user completes amount + PIN). */
    fun rootUssd(operator: String?): String? = match(operator)?.root

    /** M-03: the deterministic send key sequence for this network with slots filled, or null when this network has
     *  no confirmed sequence — then the send is never driven automatically. */
    fun sendSequence(operator: String?, number: String, amount: String, reference: String): List<String>? =
        match(operator)?.sendSequence?.map {
            it.replace("{number}", number).replace("{amount}", amount).replace("{reference}", reference)
        }

    /** J-01: every network's Mobile-Money root without the trailing '#' ("*170", "*110") — a USSD code starting with
     *  one of these can move money, so it is TIER3 whatever tool dials it. */
    fun moneyPrefixes(): List<String> = networks().map { it.root.trimEnd('#') }.distinct()

    /** Extract a cedi amount (integer or decimal) from a goal, or null. */
    fun parseAmount(goal: String): String? =
        Regex("(?<![\\d.])(\\d+(?:\\.\\d{1,2})?)").find(goal.replace(",", ""))?.groupValues?.get(1)

    /** Extract a bare phone number (≥7 digits) from a goal, or null. */
    fun parseNumber(goal: String): String? {
        val digits = goal.filter { it.isDigit() || it == ' ' || it == '-' || it == '+' }
        val m = Regex("[+]?\\d[\\d\\s-]{6,}").find(digits)?.value?.filter { it.isDigit() || it == '+' }
        return m?.takeIf { it.filter { c -> c.isDigit() }.length >= 7 }
    }

    /**
     * The amount as the read-back WRITES it: "5 cedis", "0.1 cedis", and "1 cedi" — never "1 cedis" (A20e audit
     * 2026-10-01, F03). The voice layer says it in words afterwards (Verbalize: "0.1 cedis" → "ten pesewas",
     * "1 cedi" → "one cedi"), so the figure stays a figure here.
     */
    fun cedisPhrase(amount: String): String {
        val a = amount.trim()
        val one = a.toBigDecimalOrNull()?.let { it.compareTo(java.math.BigDecimal.ONE) == 0 } ?: false
        return if (one) "1 cedi" else "$a cedis"
    }

    /** ", reference “school fees”" for a read-back when the user gave one (audit F09: it was parsed but never said). */
    fun referencePhrase(reference: String?): String =
        reference?.trim()?.trim('"', '\'', '“', '”')?.trim()?.takeIf { it.isNotEmpty() }?.let { ", reference “$it”" }.orEmpty()
}

/**
 * §A.2 — Mobile Money (send money / buy airtime). The confirmation (rendered in the user's language via
 * ConfirmTemplates, §A.1) reads back the resolved recipient and amount. On approval we open the network's
 * MoMo menu interactively (ACTION_CALL to the root USSD) and hand control back to the user for the amount
 * step and PIN — we do not submit a PIN or a constructed transfer string. Honest by construction: we say we
 * OPENED the flow, not that money moved.
 *
 * NOTE on scope: `CapabilityScope.FINANCIAL` is INTENTIONALLY un-granted (PolicyEngine §13.1 — the app never
 * moves money or enters a PIN). This tool respects that boundary: it does not transfer funds or type a PIN,
 * it only OPENS the network's own menu and hands off. So it is classified `COMMS_SEND` / `TIER3` — the
 * highest confirmation tier ("PIN is never automated"), always confirmed — not `FINANCIAL`.
 */
object MobileMoneyTool : Tool {
    override val name = "mobile_money"
    override val description = "Mobile Money and the network's menus: send money, buy airtime, check the balance; or _kind menu_goal for " +
        "anything else on the menus (a bundle like midnight, Allow Cash Out, bills, school fees, bank services, savings): _goal in the " +
        "user's words, _root *170# (MoMo) or *138# (bundles). Reads back; the user enters the PIN. Never tell the user to dial a code."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER3
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("_kind", "string", "send_money | buy_airtime | check_balance | menu_goal"),
        ToolParam("_goal", "string", "menu_goal: what the user wants, in their words.", required = false),
        ToolParam("_root", "string", "menu_goal: *170# or *138#.", required = false),
        ToolParam("_amount", "string", "Cedi amount.", required = false),
        ToolParam("_recipient", "string", "Recipient name (for send_money).", required = false),
        ToolParam("_number", "string", "Recipient number (for send_money).", required = false)
    )

    /** "Check my Mobile Money balance": the menu is opened and walked to the PIN — nothing is sent or bought. */
    const val KIND_BALANCE = "check_balance"
    const val BALANCE_PROMPT = "Check your Mobile Money balance? I'll go through the menu; you'll enter your PIN."

    /** A send (not airtime, not a balance check): the guided send-money menu. */
    private fun isSend(args: JSONObject) = args.str("_kind").let { it != "buy_airtime" && it != KIND_BALANCE && it != KIND_GOAL }
    /** Anything else on the network's own menus — walked by the navigator toward the user's words (MoneyFlow.fromPlanner). */
    const val KIND_GOAL = "menu_goal"

    /**
     * Money safety round 2 (2026-09-29), the guided send the same as momo_send: the user's spending limit is checked before
     * the read-back (with a limit set, the amount must be known to check it), a number never paid before and a phone call
     * in progress are said in the read-back. Airtime is not a send and is left as it was.
     */
    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        if (!isSend(args)) return null
        com.tekyerema.money.MoneyLimits.preflight(args.str("_amount"))?.let { (line, final) -> return ToolResult(false, line, recoverable = !final) }
        val tm = ctx.appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        GhanaMobileMoney.network(tm?.simOperatorName?.ifBlank { tm.simOperator } ?: tm?.simOperator)
            ?.let { args.put(com.tekyerema.money.GuidedSend.ARG_SIM, it) }
        val number = args.str("_number")?.takeIf { n -> n.count { it.isDigit() } >= 7 }
        if (number != null && !com.tekyerema.money.KnownRecipients.isKnown(number)) args.put(MoMoSendTool.ARG_NEW_RECIPIENT, true)
        else args.remove(MoMoSendTool.ARG_NEW_RECIPIENT)
        if (MoneyPrivacy.onCall(ctx.appContext)) args.put(MoMoSendTool.ARG_ON_CALL, true) else args.remove(MoMoSendTool.ARG_ON_CALL)
        return null
    }

    /** The read-back's warnings for a guided send (unknown prefix, first-time number, a call in progress), or null — the
     *  structured (Twi) read-back can't carry them, so the English sentence is spoken when there is one. */
    fun warning(args: JSONObject): String? {
        if (!isSend(args)) return null
        val net = args.str("_number")?.takeIf { n -> n.count { it.isDigit() } >= 7 }
            ?.let { com.tekyerema.agent.NumberSpeech.moneyReadBack(it).second }
        return listOfNotNull(net, MoMoSendTool.firstTimeWarning(args), MoneyPrivacy.callWarning(args.optBoolean(MoMoSendTool.ARG_ON_CALL)))
            .joinToString(" ").ifEmpty { null }
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "CALL_PHONE permission not granted, so I can't open Mobile Money. Grant it in the app.")
        val tm = ctx.appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val operator = tm?.simOperatorName?.ifBlank { tm.simOperator } ?: tm?.simOperator
        val root = GhanaMobileMoney.rootUssd(operator)
            ?: return ToolResult(false, "I couldn't tell which mobile network this SIM is on, so I won't guess a Mobile Money code.", recoverable = true)
        // A menu goal is the navigator's walk (MoneyFlow.fromPlanner); here only when that route is switched off.
        if (args.str("_kind") == KIND_GOAL) return ToolResult(false, "I can't walk the network's menu for that right now, so nothing was paid or sent.", recoverable = false)

        val kind = args.str("_kind")
        val amount = args.str("_amount")
        val recipient = args.str("_recipient") ?: args.str("_number")
        // Encode '#' for a tel: USSD so the dialer starts the INTERACTIVE session (user drives it + PIN).
        val uri = Uri.parse("tel:" + Uri.encode(root))
        val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.appContext.startActivity(intent)
            val what = when (kind) {
                "buy_airtime" -> "Opened Mobile Money ($root); the menu is on screen. Continue through it to buy airtime" +
                    (amount?.let { " of ${GhanaMobileMoney.cedisPhrase(it)}" } ?: "") + ", then stop for the user to enter the PIN — never type the PIN."
                KIND_BALANCE -> "Opened Mobile Money ($root); the menu is on screen. Continue through it to the balance " +
                    "(My Wallet, then Check Balance), then stop for the user to enter the PIN — never type the PIN, and never choose anything that sends or buys."
                else -> "Opened Mobile Money ($root); the menu is on screen. Continue through it: send" +
                    (amount?.let { " ${GhanaMobileMoney.cedisPhrase(it)}" } ?: " money") + (recipient?.let { " to $it" } ?: "") +
                    ", enter the reference when the menu asks, then stop for the user to enter the PIN — never type the PIN."
            }
            ToolResult(true, what, channel = "native")
        } catch (t: Throwable) {
            ToolResult(false, "Couldn't open Mobile Money: ${t.message}", recoverable = true)
        }
    }

    // The engine renders the confirmation from confirmSpecFor(); this is the fallback text (§P2.1 — the
    // wording matches reality: we OPEN Mobile Money, the user finishes the amount and PIN).
    override fun confirmationPrompt(args: JSONObject): String {
        if (args.str("_kind") == KIND_BALANCE) return BALANCE_PROMPT
        val amt = args.str("_amount")?.let { GhanaMobileMoney.cedisPhrase(it) } ?: "money"
        // No amount said: never "Buy money of airtime" (A20e 2026-09-29, offline) — the user picks the amount in the menu.
        if (args.str("_kind") == "buy_airtime") {
            // Airtime for someone else says who (sweep 2026-10-01: "buy 5 cedis credit for 0552093690" was read back as
            // plain "Buy 5 cedis of airtime" — the other person's number was in the arguments and never said).
            val whoName = args.str("_recipient")?.trim()?.takeIf { it.isNotEmpty() }
            val whoNumber = args.str("_number")?.takeIf { n -> n.count { it.isDigit() } >= 7 }
                ?.let { com.tekyerema.agent.NumberSpeech.moneyReadBack(it).first }
            val forWhom = when {
                whoName != null && whoNumber != null -> " for $whoName on $whoNumber,"
                whoNumber != null -> " for $whoNumber,"
                whoName != null -> " for $whoName"
                else -> ""
            }
            return if (args.str("_amount") == null) "Open Mobile Money to buy airtime$forWhom? You'll choose the amount in the menu and enter your PIN."
            else "Buy $amt of airtime$forWhom with Mobile Money? I'll go through the menu; you'll enter your PIN."
        }
        // Money safety (2026-09-29): a number is read in groups with its network ("0 5 5, 3 2 3, 8 0 1 1, an MTN number"),
        // and an unknown prefix is said so the user checks it.
        val name = args.str("_recipient")?.trim()?.takeIf { it.isNotEmpty() }
        val number = args.str("_number")?.takeIf { n -> n.count { it.isDigit() } >= 7 }
        val said = number?.let { com.tekyerema.agent.NumberSpeech.moneyReadBack(it).first }
        val warn = warning(args)   // round 2: + a first-time number and a call in progress
        val to = when {
            name != null && said != null && name.filter(Char::isDigit) != number.orEmpty().filter(Char::isDigit) -> " to $name on $said,"
            said != null -> " to $said,"
            name != null -> " to $name"
            else -> ""
        }
        return "Send $amt$to with Mobile Money?" + (warn?.let { "\n$it I'll go through the menu; you'll enter your PIN." }
            ?: " I'll go through the menu; you'll enter your PIN.")
    }
}
