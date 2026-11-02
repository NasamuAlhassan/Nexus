package com.tekyerema.companion

import com.tekyerema.agent.triggers.QuietHours
import com.tekyerema.agent.triggers.Schedule
import com.tekyerema.agent.triggers.ScheduleParser
import com.tekyerema.agent.triggers.ScheduledTasks

/**
 * The morning brief on a clock (MASTER_STATUS #21): the user opts in by voice — "brief me every morning at 7", "every day at
 * 7 brief me", "give me my morning brief every weekday at 6:30" — and says "stop the morning brief" to end it. It rides the
 * existing scheduled-task machinery (A-13: [ScheduledTasks], [ScheduleParser], TriggerRunner); this file is only the WORDS and
 * the pure rules, unit-tested (CompanionBriefScheduleTest).
 *
 * Findings that shaped it: "every day at 7 brief me" already parsed into a schedule whose goal is "brief me", but at its time
 * that goal is not a read-only one, so the schedule only said "It's time: brief me." — [isBriefGoal] is what lets the trigger
 * recognise the goal and read the real brief ([CompanionRuntime.scheduledBrief]). Safety is the schedule's own: never inside quiet
 * hours (unless the user set the time inside them — the existing consent rule), never while the user is on a call, in a task, a
 * voice turn or a money step (TriggerGate.userBusy → retried a few minutes later, then left in the notification shade).
 *
 * A single brief schedule ([ID]): saying a new time replaces the old one.
 */
object BriefSchedule {
    const val ID = "companion-brief"
    /** The goal every brief schedule carries; [isBriefGoal] also accepts the wordings of [CompanionIntents.Kind.BRIEF]. */
    const val GOAL = "brief me"
    /** "brief me every morning" with no hour: this one (spoken back, so the user can change it). */
    const val DEFAULT_HOUR = 7

    /** [timeGiven] = false: the user named no hour and [DEFAULT_HOUR] was used. */
    data class Plan(val schedule: Schedule, val timeGiven: Boolean)

    /** Is this schedule goal a request for the day brief? ("brief me", "my morning brief", "what's my day like" …) */
    fun isBriefGoal(goal: String): Boolean = CompanionIntents.isBrief(goal)

    private val BRIEFISH = Regex("brief|rundown|run down|my day|catch me up|run me through|walk me through|take me through")
    private val LEAD_CAN = Regex("^(?:(?:can|could|would|will) you|i (?:want|need) you to|i(?:'d| would) like you to) ")
    private val LEAD_SETUP = Regex("^(?:set up|setup|schedule|start|arrange|turn on|switch on|set)(?: for me)? (?:a |an |the |my |me a |me an )?")

    /** "brief me every morning at 7" → the schedule; null when it is not a request to schedule the brief. */
    fun parseSet(raw: String): Plan? {
        val g = CompanionIntents.core(raw)
        if (g.isEmpty() || !BRIEFISH.containsMatchIn(g)) return null
        if (isOff(g)) return null
        var given = true
        var p = ScheduleParser.parse(g)
        if (p == null) { p = ScheduleParser.parse("$g at $DEFAULT_HOUR"); given = false }
        // A one-off ("brief me at 7") is a plain schedule; only a recurring one is the morning brief.
        if (p == null || p.once) return null
        val goal = p.goal.lowercase().trim()
        val stripped = goal.replace(LEAD_CAN, "").replace(LEAD_SETUP, "").trim()
        if (!isBriefGoal(goal) && !isBriefGoal(stripped)) return null
        return Plan(p.copy(id = ID, goal = GOAL, enabled = true, once = false, allowQuiet = false, fireAt = 0L), given)
    }

    private val OFF = listOf(
        Regex("^(?:stop|cancel|end|remove|delete|turn off|switch off|no more|quit|drop|skip) (?:the |my |that |this |those |all )?" +
            "(?:daily |morning |day |every ?day |every ?morning |scheduled |regular )(?:brief|briefs|briefing|briefings)" +
            "(?: (?:every ?day|every ?morning|daily|each (?:day|morning)))?(?: any ?more)?$"),
        Regex("^(?:cancel|remove|delete|turn off|switch off) (?:the |my )?(?:brief|briefing)$"),
        Regex("^(?:stop|quit|don'?t|do not) (?:briefing|brief|giving|give|reading|read) me(?: (?:my |the )?(?:brief|briefing|day))?" +
            " (?:every ?day|every ?morning|daily|each (?:day|morning)|in the morning)(?: any ?more)?$"),
        Regex("^(?:i )?(?:don'?t|do not) (?:want|need) (?:the |my |a )?(?:daily |morning |day )(?:brief|briefing)s?(?: any ?more)?$")
    )

    /** "stop the morning brief" — [core] is [CompanionIntents.core] of what was said. */
    fun isOff(core: String): Boolean = OFF.any { it.matches(core) }

    /** The brief schedules the user has (the one this file makes, and any older "every day at 7 brief me" kind). */
    fun mine(all: List<Schedule>): List<Schedule> = all.filter { !it.id.startsWith("cfg:") && (it.id == ID || isBriefGoal(it.goal)) }

    /** What is said when the brief is set. [quietSpec]: `triggers.quietHours` (the note is only for a time inside it). */
    fun confirmLine(plan: Plan, quietSpec: String = QuietHours.configuredSpec()): String {
        val s = plan.schedule
        val whenSaid = ScheduledTasks.whenPhrase(s)
        val base = "Okay. I'll give you your brief $whenSaid."
        val picked = if (plan.timeGiven) "" else " I chose ${ScheduledTasks.timePhrase(s.hour, s.minute)}; to change it, say “brief me every morning at” and the time you want."
        val quiet = if (QuietHours.contains(QuietHours.parse(quietSpec), s.hour * 60 + s.minute))
            " That is inside your quiet hours, but you chose it, so I will still tell you then." else ""
        return "$base$picked$quiet You can say “stop the morning brief” any time."
    }

    const val OFF_LINE = "Okay. No more morning brief."
    const val NONE_LINE = "You don't have a morning brief set."
}
