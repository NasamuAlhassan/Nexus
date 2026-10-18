package com.tekyerema

import com.tekyerema.android.ContactResolver
import com.tekyerema.android.Relations
import com.tekyerema.android.Relations.SetRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C-03 / H-08: relationship contacts — "remember that Ama Mensah is my mother", then "call my mother" means her. */
class RelationsTest {
    @After fun reset() = Relations.clear()

    @Test fun relation_words_in_english_ghanaian_english_and_twi() {
        assertEquals("mother", Relations.canonical("my mum"))
        assertEquals("mother", Relations.canonical("Maame"))
        assertEquals("father", Relations.canonical("my daddy"))
        assertEquals("best friend", Relations.canonical("best friend"))
        assertEquals("uncle", Relations.canonical("wofa"))
        assertNull(Relations.canonical("sick"))
        assertNull(Relations.canonical("Ama"))
    }

    @Test fun explicit_forms_are_parsed_with_the_name_as_said() {
        assertEquals(SetRequest("mother", "mother", "Ama Mensah", true), Relations.parseSet("Remember that Ama Mensah is my mother."))
        assertEquals(SetRequest("mother", "mum", "Akos", true), Relations.parseSet("remember my mum is Akos"))
        assertEquals(SetRequest("brother", "brother", "Kofi", true), Relations.parseSet("Save Kofi as my brother"))
        assertEquals(SetRequest("father", "dad", "Kwame Mensah", true), Relations.parseSet("set my dad to Kwame Mensah"))
        assertEquals(SetRequest("mother", "mother", "Mama Ama", true), Relations.parseSet("my mother is saved as Mama Ama"))
    }

    @Test fun bare_forms_are_only_a_candidate_until_the_contact_is_found() {
        assertEquals(SetRequest("mother", "mother", "Ama", false), Relations.parseSet("Ama is my mother"))
        assertEquals(SetRequest("wife", "wife", "Efua", false), Relations.parseSet("my wife is Efua"))
        // Twi, PENDING NATIVE REVIEW: "Ama yɛ me maame" (Ama is my mother).
        assertEquals(SetRequest("mother", "maame", "Ama", false), Relations.parseSet("Ama yɛ me maame"))
    }

    @Test fun ordinary_sentences_about_family_are_not_relations() {
        for (s in listOf("my mother is sick", "my wife is calling", "my brother is at home", "she is my mother",
            "Kofi is my problem", "my mum is my mother", "Ama is my mother and call her", "my dad is not well"))
            assertNull(s, Relations.parseSet(s))
    }

    @Test fun questions_and_forgetting() {
        assertEquals("mother", Relations.parseQuery("who is my mother"))
        assertEquals("mother", Relations.parseQuery("who's my mum?"))
        assertEquals("", Relations.parseQuery("who are my family"))
        assertNull(Relations.parseQuery("who is ama"))
        assertEquals("mother", Relations.parseForget("forget who my mother is"))
        assertEquals("mother", Relations.parseForget("forget that ama is my mother"))
        assertEquals("father", Relations.parseForget("remove my dad"))
        assertNull(Relations.parseForget("forget ama"))
    }

    @Test fun filler_words_after_the_name_are_not_part_of_it() {
        assertEquals("my wife", ContactResolver.aliasesFor("my wife now").first())
        assertEquals("Kofi", ContactResolver.aliasesFor("Kofi please").first())
        assertEquals("Now", ContactResolver.aliasesFor("Now").first())
    }

    @Test fun a_told_relation_is_tried_first_when_resolving_a_contact() {
        assertEquals("my mum", ContactResolver.aliasesFor("my mum").first())
        Relations.set("mother", "Ama Mensah")
        assertEquals("Ama Mensah", ContactResolver.aliasesFor("my mum").first())
        assertEquals("Ama Mensah", ContactResolver.aliasesFor("Mother").first())
        assertEquals("Kofi", ContactResolver.aliasesFor("Kofi").first())
        assertEquals("your mother is Ama Mensah", Relations.spokenAll())
        Relations.set("brother", "Kofi Boateng")
        assertEquals("your mother is Ama Mensah and your brother is Kofi Boateng", Relations.spokenAll())
    }
}
