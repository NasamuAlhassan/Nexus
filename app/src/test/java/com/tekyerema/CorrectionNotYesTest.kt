package com.tekyerema

import com.tekyerema.voice.Confirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A spoken reply that corrects the read-back is never a yes (the owner's live test on the A20e, 14 October 2026: "I said you
 * should send it to Mom and then 2019" held "send it" and approved the transfer to the number ending 4 1 1 1).
 */
class CorrectionNotYesTest {
    private val momo = "Send 1 cedi to Mom, ending 4 1 1 1, an MTN number, with Mobile Money?\n" +
        "You haven't sent money to this number before. I'll go through the menu quietly and stop at the PIN for you to enter."

    @Test fun the_reply_from_the_phone_log_is_a_correction_not_a_yes() {
        assertTrue(Confirmation.looksLikeRevision("I said you should send it to Mom and then 2019", momo))
        assertTrue(Confirmation.looksLikeRevision("no I want you to send it to my mom like is ending and I think 2019", momo))
    }

    @Test fun a_number_the_read_back_does_not_say_makes_it_a_correction() {
        assertTrue(Confirmation.looksLikeRevision("yes send it 2019", momo))
        assertTrue(Confirmation.looksLikeRevision("yes 11 cedis", momo))
        assertTrue(Confirmation.looksLikeRevision("yes two zero one nine", momo))
        assertEquals(listOf("2019"), Confirmation.newNumbers("send it to Mom and then 2019", momo))
    }

    @Test fun repeating_what_the_read_back_says_stays_a_yes() {
        assertFalse(Confirmation.looksLikeRevision("yes", momo))
        assertFalse(Confirmation.looksLikeRevision("yes send the 1 cedi", momo))
        assertFalse(Confirmation.looksLikeRevision("yes the one ending 4111", momo))
        assertFalse(Confirmation.looksLikeRevision("okay send it to Mom", momo))
        assertFalse(Confirmation.looksLikeRevision("yes please go ahead and send it", momo))
        assertFalse(Confirmation.looksLikeRevision("go ahead, send it to her", momo))
        // part of a longer number the read-back says is not new; a short number must match exactly
        assertTrue(Confirmation.newNumbers("yes ending 4567", "Call 024 123 4567?").isEmpty())
        assertEquals(listOf("5"), Confirmation.newNumbers("yes 5", "Send 50 cedis to Ama?"))
    }

    @Test fun sending_to_someone_the_read_back_does_not_name_is_a_correction() {
        assertTrue(Confirmation.looksLikeRevision("send it to Kofi", momo))
        assertTrue(Confirmation.looksLikeRevision("yes but call Ama", "Call Kojo?"))
        assertFalse(Confirmation.looksLikeRevision("yes call him", "Call Kojo?"))
        assertFalse(Confirmation.looksLikeRevision("yes call Kojo now", "Call Kojo?"))
        assertTrue(Confirmation.looksLikeRevision("yes send the money to my sister", momo))
    }

    @Test fun i_said_is_that_answer_only_when_bare() {
        assertFalse(Confirmation.looksLikeRevision("I said yes", momo))
        assertFalse(Confirmation.looksLikeRevision("I said no", momo))
        assertEquals(Confirmation.Answer.YES, Confirmation.interpret("I said yes"))
        assertTrue(Confirmation.looksLikeRevision("I told you to send it to the other Mom", momo))
    }

    @Test fun a_decline_stays_a_decline_whatever_number_it_mentions() {
        assertFalse(Confirmation.looksLikeRevision("no don't send the 50", momo))
        assertFalse(Confirmation.looksLikeRevision("cancel it", momo))
        assertFalse(Confirmation.looksLikeRevision("no", momo))
    }

    @Test fun without_a_read_back_the_old_rules_hold() {
        assertFalse(Confirmation.looksLikeRevision("yes send 5 cedis"))
        assertFalse(Confirmation.looksLikeRevision("yes"))
        assertTrue(Confirmation.looksLikeRevision("I said you should send it to Mom and then 2019"))
    }
}
