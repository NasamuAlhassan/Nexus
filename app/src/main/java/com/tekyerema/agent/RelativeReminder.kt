package com.tekyerema.agent

import com.tekyerema.agent.triggers.Schedule
import com.tekyerema.agent.triggers.ScheduleParser
import com.tekyerema.agent.triggers.ScheduledTasks
import com.tekyerema.agent.triggers.TriggerSafety
import java.util.Calendar
import java.util.TimeZone

/**
 * A-13 — the reminders people actually say: "remind me in 20 minutes to check the stove", "in half an hour remind me
 * to call Ama", "tomorrow at 7 remind me to take my medicine". [ScheduleParser] only knew clock times today and
 * repeating days, so the most common reminder of all ("in 20 minutes…") got "tell me the time too". This turns a
 * duration or "tomorrow" into a one-off [Schedule] with its due time already pinned, so the existing trigger path
 * (TriggerReceiver → TriggerSafety.reminderLine) speaks it — a reminder never runs an action by itself.
 *
 * The read-back names BOTH the wait and the clock time ("In 20 minutes, at 3:45 PM: remind me to check the stove."),
 * so a mis-heard "15" vs "50" is caught before the user relies on it. Pure (clock and zone passed in), unit-tested.
 */
object RelativeReminder {
    data class Parsed(val schedule: Schedule, val spoken: String)

    /** Why a reminder couldn't be made from words that clearly asked for one, as a line to speak; null = not ours. */
    sealed class Result {
        data class Ok(val parsed: Parsed) : Result()
        data class Ask(val line: String) : Result()
    }

    private val I = RegexOption.IGNORE_CASE
    private const val NUM = "(\\d{1,3}|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|" +
        "twenty[- ]five|thirty|forty|forty[- ]five|fifty|sixty|ninety)"
    private const val UNIT = "(minutes?|mins?|hours?|hrs?)"
    /** "in 20 minutes", "in an hour", "in 2 hours and 15 minutes", "in an hour and a half", "in half an hour",
     *  "in 20 minutes' time", "20 minutes from now", "after 10 minutes". */
    private val DURATION = Regex(
        "\\b(?:(?:in|after) (?:about |around )?(?:(half an hour)|$NUM(?: and a half)? $UNIT(?: and $NUM $UNIT)?(?: and a half)?)" +
            "(?:'? time| from now)?|(?:(half an hour)|$NUM $UNIT(?: and $NUM $UNIT)?) from now)\\b", I)
    private val TOMORROW = Regex("\\btomorrow\\b", I)
    private val TOMORROW_PART = Regex("\\btomorrow (morning|afternoon|evening|night)\\b", I)
    private val REMIND = Regex("\\bremind me\\b|^(?:set|create|add|make) (?:a|an|me a) reminder\\b", I)
    private val FOR_REMINDER = Regex("^((?:set|create|add|make) (?:a|an|me a) reminder)\\s+for\\s+", I)

    private val WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "twenty five" to 25, "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90)

    private fun num(s: String): Int? = s.trim().lowercase().replace('-', ' ').let { it.toIntOrNull() ?: WORDS[it] }
    private fun unitMin(u: String): Int = if (u.lowercase().startsWith("h")) 60 else 1

    /** Minutes in the matched duration, or null. "an hour and a half" = 90, "2 hours and 15 minutes" = 135. */
    internal fun minutes(m: MatchResult): Int? {
        // Groups: 1 half-an-hour | 2 NUM 3 UNIT [4 NUM 5 UNIT]   (after "in"/"after")
        //         6 half-an-hour | 7 NUM 8 UNIT [9 NUM 10 UNIT]  ("… from now")
        val g = m.groupValues
        if (g[1].isNotEmpty() || g[6].isNotEmpty()) return 30
        val (n1, u1, n2, u2) = if (g[2].isNotEmpty()) listOf(g[2], g[3], g[4], g[5]) else listOf(g[7], g[8], g[9], g[10])
        var total = (num(n1) ?: return null) * unitMin(u1)
        if (n2.isNotEmpty()) total += (num(n2) ?: return null) * unitMin(u2)
        if (m.value.contains("and a half", true)) total += unitMin(u1) / 2   // "an hour and a half" = 90; minutes: no half
        return total
    }

