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
    override val description = "Open the network's Mobile Money menu to send money or buy airtime. Reads the recipient and amount back for ONE confirmation, then opens the menu so you can navigate it (choose send-money, enter the number, amount and reference). The user always enters the PIN."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER3
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("_kind", "string", "send_money | buy_airtime"),
        ToolParam("_amount", "string", "Cedi amount.", required = false),
        ToolParam("_recipient", "string", "Recipient name (for send_money).", required = false),
        ToolParam("_number", "string", "Recipient number (for send_money).", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "CALL_PHONE permission not granted, so I can't open Mobile Money. Grant it in the app.")
        val tm = ctx.appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val operator = tm?.simOperatorName?.ifBlank { tm.simOperator } ?: tm?.simOperator
        val root = GhanaMobileMoney.rootUssd(operator)
            ?: return ToolResult(false, "I couldn't tell which mobile network this SIM is on, so I won't guess a Mobile Money code. Open your Mobile Money app yourself.", recoverable = true)

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
                    (amount?.let { " of $it cedis" } ?: "") + ", then stop for the user to enter the PIN — never type the PIN."
                else -> "Opened Mobile Money ($root); the menu is on screen. Continue through it: send" +
                    (amount?.let { " $it cedis" } ?: " money") + (recipient?.let { " to $it" } ?: "") +
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
        val amt = args.str("_amount")?.let { "$it cedis" } ?: "money"
        val to = args.str("_recipient") ?: args.str("_number")
        return if (args.str("_kind") == "buy_airtime")
            "Buy $amt of airtime with Mobile Money? I'll go through the menu; you'll enter your PIN."
        else
            "Send $amt${to?.let { " to $it" } ?: ""} with Mobile Money? I'll go through the menu; you'll enter your PIN."
    }
}
