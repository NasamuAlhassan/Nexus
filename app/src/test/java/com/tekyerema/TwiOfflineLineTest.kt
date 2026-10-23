package com.tekyerema

import com.tekyerema.voice.TwiOfflineLine
import com.tekyerema.voice.TwiOfflineLine.Pick
import org.junit.Assert.assertEquals
import org.junit.Test

/** The short offline Twi line (models session's review, 23 Oct 2026): "done" only for a real action, never for a question. */
class TwiOfflineLineTest {
    private fun pick(acted: Boolean = false, ok: Boolean = true, listenAfter: Boolean = false, spoken: String = "Volume up.",
                     twi: Boolean = true, online: Boolean = false, hasTwiLine: Boolean = false, englishOnly: Boolean = true) =
        TwiOfflineLine.pick(twi, systemVoice = true, hasTwiLine = hasTwiLine, online = online, englishOnly = englishOnly,
            acted = acted, ok = ok, listenAfter = listenAfter, spoken = spoken)

    @Test fun aRealActionIsDone() = assertEquals(Pick.DONE, pick(acted = true))

    @Test fun aReportedFailureIsNotDone() = assertEquals(Pick.FAILED, pick(ok = false, spoken = "Sorry, I couldn't switch the flashlight on."))

    @Test fun aQuestionIsNeverDone() {
        assertEquals(null, pick(acted = false, listenAfter = true, spoken = "Did you mean, open settings? Say yes or no."))
        assertEquals(null, pick(acted = false, listenAfter = true, spoken = "Okay. Say it another way."))
        assertEquals(null, pick(acted = true, spoken = "Which one?"))
    }

    @Test fun aLineThatOnlyReadsFineIsNotDone() = assertEquals(null, pick(acted = false, spoken = "No contact named Kofi."))

    @Test fun onlineOrEnglishOrATwiLineKeepsTheLine() {
        assertEquals(null, pick(acted = true, online = true))
        assertEquals(null, pick(acted = true, twi = false))
        assertEquals(null, pick(acted = true, hasTwiLine = true))
        assertEquals(null, pick(acted = true, englishOnly = false))
    }
}