    /**
     * Ok with a pinned one-off, Ask when the words asked for a reminder but something is missing (no goal, "tomorrow"
     * with no time), or null when this isn't a relative/"tomorrow" reminder at all (the caller's clock-time parser runs).
     */
    fun parse(text: String, nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): Result? {
        val t = TriggerSafety.clean(text)
        val d = DURATION.find(t)
        if (d != null) {
            val mins = minutes(d) ?: return null
            if (mins < 1 || mins > 24 * 60)
                return Result.Ask("I can set a reminder from one minute up to a day ahead. Please say it again.")
            val goal = goalOf(t.removeRange(d.range))
                ?: return Result.Ask("What should I remind you about? For example: remind me in $mins minutes to check the food.")
            val at = nowMillis + mins * 60_000L
            val c = Calendar.getInstance(zone).apply { timeInMillis = at }
            val s = schedule(goal, c, at)
            return Result.Ok(Parsed(s, "In ${spokenWait(mins)}, at ${ScheduledTasks.timePhrase(s.hour, s.minute)}: $goal."))
        }
        if (!TOMORROW.containsMatchIn(t)) return null
        // "tomorrow morning at 7" → the clock parser's day-part words; plain "tomorrow" is dropped.
        val rewritten = TOMORROW_PART.replace(t) { m ->
            when (m.groupValues[1].lowercase()) { "night" -> "at night"; else -> "in the " + m.groupValues[1].lowercase() }
        }.replace(TOMORROW, " ").replace(Regex("\\s+"), " ").trim()
        val p = ScheduleParser.parse(rewritten)
            ?: return if (REMIND.containsMatchIn(t)) Result.Ask("What time tomorrow? For example: tomorrow at 7 AM remind me to call Ama.") else null
        if (!p.once) return null   // "every day … tomorrow" is not a one-off; leave it to the clock parser
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, p.hour); set(Calendar.MINUTE, p.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val goal = p.goal.replaceFirstChar { it.lowercaseChar() }
        val s = schedule(goal, c, c.timeInMillis)
        return Result.Ok(Parsed(s, "Tomorrow at ${ScheduledTasks.timePhrase(s.hour, s.minute)}: $goal."))
    }

    private fun schedule(goal: String, c: Calendar, at: Long): Schedule {
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        val id = "sch-" + Integer.toHexString("${goal.lowercase()}|$at".hashCode())
        return Schedule(id = id, goal = goal, hour = h, minute = m, days = emptySet(), once = true, fireAt = at)
    }

    /** What's left once the duration is cut out, tidied: "remind me in 20 minutes to X" → "remind me to X". */
    private fun goalOf(rest: String): String? {
        var g = rest.replace(Regex("\\s+"), " ").trim().trim(',', '.', ' ')
        g = g.replace(FOR_REMINDER) { m -> m.groupValues[1] + " " }   // "set a reminder for [20 minutes] to X"
        g = g.replace(Regex("^(?:and|then|please|kindly)\\s+", I), "").replace(Regex("\\s+(?:please)$", I), "").trim()
        g = g.replace(Regex("^((?:set|create|add|make) (?:a|an|me a) reminder)\\s+(to|about|that)\\s+", I)) { m ->
            "remind me " + m.groupValues[2].lowercase() + " "
        }
        // Nothing but the cue words left ("remind me", "set a reminder") → there is no goal.
        val bare = g.replace(Regex("^(?:remind me|(?:set|create|add|make) (?:a|an|me a) reminder)$", I), "").trim()
        if (bare.isEmpty() || g.none { it.isLetter() }) return null
        // "Remind me…" said first in the sentence: the read-back quotes it mid-sentence, so lower-case its first letter.
        return g.trim().trimEnd('.', '!', '?', ',').replaceFirstChar { it.lowercaseChar() }
    }

    /** "20 minutes", "an hour", "2 hours and 15 minutes" — also the battery's time-to-full. */
    internal fun spokenWait(mins: Int): String {
        val h = mins / 60; val m = mins % 60
        fun p(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
        return when {
            h == 0 -> p(m, "minute")
            m == 0 -> if (h == 1) "an hour" else p(h, "hour")
            m == 30 && h == 1 -> "an hour and a half"
            else -> p(h, "hour") + " and " + p(m, "minute")
        }
    }
}
