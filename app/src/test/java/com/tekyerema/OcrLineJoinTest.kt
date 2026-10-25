package com.tekyerema

import com.tekyerema.camera.TextStabilizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** P6 (25 October 2026): a page's wrapped lines are one sentence, a sign's lines stay separate. */
class OcrLineJoinTest {
    private fun say(s: String) = TextStabilizer.spokenForm(s, Int.MAX_VALUE)

    @Test fun wrappedLinesJoinIntoOneSentence() {
        assertEquals("The meeting is on Monday at 10 am in the Balme Library, room 2. Bring the receipt.",
            say("The meeting is on Monday at 10 am\nin the Balme Library, room 2.\nBring the receipt."))
    }

    @Test fun aHyphenatedWordIsMended() {
        assertEquals("Bring your registration card.", say("Bring your regis-\ntration card."))
        // a real hyphen before a capital or a number stays
        assertEquals("Wi-Fi. Room 5", say("Wi-Fi\nRoom 5").replace("Wi-Fi. Room", "Wi-Fi. Room"))
    }

    @Test fun signLinesAndHeadingsStaySeparateSentences() {
        assertEquals("University of Ghana, Legon. Notice to students. Dear Kofi,",
            say("University of Ghana, Legon\nNotice to students\n\nDear Kofi,"))
        assertEquals("EXIT. Fire assembly point", say("EXIT\nFire assembly point"))
    }
}
