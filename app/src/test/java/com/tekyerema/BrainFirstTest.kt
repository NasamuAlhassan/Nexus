package com.tekyerema

import com.tekyerema.agent.BrainFirst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 3 Nov 2026: with a brain reachable, a sentence that carries more than a command goes to the brain, not the quick layer. */
class BrainFirstTest {

    @Test fun politeness_is_not_content() {
        assertEquals("read the most recent sms message", BrainFirst.core("Could you read the most recent SMS message to me?"))
        assertEquals("open telegram", BrainFirst.core("Hey Nexus, please open Telegram for me"))
        assertEquals("what time is it", BrainFirst.core("okay so what time is it now"))
    }

    @Test fun plain_commands_stay_quick() {
        for (s in listOf("what time is it", "open telegram", "battery", "could you read the most recent SMS message to me",
                "directions to the ugcs", "set a timer for 5 minutes", "what's on my hand"))
            assertFalse(s, BrainFirst.deferToBrain(s, brainReachable = true))
    }

    @Test fun sentences_that_carry_more_go_to_the_brain() {
        // A20e, 2 Nov 2026: "where I am" inside this sentence answered the user's location instead of directions.
        for (s in listOf("I wanted to know how I can move from where I am to the great hall",
                "I want you to play I Believe on YouTube and make the volume the highest",
                "what is 2 times 11 all that divided by 10 all that multiplied by 11",
                "is nsmq a nice game to play for a student like me"))
            assertTrue(s, BrainFirst.deferToBrain(s, brainReachable = true))
    }

    @Test fun with_no_brain_the_quick_layer_keeps_everything() {
        assertFalse(BrainFirst.deferToBrain("I wanted to know how I can move from where I am to the great hall", brainReachable = false))
    }
}
