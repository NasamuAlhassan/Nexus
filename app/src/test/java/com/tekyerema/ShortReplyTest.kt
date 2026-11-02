package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.ReadingPace
import com.tekyerema.voice.ShortReply
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Short answers by default (2 Nov 2026): cut at ~30 words on a sentence end, the rest on "more". */
class ShortReplyTest {
    @After fun reset() { ShortReply.clear(); AppConfig.loadFrom(emptyList()) }

    private fun words(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }

    // A 58-word done-summary, the kind the teacher writes.
    private val long = "I checked your messages from this morning. Ama wants to know if you are coming to the meeting at four. " +
        "Kofi sent the photos from Saturday and says thank you for the help. The church group shared the programme for Sunday. " +
        "Your bank sent a statement, and nothing in it looks unusual to me. Everything else is adverts."

    @Test fun a_long_reply_is_cut_at_a_sentence_end_near_thirty_words() {
        val c = ShortReply.cut(long)
        assertNotNull(c.rest)
        assertTrue(c.head, words(c.head) <= 30)
        assertTrue(c.head, c.head.endsWith("."))
        assertTrue(c.head.startsWith("I checked your messages from this morning. Ama wants"))
        assertEquals(long, c.head + " " + c.rest)                                   // nothing lost
    }

    @Test fun questions_safety_lines_and_short_rests_are_never_cut() {
        val q = "I found two people called Kofi in your contacts: Kofi Mensah from work and Kofi Boateng from church. " +
            "Kofi Mensah was the one you called yesterday afternoon. Which Kofi should I send it to?"
        assertNull(ShortReply.cut(q).rest)
        val scam = "This message says you won a prize and must pay a fee first. Real promotions never ask you to pay to " +
            "collect a prize. It also has a link. Don't open it, and don't send any money to that number."
        assertNull(ShortReply.cut(scam).rest)
        val shortTail = "Your battery is at forty percent and the phone is not charging right now, so it will last about " +
            "five more hours of normal use today. Plug it in soon."
        assertNull(ShortReply.cut(shortTail).rest)                                   // "Plug it in soon." costs less than the tail
        assertNull(ShortReply.cut("It's sent.").rest)
        assertNull(ShortReply.cut("One sentence that runs on and on without any full stop at all because the model forgot to end " +
            "it and kept adding clauses one after the other for a very long time").rest)
    }

    @Test fun titles_are_not_sentence_ends() {
        val t = "Your appointment is with Dr. Akon at the clinic in Madina tomorrow morning at nine. Bring your card and the " +
            "letter from the hospital. The clinic says to come fifteen minutes early. They close at noon on Saturdays."
        // With a 10-word budget the first sentence still goes whole — and it must end at "nine.", not at "Dr."
        assertTrue(ShortReply.cut(t, max = 10).head.endsWith("tomorrow morning at nine."))
    }

    @Test fun asked_in_full_and_more() {
        assertTrue(ShortReply.askedInFull("read it all"))
        assertTrue(ShortReply.askedInFull("explain that in detail"))
        assertTrue(ShortReply.askedInFull("tell me everything about mobile money"))
        assertTrue(ShortReply.askedInFull("give me the full story"))
        assertFalse(ShortReply.askedInFull("summarize my messages"))
        assertFalse(ShortReply.askedInFull(null))
        for (m in listOf("more", "go on", "continue", "tell me more", "Say more.", "what else?")) assertTrue(m, ShortReply.isMore(m))
        for (m in listOf("send more money to Ama", "more volume", "what's the time")) assertFalse(m, ShortReply.isMore(m))
    }

    @Test fun the_rest_waits_for_more_then_ends() {
        val c = ShortReply.cut(long)
        ShortReply.hold(c.rest, now = 1_000L)
        val second = ShortReply.next(now = 2_000L)!!
        assertTrue(second, second.startsWith("Kofi sent") || second.startsWith("The church"))
        var guard = 0
        var last = second
        while (ShortReply.next(now = 3_000L)?.also { last = it } != null && guard++ < 5) {}
        assertFalse(last, last.endsWith(ShortReply.TAIL))                            // the final piece has no tail
        assertTrue(last.endsWith("Everything else is adverts."))
        assertNull(ShortReply.next(now = 4_000L))
        ShortReply.hold("A rest that expired. It waited too long for anyone.", now = 1_000L)
        assertNull(ShortReply.next(now = 1_000L + 120_001L))
    }

    @Test fun the_tail_reopens_the_mic_like_a_reading_part() {
        assertTrue(ReadingPace.invitesContinue("Ama wants to know. " + ShortReply.TAIL))
        assertTrue(ReadingPace.invitesContinue("There's more — say “continue”."))
        assertFalse(ReadingPace.invitesContinue("Say more about it later?"))
    }

    @Test fun switches() {
        AppConfig.loadFrom(listOf("t.json" to """{"speech":{"reply":{"maxWords":60}}}"""))
        assertNull(ShortReply.cut(long).rest)                                        // 58 words fit under 60 → said whole
        AppConfig.loadFrom(listOf("t.json" to """{"speech":{"reply":{"shortByDefault":false}}}"""))
        assertFalse(ShortReply.enabled())
    }
}
