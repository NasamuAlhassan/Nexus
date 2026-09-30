package com.tekyerema

import com.tekyerema.money.PinPractice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Settings › Practise typing a PIN (payment-safety research #10). */
class PinPracticeTest {

    @Test fun practice_numbers_never_look_like_a_real_pin_habit() {
        val r = Random(7)
        repeat(2000) {
            val t = PinPractice.target(r)
            assertEquals(4, t.length)
            assertTrue(t, t.all { it.isDigit() })
            assertTrue("at least three different digits: $t", t.toSet().size >= 3)
            assertFalse("no 1234-style run: $t", t in setOf("0123", "1234", "2345", "3456", "4567", "5678", "6789",
                "9876", "8765", "7654", "6543", "5432", "4321", "3210"))
        }
    }

    @Test fun lines_read_digits_one_by_one() {
        assertEquals("Type 4 7 2 9.", PinPractice.prompt("4729"))
        assertEquals("2 of 4 typed.", PinPractice.progress("47"))
    }

    @Test fun the_verdict_coaches_speed_and_says_what_was_typed() {
        val quick = PinPractice.check("4729", "4729", 6_200)
        assertTrue(quick.correct); assertTrue(quick.line, quick.line.startsWith("Correct, in 6.2 seconds."))
        val slow = PinPractice.check("4729", "4729", 14_000)
        assertTrue(slow.correct); assertTrue(slow.line, slow.line.contains("aim for under 10 seconds"))
        val wrong = PinPractice.check("4729", "4719", 5_000)
        assertFalse(wrong.correct)
        assertEquals("Not quite — you typed 4 7 1 9. The number was 4 7 2 9. Try again.", wrong.line)
    }
}
