package com.tekyerema.money

import org.json.JSONObject

/**
 * The guided Mobile Money send (mobile_money: Tɛkyerɛma opens the network's menu, the planner goes through it, the user
 * enters the PIN) reaching its PIN — the moment a send "reached the PIN hand-off" for the spending limits, the list of
 * numbers the user has paid, and the receipt window ([MoneyLimits], [KnownRecipients], [MoMoAfterCare]). momo_send
 * records its own hand-off; this is the same for the guided path, told by two one-line engine hooks: the user approved a
 * mobile_money send ([approved]), then the task reached the Mobile Money PIN screen and went into Secure Input ([atPin]).
 * Keyed to the task's goal and a time window, so an old approval can't be counted by a later task. Pure but for its state.
 */
object GuidedSend {
    const val WINDOW_MS = 10 * 60_000L

    private data class Approved(val goal: String, val amount: String?, val number: String?, val network: String?, val at: Long,
                                val name: String? = null)

    @Volatile private var approved: Approved? = null

    /** The user said yes to [tool]([args]) in the task for [goal]. Only a mobile_money SEND is kept. */
    fun approved(goal: String, tool: String, args: JSONObject, now: Long = System.currentTimeMillis()) {
        if (tool != "mobile_money") return
        val kind = args.optString("_kind")
        if (kind == "buy_airtime" || kind == com.tekyerema.tools.MobileMoneyTool.KIND_BALANCE) return   // only a SEND counts
        approved = Approved(goal, args.optString("_amount").takeIf { it.isNotBlank() },
            args.optString("_number").takeIf { it.isNotBlank() }, args.optString(ARG_SIM).takeIf { it.isNotBlank() }, now,
            // A recipient given by name (never a number in the name slot).
            args.optString("_recipient").takeIf { r -> r.isNotBlank() && r.count(Char::isDigit) < 7 })
    }

    /**
     * The task for [goal] went into Secure Input at [reason] ("momo_pin" = the Mobile Money PIN). With a guided send approved
     * in this task, it counts as handed off: today's total, the number as known, the receipt window. Returns whether it
     * counted (once per approval).
     */
    fun atPin(goal: String, reason: String, now: Long = System.currentTimeMillis(), app: android.content.Context? = null): Boolean {
        val a = approved ?: return false
        if (reason != "momo_pin" || a.goal != goal || now - a.at !in 0..WINDOW_MS) return false
        approved = null
        // Finance audit (2026-10-01): the hand-off goes into the ledger — the number becomes "one you have paid" only when
        // the network confirms the send, not here (money/TxLedger).
        MoneyStatus.handedOff(app, TxLedger.What.SEND, MoneyLimits.parseAmount(a.amount), a.number, a.name, a.network, now)
        return true
    }

    fun resetForTest() { approved = null }

    /** mobile_money's preflight puts the SIM's network here (same key as momo_send's). */
    const val ARG_SIM = "_sim"
}
