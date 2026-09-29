package com.tekyerema

import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.EntityCorrector.Fix
import com.tekyerema.voice.EntityCorrector.Kind
import com.tekyerema.voice.HeardAs
import com.tekyerema.voice.LanguagePipeline
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spoken doubt (2026-09-29): a recipient chosen by the name fix is said once in the read-back — "I heard 'prints'…". */
class HeardAsTest {
    @After fun clear() { HeardAs.clear(); LanguagePipeline.vocabulary = { com.tekyerema.voice.SpeechVocabulary.current() } }

    private val prince = listOf(Fix("prints", "Prince", Kind.CONTACT))

    @Test fun a_corrected_recipient_is_said_once_in_the_read_back() {
        HeardAs.note("call Prince", prince, now = 0L)
        assertEquals("I heard 'prints' — taking it as Prince.", HeardAs.line("call Prince", "make_call", JSONObject().put("contact", "Prince"), now = 1_000L))
        assertNull("once", HeardAs.line("call Prince", "make_call", JSONObject().put("contact", "Prince"), now = 2_000L))
        // The resolved full name still is that person.
        HeardAs.note("text Prince saying hi", prince, now = 0L)
        assertEquals("I heard 'prints' — taking it as Prince.",
            HeardAs.line("text Prince saying hi", "send_sms", JSONObject().put("contact", "Prince Nasamu").put("message", "hi"), now = 1_000L))
    }

    @Test fun only_for_a_recipient_of_the_same_request_while_fresh() {
        HeardAs.note("call Prince", prince, now = 0L)
        assertNull("a different request", HeardAs.line("call Ama", "make_call", JSONObject().put("contact", "Ama"), now = 1_000L))
        assertNull("not a recipient", HeardAs.line("call Prince", "open_app", JSONObject().put("app", "Prince"), now = 1_000L))
        assertNull("someone else", HeardAs.line("call Prince", "make_call", JSONObject().put("contact", "Kofi"), now = 1_000L))
        assertNull("stale", HeardAs.line("call Prince", "make_call", JSONObject().put("contact", "Prince"), now = 5 * 60_000L))
        // An app-name fix is not a person; the same words heard right the next time drop the old note.
        HeardAs.note("open YouTube", listOf(Fix("you tube", "YouTube", Kind.APP)), now = 0L)
        assertNull(HeardAs.line("open YouTube", "make_call", JSONObject().put("contact", "YouTube"), now = 1_000L))
        HeardAs.note("call Prince", prince, now = 0L)
        HeardAs.note("call Prince", emptyList(), now = 500L)
        assertNull(HeardAs.line("call Prince", "make_call", JSONObject().put("contact", "Prince"), now = 1_000L))
        // Money to a person counts too.
        HeardAs.note("send 5 cedis to Prince", prince, now = 0L)
        assertEquals("I heard 'prints' — taking it as Prince.",
            HeardAs.line("send 5 cedis to Prince", "mobile_money", JSONObject().put("_recipient", "Prince"), now = 1_000L))
    }

    @Test fun the_language_pipeline_notes_what_the_corrector_changed() {
        LanguagePipeline.vocabulary = { EntityCorrector.Vocab.of(listOf("Prince Nasamu"), emptyList()) }
        val tr = LanguagePipeline.process("call prints", "gh_en", "gh_en")
        assertEquals("call Prince", tr.canonical)
        assertEquals("I heard 'prints' — taking it as Prince.",
            HeardAs.line(tr.canonical, "make_call", JSONObject().put("contact", "Prince Nasamu")))
    }
}
