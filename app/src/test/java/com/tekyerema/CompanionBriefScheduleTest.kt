package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.triggers.FireDecision
import com.tekyerema.agent.triggers.Schedule
import com.tekyerema.agent.triggers.ScheduleParser
import com.tekyerema.agent.triggers.ScheduledTasks
import com.tekyerema.companion.BriefSchedule
import com.tekyerema.companion.CompanionIntents
import com.tekyerema.companion.CompanionIntents.Kind
import com.tekyerema.companion.CompanionSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The opt-in morning brief on a clock (companion/BriefSchedule + the A-13 scheduler): the words, the schedule, the firing rules. */
class CompanionBriefScheduleTest {
    @Before @After fun reset() { CompanionSession.resetForTest() }

    private fun plan(s: String) = BriefSchedule.parseSet(s)

    @Test fun the_plain_phrasings_set_a_daily_brief() {
        for (s in listOf("brief me every morning at 7", "Brief me every day at 7", "every day at 7 brief me",
            "every morning at 7 give me my morning brief", "give me my morning brief every day at 7 am",
            "brief me daily at 7", "please brief me every morning at 7", "set up a morning brief every day at 7",
            "can you brief me every morning at 7", "every morning at 7 tell me about my day", "every day at 7 what's my day like")) {
            val p = plan(s)
            assertNotNull(s, p)
            assertEquals(s, 7, p!!.schedule.hour)
            assertEquals(s, 0, p.schedule.minute)
            assertTrue(s, p.schedule.days.isEmpty())          // every day
            assertFalse(s, p.schedule.once)
            assertEquals(s, BriefSchedule.ID, p.schedule.id)
            assertEquals(s, BriefSchedule.GOAL, p.schedule.goal)
            assertTrue(s, p.timeGiven)
        }
    }

    @Test fun the_time_and_the_days_are_the_users() {
        assertEquals(6 * 60 + 30, plan("brief me every morning at 6:30")!!.schedule.let { it.hour * 60 + it.minute })
        assertEquals(19, plan("brief me every evening at 7")!!.schedule.hour)
        assertEquals(ScheduleParser.WEEKDAYS, plan("brief me every weekday at 7")!!.schedule.days)
        assertEquals(8, plan("brief me every morning at 8 am")!!.schedule.hour)
    }

    @Test fun no_hour_means_the_default_and_says_so() {
        val p = plan("brief me every morning")!!
        assertEquals(BriefSchedule.DEFAULT_HOUR, p.schedule.hour)
        assertFalse(p.timeGiven)
        assertTrue(BriefSchedule.confirmLine(p, "22:00-06:00").contains("I chose 7 AM"))
    }

    @Test fun anything_else_is_not_the_brief() {
        for (s in listOf("brief me", "brief me at 7", "what's my day like", "stop the morning brief",
            "remind me every day at 7 to send a brief report to Ama", "every morning at 7 read my messages",
            "brief me every morning at 7 and call Ama", "every day at 7 call Ama", "keep it brief every day at 7",
            "let's talk every day at 7", "set an alarm for 7 every morning"))
            assertNull(s, plan(s))
    }

    @Test fun stopping_the_brief() {
        for (s in listOf("stop the morning brief", "Stop my daily brief", "cancel the morning brief", "no more morning brief",
            "turn off the daily briefing", "stop briefing me every morning", "don't brief me every morning anymore",
            "I don't want the morning brief anymore", "cancel my brief", "stop the morning brief please"))
            assertEquals(s, Kind.BRIEF_OFF, CompanionIntents.classify(s))
        // a bare "stop" or "stop the brief" (interrupting a reading) is not an end to the schedule
        assertNull(CompanionIntents.classify("stop the brief"))
        assertNull(CompanionIntents.classify("stop"))
    }

    @Test fun classify_sets_and_keeps_the_old_brief() {
        assertEquals(Kind.BRIEF_SET, CompanionIntents.classify("brief me every morning at 7"))
        assertEquals(Kind.BRIEF_SET, CompanionIntents.classify("every day at 7 brief me"))
        assertEquals(Kind.BRIEF, CompanionIntents.classify("brief me"))
        assertEquals(Kind.BRIEF, CompanionIntents.classify("what's my day like"))
        assertNull(CompanionIntents.classify("remind me every day at 7 to send a brief report"))
        // stopping the spending notes in the brief is still the notes switch
        assertEquals(Kind.NOTES_OFF, CompanionIntents.classify("stop the spending notes in the brief"))
    }

