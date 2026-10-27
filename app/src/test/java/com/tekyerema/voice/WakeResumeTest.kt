package com.tekyerema.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeResumeTest {
    private fun inp(
        handedOver: Boolean = false, interjecting: Boolean = false, busy: Boolean = false, canInterject: Boolean = false,
        canRespond: Boolean = false, canPreempt: Boolean = false, speaking: Boolean = false, busyMs: Long = 0
    ) = WakeResume.Inputs(handedOver, interjecting, busy, canInterject, canRespond, canPreempt, speaking, busyMs)

    @Test fun idleListens() = assertTrue(WakeResume.decide(inp()).listen)

    @Test fun recogniserHandOverClosesEvenWhenIdle() = assertFalse(WakeResume.decide(inp(handedOver = true)).listen)

    @Test fun interjectionInFlightClosesTheMic() = assertFalse(WakeResume.decide(inp(interjecting = true, busy = true, canInterject = true)).listen)

    @Test fun busyTurnOwnsTheMic() {
        val d = WakeResume.decide(inp(busy = true, busyMs = 5_000))
        assertFalse(d.listen); assertFalse(d.stuck)
    }

    @Test fun taskWorkingWithFreeMicListens() = assertTrue(WakeResume.decide(inp(busy = true, canInterject = true)).listen)

    @Test fun taskWaitingOnTheUserListens() = assertTrue(WakeResume.decide(inp(busy = true, canRespond = true)).listen)

    @Test fun idleTailOfAFinishedTurnListens() {
        val d = WakeResume.decide(inp(busy = true, canPreempt = true))
        assertTrue(d.listen); assertEquals("idle tail of a finished turn", d.reason)
    }

    @Test fun tailWhileOurVoiceSpeaksStaysClosed() = assertFalse(WakeResume.decide(inp(busy = true, canPreempt = true, speaking = true)).listen)

    @Test fun stuckBusyListensAfterTheLimit() {
        val d = WakeResume.decide(inp(busy = true, busyMs = WakeResume.STUCK_BUSY_MS))
        assertTrue(d.listen); assertTrue(d.stuck)
    }

    @Test fun stuckBusyNeverOverridesSpeech() = assertFalse(WakeResume.decide(inp(busy = true, speaking = true, busyMs = 999_999)).listen)

    @Test fun handOverBeatsEverything() = assertFalse(WakeResume.decide(inp(handedOver = true, busy = true, busyMs = 999_999)).listen)

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(300L, WakeBackoff.delayMs(0))
        assertTrue(WakeBackoff.delayMs(3) > WakeBackoff.delayMs(1))
        assertEquals(10_000L, WakeBackoff.delayMs(50))
        assertEquals(300L, WakeBackoff.delayMs(-1))
    }

    @Test fun backoffResetsAfterAHealthyRun() {
        assertEquals(0, WakeBackoff.nextAttempt(4, WakeBackoff.HEALTHY_MS))
        assertEquals(5, WakeBackoff.nextAttempt(4, 1_000))
    }

    @Test fun staleHandOverExpires() {
        assertFalse(WakeGuards.holderExpired(0, 1_000, 999_999))
        assertFalse(WakeGuards.holderExpired(1, 0, 999_999))
        assertFalse(WakeGuards.holderExpired(1, 1_000, 1_000 + WakeGuards.HOLDER_MAX_MS - 1))
        assertTrue(WakeGuards.holderExpired(1, 1_000, 1_000 + WakeGuards.HOLDER_MAX_MS))
    }

    @Test fun silenceAndStallThresholds() {
        assertFalse(WakeGuards.silentTooLong(WakeGuards.SILENT_FRAMES_REOPEN - 1))
        assertTrue(WakeGuards.silentTooLong(WakeGuards.SILENT_FRAMES_REOPEN))
        assertFalse(WakeGuards.stalled(false, 60_000))
        assertFalse(WakeGuards.stalled(true, WakeGuards.STALL_MS - 1))
        assertTrue(WakeGuards.stalled(true, WakeGuards.STALL_MS))
    }

    @Test fun settleIsShortAndBounded() {
        assertEquals(WakeGuards.SETTLE_AFTER_SPEECH_MS, WakeGuards.settleRemainingMs(0))
        assertEquals(213L, WakeGuards.settleRemainingMs(37))
        assertEquals(0L, WakeGuards.settleRemainingMs(5_000))
        assertTrue(WakeGuards.SETTLE_AFTER_SPEECH_MS < 400)
    }
}
