package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.TwiPhrasebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-phone Twi phrasebook: device run 2026-09-29 heard the small cloud model call "thank you" "meda wo akye". */
class TwiPhrasebookTest {
    private fun a(s: String) = TwiPhrasebook.answer(s.lowercase())

    @Test fun common_phrases_are_answered_from_the_list() {
        assertEquals("In Twi, “thank you” is “Medaase”. You can also say “meda wo ase”.", a("How do you say thank you in Twi?"))
        assertTrue(a("what is good morning in Twi")!!.contains("Maakye"))
        assertTrue(a("translate welcome to Twi")!!.contains("Akwaaba"))
        assertTrue(a("what's the Twi word for water")!!.contains("Nsuo"))
        assertTrue(a("how do I say \"I love you\" in Twi")!!.contains("Me dɔ wo"))
        assertTrue(a("how do you say I'm fine in twi")!!.contains("Me ho yɛ"))
        assertTrue(a("five in Twi")!!.contains("Enum"))
    }

    @Test fun twi_words_are_explained_in_english_even_without_the_special_letters() {
        assertEquals("“Akwaaba” is Twi for “welcome”.", a("what does akwaaba mean"))
        assertEquals("“Ayekoo” is Twi for “well done”.", a("what is the meaning of ayekoo"))
        assertTrue(a("what does me do wo mean")!!.contains("i love you"))   // ɔ written as o by the recogniser
    }

    @Test fun anything_not_in_the_list_goes_to_the_model() {
        assertNull(a("how do you say the weather is hot in Twi"))
        assertNull(a("what does serendipity mean"))
        assertNull(a("open WhatsApp"))
        assertNull(a("what is the time"))
    }

    @Test fun the_fast_path_answers_it_without_a_model() {
        val i = FastIntentClassifier.classify("how do you say thank you in Twi")
        assertTrue("got $i", i is FastIntent.TwiPhrase)
        assertTrue(FastIntentClassifier.classify("what time is it") !is FastIntent.TwiPhrase)
    }
}
