package com.tekyerema

import com.tekyerema.agent.SettingsPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N-09 (3) — which Settings pages a local plan opens by their own intent, and how their titles are checked. */
class SettingsPagesTest {

    @Test fun the_pages_android_opens_directly() {
        assertEquals("battery_usage", SettingsPages.find("battery")?.target)
        assertEquals("battery_usage", SettingsPages.find("the battery page")?.target)
        assertEquals("battery_usage", SettingsPages.find("Battery usage")?.target)
        assertEquals("battery", SettingsPages.find("battery saver")?.target)
        assertEquals("about_phone", SettingsPages.find("about phone")?.target)
        assertEquals("about_phone", SettingsPages.find("About emulated device")?.target)
        assertEquals("about_phone", SettingsPages.find("about")?.target)
        assertEquals("dnd_page", SettingsPages.find("Do Not Disturb")?.target)
        assertEquals("dnd_page", SettingsPages.find("the do not disturb settings")?.target)
        assertEquals("display", SettingsPages.find("Display")?.target)
        assertEquals("sound", SettingsPages.find("sound & vibration")?.target)
        assertEquals("language", SettingsPages.find("languages and input")?.target)
        assertEquals("date", SettingsPages.find("date and time")?.target)
        assertEquals("apps", SettingsPages.find("Apps")?.target)
        assertEquals("notifications", SettingsPages.find("notifications")?.target)
    }

    @Test fun titles_are_fragments_that_survive_the_phones_wordings() {
        assertEquals("About", SettingsPages.find("about phone")?.title)        // "About phone" / "About emulated device"
        assertEquals("Sound", SettingsPages.find("sound")?.title)              // "Sound & vibration"
        assertEquals("Language", SettingsPages.find("languages")?.title)       // "Languages" / "Language and input"
        assertEquals("Settings is open, on Do Not Disturb.", "Settings is open, on ${SettingsPages.find("dnd")!!.spoken}.")
    }

    @Test fun pages_without_a_deep_link_stay_with_the_tap_path() {
        assertNull(SettingsPages.find("Software information"))
        assertNull(SettingsPages.find("Wi-Fi"))                 // its title differs by phone ("Internet" / "Wi-Fi"): the tap path checks the label it tapped
        assertNull(SettingsPages.find("network and internet"))
        assertNull(SettingsPages.find("connections"))
        assertNull(SettingsPages.find(""))
        assertNull(SettingsPages.find("the page"))
    }
}
