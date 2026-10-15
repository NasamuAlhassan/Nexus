package com.tekyerema

import com.tekyerema.agent.NextStep
import com.tekyerema.money.LastSend
import com.tekyerema.voice.Confirmation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offered next step and the late yes (agent/NextStep, 15 Oct 2026). Fake people only. */
class NextStepTest {
    @After fun tidy() = NextStep.clear()

    @Test fun after_a_send_it_offers_to_tell_the_person_and_the_goal_is_the_demo_command() {
        val o = NextStep.afterSend("Kofi Demo", failed = false, now = 0L)!!
        assertEquals("Shall I text Kofi Demo that it's sent?", o.english)
        assertEquals("tell Kofi Demo I've sent it", o.goal)
        // the goal is what LastSend turns into the real text (and that text is read back before it goes)
        LastSend.note("Kofi Demo", "0500002222", "5", "transport")
        assertEquals("I've sent you 5 cedis by Mobile Money. Reference: transport.", LastSend.expand("I've sent it", "Kofi Demo"))
        LastSend.clear()
    }

    @Test fun after_a_failed_send_it_offers_the_truth() {
        val o = NextStep.afterSend("Kofi Demo", failed = true, now = 0L)!!
        assertEquals("Shall I tell Kofi Demo it didn't go through?", o.english)
        LastSend.note("Kofi Demo", "0500002222", "5", "transport"); LastSend.markFailed()
        assertEquals("I tried to send you 5 cedis by Mobile Money, but it didn't go through.", LastSend.expand("I've sent it", "Kofi Demo"))
        LastSend.clear()
    }

    @Test fun no_offer_without_a_persons_name() {
        assertNull(NextStep.afterSend(null, false, 0L))
        assertNull(NextStep.afterSend("0500002222", false, 0L))
        assertNull(NextStep.afterReceived(" ", "20 cedis", 0L))
    }

    @Test fun money_from_a_saved_person_offers_a_thank_you() {
        val o = NextStep.afterReceived("Kofi Demo", "20 cedis", 0L)!!
        assertEquals("Shall I text Kofi Demo to say thank you?", o.english)
        assertEquals("tell Kofi Demo thank you, I've received the 20 cedis", o.goal)
    }

    @Test fun an_offer_answers_one_reply_and_expires() {
        NextStep.offer(NextStep.afterSend("Kofi Demo", false, 1_000L)!!)
        assertNotNull(NextStep.take(now = 2_000L))
        assertNull(NextStep.take(now = 2_500L))                    // taken: it answers this reply or no other
        NextStep.offer(NextStep.afterSend("Kofi Demo", false, 1_000L)!!)
        assertNull(NextStep.take(now = 1_000L + 61_000L))          // 60 s and it is gone
    }

    @Test fun only_a_short_yes_or_no_answers() {
        assertEquals(Confirmation.Answer.YES, NextStep.shortAnswer("yes"))
        assertEquals(Confirmation.Answer.YES, NextStep.shortAnswer("yes please"))
        assertEquals(Confirmation.Answer.NO, NextStep.shortAnswer("no"))
        assertNull(NextStep.shortAnswer("now call him"))         // the next request, not a yes to the offered text
        assertNull(NextStep.shortAnswer("text him"))
        assertEquals(Confirmation.Answer.YES, NextStep.shortAnswer("aane"))
        assertNull(NextStep.shortAnswer("yes and then call my mother please"))   // a new request carrying a yes-word
    }

    @Test fun a_late_yes_asks_the_unanswered_request_again_within_the_window() {
        NextStep.noteUnanswered("send money to him", now = 10_000L)
        assertEquals("send money to him", NextStep.takeLate(now = 25_000L))
        assertNull(NextStep.takeLate(now = 26_000L))
        NextStep.noteUnanswered("send money to him", now = 10_000L)
        assertNull(NextStep.takeLate(now = 10_000L + 31_000L))
        NextStep.noteUnanswered(null)
        assertNull(NextStep.takeLate())
    }

    @Test fun a_scam_warning_offers_the_balance_check_on_the_networks_menu() {
        val o = NextStep.afterScamWarning(0L)
        assertEquals("Shall I check your Mobile Money balance now?", o.english)
        assertEquals("check my mobile money balance", o.goal)
    }

    @Test fun twi_offers_exist_for_a_twi_conversation() {
        assertTrue(NextStep.afterSend("Kofi Demo", false, 0L)!!.twi!!.contains("Kofi Demo"))
        assertTrue(NextStep.afterReceived("Kofi Demo", null, 0L)!!.goal.endsWith("the money"))
    }
}
