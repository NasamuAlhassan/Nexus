package com.tekyerema.money

import com.tekyerema.core.config.AppConfig
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.operator.OperatorStore
import com.tekyerema.tools.MtnConfirm
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Spending limits the user sets for Mobile Money sends — per send and per day (money safety round 2, 2026-09-29;
 * docs/agent/research/secure_payment_auth.md recommendation 8: "user-set per-transfer and daily caps, … enforced
 * locally"). Nothing changes until the user sets one: the default is no limit.
 *
 * WHY each rule:
 *  - Checked in momo_send's and the guided mobile_money send's preflight, BEFORE the read-back: a send over the limit is
 *    never read back for a yes — it is refused with the reason and how to change it.
 *  - Counted per local day from sends that reached the PIN hand-off (the only point Tɛkyerɛma can know of; the user may
 *    still cancel at the PIN, so the count errs on the safe side).
 *  - Raising or removing a limit only takes effect after a cooling-off (config `money.limits.coolingOffMs`, 30 minutes);
 *    lowering is immediate. The commonest MoMo scam in Ghana is a caller talking the victim through a send (MyJoyOnline,
 *    Sep 2026): a caller who talks the user into lifting the limit can't have them send at once.
 *  - Persisted in the operator's store (Workstream C's pattern, the "op.*" keys), so a change is written to the activity
 *    log through [describeChange] (PrefsOperatorStore → AuditLog.SETTING).
 * The decisions are pure (the store is a map in unit tests).
 */
object MoneyLimits {
    enum class Kind { PER_SEND, DAILY }

    /** A raise or a removal waiting out the cooling-off: [value] null = the limit comes off; it applies at [at]. */
    data class Pending(val value: BigDecimal?, val at: Long)

    /** What a request to change a limit did. */
    sealed class Change {
        abstract val kind: Kind
        /** Applied now (a first limit, or a lower one). [value] null = removed (only when cooling-off is off). */
        data class Now(override val kind: Kind, val value: BigDecimal?) : Change()
        /** A raise or removal: [value] (null = off) applies at [at]; until then [current] stays. */
        data class Later(override val kind: Kind, val value: BigDecimal?, val at: Long, val current: BigDecimal) : Change()
        /** Already so ([value] null = there is no limit of this kind). */
        data class Same(override val kind: Kind, val value: BigDecimal?) : Change()
    }

    const val K_PER_SEND = "op.momo.limitPerSend"
    const val K_DAILY = "op.momo.limitDaily"
    const val K_PENDING = "op.momo.limitPending"
    const val K_SPENT = "op.momo.spent"

    @Volatile private var store: OperatorStore? = null
    private val limits = HashMap<Kind, BigDecimal>()
    private val pending = HashMap<Kind, Pending>()
    private var spentDay = Long.MIN_VALUE
    private var spent: BigDecimal = BigDecimal.ZERO

    fun coolingOffMs(): Long = AppConfig.long("money.limits.coolingOffMs", 30 * 60_000L).coerceAtLeast(0L)

    // ------------------------------------------------------------------ persistence
    /** Load from [s] (OperatorPolicy.init, so it shares the operator's store and its audit trail). */
    @Synchronized fun init(s: OperatorStore) { store = s; load(s) }

    /** No limits, nothing persisted (unit tests); with [s], load from it and persist again. */
    @Synchronized fun resetForTest(s: OperatorStore? = null) {
        store = null; limits.clear(); pending.clear(); spentDay = Long.MIN_VALUE; spent = BigDecimal.ZERO
        if (s != null) init(s)
    }

    private fun load(s: OperatorStore) {
        limits.clear(); pending.clear()
        s.get(K_PER_SEND)?.let { amount(it) }?.let { limits[Kind.PER_SEND] = it }
        s.get(K_DAILY)?.let { amount(it) }?.let { limits[Kind.DAILY] = it }
        try {
            val p = JSONObject(s.get(K_PENDING) ?: "{}")
            for (k in Kind.entries) p.optJSONObject(k.name)?.let { o ->
                pending[k] = Pending(if (o.isNull("v")) null else amount(o.optString("v")), o.optLong("at"))
            }
        } catch (_: Throwable) { pending.clear() }
        try {
            val o = JSONObject(s.get(K_SPENT) ?: "{}")
            spentDay = o.optLong("day", Long.MIN_VALUE)
            spent = amount(o.optString("total")) ?: BigDecimal.ZERO
        } catch (_: Throwable) { spentDay = Long.MIN_VALUE; spent = BigDecimal.ZERO }
    }

    private fun amount(s: String?): BigDecimal? = s?.trim()?.takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

    private fun put(key: String, value: String?) { try { store?.put(key, value) } catch (_: Throwable) {} }

    private fun keyOf(k: Kind) = if (k == Kind.PER_SEND) K_PER_SEND else K_DAILY

    private fun persistLimit(k: Kind) = put(keyOf(k), limits[k]?.toPlainString())

    private fun persistPending() {
        val o = JSONObject()
        for ((k, p) in pending) o.put(k.name, JSONObject().put("v", p.value?.toPlainString() ?: JSONObject.NULL).put("at", p.at))
        put(K_PENDING, if (pending.isEmpty()) null else o.toString())
    }

