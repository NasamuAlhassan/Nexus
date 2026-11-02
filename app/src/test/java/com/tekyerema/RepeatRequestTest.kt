package com.tekyerema

import com.tekyerema.voice.Confirmation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Say it again" at a read-back (20 Oct 2026). */
class RepeatRequestTest {
    @Test fun repeatRequestsAreRecognised() {
        for (s in listOf("say that again", "Pardon?", "repeat", "please repeat that", "what did you say", "ka bio", "San ka bio",
                "ka no bio", "mante", "Mante no.", "come again", "I didn't hear that", "what is the number again", "What's the amount?",
                "which number"))
            assertTrue(s, Confirmation.asksToRepeat(s))
        for (s in listOf("yes", "aane", "daabi", "no", "send it again to Kofi", "ka sidi du", "change the number to 0244", "", null))
            assertFalse("$s", Confirmation.asksToRepeat(s))
    }
}
