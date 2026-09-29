package com.tekyerema

import com.tekyerema.agent.ClaimCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A20e 2026-09-29: "I opened Chrome and searched for Kwame Nkrumah." after only opening Chrome. */
class ClaimCheckTest {
    private val opened = listOf("open_app({app=Chrome}) → success [intent]; verified=true (foreground is com.android.chrome); Opened Chrome")

    @Test fun a_claimed_search_with_no_search_step_is_caught() {
        val c = ClaimCheck.check("open Chrome and search for Kwame Nkrumah", "I opened Chrome and searched for Kwame Nkrumah.", opened)
        assertNotNull(c); assertEquals("search", c!!.noun)
        val searched = opened + "type({text=Kwame Nkrumah, submit=true}) → success [node]; verified=true (x); Typed"
        assertNull(ClaimCheck.check("open Chrome and search for Kwame Nkrumah", "I opened Chrome and searched for Kwame Nkrumah.", searched))
        assertNull(ClaimCheck.check("search the web for Kwame Nkrumah", "I searched the web: he was Ghana's first president.",
            listOf("web_search({query=Kwame Nkrumah}) → success [native]; verified=true (x); …")))
    }

    @Test fun sends_calls_plays_are_checked_the_same_way() {
        assertNotNull(ClaimCheck.check("text Ama hi", "Sent your message to Ama.", emptyList()))
        assertNull(ClaimCheck.check("text Ama hi", "Sent your message to Ama.", listOf("send_sms({contact=Ama}) → success [a11y]; verified=true; …")))
        assertNotNull(ClaimCheck.check("call Prince", "I called Prince.", emptyList()))
        assertNotNull(ClaimCheck.check("play the playlist on my screen", "I'm playing the playlist now.",
            listOf("play_music({query=x}) → fail [intent]; verified=false; the search opened but nothing plays")))
    }

    @Test fun ordinary_answers_and_honest_failures_are_untouched() {
        // Not asked for: an answer that mentions calling is not a claim.
        assertNull(ClaimCheck.check("who was Kwame Nkrumah", "He called for independence and led Ghana in 1957.", emptyList()))
        // Negated, or a question.
        assertNull(ClaimCheck.check("text Ama hi", "I couldn't send it — WhatsApp wasn't open.", emptyList()))
        assertNull(ClaimCheck.check("text Ama hi", "I haven't sent it yet.", emptyList()))
        assertNull(ClaimCheck.check("text Ama hi", "Which Ama should I send it to?", emptyList()))
        // Not first person: third-party text.
        assertNull(ClaimCheck.check("search for the news", "The article says the minister searched the office.", emptyList()))
    }
}