    @Test fun the_fast_path_classifier_routes_them_to_companion() {
        fun talk(s: String) = (FastIntentClassifier.classify(s) as? FastIntent.CompanionTalk)?.kind
        assertEquals("brief_set", talk("brief me every morning at 7"))
        assertEquals("brief_off", talk("stop the morning brief"))
        // a reminder that only mentions "brief" is never taken for the morning brief
        assertFalse(FastIntentClassifier.classify("remind me every day at 7 to send a brief report to Ama") is FastIntent.CompanionTalk)
    }

    @Test fun companion_off_leaves_the_words_to_the_schedule_path() {
        CompanionSession.userEnabled = false
        try { assertNull((FastIntentClassifier.classify("brief me every morning at 7") as? FastIntent.CompanionTalk)?.kind) }
        finally { CompanionSession.userEnabled = null }
    }

    // ---------------------------------------------------------------- firing
    private fun s(goal: String) = Schedule(BriefSchedule.ID, goal, 7, 0, emptySet())

    @Test fun a_brief_goal_is_spoken_by_the_brief_not_offered_or_run_by_the_agent() {
        for (g in listOf("brief me", "my morning brief", "give me my morning brief", "tell me about my day", "what's my day like"))
            assertEquals(g, FireDecision.SPEAK_BRIEF, ScheduledTasks.decide(s(g), true, false, false, 0, 5))
        // other read-only goals still run through the agent, reminders still speak
        assertEquals(FireDecision.RUN_READ_ONLY, ScheduledTasks.decide(s("read my messages"), true, false, false, 0, 5))
        assertEquals(FireDecision.SPEAK_REMINDER, ScheduledTasks.decide(s("remind me to take my medicine"), true, false, false, 0, 5))
    }

    @Test fun the_brief_never_fires_in_quiet_hours_or_when_busy() {
        assertEquals(FireDecision.SKIP_QUIET, ScheduledTasks.decide(s("brief me"), true, true, false, 0, 5))
        assertEquals(FireDecision.RETRY_LATER, ScheduledTasks.decide(s("brief me"), true, false, true, 0, 5))
        assertEquals(FireDecision.GIVE_UP_BUSY, ScheduledTasks.decide(s("brief me"), true, false, true, 5, 5))
        assertEquals(FireDecision.SKIP_DISABLED, ScheduledTasks.decide(s("brief me"), false, false, false, 0, 5))
        assertEquals(FireDecision.SKIP_DISABLED, ScheduledTasks.decide(s("brief me").copy(enabled = false), true, false, false, 0, 5))
    }

    @Test fun quiet_hours_block_a_brief_that_lands_there_by_drift() {
        // the existing rule: set for 7:00, delivered at 23:00 (Doze) → blocked; the user's own time inside the window → allowed
        val zone = java.util.TimeZone.getTimeZone("UTC")
        val at23 = java.util.Calendar.getInstance(zone).apply { clear(); set(2026, 10, 3, 23, 0) }.timeInMillis
        assertTrue(com.tekyerema.agent.triggers.QuietHours.blocksSchedule(s("brief me"), at23, zone, "22:00-06:00"))
        assertFalse(com.tekyerema.agent.triggers.QuietHours.blocksSchedule(s("brief me").copy(hour = 23), at23, zone, "22:00-06:00"))
    }

    @Test fun the_confirmation_says_when_and_how_to_stop() {
        val p = plan("brief me every morning at 7")!!
        val line = BriefSchedule.confirmLine(p, "22:00-06:00")
        assertTrue(line, line.contains("every day at 7 AM"))
        assertTrue(line, line.contains("stop the morning brief"))
        assertFalse(line, line.contains("quiet hours"))
        val early = BriefSchedule.confirmLine(plan("brief me every morning at 5")!!, "22:00-06:00")
        assertTrue(early, early.contains("quiet hours"))
    }

    @Test fun mine_finds_the_brief_schedules_only() {
        val all = listOf(
            Schedule(BriefSchedule.ID, "brief me", 7, 0, emptySet()),
            Schedule("sch-old", "give me my morning brief", 8, 0, emptySet()),
            Schedule("sch-r", "remind me to take my medicine", 9, 0, emptySet()),
            Schedule("cfg:x", "brief me", 6, 0, emptySet()))
        assertEquals(listOf(BriefSchedule.ID, "sch-old"), BriefSchedule.mine(all).map { it.id })
    }
}
