package com.tekyerema.money

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.tekyerema.agent.NumberSpeech
import com.tekyerema.core.config.AppConfig
import com.tekyerema.phonecare.MoneyAlerts
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolParam
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.str
import org.json.JSONObject
import java.math.BigDecimal

/**
 * The network's own word on the user's money, read from the phone (finance audit, 2026-10-01): its Mobile Money
 * messages are the only receipt a blind user has, and they sit in an inbox between adverts (the test phone: 922 from
 * "MobileMoney", most of them promotions). Three jobs:
 *  - [handedOff] — one place for "a payment reached the PIN": the ledger entry ([TxLedger]), today's total for a send
 *    ([MoneyLimits]), the wrong-number guide's network ([MoMoAfterCare]) and the follow-up look ([PhoneAlerts]);
 *  - [checkInbox] — settle hand-offs that are still waiting from the inbox itself (READ_SMS), so the outcome does not
 *    depend on a notification having been seen;
 *  - [Lines] — answers built from parsed fields, never a message's raw text and never a code: "did I receive any money
 *    today", "read my last Mobile Money message".
 * Only a TRUSTED sender id counts ([MoneyAlerts.isTrustedSender], checked against the SMS's real address).
 */
object MoneyStatus {
    /** One of the network's messages as the inbox has it. */
    data class Entry(val msg: MoMoOutcome.Msg, val at: Long)

    // ------------------------------------------------------------------ the hand-off
    /** A payment reached the network's PIN / confirmation screen. [app] null = unit tests (no follow-up scheduled). */
    fun handedOff(app: Context?, what: TxLedger.What, amount: BigDecimal?, number: String?, name: String?, network: String?,
                  now: Long = System.currentTimeMillis(), registered: String? = null): TxLedger.Tx {
        if (what == TxLedger.What.SEND) amount?.let { MoneyLimits.recordSend(it, now) }
        MoMoAfterCare.handedOff(network, amount, now)
        MoMoRetry.clear()
        val tx = TxLedger.handedOff(what, amount, number, name, network, now, registered)
        if (app != null) try { com.tekyerema.phonecare.PhoneAlerts.followUp(app, tx.at) } catch (_: Throwable) {}
        return tx
    }

