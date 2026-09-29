package com.tekyerema

import com.tekyerema.agent.Spelling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spelling a word out loud, letter by letter — with the sound-alikes a recogniser can't tell apart. */
class SpellingTest {
    private fun a(s: String) = Spelling.answer(s.lowercase())

    @Test fun a_word_is_spelled_letter_by_letter() {
        assertEquals("“Accommodation” is spelled A, C, C, O, M, M, O, D, A, T, I, O, N.", a("Spell accommodation"))
        assertEquals("“Necessary” is spelled N, E, C, E, S, S, A, R, Y.", a("How do you spell necessary?"))
        assertEquals("“Necessary” is spelled N, E, C, E, S, S, A, R, Y.", a("can you spell the word necessary for me please"))
        assertEquals("“Receive” is spelled R, E, C, E, I, V, E.", a("how is receive spelled"))
        assertEquals("“Receive” is spelled R, E, C, E, I, V, E.", a("what's the spelling of receive"))
        assertEquals("“Mother-in-law” is spelled M, O, T, H, E, R, hyphen, I, N, hyphen, L, A, W.", a("spell mother-in-law"))
        assertEquals("“Ice cream” is spelled I, C, E, space, C, R, E, A, M.", a("how do I spell ice cream"))
    }

    @Test fun sound_alikes_get_every_spelling() {
        assertEquals("“There”, as in over there, is spelled T, H, E, R, E. If you meant belonging to them: their, T, H, E, I, R. " +
            "If you meant they are: they're, T, H, E, Y, apostrophe, R, E.", a("spell there"))
        val two = a("how do you spell two")!!
        assertTrue(two, two.startsWith("“Two”, as in the number 2, is spelled T, W, O."))
        assertTrue(two, two.contains("to, T, O.") && two.contains("too, T, O, O."))
        assertTrue(a("spell weather")!!.contains("whether, W, H, E, T, H, E, R."))
    }

    @Test fun anything_else_goes_to_the_model() {
        for (s in listOf("spell it", "spell that again", "spell my name", "spell her name", "spell check", "turn on spell check",
            "spell necessary in twi", "spell 40", "spell tɛkyerɛma", "spell out", "how do you spell", "spell the message to ama",
            "spell a very long sentence here", "what is the time", "open whatsapp"))
            assertNull(s, a(s))
    }
}
