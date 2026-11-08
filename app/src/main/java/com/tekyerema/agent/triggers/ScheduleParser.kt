package com.tekyerema.agent.triggers

import java.util.Calendar

/**
 * A-13 — turns a spoken scheduling request into a [Schedule]. Pure (no clock, no Android), unit-tested.
 *
 *   "every day at 7 read my calendar"              → daily 07:00, "read my calendar"
 *   "every weekday at 6:30 am tell me the weather" → Mon–Fri 06:30, "tell me the weather"
 *   "at 9 pm remind me to take my medicine"        → one-off 21:00, "remind me to take my medicine"
 *   "remind me every day at 9 p.m. to take my pills", "read my messages on Mondays and Fridays at 5 pm",
 *   "every night at 9 …" (→ 21:00), "at noon …", "at 7 in the evening …"
 *
 * Returns null when there is no clock time, the time is impossible (25:00, 13 pm), nothing is left as the goal, or
 * the phrase needs a calendar date ("tomorrow …") — the caller should then ask again rather than guess.
 * A bare hour without am/pm is read on the 24-hour clock ("at 7" = 07:00, "at 19" = 19:00) unless a day-part
 * says otherwise ("every evening at 7" = 19:00).
 */
object ScheduleParser {
    val WEEKDAYS: Set<Int> = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    val WEEKEND: Set<Int> = setOf(Calendar.SATURDAY, Calendar.SUNDAY)

    private val DAY_INDEX = mapOf(
        "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY, "sunday" to Calendar.SUNDAY
    )
    private const val DAY = "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?"
    private val I = RegexOption.IGNORE_CASE

    private val DAILY = Regex("\\b(?:every\\s*day|daily|each\\s+day|every\\s+(morning|afternoon|evening|night))\\b", I)
    private val WEEKDAY_RX = Regex("\\b(?:(?:every|on|each)\\s+weekdays?|weekdays)\\b", I)
    private val WEEKEND_RX = Regex("\\b(?:(?:every|on|each|at)\\s+(?:the\\s+)?weekends?|weekends)\\b", I)
    private val DAYS_RX = Regex("\\b(?:every|on|each)\\s+($DAY(?:\\s*(?:,\\s*and|,|and|&)\\s*$DAY)*)\\b", I)
    private val DAY_TOKEN = Regex("monday|tuesday|wednesday|thursday|friday|saturday|sunday", I)

    /** "at 7", "at 7:30", "at 7.30", "at 7 30", "at 6:30 am", "at 9 p.m.", "at 7 o'clock", "at 19:00". */
    private val TIME_RX = Regex(
        "\\bat\\s+(\\d{1,2})(?:(?:[:.]|\\s)(\\d{2}))?\\b(?:\\s*([ap])\\.?\\s?m\\b\\.?)?(?:\\s*o'?\\s?clock\\b)?", I
    )
    private val NOON_RX = Regex("\\bat\\s+(noon|midday|midnight)\\b", I)
    private val PART_RX = Regex(
        "\\b(in the morning|this morning|in the afternoon|this afternoon|in the evening|this evening|at night|tonight)\\b", I
    )
    private val NEEDS_DATE = Regex("\\b(?:tomorrow|next week|next month|on the \\d{1,2}(?:st|nd|rd|th)?)\\b", I)
    private val SET_REMINDER = Regex("^(?:set|create|add|make)\\s+(?:a|an|me a)\\s+reminder\\s+(to|about|for|that)\\s+", I)
    private val LEADING_JUNK = Regex("^(?:[,;:.!?\\-–—]+|(?:and|then|please|kindly|also|hey tekyerema|tekyerema|tɛkyerɛma|(?:hey|okay|ok) nexus|nexus)\\b)\\s*", I)
    private val TRAILING_JUNK = Regex("\\s*(?:[,;:\\-–—]+|\\b(?:and|then|please))$", I)

    fun parse(text: String): Schedule? {
        val t = TriggerSafety.clean(text).replace(Regex("(?i)(\\d)\\s*([ap])\\.?\\s?m\\.?(?![a-z])"), "$1 $2m")
        if (t.isBlank() || NEEDS_DATE.containsMatchIn(t)) return null
        val removed = BooleanArray(t.length)
        fun cut(r: IntRange) { for (i in r) if (i in removed.indices) removed[i] = true }

        // ---- recurrence (none ⇒ one-off)
        var days: Set<Int>? = null
        var hint: String? = null
        DAILY.find(t)?.let { m -> days = emptySet(); hint = m.groupValues[1].ifEmpty { null }; cut(m.range) }
        if (days == null) WEEKDAY_RX.find(t)?.let { m -> days = WEEKDAYS; cut(m.range) }
        if (days == null) WEEKEND_RX.find(t)?.let { m -> days = WEEKEND; cut(m.range) }
        if (days == null) DAYS_RX.find(t)?.let { m ->
            days = DAY_TOKEN.findAll(m.groupValues[1]).mapNotNull { DAY_INDEX[it.value.lowercase()] }.toSet()
            cut(m.range)
        }

        // ---- day-part hint ("tonight", "in the evening")
        PART_RX.find(t)?.let { m -> if (hint == null) hint = m.groupValues[1]; cut(m.range) }

        // ---- clock time (the earliest one wins)
        val tm = TIME_RX.find(t)
        val noon = NOON_RX.find(t)
        var hour: Int
        val minute: Int
        if (tm != null && (noon == null || tm.range.first < noon.range.first)) {
            hour = tm.groupValues[1].toInt()
            minute = tm.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = tm.groupValues[3].lowercase()
            if (ap.isNotEmpty()) {
                if (hour !in 1..12) return null
                hour = hour % 12 + if (ap == "p") 12 else 0
            } else hour = applyHint(hour, hint)
            cut(tm.range)
        } else if (noon != null) {
            hour = if (noon.groupValues[1].equals("midnight", true)) 0 else 12
            minute = 0
            cut(noon.range)
        } else return null
        if (hour !in 0..23 || minute !in 0..59) return null

        // ---- the goal is what's left
        val rest = String(CharArray(t.length) { if (removed[it]) ' ' else t[it] })
        val goal = cleanupGoal(rest)
        if (goal.length < 2 || goal.none { it.isLetter() }) return null

        val d = days ?: emptySet()
        val once = days == null
        val id = "sch-" + Integer.toHexString("${goal.lowercase()}|$hour|$minute|${d.sorted()}|$once".hashCode())
        return Schedule(id = id, goal = goal, hour = hour, minute = minute, days = d, once = once)
    }

    /** No am/pm given: read the hour through a day-part word, else on the 24-hour clock. */
    private fun applyHint(hour: Int, hint: String?): Int {
        val h = hint?.lowercase() ?: return hour
        return when {
            "evening" in h || "night" in h -> when (hour) { in 5..11 -> hour + 12; 12 -> 0; else -> hour }
            "afternoon" in h -> if (hour in 1..6) hour + 12 else hour
            "morning" in h -> if (hour == 12) 0 else hour
            else -> hour
        }
    }

    private fun cleanupGoal(s: String): String {
        var g = s.replace(Regex("\\s+"), " ").trim().replace(Regex("\\s+([,.;:!?])"), "$1")
        while (true) {
            val next = g.replace(LEADING_JUNK, "").replace(TRAILING_JUNK, "").trim()
            if (next == g) break
            g = next
        }
        g = g.replace(SET_REMINDER) { m -> "remind me " + (if (m.groupValues[1].equals("for", true)) "about" else m.groupValues[1].lowercase()) + " " }
        return g.trim().trimEnd('.', '!', '?', ',', ' ')
    }
}
