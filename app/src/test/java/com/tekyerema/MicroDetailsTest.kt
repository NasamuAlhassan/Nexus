package com.tekyerema

import com.tekyerema.voice.Closers
import com.tekyerema.voice.Coaching
import com.tekyerema.voice.QuietHours
import com.tekyerema.voice.RepeatIntent
import com.tekyerema.voice.Verbalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** The small human details (docs/agent/research/micro_details.md, quick wins): varied closers, "say that again",
 *  12-hour times, quiet nights, escalating no-input, shrinking coaching. */
class MicroDetailsTest {

    @Before fun fresh() { Coaching.resetForTest() }

    // ---- varied "Done." (win 1)
    @Test fun `done varies and never repeats back to back`() {
        val rnd = Random(7)
        val seen = HashSet<String>()
        var prev = ""
        repeat(30) { val d = Closers.done(twi = false, rnd = rnd); assertFalse(d == prev); prev = d; seen += d }
        assertTrue("should rotate, saw $seen", seen.size >= 3)
        assertTrue(Closers.done(twi = true, rnd = rnd).first().isUpperCase())
    }

    // ---- warm opener (win 8)
    @Test fun `a long task's result gets one warm word, an already-warm line is left alone`() {
        val w = Closers.warm("It's sent.", twi = false, rnd = Random(3))
        assertTrue(w, Regex("^(Alright|Okay|Right) — it's sent\\.$").matches(w))
        assertEquals("Okay, I won't.", Closers.warm("Okay, I won't.", twi = false))
        assertEquals("Sorry, that failed.", Closers.warm("Sorry, that failed.", twi = false))
        assertEquals("", Closers.warm("", twi = false))
    }

    // ---- escalating no-input (win 5)
    @Test fun `no-input escalates then goes quiet`() {
        assertEquals("I didn't hear anything. Tap and try again.", Closers.noInput(1))
        assertTrue(Closers.noInput(2)!!.contains("what time is it"))
        assertNull(Closers.noInput(3))
        assertNull(Closers.noInput(9))
    }

    // ---- "say that again" (win 2)
    @Test fun `repeat is recognised, commands are not`() {
        for (s in listOf("Say that again", "repeat", "Repeat that.", "pardon?", "come again", "what did you say",
            "I didn't catch that", "say it again please"))
            assertTrue(s, RepeatIntent.matches(s))
        for (s in listOf("repeat this song", "read my messages again", "say hello to Ama", "what did Kwame say", ""))
            assertFalse(s, RepeatIntent.matches(s))
    }

    // ---- 12-hour clock (win 3)
    @Test fun `24-hour times are said the human way`() {
        assertTrue(Verbalize.apply("Your alarm is at 13:45."), Verbalize.apply("Your alarm is at 13:45.").contains("one forty five in the afternoon"))
        assertTrue(Verbalize.apply("At 21:00."), Verbalize.apply("At 21:00.").contains("nine at night"))
        assertTrue(Verbalize.apply("At 17:05."), Verbalize.apply("At 17:05.").contains("five oh five in the evening"))
        assertTrue(Verbalize.apply("At 0:07."), Verbalize.apply("At 0:07.").contains("twelve oh seven at night"))
        // 1–12 stays as before — it usually already carries AM or PM around it.
        assertTrue(Verbalize.apply("At 9:30 AM."), Verbalize.apply("At 9:30 AM.").contains("nine thirty"))
        assertFalse(Verbalize.apply("At 9:30 AM."), Verbalize.apply("At 9:30 AM.").contains("night"))
    }

    // ---- quiet nights (win 4)
    @Test fun `night is softer, day is not`() {
        assertEquals(0.65f, QuietHours.factor(hour = 22))
        assertEquals(0.65f, QuietHours.factor(hour = 2))
        assertEquals(1f, QuietHours.factor(hour = 7))
        assertEquals(1f, QuietHours.factor(hour = 12))
        assertEquals(0.65f, QuietHours.factor(hour = 21))
    }

    // ---- shrinking coaching (win 7)
    @Test fun `a coaching tail shrinks after five hearings`() {
        repeat(Coaching.FULL) { assertEquals("full words", Coaching.tail("t1", "full words", "short")) }
        assertEquals("short", Coaching.tail("t1", "full words", "short"))
        // With no short form given, the tail disappears entirely once learned.
        repeat(Coaching.FULL) { assertEquals(" You can say cancel to stop.", Coaching.tail("t2", " You can say cancel to stop.")) }
        assertEquals("", Coaching.tail("t2", " You can say cancel to stop."))
        // The messages tail keeps "say continue" in BOTH forms — it is what reopens the mic.
        Coaching.resetForTest()
        repeat(Coaching.FULL + 2) {
            assertTrue(com.tekyerema.notifications.MessageReading.more(3).contains("say continue"))
        }
    }
}
