package com.tekyerema

import com.tekyerema.camera.VisionPrompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit, 22 Oct 2026: the counting prompt's only example must not show a count the model copies onto a single note. */
class MoneyPromptExampleTest {
    @Test fun theOnlyExampleCountsOneNote() {
        assertFalse(VisionPrompts.MONEY.contains("\"count\":2"))
        assertTrue(VisionPrompts.MONEY.contains("\"count\":1"))
    }
}
