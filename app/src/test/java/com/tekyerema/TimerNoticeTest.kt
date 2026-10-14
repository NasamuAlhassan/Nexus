package com.tekyerema

import com.tekyerema.agent.TimerNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Stop the timer" says stopped only when the Clock shows no running timer (A20e, 14 Oct 2026). */
class TimerNoticeTest {
    @Test fun a_timer_still_shown_is_running() {
        assertTrue(TimerNotice.stillRunning(listOf("timer_channel progress Timer 01:42")))
        assertTrue(TimerNotice.stillRunning(listOf("Upcoming alarm 06:00", "Timer")))
    }

    @Test fun nothing_left_or_only_a_stopwatch_or_alarm_is_not_a_timer() {
        assertFalse(TimerNotice.stillRunning(emptyList()))
        assertFalse(TimerNotice.stillRunning(listOf("stopwatch_channel Stopwatch 00:12")))
        assertFalse(TimerNotice.stillRunning(listOf("Upcoming alarm 06:00")))
    }

    @Test fun an_ongoing_clock_notice_we_cannot_read_counts_as_running() {
        // The agent then looks on screen instead of the reply claiming a stop that didn't happen.
        assertTrue(TimerNotice.stillRunning(listOf("default 01:42")))
    }

    @Test fun the_stop_control_is_cancel_or_stop_never_stopwatch_or_pause() {
        val samsung = listOf(1 to "Alarm", 2 to "World clock", 3 to "Stopwatch", 4 to "Timer", 5 to "Pause", 6 to "Cancel")
        org.junit.Assert.assertEquals(6, TimerNotice.stopButton(samsung))
        val google = listOf(1 to "Pause", 2 to "Delete", 3 to "Add 1 minute")
        org.junit.Assert.assertEquals(2, TimerNotice.stopButton(google))
        org.junit.Assert.assertNull(TimerNotice.stopButton(listOf(1 to "Stopwatch", 2 to "Pause", 3 to null)))
    }

    @Test fun the_timer_screen_still_has_a_timer_while_pause_or_resume_shows() {
        assertTrue(TimerNotice.showsRunning(listOf("Cancel", "Pause")))
        assertTrue(TimerNotice.showsRunning(listOf("Cancel", "Resume")))
        assertFalse(TimerNotice.showsRunning(listOf("Start", "Hours", null)))
    }
}
