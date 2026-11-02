package com.tekyerema

import com.tekyerema.core.TranslateGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/agent/KEYBOARD_LANGUAGES.md §5: names and numbers never go through translation. */
class TranslateGuardTest {

    @Test fun numbers_and_names_become_slots_and_come_back_unchanged() {
        val g = TranslateGuard.protect("Ka kyerɛ Ama Serwaa sɛ me de 50 bɛba 0244 123 456 so", listOf("Ama", "Ama Serwaa"))
        assertFalse(g.text, g.text.contains("50") || g.text.contains("0244") || g.text.contains("Serwaa"))
        assertEquals("Ama Serwaa", g.slots["⟦P1⟧"])
        assertTrue(g.slots.values.contains("50")); assertTrue(g.slots.values.contains("0244 123 456"))
        // A pretend translation that kept the slots, in another order.
        val translated = "Tell ⟦P1⟧ that I will bring ⟦N1⟧ to ⟦N2⟧"
        assertEquals("Tell Ama Serwaa that I will bring 50 to 0244 123 456", TranslateGuard.restore(translated, g))
    }

    @Test fun a_lost_or_doubled_slot_is_never_guessed() {
        val g = TranslateGuard.protect("send 20 to Kofi", listOf("Kofi"))
        assertNull("the number slot was lost", TranslateGuard.restore("send to ⟦P1⟧", g))
        assertNull("a slot appeared twice", TranslateGuard.restore("send ⟦N1⟧ ⟦N1⟧ to ⟦P1⟧", g))
        assertEquals("send 20 to Kofi", TranslateGuard.restore("send ⟦N1⟧ to ⟦P1⟧", g))
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
