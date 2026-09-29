package com.tekyerema

import com.tekyerema.agent.habits.HabitOffer
import com.tekyerema.agent.habits.Habits
import com.tekyerema.agent.habits.RepeatDetector
import com.tekyerema.agent.habits.RepeatDetector.Answer
import com.tekyerema.agent.habits.RepeatDetector.Cadence
import com.tekyerema.agent.habits.RepeatDetector.Event
import com.tekyerema.agent.habits.RepeatDetector.Kind
import com.tekyerema.agent.habits.RepeatDetector.Memory
import com.tekyerema.agent.triggers.FireDecision
import com.tekyerema.agent.triggers.Schedule
import com.tekyerema.agent.triggers.ScheduledTasks
import com.tekyerema.agent.triggers.TriggerSafety
import com.tekyerema.core.config.AppConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.TimeZone

/** "You ask this every Friday — shall I remind you?" — agent/habits: kinds (the consequential filter), week and day
 *  patterns, time zones, offering once, the declined memory, and a yes that only ever saves a SPOKEN reminder. */
class RepeatDetectorTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    private val accra: TimeZone = TimeZone.getTimeZone("Africa/Accra")

    private fun at(date: LocalDate, h: Int, m: Int, zone: TimeZone = accra): Long =
        ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, h, m, 0, 0, zone.toZoneId()).toInstant().toEpochMilli()

    private val friday: LocalDate = LocalDate.of(2026, 10, 2)
    private fun ev(kind: Kind, date: LocalDate, h: Int, m: Int, zone: TimeZone = accra) = Event(kind.id, at(date, h, m, zone))

    // ------------------------------------------------------------ what may ever be noted

    @Test fun reads_and_info_requests_are_habit_kinds() {
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        mapOf(
            "What's the time?" to Kind.TIME, "what time is it" to Kind.TIME,
            "check my airtime balance" to Kind.AIRTIME, "How much credit do I have?" to Kind.AIRTIME, "airtime balance" to Kind.AIRTIME,
            "what's the weather like today" to Kind.WEATHER, "will it rain today" to Kind.WEATHER,
            "what's the news" to Kind.NEWS, "read me the headlines" to Kind.NEWS,
            "read my messages" to Kind.MESSAGES, "what did I miss" to Kind.MESSAGES,
            "play some music" to Kind.MUSIC, "Please, what's my battery level?" to Kind.BATTERY,
            "Hey Tɛkyerɛma, what's the time" to Kind.TIME
        ).forEach { (goal, kind) -> assertEquals(goal, kind, RepeatDetector.kindOf(goal)) }
        // The fast path's read-only intents count whatever the words (Twi "bere bɛn" = what time).
        assertEquals(Kind.TIME, RepeatDetector.kindOf("bere bɛn", "TIME"))
        assertEquals(Kind.MUSIC, RepeatDetector.kindOf("play Sarkodie on YouTube Music", "PLAY_MUSIC"))
        assertEquals(Kind.MESSAGES, RepeatDetector.kindOf("catch me up", "DIGEST"))
    }

    @Test fun money_sending_calling_deleting_and_compounds_are_never_noted() {
        listOf(
            "send 50 cedis to Ama", "check my MoMo balance", "what's my mobile money balance", "buy airtime",
            "buy airtime for 5 cedis", "top up my airtime", "call Kofi", "delete my messages", "text Ama",
            "read my messages and reply to Ama", "transfer money to Kwame", "play music and call mum", "send my location",
            "remind me to call Ama", "message Ama what's the time", "turn on the flashlight", "open WhatsApp",
            "what's the time then call Ama", "set an alarm for 6", "pay the ECG bill", "check my bank balance", ""
        ).forEach { assertNull("never a habit: '$it'", RepeatDetector.kindOf(it)) }
        // A consequential word vetoes even a read-only fast intent; other fast intents are not habits.
        assertNull(RepeatDetector.kindOf("call Ama", "TIME"))
        assertNull(RepeatDetector.kindOf("open WhatsApp", "OPEN_APP"))
        assertNull(RepeatDetector.kindOf("turn on the torch", "FLASHLIGHT_ON"))
        assertNull(RepeatDetector.kindOf("emergency", "EMERGENCY_LINE"))
    }

    // ------------------------------------------------------------ patterns

    @Test fun the_same_request_on_three_fridays_around_9_is_a_weekly_habit() {
        val history = listOf(ev(Kind.AIRTIME, friday.minusWeeks(2), 9, 5), ev(Kind.AIRTIME, friday.minusWeeks(1), 8, 50))
        val now = ev(Kind.AIRTIME, friday, 9, 10)
        val p = RepeatDetector.detect(history, now, accra)
        assertNotNull(p); p!!
        assertEquals(Cadence.WEEKLY, p.cadence)
        assertEquals(Calendar.FRIDAY, p.calendarDay)
        assertEquals(9 to 0, p.hour to p.minute)   // the middle time, to the quarter hour
        assertEquals("You often check your airtime balance on Fridays around 9 AM. Shall I remind you every Friday at 9 AM?",
            RepeatDetector.offerLine(p))
    }

    @Test fun two_weeks_other_hours_other_days_or_other_kinds_are_not_a_habit() {
        val now = ev(Kind.AIRTIME, friday, 9, 10)
        assertNull("two weeks only", RepeatDetector.detect(listOf(ev(Kind.AIRTIME, friday.minusWeeks(1), 9, 0)), now, accra))
        assertNull("one Friday at 11:00 is outside 90 minutes", RepeatDetector.detect(
            listOf(ev(Kind.AIRTIME, friday.minusWeeks(2), 9, 0), ev(Kind.AIRTIME, friday.minusWeeks(1), 11, 0)), now, accra))
        assertNull("Thursdays are not Fridays", RepeatDetector.detect(
            listOf(ev(Kind.AIRTIME, friday.minusWeeks(2).minusDays(1), 9, 0), ev(Kind.AIRTIME, friday.minusWeeks(1).minusDays(1), 9, 0)), now, accra))
        assertNull("the weather is not the airtime", RepeatDetector.detect(
            listOf(ev(Kind.WEATHER, friday.minusWeeks(2), 9, 0), ev(Kind.WEATHER, friday.minusWeeks(1), 9, 0)), now, accra))
        assertNull("twice on the same Friday is still one week", RepeatDetector.detect(
            listOf(ev(Kind.AIRTIME, friday.minusWeeks(1), 9, 0), ev(Kind.AIRTIME, friday.minusWeeks(1), 9, 30)), now, accra))
        assertNull("a routine dropped months ago is not a habit now", RepeatDetector.detect(
            listOf(ev(Kind.AIRTIME, friday.minusWeeks(12), 9, 0), ev(Kind.AIRTIME, friday.minusWeeks(11), 9, 0)), now, accra))
    }

    @Test fun four_of_the_last_seven_days_at_a_similar_hour_is_a_daily_habit() {
        val now = ev(Kind.TIME, friday, 7, 5)
        val three = listOf(ev(Kind.TIME, friday.minusDays(1), 6, 55), ev(Kind.TIME, friday.minusDays(3), 7, 20))
        assertNull("three days is not enough", RepeatDetector.detect(three, now, accra))
        val four = three + ev(Kind.TIME, friday.minusDays(6), 7, 0)
        val p = RepeatDetector.detect(four, now, accra)!!
        assertEquals(Cadence.DAILY, p.cadence)
        assertEquals(7 to 0, p.hour to p.minute)
        assertEquals("You often ask me the time around 7 AM. Shall I remind you every day at 7 AM?", RepeatDetector.offerLine(p))
        assertNull("a week ago is outside the last seven days",
            RepeatDetector.detect(three + ev(Kind.TIME, friday.minusDays(7), 7, 0), now, accra))
    }

    @Test fun days_and_hours_are_the_phones_wall_clock_across_a_daylight_saving_change() {
        // London leaves summer time on 25 Oct 2026: 9:00 is 08:00Z before and 09:00Z after — still "Fridays at 9".
        val london = TimeZone.getTimeZone("Europe/London")
        val f = LocalDate.of(2026, 10, 30)
        val history = listOf(ev(Kind.NEWS, f.minusWeeks(2), 9, 0, london), ev(Kind.NEWS, f.minusWeeks(1), 9, 0, london))
        val p = RepeatDetector.detect(history, ev(Kind.NEWS, f, 9, 0, london), london)!!
        assertEquals(Calendar.FRIDAY, p.calendarDay)
        assertEquals(9 to 0, p.hour to p.minute)
    }

    @Test fun the_same_moments_in_another_time_zone_fall_on_another_day() {
        // Fridays 23:30 in Accra are Saturdays 02:30 in Nairobi (UTC+3).
        val nairobi = TimeZone.getTimeZone("Africa/Nairobi")
        val history = listOf(ev(Kind.MUSIC, friday.minusWeeks(2), 23, 30), ev(Kind.MUSIC, friday.minusWeeks(1), 23, 30))
        val now = ev(Kind.MUSIC, friday, 23, 30)
        assertEquals(Calendar.FRIDAY, RepeatDetector.detect(history, now, accra)!!.calendarDay)
        val there = RepeatDetector.detect(history, now, nairobi)!!
        assertEquals(Calendar.SATURDAY, there.calendarDay)
        assertEquals(2 to 30, there.hour to there.minute)
    }

    @Test fun history_is_bounded_and_decoding_is_tolerant() {
        var h = emptyList<Event>()
        repeat(205) { h = RepeatDetector.record(h, Event("time", 1_000L + it)) }
        assertEquals(RepeatDetector.MAX_HISTORY, h.size)
        assertEquals("the oldest are dropped", 1_005L, h.first().at)
        assertEquals(h, RepeatDetector.decode(RepeatDetector.encode(h)))
        assertTrue(RepeatDetector.decode("not json").isEmpty())
        assertEquals(listOf(Event("time", 5L)), RepeatDetector.decode("""[{"k":"time","t":5},{"k":"send_money","t":6},{"k":"news"}]"""))
    }

    // ------------------------------------------------------------ offering once

    private val weekly = RepeatDetector.Pattern(Kind.AIRTIME, Cadence.WEEKLY, Calendar.FRIDAY, 9, 0)

    @Test fun at_most_one_offer_a_day_and_a_no_is_remembered_forever() {
        val today = 20_000L
        assertTrue(RepeatDetector.mayOffer(weekly, Memory(), today))
        val offered = RepeatDetector.offered(weekly, Memory(), today)
        assertFalse("one offer a day", RepeatDetector.mayOffer(weekly.copy(kind = Kind.NEWS), offered, today))
        val declined = RepeatDetector.answered(weekly, offered, Answer.NO)
        assertFalse(RepeatDetector.mayOffer(weekly, declined, today + 1))
        assertFalse("forever", RepeatDetector.mayOffer(weekly.copy(hour = 10), declined, today + 3650))
        assertTrue("another pattern may still come", RepeatDetector.mayOffer(weekly.copy(kind = Kind.NEWS), declined, today + 1))
        // Saying yes also stops the offers (the reminder exists).
        assertFalse(RepeatDetector.mayOffer(weekly, RepeatDetector.answered(weekly, offered, Answer.YES), today + 30))
    }

    @Test fun no_to_a_daily_reminder_also_covers_that_kinds_weekly_ones() {
        val daily = weekly.copy(cadence = Cadence.DAILY, calendarDay = 0)
        val m = RepeatDetector.answered(daily, RepeatDetector.offered(daily, Memory(), 1L), Answer.NO)
        assertFalse(RepeatDetector.mayOffer(weekly, m, 10L))
    }

    @Test fun an_unanswered_offer_comes_back_once_a_week_later() {
        val m1 = RepeatDetector.answered(weekly, RepeatDetector.offered(weekly, Memory(), 100L), Answer.NONE)
        assertFalse("not the next day", RepeatDetector.mayOffer(weekly, m1, 101L))
        assertTrue("a week later", RepeatDetector.mayOffer(weekly, m1, 107L))
        val m2 = RepeatDetector.offered(weekly, m1, 107L)
        assertFalse("never a third time", RepeatDetector.mayOffer(weekly, m2, 200L))
    }

    @Test fun memory_round_trips() {
        val m = Memory(setOf("news@day6"), setOf("time@daily"), 42L, mapOf("airtime@day6" to (1 to 40L)))
        assertEquals(m, RepeatDetector.decodeMemory(RepeatDetector.encodeMemory(m)))
        assertEquals(Memory(), RepeatDetector.decodeMemory("{broken"))
    }

    // ------------------------------------------------------------ a yes only ever SPEAKS

    @Test fun the_reminder_only_speaks_and_never_runs_the_request() {
        val s = RepeatDetector.schedule(weekly)
        assertEquals(setOf(Calendar.FRIDAY), s.days)
        assertEquals(9 to 0, s.hour to s.minute)
        assertFalse(s.once)
        assertTrue(s.valid)
        assertEquals("habit-airtime-day6", s.id)
        // The scheduler SPEAKS a "remind me …" goal and never hands it to the agent (A-13 FireDecision).
        assertEquals(FireDecision.SPEAK_REMINDER, ScheduledTasks.decide(s, flagOn = true, quietBlocks = false, busy = false, attempt = 0, maxRetries = 5))
        assertEquals("Reminder: you usually check your airtime balance around now. Just ask if you want it.", TriggerSafety.reminderLine(s.goal))
        val daily = RepeatDetector.schedule(weekly.copy(kind = Kind.TIME, cadence = Cadence.DAILY, calendarDay = 0, hour = 7))
        assertTrue(daily.days.isEmpty())
        assertEquals("Okay. I'll remind you every day at 7 AM. It's only a reminder — I won't do it by myself.", RepeatDetector.yesLine(daily))
        for (k in Kind.entries) assertEquals(FireDecision.SPEAK_REMINDER, ScheduledTasks.decide(
            RepeatDetector.schedule(weekly.copy(kind = k)), true, false, false, 0, 5))
    }

    // ------------------------------------------------------------ the Android glue, over in-memory preferences

    private fun habits(saved: MutableList<Schedule>): Habits {
        val dir: File = Files.createTempDirectory("habits").toFile()
        return Habits(FakeContext(dir), zone = { accra }, addSchedule = { s -> saved += s; s },
            findSchedule = { id -> saved.firstOrNull { it.id == id } })
    }

    @Test fun habits_offer_on_the_third_friday_then_a_yes_saves_one_spoken_reminder() {
        val saved = mutableListOf<Schedule>()
        val h = habits(saved)
        assertNull(h.completed("check my airtime balance", null, at(friday.minusWeeks(2), 9, 0), canAsk = true))
        assertNull(h.completed("check my airtime balance", null, at(friday.minusWeeks(1), 9, 5), canAsk = true))
        val offer: HabitOffer = h.completed("How much airtime do I have?", null, at(friday, 9, 10), canAsk = true)!!
        assertTrue(offer.question.startsWith("You often check your airtime balance on Fridays around 9 AM."))
        val line = h.answered(offer, Answer.YES)
        assertEquals(1, saved.size)
        assertEquals(FireDecision.SPEAK_REMINDER, ScheduledTasks.decide(saved[0], true, false, false, 0, 5))
        assertTrue(line.contains("every Friday at 9 AM"))
        // Already reminding: never offered again.
        assertNull(h.completed("check my airtime balance", null, at(friday.plusWeeks(1), 9, 0), canAsk = true))
    }

    @Test fun habits_remember_a_no_and_never_note_money_or_typed_offers() {
        val saved = mutableListOf<Schedule>()
        val h = habits(saved)
        // Money is never noted, so three Fridays of it offer nothing.
        for (w in 2 downTo 0) assertNull(h.completed("send 20 cedis to Ama", null, at(friday.minusWeeks(w.toLong()), 9, 0), canAsk = true))
        // A typed request is noted but not asked about.
        assertNull(h.completed("what's the news", null, at(friday.minusWeeks(2), 18, 0), canAsk = false))
        assertNull(h.completed("what's the news", null, at(friday.minusWeeks(1), 18, 0), canAsk = false))
        assertNull(h.completed("what's the news", null, at(friday, 18, 0), canAsk = false))
        val offer = h.completed("what's the news", null, at(friday.plusWeeks(1), 18, 0), canAsk = true)!!
        assertEquals(RepeatDetector.NO_LINE, h.answered(offer, Answer.NO))
        assertTrue(saved.isEmpty())
        assertNull("no is remembered", h.completed("what's the news", null, at(friday.plusWeeks(2), 18, 0), canAsk = true))
    }

    @Test fun habits_are_off_when_schedules_are_off() {
        AppConfig.loadFrom(listOf("config/habits.json" to """{"habits":{"offers":{"enabled":false}}}"""))
        val h = habits(mutableListOf())
        for (w in 2 downTo 0) assertNull(h.completed("check my airtime balance", null, at(friday.minusWeeks(w.toLong()), 9, 0), canAsk = true))
    }
}