    // ------------------------------------------------------------------ the inbox
    fun canRead(app: Context): Boolean = app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** The network's transaction messages since [sinceMs], newest first (at most [scan] rows looked at). Codes and
     *  adverts are dropped here: nothing downstream ever holds one. Empty without READ_SMS. */
    fun inbox(app: Context, sinceMs: Long, scan: Int = AppConfig.int("money.status.scanLimit", 200)): List<Entry> {
        if (!canRead(app)) return emptyList()
        val out = ArrayList<Entry>()
        try {
            app.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.TextBasedSmsColumns.ADDRESS, Telephony.TextBasedSmsColumns.BODY, Telephony.TextBasedSmsColumns.DATE),
                "${Telephony.TextBasedSmsColumns.DATE} > ?", arrayOf(sinceMs.toString()),
                "${Telephony.TextBasedSmsColumns.DATE} DESC")?.use { c ->
                var rows = 0
                while (rows++ < scan && c.moveToNext()) {
                    if (!MoneyAlerts.isTrustedSender(c.getString(0))) continue
                    val body = c.getString(1) ?: continue
                    if (MoMoOutcome.isSecret(body)) continue
                    val msg = MoMoOutcome.parse(body) ?: continue
                    if (msg.kind == MoMoOutcome.Kind.OTP) continue
                    out.add(Entry(msg, c.getLong(2)))
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    /** Settle every hand-off still waiting from the inbox (oldest message first). Returns what was settled just now. */
    fun checkInbox(app: Context, withBalance: Boolean = false, now: Long = System.currentTimeMillis()): List<TxLedger.Settled> {
        val waiting = TxLedger.unsettled(now)
        if (waiting.isEmpty()) return emptyList()
        val since = waiting.minOf { it.at } - 1
        return inbox(app, since).sortedBy { it.at }.mapNotNull { TxLedger.settle(it.msg, it.at, withBalance, now) }
    }

    // ------------------------------------------------------------------ spoken answers (pure)
    object Lines {
        private fun cedis(amount: String?): String = amount?.let { MoneyAlerts.spokenCedis(it) } ?: "some money"
        private fun who(m: MoMoOutcome.Msg): String? =
            m.party?.let { MoneyAlerts.cleanName(it) ?: it } ?: m.partyNumber?.let { NumberSpeech.spoken(it) }

        /** What one message says happened, as a clause: "you received 5 cedis from Kofi Mensah". */
        fun says(m: MoMoOutcome.Msg): String = when (m.kind) {
            MoMoOutcome.Kind.SENT -> "you sent ${cedis(m.amount)}" + (who(m)?.let { " to $it" } ?: "")
            MoMoOutcome.Kind.PAID -> "you paid ${cedis(m.amount)}" + (who(m)?.let { " to $it" } ?: "")
            MoMoOutcome.Kind.FAILED -> "a payment of ${cedis(m.amount)}" + (who(m)?.let { " to $it" } ?: "") + " failed"
            MoMoOutcome.Kind.RECEIVED -> "you received ${cedis(m.amount)}" + (who(m)?.let { " from $it" } ?: "")
            MoMoOutcome.Kind.CASH_IN -> "${cedis(m.amount)} was paid into your wallet" + (who(m)?.let { " by $it" } ?: "")
            MoMoOutcome.Kind.CASH_OUT -> "${cedis(m.amount)} was cashed out" + (who(m)?.let { " at $it" } ?: "")
            MoMoOutcome.Kind.AIRTIME -> "you bought ${cedis(m.amount)} of airtime"
            MoMoOutcome.Kind.BUNDLE -> "you bought a ${cedis(m.amount)} bundle"
            MoMoOutcome.Kind.OTP -> "a code was sent"
        }

        /** "Read my last Mobile Money message" — the newest transaction message; the balance only when [withBalance]. */
        fun lastMessage(entries: List<Entry>, withBalance: Boolean, now: Long = System.currentTimeMillis()): String {
            val e = entries.maxByOrNull { it.at } ?: return "I don't see a Mobile Money transaction message on this phone."
            val bal = e.msg.balance?.takeIf { withBalance }?.let { " Your balance after it was ${MoneyAlerts.spokenCedis(it)}." }.orEmpty()
            return "Your last Mobile Money message, from ${TxLedger.ago(now - e.at)}, says ${says(e.msg)}.$bal"
        }

        /** "Did I receive any money today?" — [entries] since local midnight. At most three are read out. */
        fun receivedToday(entries: List<Entry>): String {
            val got = entries.filter { it.msg.kind == MoMoOutcome.Kind.RECEIVED || it.msg.kind == MoMoOutcome.Kind.CASH_IN }
                .sortedByDescending { it.at }
            if (got.isEmpty()) return "No — I don't see any Mobile Money coming in today."
            val said = got.take(3).map { "${cedis(it.msg.amount)}" + (who(it.msg)?.let { w -> " from $w" } ?: "") }
            val list = if (said.size == 1) said[0] else said.dropLast(1).joinToString(", ") + ", and " + said.last()
            val more = (got.size - 3).takeIf { it > 0 }?.let { ", and $it more" } ?: ""
            return "Yes — ${if (got.size == 1) "one payment" else "${got.size} payments"} came in today: $list$more."
        }

        /** The last payment out, from the inbox, for "what was my last transaction" when nothing went through Tɛkyerɛma. */
        fun lastOutgoing(entries: List<Entry>, now: Long = System.currentTimeMillis()): String? {
            val e = entries.filter { it.msg.outgoingDone || it.msg.kind == MoMoOutcome.Kind.FAILED }.maxByOrNull { it.at } ?: return null
            return "The last payment in your Mobile Money messages, from ${TxLedger.ago(now - e.at)}, says ${says(e.msg)}."
        }
    }
}

/**
 * "Did that payment go through?", "what was my last transaction?", "how much have I sent today?", "did I receive any
 * money today?", "read my last MoMo message" — answered on the phone from the ledger and the network's own messages
 * (money/MoneyFlow decides the call; no model does — [hiddenFromModel]). Reads only; nothing is dialled.
 */
object MoneyStatusTool : Tool {
    const val NAME = "money_status"
    const val ARG_MODE = "mode"
    const val STATUS = "status"
    const val LAST = "last"
    const val SPENT = "spent"
    const val RECEIVED = "received"
    const val MESSAGE = "message"

    override val name = NAME
    override val description = "Mobile Money outcome and history from the phone's own records (used by the on-phone money flow only)."
    override val scope = CapabilityScope.COMMS_READ
    override val riskTier = RiskTier.TIER0
    override val isAccelerator = true
    override val hiddenFromModel = true
    override val params = listOf(ToolParam(ARG_MODE, "string", "status | last | spent | received | message", required = false,
        enumValues = listOf(STATUS, LAST, SPENT, RECEIVED, MESSAGE)))

    private const val NO_SMS = "I need permission to read your text messages for that — allow SMS access for Tɛkyerɛma in Settings."

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val app = ctx.appContext
        val now = System.currentTimeMillis()
        val s = com.tekyerema.settings.Settings(app)
        // A balance is only said when the user switched balances on, or nothing but their own ears can hear it.
        val withBalance = s.alertMoneyBalance || com.tekyerema.phonecare.PhoneAlerts.privateAudio(app)
        val canRead = MoneyStatus.canRead(app)
        if (canRead) MoneyStatus.checkInbox(app, withBalance, now)   // settle what is waiting before answering about it
        val dayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val week = now - 7 * 24 * 3_600_000L
        val line = when (args.str(ARG_MODE) ?: STATUS) {
            SPENT -> if (args.str("day") == "yesterday") TxLedger.spentLine(dayStart - 24 * 3_600_000L, now, until = dayStart, day = "yesterday")
                else TxLedger.spentLine(dayStart, now)
            RECEIVED -> if (!canRead) NO_SMS else MoneyStatus.Lines.receivedToday(MoneyStatus.inbox(app, dayStart))
            MESSAGE -> if (!canRead) NO_SMS else MoneyStatus.Lines.lastMessage(MoneyStatus.inbox(app, now - 30L * 24 * 3_600_000L), withBalance, now)
            LAST -> if (TxLedger.all(now).isNotEmpty()) TxLedger.lastLine(now)
                else (if (canRead) MoneyStatus.Lines.lastOutgoing(MoneyStatus.inbox(app, week), now) else null) ?: TxLedger.NOTHING
            else -> if (TxLedger.latest(now) != null) TxLedger.statusLine(now)
                else (if (canRead) MoneyStatus.Lines.lastOutgoing(MoneyStatus.inbox(app, week), now)
                    ?.let { "I didn't take a payment to your PIN, so I can only go by your messages. $it" } else null) ?: TxLedger.NOTHING
        }
        return ToolResult(true, line, channel = "native")
    }
}
