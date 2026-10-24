package com.tekyerema

import android.content.ComponentCallbacks2 as CC
import com.tekyerema.voice.TrimPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone log, 24 Oct 2026: a voice used seconds ago was dropped on every "running low" and reloaded for 10 s. */
class TrimPolicyTest {
    private val grace = 90_000L

    @Test fun aRecentlyUsedModelSurvivesRunningLow() {
        assertFalse(TrimPolicy.shouldRelease(CC.TRIM_MEMORY_RUNNING_LOW, 5_000L, grace))
        assertFalse(TrimPolicy.shouldRelease(CC.TRIM_MEMORY_RUNNING_LOW, 89_999L, grace))
    }

    @Test fun anIdleModelIsReleasedOnRunningLow() {
        assertTrue(TrimPolicy.shouldRelease(CC.TRIM_MEMORY_RUNNING_LOW, 90_000L, grace))
        assertTrue(TrimPolicy.shouldRelease(CC.TRIM_MEMORY_RUNNING_LOW, Long.MAX_VALUE, grace))
    }

    @Test fun realPressureOrBackgroundReleasesEvenARecentOne() {
        for (level in listOf(CC.TRIM_MEMORY_RUNNING_CRITICAL, CC.TRIM_MEMORY_BACKGROUND, CC.TRIM_MEMORY_MODERATE, CC.TRIM_MEMORY_COMPLETE))
            assertTrue("level $level", TrimPolicy.shouldRelease(level, 1_000L, grace))
    }

    @Test fun mildSignalsAndUiHiddenReleaseNothing() {
        assertFalse(TrimPolicy.shouldRelease(CC.TRIM_MEMORY_RUNNING_MODERATE, Long.MAX_VALUE, grace))
        assertFalse(TrimPolicy.shouldRelease(CC.TRIM_MEMORY_UI_HIDDEN, Long.MAX_VALUE, grace))
    }

    @Test fun zeroGraceIsTheOldBehaviour() {
        assertTrue(TrimPolicy.shouldRelease(CC.TRIM_MEMORY_RUNNING_LOW, 0L, 0L))
    }
}