    // ------------------------------------------------------------------ state
    /** A pending raise/removal whose cooling-off is over applies now. */
    private fun settle(now: Long) {
        val due = pending.filterValues { now >= it.at }
        if (due.isEmpty()) return
        for ((k, p) in due) {
            pending.remove(k)
            if (p.value == null) limits.remove(k) else limits[k] = p.value
            persistLimit(k)
        }
        persistPending()
    }

    private fun rollOver(now: Long) {
        val d = OperatorPolicy.dayOf(now)
        if (d != spentDay) { spentDay = d; spent = BigDecimal.ZERO }
    }

    /** The limit in force at [now] (null = none). */
    @Synchronized fun limit(kind: Kind, now: Long = System.currentTimeMillis()): BigDecimal? { settle(now); return limits[kind] }

    /** The raise or removal waiting to apply, or null. */
    @Synchronized fun pendingChange(kind: Kind, now: Long = System.currentTimeMillis()): Pending? { settle(now); return pending[kind] }

    /** Sent today (sends that reached the PIN hand-off). */
    @Synchronized fun spentToday(now: Long = System.currentTimeMillis()): BigDecimal { rollOver(now); return spent }

    /** Any limit in force. */
    fun any(now: Long = System.currentTimeMillis()): Boolean = limit(Kind.PER_SEND, now) != null || limit(Kind.DAILY, now) != null

    /**
     * Set the [kind] limit to [value] (null = remove it). A first limit or a lower one applies at once; a higher one or a
     * removal waits out [coolingOffMs] (a later request replaces a waiting one; a lower one cancels it).
     */
    @Synchronized fun set(kind: Kind, value: BigDecimal?, now: Long = System.currentTimeMillis()): Change {
        settle(now)
        val v = value?.takeIf { it.signum() >= 0 }?.let { if (it.signum() == 0) BigDecimal.ZERO else it.stripTrailingZeros() }
        val cur = limits[kind]
        val same = if (cur == null) v == null else v != null && v.compareTo(cur) == 0
        if (same) {
            if (pending.remove(kind) != null) persistPending()
            return Change.Same(kind, cur)
        }
        val lowering = v != null && (cur == null || v < cur)
        if (lowering || coolingOffMs() == 0L) {
            if (v == null) limits.remove(kind) else limits[kind] = v
            persistLimit(kind)
            if (pending.remove(kind) != null) persistPending()
            return Change.Now(kind, v)
        }
        val at = now + coolingOffMs()
        pending[kind] = Pending(v, at)
        persistPending()
        return Change.Later(kind, v, at, cur!!)
    }

    /**
     * Why [amount] can't be sent now, or null when it is within the limits (or none is set). The line is spoken as it is
     * and stays short: it rides in the step's history line (160 characters) to the end of the task.
     */
    @Synchronized fun check(amount: BigDecimal, now: Long = System.currentTimeMillis()): String? {
        settle(now); rollOver(now)
        limits[Kind.PER_SEND]?.let { if (amount > it) return perSendLine(it) }
        limits[Kind.DAILY]?.let { if (spent + amount > it) return dailyLine(it, spent) }
        return null
    }

    /** A send reached the PIN hand-off: it counts toward today's total. */
    @Synchronized fun recordSend(amount: BigDecimal, now: Long = System.currentTimeMillis()) {
        if (amount.signum() <= 0) return
        rollOver(now)
        spent += amount
        put(K_SPENT, JSONObject().put("day", spentDay).put("total", spent.toPlainString()).toString())
    }

    private val FIGURE = Regex("\\d+(?:\\.\\d+)?")

    /** "5", "0.10", "1,250.00", "GHS 5", "5 cedis" → the amount in cedis ("50 pesewas" → 0.50); null when there isn't
     *  exactly one figure — then, with a limit set, the send is not read back until the amount is clear. */
    fun parseAmount(raw: String?): BigDecimal? {
        val s = raw?.replace(",", "")?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val figures = FIGURE.findAll(s).toList()
        if (figures.size != 1) return null
        val v = figures[0].value.toBigDecimalOrNull() ?: return null
        return if (Regex("\\bpesewas?\\b").containsMatchIn(s)) v.movePointLeft(2) else v
    }

    /** The preflight check for a send of [rawAmount]: the refusal (ends the task), "say the amount" (the planner asks), or
     *  null when it may be read back. */
    fun preflight(rawAmount: String?, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
        val amount = parseAmount(rawAmount)
        if (amount != null) return check(amount, now)?.let { it to true }
        if (!any(now)) return null
        return "You've set a Mobile Money limit, so I need the amount as a number to check it — ask the user how much." to false
    }

    // ------------------------------------------------------------------ spoken (pure)
    private const val HOW_TO_CHANGE = "You can change the limit in Settings or by saying “set my MoMo limit”."

    fun perSendLine(limit: BigDecimal): String = "That's more than your limit of ${cedis(limit)} per send. $HOW_TO_CHANGE"

