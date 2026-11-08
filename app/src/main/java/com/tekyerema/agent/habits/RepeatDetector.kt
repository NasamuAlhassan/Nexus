package com.tekyerema.agent.habits

import com.tekyerema.agent.triggers.Schedule
import com.tekyerema.agent.triggers.ScheduledTasks
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.math.abs

/**
 * "You ask this every Friday — shall I remind you?" OpenClaw's scheduler notices repeated requests and offers to schedule
 * them (docs/agent/research/openclaw.md). This is the pure half, unit-tested; [Habits] is the Android glue (storage, the
 * A-13 scheduler) and VoiceController asks the question.
 *
 * Safety comes first, and only gets stricter:
 *  - Only a closed allow-list of READ / INFO request kinds is ever noted ([kindOf]): the time, the battery, the weather,
 *    the news, the airtime balance, reading messages, playing music. Money, sending, calling, deleting — anything
 *    consequential — is never recorded, so it can never be offered. When unsure it says no.
 *  - Only the KIND and the time are kept, never the user's words, in a bounded list ([MAX_HISTORY]).
 *  - A yes saves a reminder that only SPEAKS ([reminderGoal] is a "remind me that …" goal, which the scheduler says aloud
 *    and never hands to the agent): the request itself is never performed for the user. A no is remembered forever.
 *  - At most one offer a day. Each pattern is offered once; an unanswered offer may come back once, a week later.
 *
 * A pattern is the SAME kind of request on the same weekday at a similar hour (±[WINDOW_MIN] minutes) in at least
 * [WEEKS_NEEDED] different weeks, or at a similar hour on at least [DAYS_NEEDED] of the last [DAYS_WINDOW] days. The
 * request that just finished anchors it (the offer comes right after it). All days and hours are the phone's local wall
 * clock in the given time zone, so a daylight-saving change or travel doesn't split a habit.
 */
object RepeatDetector {
    const val MAX_HISTORY = 200
    const val WINDOW_MIN = 90
    const val WEEKS_NEEDED = 3
    const val DAYS_NEEDED = 4
    const val DAYS_WINDOW = 7L
    /** A weekly habit counts only the last few weeks — an old routine the user dropped is not a habit any more. */
    const val LOOKBACK_WEEKS = 8L
    /** An unanswered offer (silence, a mumble) may come back once, no sooner than a week later. */
    const val MAX_UNANSWERED = 2
    const val RETRY_DAYS = 7L

    /** The request kinds that may ever be offered — reads and information only. [you] is said to the user ("You often
     *  check your battery"), [me] is the user's own words inside the reminder goal (the scheduler turns I/my into you/your). */
    enum class Kind(val id: String, val you: String, val me: String) {
        TIME("time", "ask me the time", "ask for the time"),
        BATTERY("battery", "check your battery", "check my battery"),
        WEATHER("weather", "ask about the weather", "ask about the weather"),
        NEWS("news", "ask for the news", "ask for the news"),
        AIRTIME("airtime", "check your airtime balance", "check my airtime balance"),
        MESSAGES("messages", "check your messages", "check my messages"),
        MUSIC("music", "play music", "play music");

        companion object { fun of(id: String): Kind? = entries.firstOrNull { it.id == id } }
    }

    enum class Cadence { DAILY, WEEKLY }

    /** A habit found: [calendarDay] is a java.util.Calendar day (SUNDAY = 1 … SATURDAY = 7), 0 for a daily one. */
    data class Pattern(val kind: Kind, val cadence: Cadence, val calendarDay: Int, val hour: Int, val minute: Int) {
        /** What a yes or a no is remembered under. The hour is not part of it: a habit drifting by half an hour is
         *  the same habit, and a "no" must stay a no. */
        val key: String get() = kind.id + "@" + if (cadence == Cadence.DAILY) "daily" else "day$calendarDay"
    }

    // ------------------------------------------------------------------ what may be noted

    /**
     * The habit kind of a request that finished well, or null when it must never be offered. [fastIntent] is the
     * local fast path's intent name (FastOutcome.intent) when it answered. Conservative on purpose: the WHOLE request
     * must be one of a few read/info phrasings (or a read-only fast intent), and a consequential or compound word
     * anywhere vetoes it.
     */
    fun kindOf(goal: String, fastIntent: String? = null): Kind? {
        val g = normalize(goal)
        if (g.isEmpty() || g.length > 120) return null
        if (CONSEQUENTIAL.containsMatchIn(g) || MESSAGING_VERB.containsMatchIn(g) || COMPOUND.containsMatchIn(g)) return null
        fastIntent?.let { FAST_KINDS[it] }?.let { return it }
        return ALLOW.firstOrNull { (_, re) -> re.matches(g) }?.first
    }

