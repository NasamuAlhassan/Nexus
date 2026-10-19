package com.tekyerema.money

import com.tekyerema.agent.NumberSpeech
import com.tekyerema.core.config.AppConfig
import com.tekyerema.operator.OperatorStore
import com.tekyerema.phonecare.MoneyAlerts
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * What became of each Mobile Money transaction Tɛkyerɛma took as far as the PIN (finance audit, 2026-10-01).
 *
 * Why: Tɛkyerɛma's part of a payment ends where the user's PIN begins, and until now so did its knowledge — the hand-off
 * was counted as a send (today's total, "a number you've paid before"), a receipt was only read when money alerts were
 * switched on (they are off by default), a FAILED message was never recognised, and "did that payment go through?" went
 * to the cloud model, which asked "which payment?". A blind user cannot glance at the balance to settle it, so the
 * outcome is tracked here in four honest states:
 *
 *   AT_PIN   handed to the user at the network's PIN / confirmation screen — the money has NOT moved yet;
 *   DONE     the network's own message, from its trusted sender, says the payment was made;
 *   FAILED   the network's own message says it failed;
 *   UNKNOWN  no message was seen in [windowMs] — it may have been cancelled, timed out, or the message is late.
 *
 * Rules that follow from it:
 *  - a number becomes "one you've paid before" only at DONE (it used to at the hand-off: a send the user cancelled at the
 *    PIN silenced the first-time warning for that number next time — the scam script's number included);
 *  - today's total still counts from the hand-off (the safe side) and is given back when the network says FAILED;
 *  - the same amount to the same number inside [repeatMs] is flagged in the read-back ([repeatWarning]) — a repeat is
 *    how a double payment happens when the first outcome is unclear;
 *  - only the network's trusted sender settles anything; a text from a phone number never does.
 *
 * Privacy: the recipient's number is never kept — its last three digits (to tell two sends apart by ear) and the salted
 * hash [KnownRecipients] already uses. Names and amounts stay in the operator's store on this phone; "delete everything"
 * empties it. Bounded ([MAX]). Pure but for its small state → unit-tested with a map for a store.
 */
object TxLedger {
    enum class State { AT_PIN, DONE, FAILED, UNKNOWN }
    enum class What { SEND, AIRTIME }

    data class Tx(
        /** The hand-off time — also the entry's id. */
        val at: Long,
        val what: What,
        /** Plain cedis ("5", "0.5"); null when the user never gave one (a guided send). */
        val amount: String?,
        /** Who it was for, in the user's words (a contact name) or the network's registered name; null = a bare number. */
        val name: String?,
        /** The last three digits of the recipient's number — never the number. */
        val tail: String?,
        /** [KnownRecipients]' salted hash of the number. */
        val key: String?,
        val network: String?,
        val state: State = State.AT_PIN,
        val settledAt: Long = 0L,
        /** The network's transaction ID from its message — said only when the user asks for it. */
        val txnId: String? = null,
        /** The recipient's registered name as the network showed it at the PIN screen — a message naming someone else
         *  is not about this hand-off. */
        val registered: String? = null,
        /** The registered name the network's message gave. */
        val party: String? = null,
        /** The message that settled it (its transaction ID, else kind + amount + party): one message settles one
         *  hand-off, once — the app's push, the SMS and a later look at the inbox are the same message. */
        val sig: String? = null,
        /** The outcome has been said to the user (so a late duplicate message or the follow-up doesn't say it twice). */
        val told: Boolean = false
    )

    /** A transaction settled by a message, and the line to say. */
    data class Settled(val tx: Tx, val line: String)

    const val K_LEDGER = "op.momo.ledger"
    const val MAX = 30

    /** How long after the hand-off a message from the network still settles it; after that it is UNKNOWN. */
    fun windowMs(): Long = AppConfig.long("money.outcome.windowMs", 10 * 60_000L)
    /** The same amount to the same number inside this long is flagged in the read-back. */
    fun repeatMs(): Long = AppConfig.long("money.outcome.repeatMs", 15 * 60_000L)

    @Volatile private var store: OperatorStore? = null
    private val entries = ArrayList<Tx>()   // oldest first

    // ------------------------------------------------------------------ persistence
    @Synchronized fun init(s: OperatorStore) { store = s; load(s) }

    @Synchronized fun resetForTest(s: OperatorStore? = null) { store = null; entries.clear(); if (s != null) init(s) }

    /** "Delete everything". */
    @Synchronized fun clear() { entries.clear(); try { store?.put(K_LEDGER, null) } catch (_: Throwable) {} }

    private fun load(s: OperatorStore) {
        entries.clear()
        try {
            val a = JSONArray(s.get(K_LEDGER) ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                fun str(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
                entries.add(Tx(
                    at = o.optLong("at"),
                    what = try { What.valueOf(o.optString("what", "SEND")) } catch (_: Throwable) { What.SEND },
                    amount = str("amount"), name = str("name"), tail = str("tail"), key = str("key"), network = str("net"),
                    state = try { State.valueOf(o.optString("state", "UNKNOWN")) } catch (_: Throwable) { State.UNKNOWN },
                    settledAt = o.optLong("settled"), txnId = str("txn"), registered = str("registered"), party = str("party"),
                    sig = str("sig"), told = o.optBoolean("told")))
            }
        } catch (_: Throwable) { entries.clear() }
    }

    private fun save() {
        while (entries.size > MAX) entries.removeAt(0)
        val a = JSONArray()
        for (t in entries) a.put(JSONObject().put("at", t.at).put("what", t.what.name).put("amount", t.amount ?: JSONObject.NULL)
            .put("name", t.name ?: JSONObject.NULL).put("tail", t.tail ?: JSONObject.NULL).put("key", t.key ?: JSONObject.NULL)
            .put("net", t.network ?: JSONObject.NULL).put("state", t.state.name).put("settled", t.settledAt)
            .put("txn", t.txnId ?: JSONObject.NULL).put("registered", t.registered ?: JSONObject.NULL)
            .put("party", t.party ?: JSONObject.NULL).put("sig", t.sig ?: JSONObject.NULL).put("told", t.told))
        try { store?.put(K_LEDGER, if (entries.isEmpty()) null else a.toString()) } catch (_: Throwable) {}
    }

    private fun replace(old: Tx, new: Tx): Tx {
        val i = entries.indexOfFirst { it.at == old.at }
        if (i >= 0) entries[i] = new
        save()
        return new
    }

    // ------------------------------------------------------------------ events
    /** A transaction reached the network's PIN / confirmation screen and is now the user's to authorise. */
    @Synchronized fun handedOff(what: What, amount: BigDecimal?, number: String?, name: String?, network: String?,
                                now: Long = System.currentTimeMillis(), registered: String? = null): Tx {
        expire(now)
        val n = NumberSpeech.normalize(number)
        val tx = Tx(at = uniqueAt(now), what = what, amount = amount?.let { plain(it) }, name = name?.trim()?.takeIf { it.isNotEmpty() },
            tail = n.takeLast(3).takeIf { n.length >= 7 }, key = if (n.isEmpty()) null else KnownRecipients.keyOf(n),
            network = network?.lowercase(), registered = registered?.trim()?.takeIf { it.isNotEmpty() })
        entries.add(tx)
        save()
        return tx
    }

    private fun uniqueAt(now: Long): Long { var t = now; while (entries.any { it.at == t }) t++; return t }

    /** Hand-offs older than [windowMs] with no word from the network are UNKNOWN from now on. Returns those just changed. */
    @Synchronized fun expire(now: Long = System.currentTimeMillis()): List<Tx> {
        val stale = entries.filter { it.state == State.AT_PIN && now - it.at > windowMs() }
        return stale.map { replace(it, it.copy(state = State.UNKNOWN, settledAt = now)) }
    }

    /**
     * One of the network's own messages ([msg], already checked to come from its TRUSTED sender, received at [receivedAt]):
     * the hand-off it settles, with the line to say — or null when it is about something Tɛkyerɛma didn't take to the PIN
     * (a payment made by hand, money received, an advert). A message that came before the hand-off never settles it.
     *
     * Strict on purpose (code review, 2026-10-02 — a wrong "confirmed" or "failed" is worse than "not known"):
     *  - the message's amount must be the hand-off's amount exactly; a message with no amount settles nothing;
     *  - when the message names a number or a person, it must be the hand-off's (the number by its hash, the person
     *    against the registered name the network showed at the PIN screen);
     *  - one message settles one hand-off, once ([Tx.sig]); of several that fit, the oldest;
     *  - a "done" from the network corrects an earlier "failed" for the same hand-off, never the other way round.
     * [now] is the real time (the daily total rolls over on it — never on a message's own, possibly yesterday's, time).
     */
    @Synchronized fun settle(msg: MoMoOutcome.Msg, receivedAt: Long = System.currentTimeMillis(), withBalance: Boolean = false,
                             now: Long = System.currentTimeMillis(), eachItsOwn: Boolean = false, onlyAt: Long? = null): Settled? {
        val failed = msg.kind == MoMoOutcome.Kind.FAILED
        if (!failed && !msg.outgoingDone) return null
        val shown = decimal(msg.amount) ?: return null
        // [onlyAt]: the caller KNOWS which hand-off this answer belongs to (the screen after the PIN belongs to the hand-off
        // just made). Without it a message settles the first open entry of that amount — and a wrong PIN on a second send
        // of 0.10 marked the FIRST, still-open 0.10 entry failed while the second kept waiting (emulator, 13 Oct 2026, 03:29).
        fun candidate(t: Tx) = onlyAt == null || t.at == onlyAt
        // The same message again? By its transaction ID when both copies carry one; otherwise by what it says (kind,
        // amount, party) within the window — the app's push may not carry the ID the SMS does. [eachItsOwn]: the
        // network's SCREEN after the PIN (money/ussd/AfterPin) is one answer to one hand-off, never a copy of an
        // earlier one — a second wrong PIN on the same amount within ten minutes is a second failure.
        val sig = "m:${msg.kind.name}|${plain(shown)}|${msg.party.orEmpty().trim().lowercase()}"
        val id = msg.txnId?.takeIf { it.isNotBlank() }
        if (!eachItsOwn && entries.any { e -> e.sig != null && ((id != null && e.txnId == id) ||
                ((id == null || e.txnId == null) && e.sig == sig && kotlin.math.abs(e.settledAt - receivedAt) <= windowMs())) }) return null
        fun ours(t: Tx) = candidate(t) && receivedAt - t.at in 0..windowMs() && same(decimal(t.amount), shown) && fits(t, msg) && partyFits(t, msg)
        val tx = entries.firstOrNull { (it.state == State.AT_PIN || it.state == State.UNKNOWN) && ours(it) }
            ?: (if (failed) null else entries.firstOrNull { it.state == State.FAILED && ours(it) })
            ?: return null
        val wasFailed = tx.state == State.FAILED
        val done = tx.copy(state = if (failed) State.FAILED else State.DONE, settledAt = receivedAt, txnId = msg.txnId,
            party = msg.party, sig = sig, told = true)
        replace(tx, done)
        if (tx.what == What.SEND) {                                    // airtime never counted toward the send total
            if (failed) MoneyLimits.releaseSend(shown, tx.at, now)
            else {
                if (wasFailed) MoneyLimits.recordSend(shown, now)
                tx.key?.let { KnownRecipients.addKey(it) }
            }
        }
        return Settled(done, outcomeLine(done, msg.balance?.takeIf { withBalance }))
    }

    /** A person-to-person send is settled by a payment message; an airtime purchase by an airtime message, or by a
     *  payment whose other party is the airtime vendor — never by a payment to a person; a failure message by either. */
    private fun fits(tx: Tx, msg: MoMoOutcome.Msg): Boolean = when (msg.kind) {
        MoMoOutcome.Kind.FAILED -> true
        MoMoOutcome.Kind.AIRTIME -> tx.what == What.AIRTIME
        MoMoOutcome.Kind.SENT, MoMoOutcome.Kind.PAID ->
            tx.what == What.SEND || (tx.what == What.AIRTIME && AIRTIME_PARTY.containsMatchIn(msg.party.orEmpty()))
        else -> false
    }

    private val AIRTIME_PARTY = Regex("(?i)airtime|vendor|top ?up|recharge")

    /** The message is not about someone else: its number (when it shows one) is this hand-off's, and its name (when both
     *  are known) is not a different person from the one the network showed at the PIN screen. */
    private fun partyFits(tx: Tx, msg: MoMoOutcome.Msg): Boolean {
        val shownKey = msg.partyNumber?.let { KnownRecipients.keyOf(NumberSpeech.normalize(it), create = false) }
        if (shownKey != null && tx.key != null && shownKey != tx.key) return false
        if (tx.what == What.SEND && com.tekyerema.money.ussd.Boundary.nameDiffers(tx.registered, msg.party)) return false
        return true
    }

    /** The follow-up said "nothing seen yet" for [tx] — it isn't said twice. */
    /** The user abandoned the newest hand-off at the PIN screen (Cancel, the power button): its open entry is dropped, so the repeat
     *  guard does not say "this would pay twice" about a payment that never happened (council review, 19 Oct 2026). */
    @Synchronized fun abandon(): Boolean {
        val open = entries.lastOrNull { it.state == State.AT_PIN } ?: return false
        entries.removeAll { it.at == open.at }
        save()
        return true
    }

    @Synchronized fun markTold(tx: Tx) { entries.firstOrNull { it.at == tx.at }?.let { replace(it, it.copy(told = true)) } }

    // ------------------------------------------------------------------ questions
    /** The newest entry, or null. */
    @Synchronized fun latest(now: Long = System.currentTimeMillis()): Tx? { expire(now); return entries.lastOrNull() }

    @Synchronized fun all(now: Long = System.currentTimeMillis()): List<Tx> { expire(now); return entries.toList() }

    /** Hand-offs still waiting for the network's word. */
    @Synchronized fun waiting(now: Long = System.currentTimeMillis()): List<Tx> { expire(now); return entries.filter { it.state == State.AT_PIN } }

    /** Hand-offs of the last day with no word from the network — what a look at the inbox may still settle. */
    @Synchronized fun unsettled(now: Long = System.currentTimeMillis()): List<Tx> {
        expire(now)
        return entries.filter { (it.state == State.AT_PIN || it.state == State.UNKNOWN) && now - it.at in 0..24 * 3_600_000L }
    }

    /**
     * The earlier hand-off a new send of [amount] to [number] would repeat — same number, same amount, inside [repeatMs],
     * and not one the network said FAILED. Null = not a repeat.
     */
    @Synchronized fun repeatOf(number: String?, amount: BigDecimal?, now: Long = System.currentTimeMillis()): Tx? {
        expire(now)
        val key = KnownRecipients.keyOf(NumberSpeech.normalize(number), create = false) ?: return null
        amount ?: return null
        return entries.lastOrNull { it.what == What.SEND && it.key == key && it.state != State.FAILED &&
            now - it.at in 0..repeatMs() && same(decimal(it.amount), amount) }
    }

    /** The read-back's warning for a repeat ([repeatOf]) — what happened to the first one decides the words. Pure. */
    fun repeatWarning(first: Tx, now: Long = System.currentTimeMillis()): String {
        val ago = ago(now - first.at)
        return when (first.state) {
            State.DONE -> "You sent the same amount to this number $ago, and ${net(first)} confirmed it. This would be a second payment."
            else -> "You took the same amount to this number as far as your PIN $ago, and I haven't seen whether it went through. " +
                "If it did, this would pay twice."
        }
    }

    /** "Did that payment go through?" — about the newest entry. Pure given the ledger. */
    fun statusLine(now: Long = System.currentTimeMillis()): String {
        val tx = latest(now) ?: return NOTHING
        val what = describe(tx)
        val ago = ago(now - tx.at)
        return when (tx.state) {
            State.DONE -> "Yes — ${net(tx)} confirmed ${doneWords(tx)}, $ago."
            State.FAILED -> "No — ${net(tx)} said $what failed, $ago. Nothing left your wallet for it."
            State.AT_PIN -> "I took $what as far as your PIN $ago, and I haven't seen ${net(tx)}'s message about it yet. " +
                "If you entered your PIN, give it a minute and ask me again. Don't send it a second time yet."
            State.UNKNOWN -> "I'm not sure. I took $what as far as your PIN $ago, but I never saw ${net(tx)}'s message for it. " +
                "It may not have gone through. Check your Mobile Money balance before you send it again."
        }
    }

    /** "What was my last transaction?" — the newest entry the network confirmed, else the newest of any state. */
    fun lastLine(now: Long = System.currentTimeMillis()): String {
        val all = all(now)
        val tx = all.lastOrNull { it.state == State.DONE } ?: return if (all.isEmpty()) NOTHING else statusLine(now)
        val newer = all.lastOrNull()?.takeIf { it.at != tx.at }
        return "The last payment ${net(tx)} confirmed through me was ${doneWords(tx)}, ${ago(now - tx.at)}." +
            (newer?.let { " Since then: " + statusLine(now) } ?: "")
    }

    /** "How much have I sent today?" — confirmed and not-yet-confirmed apart. [dayStart] = local midnight. */
    fun spentLine(dayStart: Long, now: Long = System.currentTimeMillis()): String {
        val today = all(now).filter { it.at >= dayStart }
        val done = today.filter { it.state == State.DONE }
        val open = today.filter { it.state == State.AT_PIN || it.state == State.UNKNOWN }
        fun total(l: List<Tx>) = l.mapNotNull { decimal(it.amount) }.fold(BigDecimal.ZERO, BigDecimal::add)
        if (done.isEmpty() && open.isEmpty()) return "I haven't taken any Mobile Money payment to your PIN today."
        val a = if (done.isEmpty()) "Nothing I took to your PIN today has been confirmed yet."
            else "Today the network has confirmed ${cedis(total(done))} through me, in ${count(done.size)}."
        val b = if (open.isEmpty()) "" else " Another ${cedis(total(open))} in ${count(open.size)} reached your PIN, but I haven't seen it confirmed."
        return a + b + " That's only what went through me — your Mobile Money statement has everything."
    }

    const val NOTHING = "I haven't taken a Mobile Money payment as far as your PIN, so I have nothing to check. " +
        "I can read your last Mobile Money message if you ask."

    // ------------------------------------------------------------------ words (pure)
    /** What the network's message settled, as a spoken line: the receipt, or the failure. */
    fun outcomeLine(tx: Tx, balance: String? = null): String {
        val bal = balance?.let { " Your balance is ${MoneyAlerts.spokenCedis(it)}." }.orEmpty()
        if (tx.state == State.FAILED)
            return "${net(tx)} says ${describe(tx)} failed. Nothing left your wallet for it.$bal"
        return "${net(tx)} confirms ${doneWords(tx)}.$bal"
    }

    /** What to say when nothing has come from the network [afterMs] after the hand-off. */
    fun silentLine(tx: Tx): String =
        // (No "PIN" in a line that is spoken as an alert: with a four-digit amount it would read as a code and be dropped
        // by the alert path's own secret check, core/Redact.)
        "I haven't seen ${net(tx)}'s message for ${describe(tx)}. If you finished the payment, it may still be on its way; " +
            "if you didn't, nothing was sent. Ask me “did my payment go through” to check again."

    /** "5 cedis sent to Kofi Mensah" / "5 cedis of airtime bought". The network's figure and name win when it gave them. */
    private fun doneWords(tx: Tx): String {
        val amount = cedisOf(tx.amount)
        if (tx.what == What.AIRTIME) return "$amount of airtime bought" + (tx.name?.let { " for $it" } ?: "")
        val who = tx.party?.let { MoneyAlerts.cleanName(it) } ?: tx.name ?: tx.tail?.let { "the number ending ${it.toList().joinToString(" ")}" }
        return "$amount sent" + (who?.let { " to $it" } ?: "")
    }

    /** "the 5 cedis for Kofi" / "the 5 cedis of airtime" / "the payment". */
    private fun describe(tx: Tx): String {
        val amount = tx.amount?.let { cedisOf(it) }
        if (tx.what == What.AIRTIME) return (amount?.let { "the $it of airtime" } ?: "the airtime") + (tx.name?.let { " for $it" } ?: "")
        val who = tx.name ?: tx.tail?.let { "the number ending ${it.toList().joinToString(" ")}" }
        return (amount?.let { "the $it" } ?: "the payment") + (who?.let { " for $it" } ?: "")
    }

    private fun net(tx: Tx): String = tx.network?.let { NumberSpeech.networkName(it) } ?: "the network"

    private fun cedisOf(amount: String?): String = decimal(amount)?.let { cedis(it) } ?: "money"
    private fun cedis(v: BigDecimal): String = MoneyLimits.cedis(v)

    private fun count(n: Int) = if (n == 1) "1 payment" else "$n payments"

    /** "a moment ago", "3 minutes ago", "2 hours ago", "yesterday", "4 days ago". */
    fun ago(ms: Long): String {
        val min = ms / 60_000L
        return when {
            min < 1 -> "a moment ago"
            min == 1L -> "a minute ago"
            min < 60 -> "$min minutes ago"
            min < 120 -> "an hour ago"
            min < 24 * 60 -> "${min / 60} hours ago"
            min < 48 * 60 -> "yesterday"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    private fun decimal(s: String?): BigDecimal? = s?.replace(",", "")?.trim()?.toBigDecimalOrNull()
    private fun same(a: BigDecimal?, b: BigDecimal?): Boolean = a != null && b != null && a.compareTo(b) == 0
    private fun plain(v: BigDecimal): String = if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()
}
