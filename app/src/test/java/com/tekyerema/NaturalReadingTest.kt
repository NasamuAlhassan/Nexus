package com.tekyerema

import com.tekyerema.notifications.MessageCursor
import com.tekyerema.notifications.MessageReading
import com.tekyerema.voice.SpokenText
import com.tekyerema.voice.SpokenText.BREAK
import com.tekyerema.voice.SpokenText.PAUSE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, A20e 2026-09-30: messages were read "straight through… not like a human being" — one at a time, with pauses. */
class NaturalReadingTest {
    @org.junit.Before fun freshCoaching() { com.tekyerema.voice.Coaching.resetForTest() }   // the shrinking tail starts full

    @After fun reset() = MessageCursor.clear()

    @Test fun pause_marks_become_timed_silences_and_never_trail() {
        val segs = SpokenText.segments("From Ama.${PAUSE}Are you coming?${BREAK}From Kofi.${PAUSE}Ok.", pauseMs = 400, breakMs = 900)
        assertEquals(listOf("From Ama." to 400L, "Are you coming?" to 900L, "From Kofi." to 400L, "Ok." to 0L),
            segs.map { it.text to it.pauseAfterMs })
        assertEquals(listOf("Hello." to 0L), SpokenText.segments("Hello.${BREAK}").map { it.text to it.pauseAfterMs })
        assertEquals("From Ama. Are you coming? From Kofi.", SpokenText.plain("From Ama.${PAUSE}Are you coming?${BREAK}From Kofi."))
    }

    @Test fun a_message_is_read_as_a_person_would_say_it() {
        assertEquals("Are you coming?", SpokenText.forEar("Are you coming???"))
        assertEquals("I'm outside.", SpokenText.forEar("I'm outside"))                          // a full stop so the voice ends
        assertEquals("Hello. How are you?", SpokenText.forEar("Hello\nHow are you?"))           // a line break is a sentence
        assertEquals("Wow!", SpokenText.forEar("Wow!!!!"))
        assertEquals("See a link.", SpokenText.forEar("See https://example.com/x?y=1"))
        assertEquals("Happy birthday!", SpokenText.forEar("Happy birthday 🎉🎂!"))
        assertEquals("An emoji.", SpokenText.forEar("👍"))
        assertEquals("Please call me now.", SpokenText.forEar("PLEASE CALL ME NOW"))              // shouting isn't spelled out
        assertEquals("Send the MTN code urgently.", SpokenText.forEar("Send the MTN code URGENTLY"))   // acronyms stay
        assertEquals("Please call you tomorrow, thanks.", SpokenText.forEar("pls call u tmrw, thx"))
        assertEquals("Mom and dad.", SpokenText.forEar("Mom & dad"))
        assertEquals("Wait…", SpokenText.forEar("Wait......"))
        assertEquals("Me pɛ sɛ meba.", SpokenText.forEar("Me pɛ sɛ meba"))                      // Twi letters untouched
        assertEquals("Pay 050 123 4567 GH₵200.", SpokenText.forEar("Pay 050 123 4567 GH₵200"))  // numbers untouched
    }

    @Test fun messages_are_read_one_at_a_time_with_who_and_when_first() {
        val m = MessageReading.Msg("Ama", "WhatsApp", "10 minutes ago", "Are you coming")
        assertEquals("From Ama, on WhatsApp, 10 minutes ago.${PAUSE}Are you coming.", MessageReading.item(m))
        assertEquals("From someone.${PAUSE}Hi.", MessageReading.item(MessageReading.Msg(null, null, null, "hi")))
    }

    @Test fun three_per_turn_then_continue_reads_on() {
        val msgs = (1..5).map { MessageReading.Msg("P$it", null, null, "Message $it") }
        val p = MessageReading.page("You have 5 new messages.", msgs)
        val parts = p.text.split(BREAK)
        assertEquals("You have 5 new messages.", parts.first())
        assertEquals(5, parts.size)                                                    // intro + 3 + "more"
        assertEquals("There are 2 more — say continue.", parts.last())
        assertTrue(com.tekyerema.voice.ReadingPace.invitesContinue(SpokenText.plain(p.text)))   // the mic re-opens for it
        MessageCursor.set(p.rest, now = 1_000)
        assertTrue(MessageCursor.next(now = 2_000)!!.startsWith("From P4."))
        assertNull("nothing left", MessageCursor.next(now = 3_000))
        MessageCursor.set(p.rest, now = 0)
        assertNull("stale after five minutes", MessageCursor.next(now = 6 * 60_000L))
        assertFalse(MessageCursor.pending)
    }
}
