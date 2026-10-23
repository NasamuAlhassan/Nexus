package com.tekyerema

import com.tekyerema.camera.VisionLadder
import com.tekyerema.camera.VisionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera's two vision rungs, hedged (VisionLadder.race, 23 Oct 2026): timings in virtual time. */
class VisionRaceTest {
    private fun answer(text: String, ms: Long): suspend () -> VisionResult = { delay(ms); VisionResult.Described(text) }
    private fun failure(reason: String, ms: Long): suspend () -> VisionResult = { delay(ms); VisionResult.Failed(reason) }

    @Test fun aQuestionPrefersTheSmartRungWhenItComesInTime() = runTest {
        val r = VisionLadder.race(answer("groq", 1_000), answer("gemini", 3_000), smart = true, hedgeMs = 2_000, smartWaitMs = 4_500)
        assertEquals(VisionResult.Described("gemini"), r)
        assertEquals(3_000, currentTime)
    }

    @Test fun aQuestionTakesTheFastAnswerWhenTheSmartRungIsLate() = runTest {
        val r = VisionLadder.race(answer("groq", 1_000), answer("gemini", 9_000), smart = true, hedgeMs = 2_000, smartWaitMs = 4_500)
        assertEquals(VisionResult.Described("groq"), r)
        assertEquals(4_500, currentTime)
    }

    @Test fun aSmartRungThatFailsDoesNotHoldTheUser() = runTest {
        val r = VisionLadder.race(answer("groq", 1_000), failure("HTTP 429", 300), smart = true, hedgeMs = 2_000, smartWaitMs = 4_500)
        assertEquals(VisionResult.Described("groq"), r)
        assertEquals(1_000, currentTime)
    }

    @Test fun aPlainDescriptionNeverSpendsTheSmartRungWhenTheFastOneAnswers() = runTest {
        var smartCalled = false
        val r = VisionLadder.race(answer("groq", 1_200), { smartCalled = true; VisionResult.Described("gemini") },
            smart = false, hedgeMs = 2_000, smartWaitMs = 4_500)
        assertEquals(VisionResult.Described("groq"), r)
        assertTrue(!smartCalled)
    }

    @Test fun aSlowFastRungIsHedgedAndTheFirstAnswerWins() = runTest {
        val r = VisionLadder.race(answer("groq", 5_000), answer("gemini", 1_000), smart = false, hedgeMs = 2_000, smartWaitMs = 4_500)
        assertEquals(VisionResult.Described("gemini"), r)
        assertEquals(3_000, currentTime)   // started at the 2-s hedge, answered a second later
    }

    @Test fun aFastFailureStartsTheSmartRungAtOnce() = runTest {
        val r = VisionLadder.race(failure("HTTP 413", 200), answer("gemini", 1_000), smart = false, hedgeMs = 2_000, smartWaitMs = 4_500)
        assertEquals(VisionResult.Described("gemini"), r)
        assertEquals(1_200, currentTime)
    }

    @Test fun bothFailingIsAFailure() = runTest {
        val r = VisionLadder.race(failure("HTTP 429", 100), failure("HTTP 503", 200), smart = true, hedgeMs = 2_000, smartWaitMs = 4_500)
        assertTrue(r is VisionResult.Failed)
    }
}
