package com.tekyerema

import com.tekyerema.agent.Dismissal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "No thanks" means nothing to do — never a task (A20e, 6 Nov 2026). */
class DismissalTest {
    @Test fun dismissals_are_recognised() {
        for (s in listOf("no", "No thanks", "no, it's fine", "never mind", "nevermind", "forget it", "nothing", "no, leave it",
            "okay, never mind", "not now", "no thanks, that's all", "cancel", "Nothing else, thanks", "no no, it's fine."))
            assertTrue(s, Dismissal.isDismissal(s))
    }

    @Test fun requests_and_answers_with_content_are_not() {
        for (s in listOf("no, send it to Kofi", "no, Richmond", "the first one", "yes", "two minutes", "text Prince I'm late",
            "no I want walking directions to the Balme library", "nothing is playing, open YouTube", "", "   "))
            assertFalse(s, Dismissal.isDismissal(s))
    }
}