    fun dailyLine(limit: BigDecimal, spentToday: BigDecimal): String =
        "That's over your daily limit of ${cedis(limit)}" +
            (if (spentToday.signum() > 0) ": you've sent ${cedis(spentToday)} today. " else ". ") + HOW_TO_CHANGE

    /** A preflight message that is a limit refusal — it ENDS the task (RoutingBrain.moneyEnd), no planner works round it. */
    fun isRefusal(message: String?): Boolean = message != null &&
        (message.startsWith("That's more than your limit of ") || message.startsWith("That's over your daily limit of "))

    fun cedis(v: BigDecimal): String = MtnConfirm.cedis(v)

    private fun what(kind: Kind, v: BigDecimal): String = if (kind == Kind.PER_SEND) "${cedis(v)} per send" else "${cedis(v)} a day"

    /** "in 30 minutes" / "in 1 minute" / "in less than a minute". */
    fun spokenWait(ms: Long): String {
        val min = Math.ceil(ms.coerceAtLeast(0L) / 60_000.0).toLong()
        return when {
            ms < 60_000L -> "in less than a minute"
            min == 1L -> "in 1 minute"
            else -> "in $min minutes"
        }
    }

    /** What to say after [set]. */
    fun changeLine(c: Change, now: Long = System.currentTimeMillis()): String = when (c) {
        is Change.Now -> when {
            c.value == null -> "Okay — there's no Mobile Money limit ${if (c.kind == Kind.PER_SEND) "per send" else "a day"} now."
            c.kind == Kind.PER_SEND -> "Okay — your Mobile Money limit is now ${cedis(c.value)} per send."
            else -> "Okay — your daily Mobile Money limit is now ${cedis(c.value)}."
        }
        is Change.Later -> (if (c.value == null) "Okay — the limit comes off ${spokenWait(c.at - now)}."
            else "Okay — your new limit starts ${spokenWait(c.at - now)}.") +
            " Until then, it stays at ${what(c.kind, c.current)}."
        is Change.Same -> when {
            c.value == null -> if (c.kind == Kind.PER_SEND) "You don't have a Mobile Money limit per send." else "You don't have a daily Mobile Money limit."
            c.kind == Kind.PER_SEND -> "Your Mobile Money limit is already ${cedis(c.value)} per send."
            else -> "Your daily Mobile Money limit is already ${cedis(c.value)}."
        }
    }

    /** "Remove my MoMo limit" (both kinds): one sentence for the pair. */
    fun removeAllLine(changes: List<Change>, now: Long = System.currentTimeMillis()): String {
        val had = changes.filter { !(it is Change.Same && it.value == null) }
        if (had.isEmpty()) return "You don't have a Mobile Money limit set."
        return had.joinToString(" ") { changeLine(it, now) }
    }

    /** "What is my MoMo limit?" */
    fun statusLine(now: Long = System.currentTimeMillis()): String {
        val per = limit(Kind.PER_SEND, now)
        val day = limit(Kind.DAILY, now)
        val pend = Kind.entries.mapNotNull { k -> pendingChange(k, now)?.let { k to it } }
        val spentNow = spentToday(now)
        val head = when {
            per != null && day != null -> "Your Mobile Money limit is ${cedis(per)} per send, and ${cedis(day)} a day."
            per != null -> "Your Mobile Money limit is ${cedis(per)} per send, with no daily limit."
            day != null -> "Your daily Mobile Money limit is ${cedis(day)}, with no limit per send."
            else -> "You haven't set a Mobile Money limit."
        }
        val sentLine = if (day != null) " You've sent ${cedis(spentNow)} today." else ""
        val pendLine = pend.joinToString("") { (k, p) ->
            if (p.value == null) " Your limit ${if (k == Kind.PER_SEND) "per send" else "a day"} comes off ${spokenWait(p.at - now)}."
            else " A new limit of ${what(k, p.value)} starts ${spokenWait(p.at - now)}."
        }
        val hint = if (per == null && day == null && pend.isEmpty()) " Say, for example, “set my MoMo limit to 200 cedis”." else ""
        return head + sentLine + pendLine + hint
    }

    /** The activity-log words for a change to one of these keys (OperatorPolicy.describeChange); null = not a choice. */
    fun describeChange(key: String, value: String?): String? = when (key) {
        K_PER_SEND -> amount(value)?.let { "set the Mobile Money limit to ${cedis(it)} per send" } ?: "removed the Mobile Money limit per send"
        K_DAILY -> amount(value)?.let { "set the daily Mobile Money limit to ${cedis(it)}" } ?: "removed the daily Mobile Money limit"
        K_PENDING -> try {
            val o = JSONObject(value ?: "{}")
            Kind.entries.mapNotNull { k -> o.optJSONObject(k.name)?.let { p ->
                val v = if (p.isNull("v")) null else amount(p.optString("v"))
                val which = if (k == Kind.PER_SEND) "per send" else "a day"
                if (v == null) "asked to remove the Mobile Money limit $which (after the waiting time)"
                else "asked to raise the Mobile Money limit to ${cedis(v)} $which (after the waiting time)"
            } }.joinToString("; ").ifEmpty { null }
        } catch (_: Throwable) { null }
        else -> null
    }
}
