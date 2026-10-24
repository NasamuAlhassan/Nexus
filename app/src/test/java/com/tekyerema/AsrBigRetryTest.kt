package com.tekyerema

import com.tekyerema.voice.AsrMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone log, 24 Oct 2026: the big English model was retried — and the small one unloaded — on every request. */
class AsrBigRetryTest {
    private val refused = 1_000_000L to 1_487L   // refused at t=1,000 s; it needed 1,487 MB

    @Test fun aRecentRefusalWithMemoryStillShortGoesStraightToTheSmallModel() {
        assertTrue(AsrMemory.skipBig(refused, nowMs = 1_030_000L, availMb = 756, retryMs = 120_000L))
    }

    @Test fun neverRefusedOrLongAgoOrMemoryBackTriesTheBigModel() {
        assertFalse("never refused", AsrMemory.skipBig(null, 5_000L, 300, 120_000L))
        assertFalse("the window has passed", AsrMemory.skipBig(refused, 1_000_000L + 121_000L, 756, 120_000L))
        assertFalse("memory came back (within 15 %)", AsrMemory.skipBig(refused, 1_030_000L, 1_300, 120_000L))
    }
}
