package com.tekyerema

import com.tekyerema.agent.LocalBrain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Set the assistant volume to max" is the phone's Assistant slider, not the media one (A20e, 25 Oct 2026). */
class AssistantVolumeTest {
    private fun hit(s: String) = LocalBrain.RE_VOL_ASSISTANT.containsMatchIn(s)

    @Test fun theAssistantsOwnVolumeIsRecognised() {
        assertTrue(hit("set the assistant volume to max"))
        assertTrue(hit("turn up your volume"))
        assertTrue(hit("assistant volume up"))
        assertTrue(hit("make the volume of the assistant louder"))
    }

    @Test fun plainVolumeStaysOnMedia() {
        assertFalse(hit("set the volume to max"))
        assertFalse(hit("volume up"))
        assertFalse(hit("turn the volume down"))
    }
}
