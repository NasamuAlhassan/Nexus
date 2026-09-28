package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.triggers.Cooldown
import com.tekyerema.agent.triggers.FireDecision
import com.tekyerema.agent.triggers.NotificationRule
import com.tekyerema.agent.triggers.NotificationTriggers
import com.tekyerema.agent.triggers.Offer
import com.tekyerema.agent.triggers.ProactiveRules
import com.tekyerema.agent.triggers.QuietHours
import com.tekyerema.agent.triggers.Schedule
import com.tekyerema.agent.triggers.ScheduleParser
import com.tekyerema.agent.triggers.ScheduledTasks
import com.tekyerema.agent.triggers.TriggerGate
import com.tekyerema.agent.triggers.TriggerSafety
import com.tekyerema.core.config.AppConfig
import com.tekyerema.notifications.NotificationRecord
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** A-11 / A-12 / A-13 — unattended triggers: safety classifier, schedule parsing/timing, notification rules, quiet hours. */
class TriggersTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    private val accra: TimeZone = TimeZone.getTimeZone("Africa/Accra")

    private fun at(y: Int, month: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(accra).apply { clear(); set(y, month, d, h, min, 0) }.timeInMillis

    private fun cal(millis: Long): Calendar = Calendar.getInstance(accra).apply { timeInMillis = millis }

    // ------------------------------------------------------------ TriggerSafety

    @Test fun read_only_goals_are_allowed() {
        listOf(
            "read my messages",
            "Tell me the time",
            "What’s my battery level?",
            "check my calendar",
            "how many unread messages do I have",
            "remind me to take my medicine",
            "say good morning",
            "read my text messages",
            "tell me who called me",
            "read my missed calls and tell me the time",
            "Please read my notifications",
            "what's on my calendar today and the weather",
            "read my call log"
        ).forEach { assertTrue("should be read-only: $it", TriggerSafety.isReadOnlyGoal(it)) }
    }

    @Test fun consequential_goals_are_denied_including_ones_hidden_behind_a_read() {
        listOf(
            "read my messages and reply to Ama",          // the classic: a read smuggling a reply
            "send Ama a message",
            "call Kofi",
            "check my MoMo balance",
            "tell me my balance",
            "read my messages and message Ama",
            "read my messages then open WhatsApp",
            "read my messages and tell Ama I'm late",
            "read my messages & whatsapp Kofi",
            "check in for my flight",
            "say happy birthday to Ama on WhatsApp",
            "text Ama I'm coming",
            "remind me to call Ama",
            "what time is it? then share my location",
            "check my notifications and pay the ECG bill",
            "buy airtime",
            "delete old messages",
            "Tekyerema, read my SMS and forward them to Kofi",
            "check my notifications and transfer 50 cedis to Kofi",
            "open WhatsApp",
            "",
            "   "
        ).forEach { assertFalse("should be denied: '$it'", TriggerSafety.isReadOnlyGoal(it)) }
    }

    @Test fun reminders_are_spoken_back_to_the_user() {
        assertEquals("It's time to take your medicine.", TriggerSafety.reminderLine("remind me to take my medicine"))
        assertEquals("It's time to call Ama.", TriggerSafety.reminderLine("Remind me to call Ama"))
        assertEquals("Reminder: the meeting with Kofi.", TriggerSafety.reminderLine("remind me about the meeting with Kofi"))
        assertEquals("Good morning.", TriggerSafety.reminderLine("say good morning"))
        assertNull(TriggerSafety.reminderLine("read my messages"))
        assertEquals("It's time: send Ama the money.", TriggerSafety.itsTime("send Ama the money"))
    }

    @Test fun fire_decision_never_runs_anything_consequential() {
        fun s(goal: String, enabled: Boolean = true) = Schedule("x", goal, 7, 0, emptySet(), enabled)
        assertEquals(FireDecision.RUN_READ_ONLY, ScheduledTasks.decide(s("read my messages"), true, false, false, 0, 5))
        assertEquals(FireDecision.SPEAK_REMINDER, ScheduledTasks.decide(s("remind me to take my medicine"), true, false, false, 0, 5))
        assertEquals(FireDecision.SPEAK_REMINDER, ScheduledTasks.decide(s("remind me to call Ama"), true, false, false, 0, 5))
        assertEquals(FireDecision.SPEAK_OFFER, ScheduledTasks.decide(s("send Ama a message"), true, false, false, 0, 5))
        assertEquals(FireDecision.SPEAK_OFFER, ScheduledTasks.decide(s("read my messages and reply to Ama"), true, false, false, 0, 5))
        assertEquals(FireDecision.RETRY_LATER, ScheduledTasks.decide(s("read my messages"), true, false, true, 0, 5))
        assertEquals(FireDecision.GIVE_UP_BUSY, ScheduledTasks.decide(s("read my messages"), true, false, true, 5, 5))
        assertEquals(FireDecision.SKIP_QUIET, ScheduledTasks.decide(s("read my messages"), true, true, false, 0, 5))
        assertEquals(FireDecision.SKIP_DISABLED, ScheduledTasks.decide(s("read my messages"), false, false, false, 0, 5))
        assertEquals(FireDecision.SKIP_DISABLED, ScheduledTasks.decide(s("read my messages", enabled = false), true, false, false, 0, 5))
    }

    @Test fun agent_busy_means_any_non_idle_non_terminal_status() {
        listOf(AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.WAITING_CONFIRMATION, AgentStatus.SECURE_INPUT)
            .forEach { assertTrue(it.name, TriggerGate.isAgentBusy(it)) }
        listOf(AgentStatus.IDLE, AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED, AgentStatus.CANNOT_DO, AgentStatus.FAILED, AgentStatus.STOPPED)
            .forEach { assertFalse(it.name, TriggerGate.isAgentBusy(it)) }
    }

    // ------------------------------------------------------------ ScheduleParser

    @Test fun parses_a_daily_schedule() {
        val s = ScheduleParser.parse("every day at 7 read my calendar")!!
        assertEquals(7, s.hour); assertEquals(0, s.minute)
        assertTrue(s.days.isEmpty()); assertFalse(s.once)
        assertEquals("read my calendar", s.goal)

        val pills = ScheduleParser.parse("Remind me every day at 9 p.m. to take my pills.")!!
        assertEquals(21, pills.hour); assertTrue(pills.days.isEmpty()); assertFalse(pills.once)
        assertEquals("Remind me to take my pills", pills.goal)

        val night = ScheduleParser.parse("every night at 9 tell me my battery")!!
        assertEquals(21, night.hour); assertEquals("tell me my battery", night.goal)
    }

    @Test fun parses_weekdays_and_named_days() {
        val s = ScheduleParser.parse("every weekday at 6:30 am tell me the weather")!!
        assertEquals(6, s.hour); assertEquals(30, s.minute)
        assertEquals(ScheduleParser.WEEKDAYS, s.days); assertFalse(s.once)
        assertEquals("tell me the weather", s.goal)

        val md = ScheduleParser.parse("read my messages on Mondays and Fridays at 5 pm")!!
        assertEquals(setOf(Calendar.MONDAY, Calendar.FRIDAY), md.days)
        assertEquals(17, md.hour); assertEquals("read my messages", md.goal)
    }

    @Test fun parses_a_one_off_with_am_pm() {
        val s = ScheduleParser.parse("at 9 pm remind me to take my medicine")!!
        assertEquals(21, s.hour); assertEquals(0, s.minute)
        assertTrue(s.once); assertTrue(s.days.isEmpty())
        assertEquals("remind me to take my medicine", s.goal)

        val eve = ScheduleParser.parse("at 7 in the evening read my messages")!!
        assertEquals(19, eve.hour); assertTrue(eve.once); assertEquals("read my messages", eve.goal)

        val am = ScheduleParser.parse("at 12 am say goodnight")!!
        assertEquals(0, am.hour)
        assertEquals(12, ScheduleParser.parse("at noon read my messages")!!.hour)
    }

    @Test fun rejects_unparseable_schedules() {
        assertNull(ScheduleParser.parse("read my calendar"))                         // no time
        assertNull(ScheduleParser.parse("every day at 25 read my calendar"))         // impossible hour
        assertNull(ScheduleParser.parse("at 13 pm say hello"))                       // 13 pm
        assertNull(ScheduleParser.parse("every day at 7:75 read my calendar"))       // impossible minute
        assertNull(ScheduleParser.parse("every day at 7"))                           // nothing to do
        assertNull(ScheduleParser.parse(""))
        assertNull(ScheduleParser.parse("tomorrow at 9 remind me to go to church"))  // needs a date, not a clock time
    }

    // ------------------------------------------------------------ ScheduledTasks timing + storage

    @Test fun next_fire_crosses_the_day_boundary() {
        val daily7 = Schedule("d", "read my calendar", 7, 0, emptySet())
        assertEquals(at(2026, Calendar.SEPTEMBER, 29, 7, 0), ScheduledTasks.nextFire(daily7, at(2026, Calendar.SEPTEMBER, 28, 8, 0), accra))
        assertEquals(at(2026, Calendar.SEPTEMBER, 28, 7, 0), ScheduledTasks.nextFire(daily7, at(2026, Calendar.SEPTEMBER, 28, 6, 59), accra))
        // Exactly at the fire time → the NEXT occurrence (never the one firing now).
        assertEquals(at(2026, Calendar.SEPTEMBER, 29, 7, 0), ScheduledTasks.nextFire(daily7, at(2026, Calendar.SEPTEMBER, 28, 7, 0), accra))
        // Late evening across a month boundary.
        val late = Schedule("l", "read my messages", 23, 30, emptySet())
        assertEquals(at(2026, Calendar.OCTOBER, 1, 23, 30), ScheduledTasks.nextFire(late, at(2026, Calendar.SEPTEMBER, 30, 23, 45), accra))
    }

    @Test fun weekday_schedule_on_a_saturday_waits_for_monday() {
        val saturday = at(2026, Calendar.OCTOBER, 3, 9, 0)
        assertEquals(Calendar.SATURDAY, cal(saturday).get(Calendar.DAY_OF_WEEK))
        val weekday = Schedule("w", "tell me the weather", 6, 30, ScheduleParser.WEEKDAYS)
        val next = ScheduledTasks.nextFire(weekday, saturday, accra)
        assertEquals(at(2026, Calendar.OCTOBER, 5, 6, 30), next)
        assertEquals(Calendar.MONDAY, cal(next).get(Calendar.DAY_OF_WEEK))
        // Friday after 6:30 also skips the weekend.
        assertEquals(at(2026, Calendar.OCTOBER, 5, 6, 30), ScheduledTasks.nextFire(weekday, at(2026, Calendar.OCTOBER, 2, 7, 0), accra))
    }

    @Test fun schedules_round_trip_through_json_and_describe_themselves() {
        val list = listOf(
            Schedule("a", "tell me the weather", 6, 30, ScheduleParser.WEEKDAYS),
            Schedule("b", "remind me to take my medicine", 21, 0, emptySet(), once = true, fireAt = 123L),
            Schedule("c", "read my messages", 17, 0, setOf(Calendar.MONDAY, Calendar.FRIDAY), enabled = false, allowQuiet = true)
        )
        assertEquals(list, ScheduledTasks.decode(ScheduledTasks.encode(list)))
        assertTrue(ScheduledTasks.decode("not json").isEmpty())
        assertTrue(ScheduledTasks.decode("""[{"id":"x","goal":"read","hour":30}]""").isEmpty())   // invalid entry skipped
        assertEquals("every weekday at 6:30 AM", ScheduledTasks.whenPhrase(list[0]))
        assertEquals("at 9 PM", ScheduledTasks.whenPhrase(list[1]))
        assertEquals("every Monday and Friday at 5 PM", ScheduledTasks.whenPhrase(list[2]))
        assertEquals("Every day at 7 AM: read my calendar.", ScheduledTasks.describe(Schedule("d", "read my calendar", 7, 0, emptySet())))
    }

    // ------------------------------------------------------------ NotificationTriggers

    private val ama = NotificationRecord(
        key = "k1", packageName = "com.whatsapp", title = "Ama Mensah", text = "Are you coming? It's urgent",
        postTime = 1L, canReply = true, replyAction = null, appLabel = "WhatsApp", sender = "Ama Mensah"
    )

    private fun rule(who: String, contains: String? = null, action: String = "announce") = NotificationRule("r", who, contains, action)

    @Test fun notification_rules_match_by_sender_app_and_contains() {
        assertTrue(NotificationTriggers.matches(rule("Ama"), ama))                 // sender, whole word
        assertFalse(NotificationTriggers.matches(rule("Amadou"), ama))
        assertFalse(NotificationTriggers.matches(rule("Ama"), ama.copy(sender = "Amadou", title = "Amadou")))
        assertTrue(NotificationTriggers.matches(rule("WhatsApp"), ama))            // app name
        assertTrue(NotificationTriggers.matches(rule("com.whatsapp"), ama))        // package id
        assertFalse(NotificationTriggers.matches(rule("Telegram"), ama))
        assertTrue(NotificationTriggers.matches(rule("Ama", contains = "URGENT"), ama))
        assertFalse(NotificationTriggers.matches(rule("Ama", contains = "money"), ama))
        assertTrue(NotificationTriggers.matches(rule("*"), ama))
        assertFalse(NotificationTriggers.matches(rule(" "), ama))
    }

    @Test fun notification_lines_announce_or_read_and_never_more() {
        assertEquals("New message from Ama Mensah on WhatsApp.", NotificationTriggers.announceLine(ama))
        assertEquals("New notification from WhatsApp.", NotificationTriggers.announceLine(ama.copy(sender = null, title = null)))
        assertEquals("Ama Mensah on WhatsApp says: Are you coming? It's urgent", NotificationTriggers.lineFor(rule("Ama", action = "read"), ama))
        assertEquals(NotificationTriggers.announceLine(ama), NotificationTriggers.lineFor(rule("Ama", action = "announce"), ama))
    }

    @Test fun notification_rules_default_to_empty_and_unknown_actions_become_announce() {
        assertTrue(NotificationTriggers.configRules().isEmpty())
        AppConfig.loadFrom(listOf("config/triggers.json" to
            """{"triggers":{"notification":[{"app":"WhatsApp","action":"read"},{"sender":"Ama","action":"reply"},{"action":"announce"}]}}"""))
        val rules = NotificationTriggers.configRules()
        assertEquals(2, rules.size)                                    // the rule with no app/sender is dropped
        assertEquals("read", rules[0].action)
        assertEquals("announce", rules[1].action)                     // "reply" is not an action a trigger can take
        val back = NotificationTriggers.parseRules(JSONArray(NotificationTriggers.encodeRules(rules)))
        assertEquals(rules, back)
    }

    @Test fun cooldown_allows_one_event_per_key_per_window() {
        val c = Cooldown()
        assertTrue(c.tryAcquire("whatsapp|ama", 0L, 60_000L))
        assertFalse(c.tryAcquire("whatsapp|ama", 30_000L, 60_000L))
        assertTrue(c.tryAcquire("whatsapp|kofi", 30_000L, 60_000L))
        assertTrue(c.tryAcquire("whatsapp|ama", 60_000L, 60_000L))
    }

    // ------------------------------------------------------------ Quiet hours

    @Test fun quiet_hours_cross_midnight() {
        val w = QuietHours.parse("22:00-06:00")
        assertNotNull(w)
        assertTrue(QuietHours.contains(w, 23 * 60 + 30))
        assertTrue(QuietHours.contains(w, 0))
        assertTrue(QuietHours.contains(w, 5 * 60 + 59))
        assertFalse(QuietHours.contains(w, 6 * 60))
        assertFalse(QuietHours.contains(w, 12 * 60))
        assertFalse(QuietHours.contains(w, 21 * 60 + 59))
        assertTrue(QuietHours.contains(w, 22 * 60))

        val day = QuietHours.parse("13:00-15:00")
        assertTrue(QuietHours.contains(day, 14 * 60)); assertFalse(QuietHours.contains(day, 15 * 60)); assertFalse(QuietHours.contains(day, 12 * 60 + 59))
        assertNull(QuietHours.parse("off"))
        assertEquals(QuietHours.parse(QuietHours.DEFAULT), QuietHours.parse("nonsense"))   // bad config → the safe default

        assertTrue(QuietHours.isQuiet(at(2026, Calendar.SEPTEMBER, 28, 23, 0), accra, "22:00-06:00"))
        assertFalse(QuietHours.isQuiet(at(2026, Calendar.SEPTEMBER, 28, 12, 0), accra, "22:00-06:00"))
        // Config drives the default spec.
        assertTrue(QuietHours.isQuiet(at(2026, Calendar.SEPTEMBER, 28, 23, 0), accra))
        AppConfig.loadFrom(listOf("config/triggers.json" to """{"triggers":{"quietHours":"off"}}"""))
        assertFalse(QuietHours.isQuiet(at(2026, Calendar.SEPTEMBER, 28, 23, 0), accra))
    }

    @Test fun a_schedule_set_inside_quiet_hours_is_consent_but_a_late_alarm_is_not() {
        val elevenPm = Schedule("m", "remind me to take my medicine", 23, 0, emptySet())
        assertFalse(QuietHours.blocksSchedule(elevenPm, at(2026, Calendar.SEPTEMBER, 28, 23, 0), accra, QuietHours.DEFAULT))
        val ninefiftyfive = Schedule("n", "read my messages", 21, 55, emptySet())
        assertTrue(QuietHours.blocksSchedule(ninefiftyfive, at(2026, Calendar.SEPTEMBER, 28, 22, 10), accra, QuietHours.DEFAULT))
        assertFalse(QuietHours.blocksSchedule(ninefiftyfive.copy(allowQuiet = true), at(2026, Calendar.SEPTEMBER, 28, 22, 10), accra, QuietHours.DEFAULT))
        assertFalse(QuietHours.blocksSchedule(ninefiftyfive, at(2026, Calendar.SEPTEMBER, 28, 21, 55), accra, QuietHours.DEFAULT))
    }

    // ------------------------------------------------------------ ProactiveRules (pure parts)

    @Test fun proactive_offers_match_exact_or_prefix_and_count_days() {
        AppConfig.loadFrom(listOf("config/proactive.json" to
            """{"proactive":{"rules":[{"package":"com.mtn.momo*","line":"Want me to check your MoMo balance?"},{"package":"com.whatsapp","line":"Want me to read your new messages?"},{"package":"","line":"x"}]}}"""))
        val offers = ProactiveRules.offers()
        assertEquals(2, offers.size)
        assertEquals("Want me to check your MoMo balance?", ProactiveRules.find(offers, "com.mtn.momo.app")?.line)
        assertEquals("com.whatsapp", ProactiveRules.find(offers, "com.whatsapp")?.packageName)
        assertNull(ProactiveRules.find(offers, "com.whatsapp.w4b"))
        assertNull(ProactiveRules.find(listOf(Offer("com.a", "x")), "com.b"))
        assertEquals(20260928, ProactiveRules.dayStamp(at(2026, Calendar.SEPTEMBER, 28, 10, 0), accra))
    }
}
