package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.tools.Grounding
import com.tekyerema.tools.MatchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tools/Grounding (13 October 2026): the thing in the user's hand goes by many names. On the emulator the Settings entry is
 * "About emulated device"; the planner asked for 'About phone', the resolver scrolled the whole list, said it wasn't there
 * and tapped 'System' (audit/emu-2026-10-13/goals/about_v1). Also the "nearest labels" hint for an honest not-found.
 */
class GroundingDeviceWordsTest {
    private fun el(id: Int, label: String?, clickable: Boolean = true) =
        UiElement(id, "Button", label, Box(0, id * 100, 100, id * 100 + 90), clickable = clickable)

    private val settingsBottom = listOf(el(0, "Accessibility"), el(1, "Privacy"), el(2, "Safety & emergency"), el(3, "System"),
        el(4, "About emulated device"), el(5, "Tips & support"))

    @Test fun about_phone_finds_about_emulated_device_tablet_or_device() {
        assertEquals(4, (Grounding.bestMatch(settingsBottom, "About phone") as MatchOutcome.Found).element.id)
        assertEquals(0, (Grounding.bestMatch(listOf(el(0, "About tablet")), "About phone") as MatchOutcome.Found).element.id)
        assertEquals(0, (Grounding.bestMatch(listOf(el(0, "About device")), "About phone") as MatchOutcome.Found).element.id)
        assertEquals(1, (Grounding.bestMatch(listOf(el(0, "Reset options"), el(1, "About phone")), "about device") as MatchOutcome.Found).element.id)
    }

    @Test fun the_bare_word_phone_is_still_the_phone_app_not_any_device_label() {
        val home = listOf(el(0, "Device care"), el(1, "Connected devices"), el(2, "Phone"))
        assertEquals(2, (Grounding.bestMatch(home, "Phone") as MatchOutcome.Found).element.id)
        // No Phone app on this screen: the device labels are not offered in its place.
        assertTrue(Grounding.bestMatch(home.take(2), "Phone") !is MatchOutcome.Found)
        // A different phrase with a device word does not become a different entry.
        assertTrue(Grounding.bestMatch(settingsBottom, "Reset phone") !is MatchOutcome.Found)
    }

    @Test fun the_nearest_labels_share_a_real_word_shortest_first() {
        assertEquals(listOf("About emulated device"), Grounding.nearLabels(settingsBottom, "About phone"))
        assertEquals(listOf("Safety & emergency"), Grounding.nearLabels(settingsBottom, "emergency contacts"))
        assertEquals(emptyList<String>(), Grounding.nearLabels(settingsBottom, "Wi-Fi"))
        // Filler words and short words never make a neighbour.
        assertEquals(emptyList<String>(), Grounding.nearLabels(settingsBottom, "the option"))
        val many = listOf(el(0, "Sound & vibration settings"), el(1, "Sound"), el(2, "Notification sound"), el(3, "Sound effects"))
        assertEquals(listOf("Sound", "Sound effects", "Notification sound"), Grounding.nearLabels(many, "sound profile"))
    }
}
