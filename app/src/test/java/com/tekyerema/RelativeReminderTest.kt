package com.tekyerema

import com.tekyerema.agent.RelativeReminder
import com.tekyerema.agent.RelativeReminder.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** A-13: "remind me in 20 minutes…", "tomorrow at 7…" — pinned one-offs, read back with the wait AND the clock time. */
class RelativeReminderTest {
    private val utc = TimeZone.getTimeZone("UTC")
    /** 28 Sep 2026, 15:25:00 UTC. */
    private val now = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.SEPTEMBER, 28, 15, 25, 0) }.timeInMillis

    private fun ok(text: String) = (RelativeReminder.parse(text, now, utc) as? Result.Ok)?.parsed
        ?: throw AssertionError("not a reminder: $text → ${RelativeReminder.parse(text, now, utc)}")

    @Test fun in_n_minutes_is_pinned_and_read_back_with_the_clock_time() {
        val p = ok("Remind me in 20 minutes to check the food.")
        assertEquals(now + 20 * 60_000L, p.schedule.fireAt)
        assertEquals(15, p.schedule.hour); assertEquals(45, p.schedule.minute)
        assertTrue(p.schedule.once)
        assertEquals("remind me to check the food", p.schedule.goal)
        assertEquals("In 20 minutes, at 3:45 PM: remind me to check the food.", p.spoken)
    }

    @Test fun the_ways_people_say_a_wait() {
        assertEquals(30, (ok("in half an hour remind me to call Ama").schedule.fireAt - now) / 60_000L)
        assertEquals(90, (ok("remind me to take my pills in an hour and a half").schedule.fireAt - now) / 60_000L)
        assertEquals(90, (ok("remind me in one and a half hours to rest").schedule.fireAt - now) / 60_000L)
        assertEquals(135, (ok("remind me in 2 hours and 15 minutes to call Kofi").schedule.fireAt - now) / 60_000L)
        assertEquals(25, (ok("remind me in twenty five minutes to go").schedule.fireAt - now) / 60_000L)
        assertEquals(60, (ok("remind me in an hour to check the stove").schedule.fireAt - now) / 60_000L)
        val p = ok("set a reminder for 10 minutes from now to turn off the stove")
        assertEquals(10, (p.schedule.fireAt - now) / 60_000L)
        assertEquals("remind me to turn off the stove", p.schedule.goal)
        assertEquals("In an hour and a half, at 4:55 PM: remind me to take my pills.",
            ok("remind me to take my pills in an hour and a half").spoken)
    }

    @Test fun tomorrow_at_a_time_is_the_next_day() {
        val p = ok("tomorrow at 7 remind me to take my medicine")
        val c = Calendar.getInstance(utc).apply { timeInMillis = p.schedule.fireAt }
        assertEquals(29, c.get(Calendar.DAY_OF_MONTH)); assertEquals(7, c.get(Calendar.HOUR_OF_DAY)); assertEquals(0, c.get(Calendar.MINUTE))
        assertEquals("Tomorrow at 7 AM: remind me to take my medicine.", p.spoken)
        assertEquals(19, ok("remind me tomorrow evening at 7 to call Ama").schedule.hour)
    }

    @Test fun missing_parts_are_asked_for_not_guessed() {
        assertTrue(RelativeReminder.parse("remind me in 20 minutes", now, utc) is Result.Ask)
        assertTrue(RelativeReminder.parse("remind me tomorrow to call Ama", now, utc) is Result.Ask)
        assertTrue(RelativeReminder.parse("remind me in 30 hours to call Ama", now, utc) is Result.Ask)
    }

    @Test fun clock_times_and_other_words_are_left_to_the_schedule_parser() {
        assertNull(RelativeReminder.parse("at 9 pm remind me to take my medicine", now, utc))
        assertNull(RelativeReminder.parse("every day at 7 read my calendar", now, utc))
        assertNull(RelativeReminder.parse("remind me to buy 2 hours of airtime", now, utc))
    }

    @Test fun spoken_waits() {
        assertEquals("1 minute", RelativeReminder.spokenWait(1))
        assertEquals("an hour", RelativeReminder.spokenWait(60))
        assertEquals("2 hours and 15 minutes", RelativeReminder.spokenWait(135))
    }
}
