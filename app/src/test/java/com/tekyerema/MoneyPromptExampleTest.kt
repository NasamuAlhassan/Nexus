package com.tekyerema

import com.tekyerema.camera.VisionPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit, 22 Oct 2026: the counting prompt's only example must not show a count the model copies onto a single note. */
class MoneyPromptExampleTest {
    @Test fun theOnlyExampleCountsOneNote() {
        // 24 Oct 2026: no count at all — one entry per physical note, with its box; the phone counts (MoneyTally).
        assertFalse(VisionPrompts.MONEY.contains("\"count\""))
        assertEquals(1, Regex("\"value_ghs\"").findAll(VisionPrompts.MONEY).count())
        assertTrue(VisionPrompts.MONEY.contains("\"box\""))
    }
}
