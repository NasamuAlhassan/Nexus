package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.ScreenReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6 (25 October 2026), measured on the emulator: Settings read "Profile picture, double tap to open Google Account", Messages read "Illustration of …". */
class ScreenReadoutCleanTest {
    private fun el(id: Int, label: String) = UiElement(id, "TextView", label, Box(0, id * 100, 500, id * 100 + 80))
    private fun snap(vararg labels: String) =
        UiSnapshot("com.example", "Title", labels.mapIndexed { i, l -> el(i + 1, l) }, 0L, appName = "Example")

    @Test fun spokenHintsAreNotRead() {
        assertEquals("Nothing planned", ScreenReadout.clean("Nothing planned. Tap to create"))
        assertEquals("Signed in as Kofi", ScreenReadout.clean("Signed in as Kofi, double tap to open Google Account"))
        // a sentence of the content that merely begins with those words stays
        assertEquals("Tap to pay at the till", ScreenReadout.clean("Tap to pay at the till"))
    }

    @Test fun decorativeAndChromeNodesAreSkipped() {
        val said = ScreenReadout.items(snap("Profile picture, double tap to open Google Account", "Navigate up", "More options",
            "Illustration of the media sharing feature.", "Sign In", "Your balance is GH₵ 5.00"))
        assertEquals(listOf("Sign In", "Your balance is GH₵ 5.00"), said)
    }

    @Test fun realContentWithTheSameWordsIsKept() {
        assertTrue(ScreenReadout.items(snap("Back to school sale")).contains("Back to school sale"))
        assertFalse(ScreenReadout.isChrome("Image of the receipt is attached"))
    }
}
