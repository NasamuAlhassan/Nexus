package com.tekyerema

import com.tekyerema.core.TranslateGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/agent/KEYBOARD_LANGUAGES.md §5: names and numbers never go through translation (slots X1, X2… — live probe, 2 Nov). */
class TranslateGuardTest {

    @Test fun numbers_and_names_become_slots_and_come_back_unchanged() {
        val g = TranslateGuard.protect("Ka kyerɛ Ama Serwaa sɛ me de 50 bɛba 0244 123 456 so", listOf("Ama", "Ama Serwaa"))
        assertFalse(g.text, g.text.contains("50") || g.text.contains("0244") || g.text.contains("Serwaa"))
        assertEquals(mapOf("X1" to "50", "X2" to "0244 123 456", "X3" to "Ama Serwaa"), g.slots)
        // A translation that kept the slots, in another order.
        assertEquals("Tell Ama Serwaa that I will bring 50 to 0244 123 456",
            TranslateGuard.restore("Tell X3 that I will bring X1 to X2", g))
    }

    @Test fun a_lost_doubled_or_invented_slot_is_never_guessed() {
        val g = TranslateGuard.protect("send 20 to Kofi", listOf("Kofi"))   // X1 = 20, X2 = Kofi
        assertNull("X1 lost", TranslateGuard.restore("send to X2", g))
        assertNull("X1 twice", TranslateGuard.restore("send X1 X1 to X2", g))
        assertNull("X3 never sent", TranslateGuard.restore("send X1 to X2 and X3", g))
        assertEquals("send 20 to Kofi", TranslateGuard.restore("send X1 to X2", g))
    }

    @Test fun a_glued_suffix_is_dropped_and_X1_is_never_read_inside_X10() {
        val g = TranslateGuard.protect("Kofi and Ama", listOf("Kofi", "Ama"))   // X1 = Kofi, X2 = Ama
        assertEquals("Kofi nye Ama", TranslateGuard.restore("X1e nye X2", g))   // Ewe glued "e" (live probe)
        val ten = TranslateGuard.protect("1 2 3 4 5 6 7 8 9 10".split(" ").joinToString(" and "))
        assertEquals(10, ten.slots.size)
        assertEquals("1 and 2 and 3 and 4 and 5 and 6 and 7 and 8 and 9 and 10",
            TranslateGuard.restore("X1 and X2 and X3 and X4 and X5 and X6 and X7 and X8 and X9 and X10", ten))
    }

    @Test fun names_match_whole_words_only_and_text_without_any_is_untouched() {
        val g = TranslateGuard.protect("Amanda is coming", listOf("Ama"))
        assertEquals("Amanda is coming", g.text)
        assertTrue(g.slots.isEmpty())
        assertEquals("Amanda is coming", TranslateGuard.restore("Amanda is coming", g))
    }

    @Test fun times_and_amounts_with_separators_are_one_slot() {
        val g = TranslateGuard.protect("meet at 10:30 with 1,500.50 cedis")
        assertEquals(listOf("10:30", "1,500.50"), g.slots.values.toList())
    }
}
