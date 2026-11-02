package com.tekyerema.companion

import java.math.BigDecimal
import java.time.Instant
import java.util.TimeZone

/**
 * The daily brief ("what's my day like"), assembled ON THE PHONE with no model and no network: the day's calendar, a
 * headline about unread messages, one thing the user told us they like, and — only if the user wants it — a gentle note
 * about their spending. Pure and unit-tested (CompanionBriefTest); [CompanionRuntime] gathers the inputs from the phone.
 *
 * Tone rules, enforced by tests: suggestions, never judgement (no "too much", "overspent", "careful", "you should"); the
 * money is only what went through Tɛkyerɛma, and says so; never a purchase, never a push to spend; nothing nags — a
 * quiet day says little. When the phone is reading in private mode on the loudspeaker ([Inputs.hidePrivate]) there are no
 * names, titles or amounts, only counts.
 */
object CompanionBrief {
    data class Event(val title: String?, val beginMs: Long, val allDay: Boolean)

    /** Confirmed payments through Tɛkyerɛma: today's, and the last seven days' (today included). */
    data class Money(val today: BigDecimal, val todayCount: Int, val week: BigDecimal, val weekCount: Int)

    /** Something the user told us they like: verb "like" | "love" | "enjoy", and the thing ("gospel music"). */
    data class Liked(val verb: String, val what: String)

    data class Inputs(
        val nowMs: Long,
        val zone: TimeZone = TimeZone.getDefault(),
        /** The name the user asked to be called, or null. */
        val name: String? = null,
        /** Today's events; null = the calendar can't be read (no permission or access off). */
        val events: List<Event>? = null,
        /** How many events today in all, when [events] holds only the first few. */
        val eventTotal: Int = events?.size ?: 0,
        /** False = notification access is off, so nothing is said about messages (it is not "no messages"). */
        val messagesKnown: Boolean = true,
        val messageCount: Int = 0,
        val topSender: String? = null,
        val topSenderCount: Int = 0,
        val missedCalls: Int = 0,
        val liked: Liked? = null,
        /** Null = the user turned money notes off, or the ledger has nothing to say. */
        val money: Money? = null,
        /** Private reading on the loudspeaker: counts only. */
        val hidePrivate: Boolean = false
    )

    private const val SHOWN_EVENTS = 3

    fun build(i: Inputs, cedis: (BigDecimal) -> String = { plainCedis(it) }): String {
        val parts = ArrayList<String>()
        parts.add(greeting(i))
        parts.add(calendar(i))
        messages(i)?.let { parts.add(it) }
        i.liked?.let { parts.add("You told me you ${it.verb} ${it.what}, so maybe make a little time for it today.") }
        if (!i.hidePrivate) i.money?.let { m -> CompanionMoney.briefNote(m, cedis)?.let { parts.add(it) } }
        return parts.joinToString(" ")
    }

    fun greeting(i: Inputs): String {
        val hour = Instant.ofEpochMilli(i.nowMs).atZone(i.zone.toZoneId()).hour
        val g = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
        val n = i.name?.trim()?.takeIf { it.isNotEmpty() }
        return if (n != null) "$g, $n." else "$g."
    }