    /** Lower-case words only, courtesy and wake words dropped: "Please, what's the time?" → "what's the time". */
    fun normalize(text: String): String {
        var g = text.lowercase().replace('’', '\'').replace('‘', '\'')
            .replace(Regex("[^\\p{L}\\p{N}'\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        while (true) {
            val next = g.replace(LEAD, "").replace(TAIL, "").trim()
            if (next == g) break
            g = next
        }
        return g
    }

    private val LEAD = Regex("^(?:please|kindly|hey|hi|ok|okay|so|tekyerema|tɛkyerɛma|nexus|can you|could you|would you|will you)\\b\\s*")
    private val TAIL = Regex("\\s*\\b(?:please|for me|right now|now)$")

    /** Read-only fast intents (LocalFastRouter) by name. */
    private val FAST_KINDS = mapOf(
        "TIME" to Kind.TIME, "BATTERY" to Kind.BATTERY, "DIGEST" to Kind.MESSAGES, "LAST_SMS" to Kind.MESSAGES,
        "PLAY_MUSIC" to Kind.MUSIC
    )

    private const val IS = "(?:'s|s| is)"
    private val ALLOW: List<Pair<Kind, Regex>> = listOf(
        Kind.TIME to Regex("^(?:what$IS the time|what time is it|tell me the time|check the time|the time|time)$"),
        Kind.BATTERY to Regex("^(?:(?:what$IS )?(?:my |the )?battery(?: level| percentage| life)?|" +
            "how much (?:battery|charge)(?: do i have| is left| have i got)?(?: left)?|check (?:my |the )?battery(?: level)?|" +
            "how$IS (?:my |the )?battery)$"),
        Kind.WEATHER to Regex("^(?:what$IS the weather(?: like)?(?: today| tomorrow| outside| in [a-z ]{2,30})?|" +
            "(?:tell me |check )?(?:the )?weather(?: today| forecast| for today)?|what$IS the (?:weather )?forecast(?: for)?(?: today| tomorrow)?|" +
            "(?:is|will) it (?:going to )?rain(?: today| tomorrow)?|how$IS the weather(?: today)?)$"),
        Kind.NEWS to Regex("^(?:what$IS (?:in )?the news(?: today)?|(?:read|tell|give) me the (?:news|headlines)(?: today)?|" +
            "(?:the |today's )?(?:news|headlines)(?: today)?|what$IS (?:happening|new) in (?:ghana|the world)(?: today)?|any news(?: today)?)$"),
        // The balance only — "buy airtime", "send airtime" or anything with MoMo never gets here (CONSEQUENTIAL).
        Kind.AIRTIME to Regex("^(?:(?:check|what$IS|tell me) (?:my |the )?(?:airtime|credit)(?: balance)?|" +
            "how much (?:airtime|credit)(?: do i have| is left| have i got)?(?: left)?|(?:my )?(?:airtime|credit) balance)$"),
        Kind.MESSAGES to Regex("^(?:(?:read|check) (?:my )?(?:new |unread |latest |recent )?(?:messages|texts|text messages|sms|notifications)|" +
            "what did i miss|(?:do i have|have i got|are there|any) (?:any )?(?:new )?(?:messages|texts|notifications))$"),
        Kind.MUSIC to Regex("^(?:play (?:some |my )?(?:music|songs)|put on (?:some )?music)$")
    )

    /** Anything that spends, sends, calls, changes or deletes — never a habit to offer. */
    private val CONSEQUENTIAL = Regex(
        "\\b(?:send|sends|sent|sending|call|calls|calling|dial|ring|pay|pays|paid|payment|transfer|buy|bought|purchase|" +
            "order|book|delete|remove|erase|clear|uninstall|install|download|reply|respond|forward|share|post|upload|" +
            "momo|mobile money|money|cash|cedi|cedis|ghs|wallet|bank|loan|withdraw|deposit|top ?up|recharge|pin|" +
            "password|otp|lock|unlock|reset|wipe|block|sign|login|log in|subscribe|set|turn|switch)\\b"
    )
    private val MESSAGING_VERB = Regex("^(?:message|text|whatsapp|e-?mail|sms|tell|ask|remind)\\s+(?!me\\b)")
    private val COMPOUND = Regex("\\b(?:and|then|also|after that|plus)\\b")

    // ------------------------------------------------------------------ history

    /** One successful request, kept as its kind and time only. */
    data class Event(val kind: String, val at: Long)

    /** [history] with [event] added, oldest dropped past [MAX_HISTORY]. */
    fun record(history: List<Event>, event: Event, max: Int = MAX_HISTORY): List<Event> =
        (history + event).sortedBy { it.at }.takeLast(max)

    fun encode(history: List<Event>): String =
        JSONArray().apply { history.forEach { put(JSONObject().put("k", it.kind).put("t", it.at)) } }.toString()

    /** Tolerant: bad entries are skipped, a bad document is empty. */
    fun decode(text: String?): List<Event> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(text) } catch (_: Throwable) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val k = o.optString("k", ""); val t = o.optLong("t", -1L)
            if (Kind.of(k) != null && t > 0) Event(k, t) else null
        }
    }

    // ------------------------------------------------------------------ patterns

    /**
     * The habit [now] (the request that just finished) belongs to, or null. [history] may already contain [now].
     * DAILY wins over WEEKLY: an every-day habit is also an every-Friday one.
     */
    fun detect(history: List<Event>, now: Event, zone: TimeZone): Pattern? {
        val kind = Kind.of(now.kind) ?: return null
        val z = zone.toZoneId()
        val nowT = Instant.ofEpochMilli(now.at).atZone(z)
        val today = nowT.toLocalDate()
        val nowMin = minuteOf(nowT)
        // One sample per local date: the request closest to now's time of day, within the window.
        val byDate: Map<LocalDate, Int> = (history + now).asSequence()
            .filter { it.kind == now.kind }
            .map { Instant.ofEpochMilli(it.at).atZone(z) }
            .filter { !it.toLocalDate().isAfter(today) && abs(minuteOf(it) - nowMin) <= WINDOW_MIN }
            .groupBy { it.toLocalDate() }
            .mapValues { (_, v) -> v.map { minuteOf(it) }.minBy { abs(it - nowMin) } }

        val lastDays = byDate.filterKeys { !it.isBefore(today.minusDays(DAYS_WINDOW - 1)) }
        if (today in lastDays && lastDays.size >= DAYS_NEEDED) return pattern(kind, Cadence.DAILY, 0, lastDays.values)

        val sameWeekday = byDate.filterKeys { it.dayOfWeek == today.dayOfWeek && !it.isBefore(today.minusWeeks(LOOKBACK_WEEKS)) }
        if (today in sameWeekday && sameWeekday.size >= WEEKS_NEEDED)
            return pattern(kind, Cadence.WEEKLY, calendarDay(today.dayOfWeek), sameWeekday.values)
        return null
    }

    private fun minuteOf(t: ZonedDateTime): Int = t.hour * 60 + t.minute

    /** The usual time: the middle of the samples, rounded to a quarter hour (a reminder "at 9:07" sounds like a bug). */
    private fun pattern(kind: Kind, cadence: Cadence, day: Int, minutes: Collection<Int>): Pattern {
        val sorted = minutes.sorted()
        val mid = sorted[(sorted.size - 1) / 2]
        val rounded = (((mid + 7) / 15) * 15).coerceAtMost(23 * 60 + 45)
        return Pattern(kind, cadence, day, rounded / 60, rounded % 60)
    }

    /** java.time's Monday = 1 … Sunday = 7 → java.util.Calendar's SUNDAY = 1 … SATURDAY = 7 (what [Schedule] holds). */
    fun calendarDay(d: DayOfWeek): Int = d.value % 7 + 1

    /** The local date as a day number — "today" for the once-a-day rule. */
    fun epochDay(at: Long, zone: TimeZone): Long = Instant.ofEpochMilli(at).atZone(zone.toZoneId()).toLocalDate().toEpochDay()

    // ------------------------------------------------------------------ offering once

    /** What has been said to offers so far. [unanswered]: pattern key → (offers without a yes/no, day of the last one). */
    data class Memory(
        val declined: Set<String> = emptySet(),
        val accepted: Set<String> = emptySet(),
        val lastOfferDay: Long = Long.MIN_VALUE,
        val unanswered: Map<String, Pair<Int, Long>> = emptyMap()
    )

    enum class Answer { YES, NO, NONE }

    /** May [p] be offered [today]? Never after a yes or a no (a daily one covers that kind's weekly ones too), never twice
     *  in a day, and an unanswered one only once more, a week later. */
    fun mayOffer(p: Pattern, m: Memory, today: Long): Boolean {
        val daily = p.kind.id + "@daily"
        return when {
            p.key in m.declined || p.key in m.accepted || daily in m.declined || daily in m.accepted -> false
            m.lastOfferDay == today -> false
            else -> m.unanswered[p.key]?.let { (n, day) -> n < MAX_UNANSWERED && today - day >= RETRY_DAYS } ?: true
        }
    }

    /** Memory once [p] has been offered [today] — counted as unanswered until a yes or a no comes back. */
    fun offered(p: Pattern, m: Memory, today: Long): Memory =
        m.copy(lastOfferDay = today, unanswered = m.unanswered + (p.key to ((m.unanswered[p.key]?.first ?: 0) + 1 to today)))

    /** Memory after the user's [answer]: a yes or a no is kept forever. */
    fun answered(p: Pattern, m: Memory, answer: Answer): Memory = when (answer) {
        Answer.YES -> m.copy(accepted = m.accepted + p.key, unanswered = m.unanswered - p.key)
        Answer.NO -> m.copy(declined = m.declined + p.key, unanswered = m.unanswered - p.key)
        Answer.NONE -> m
    }

    fun encodeMemory(m: Memory): String = JSONObject()
        .put("declined", JSONArray(m.declined.sorted()))
        .put("accepted", JSONArray(m.accepted.sorted()))
        .put("lastOfferDay", m.lastOfferDay)
        .put("unanswered", JSONObject().apply { m.unanswered.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) } })
        .toString()

    fun decodeMemory(text: String?): Memory {
        if (text.isNullOrBlank()) return Memory()
        val o = try { JSONObject(text) } catch (_: Throwable) { return Memory() }
        fun set(name: String): Set<String> = o.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }.toSet() }.orEmpty()
        val un = o.optJSONObject("unanswered")?.let { u ->
            u.keys().asSequence().mapNotNull { k -> u.optJSONArray(k)?.let { a -> k to (a.optInt(0) to a.optLong(1)) } }.toMap()
        }.orEmpty()
        return Memory(set("declined"), set("accepted"), o.optLong("lastOfferDay", Long.MIN_VALUE), un)
    }

    // ------------------------------------------------------------------ words and the reminder

    private val DAY_NAMES = mapOf(1 to "Sunday", 2 to "Monday", 3 to "Tuesday", 4 to "Wednesday", 5 to "Thursday", 6 to "Friday", 7 to "Saturday")

    /** "You often check your airtime balance on Fridays around 9 AM. Shall I remind you every Friday at 9 AM?" */
    fun offerLine(p: Pattern): String {
        val t = ScheduledTasks.timePhrase(p.hour, p.minute)
        return if (p.cadence == Cadence.DAILY) "You often ${p.kind.you} around $t. Shall I remind you every day at $t?"
        else {
            val day = DAY_NAMES[p.calendarDay] ?: "that day"
            "You often ${p.kind.you} on ${day}s around $t. Shall I remind you every $day at $t?"
        }
    }

    /** A SPEAK-ONLY goal: the scheduler says "Reminder: you usually check your airtime balance around now. Just ask if you
     *  want it." (TriggerSafety.reminderLine) and never hands a "remind me …" goal to the agent. */
    fun reminderGoal(p: Pattern): String = "remind me that I usually ${p.kind.me} around now. Just ask if you want it"

    /** The recurring A-13 schedule a yes saves. The id is per pattern, so saying yes again replaces rather than doubles. */
    fun schedule(p: Pattern): Schedule = Schedule(
        id = "habit-" + p.key.replace('@', '-'),
        goal = reminderGoal(p), hour = p.hour, minute = p.minute,
        days = if (p.cadence == Cadence.DAILY) emptySet() else setOf(p.calendarDay)
    )

    fun yesLine(s: Schedule): String = "Okay. I'll remind you ${ScheduledTasks.whenPhrase(s)}. It's only a reminder — I won't do it by myself."
    const val NO_LINE = "Okay, I won't ask about that again."
    const val LATER_LINE = "Okay, maybe another time."
}

/** An offer to make now: the spoken [question] about [pattern]. */
data class HabitOffer(val pattern: RepeatDetector.Pattern, val question: String,
                      /** Set for the memory's ask-once question ("Should I remember that 'my wife' means Pearl?", memory/MemoryLearning),
                       *  which rides this same one-question-after-a-task slot; [pattern] is then only a placeholder. */
                      val memoryOfferId: String? = null)

/** The voice hook's view of habits (VoiceController). [Habits] is the real one; tests use a fake. */
interface HabitHost {
    /** A request finished successfully: note it (allow-listed kinds only). Returns the offer to make right now — at most
     *  one a day — or null. [canAsk] false (a typed request, replies not read aloud) only notes it. */
    fun completed(goal: String, fastIntent: String?, now: Long, canAsk: Boolean): HabitOffer?
    /** The user's answer to [offer]: YES saves the speak-only reminder, NO is remembered forever. Returns the line to say. */
    fun answered(offer: HabitOffer, answer: RepeatDetector.Answer): String
}