    /** "10 AM", "2:30 PM" — said, not shown, so no locale and no leading zero. */
    fun clock(ms: Long, zone: TimeZone): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone.toZoneId())
        val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
        val suffix = if (t.hour < 12) "AM" else "PM"
        return if (t.minute == 0) "$h12 $suffix" else "$h12:${t.minute.toString().padStart(2, '0')} $suffix"
    }

    private fun calendar(i: Inputs): String {
        val events = i.events ?: return "I can't see your calendar yet. Allow Calendar for Nexus in Settings and I'll include it."
        if (events.isEmpty() && i.eventTotal == 0) return "Your calendar is clear today."
        // Ahead of now: all-day events, and timed ones that haven't finished their first half hour.
        val ahead = events.filter { it.allDay || it.beginMs + 30 * 60_000L >= i.nowMs }.sortedBy { if (it.allDay) Long.MIN_VALUE else it.beginMs }
        val hidden = (i.eventTotal - events.size).coerceAtLeast(0)
        if (ahead.isEmpty()) return if (hidden > 0) "You have $hidden more on your calendar today." else "You have nothing else on your calendar today."
        val total = ahead.size + hidden
        if (i.hidePrivate) {
            val next = ahead.firstOrNull { !it.allDay }
            return "You have $total thing${if (total == 1) "" else "s"} on today" +
                (next?.let { ", the next at ${clock(it.beginMs, i.zone)}" } ?: "") + "."
        }
        val said = ahead.take(SHOWN_EVENTS).map { e ->
            val title = e.title?.trim()?.takeIf { it.isNotEmpty() }?.let { clip(it, 40) } ?: "an event"
            if (e.allDay) "$title, all day" else "$title at ${clock(e.beginMs, i.zone)}"
        }
        val more = (total - said.size).takeIf { it > 0 }?.let { ", and $it more" }.orEmpty()
        return "On today: ${said.joinToString(", ")}$more."
    }

    private fun messages(i: Inputs): String? {
        if (!i.messagesKnown) return null
        val calls = if (i.missedCalls > 0) "${i.missedCalls} missed call${if (i.missedCalls == 1) "" else "s"}" else null
        if (i.messageCount <= 0) return calls?.let { "You have $it, and no new messages." } ?: "Nothing new in your messages."
        val head = "${i.messageCount} new message${if (i.messageCount == 1) "" else "s"}"
        val who = if (!i.hidePrivate && i.topSender != null && i.topSenderCount > 0 && i.messageCount > 1) ", ${i.topSenderCount} from ${i.topSender}"
            else if (!i.hidePrivate && i.topSender != null && i.messageCount == 1) ", from ${i.topSender}" else ""
        return if (calls != null) "You have $head$who, and $calls." else "You have $head$who."
    }

    private fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max).trimEnd() + "…"

    private val LIKE_KEYS = mapOf("i like" to "like", "i love" to "love", "i enjoy" to "enjoy")

    /**
     * One thing the user TOLD us they like ("remember that I like gospel music" is stored as FACT "i like" → "gospel music"),
     * chosen by [dayOfYear] so the brief varies from day to day. Only facts the user said themselves; null when there are none.
     * Pure: [facts] are the FACT entries of the user's memory (the caller has checked the user hasn't switched that kind off).
     */
    fun pickLiked(facts: List<com.tekyerema.memory.MemEntry>, dayOfYear: Int): Liked? {
        val likes = facts.filter { it.kind == com.tekyerema.memory.MemKind.FACT && it.told && it.key.trim().lowercase() in LIKE_KEYS && it.value.isNotBlank() }
            .sortedBy { it.firstAt }
        if (likes.isEmpty()) return null
        val e = likes[dayOfYear.mod(likes.size)]
        return Liked(LIKE_KEYS.getValue(e.key.trim().lowercase()), e.value.trim())
    }

    /** A figure said as cedis, when the caller has no better speller (the app passes MoneyLimits.cedis). */
    fun plainCedis(v: BigDecimal): String {
        val s = v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().removeSuffix(".00")
        return "$s cedis"
    }
}

/**
 * The money note: how the user is doing with what went through Tɛkyerɛma, in words that suggest and never judge. Pure.
 */
object CompanionMoney {
    private const val HONEST = "That's only what went through me, so your Mobile Money statement has the full picture."

    /** Today is clearly more than the days before it: at least two payments, and 1.5 times the earlier days' daily average. */
    fun aboveUsual(m: CompanionBrief.Money): Boolean {
        if (m.todayCount < 2) return false
        val before = m.week - m.today
        if (before.signum() <= 0) return false
        val perDay = before.divide(BigDecimal(6), 4, java.math.RoundingMode.HALF_UP)
        return m.today > perDay.multiply(BigDecimal("1.5"))
    }

    private fun payments(n: Int) = if (n == 1) "1 payment" else "$n payments"

    /** The sums from the confirmed payments ([payments]: when, how much): today since [dayStart], and the last 7 days up to [now]. */
    fun slice(payments: List<Pair<Long, BigDecimal>>, dayStart: Long, now: Long): CompanionBrief.Money {
        val weekStart = now - 7 * 24 * 3_600_000L
        val week = payments.filter { it.first in weekStart..now && it.second.signum() > 0 }
        val today = week.filter { it.first >= dayStart }
        fun sum(l: List<Pair<Long, BigDecimal>>) = l.fold(BigDecimal.ZERO) { a, p -> a + p.second }
        return CompanionBrief.Money(sum(today), today.size, sum(week), week.size)
    }

    /** One sentence for the day brief, or null when nothing went out today (a quiet day gets no note). */
    fun briefNote(m: CompanionBrief.Money, cedis: (BigDecimal) -> String): String? {
        if (m.todayCount <= 0 || m.today.signum() <= 0) return null
        val base = "Today you've spent ${cedis(m.today)} through me"
        return if (aboveUsual(m)) "$base. That's a little more than your usual day, so maybe keep the rest for what you need today."
            else "$base, in ${payments(m.todayCount)}."
    }

    /** The answer to "how am I doing with money (this week)" — asked for, so it says more, and is honest about its limits. */
    fun answer(m: CompanionBrief.Money, week: Boolean, cedis: (BigDecimal) -> String): String {
        if (week) {
            if (m.weekCount <= 0) return "I haven't seen any payment go through me in the last 7 days, so there's nothing to report. $HONEST"
            val today = if (m.todayCount > 0) " Of that, ${cedis(m.today)} was today." else ""
            val tip = if (aboveUsual(m)) " Today was busier than your other days, so maybe keep a little aside for the days ahead." else ""
            return "In the last 7 days you've spent ${cedis(m.week)} through me, in ${payments(m.weekCount)}.$today$tip $HONEST"
        }
        if (m.todayCount <= 0) return "I haven't seen any payment go through me today. $HONEST"
        val tip = if (aboveUsual(m)) " That's a little more than your usual day, so maybe keep the rest for what you need today." else ""
        return "Today you've spent ${cedis(m.today)} through me, in ${payments(m.todayCount)}.$tip $HONEST"
    }
}
