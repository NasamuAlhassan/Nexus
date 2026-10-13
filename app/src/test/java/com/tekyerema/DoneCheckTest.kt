package com.tekyerema

import com.tekyerema.agent.DoneCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** P1-14: a request that names a page is done only on that page. */
class DoneCheckTest {
    private val settingsList = listOf("Settings", "Search settings", "Network & internet")

    @Test fun the_pages_a_request_names() {
        assertEquals(listOf("battery"), DoneCheck.targets("open settings and go to the battery page and tell me the percentage"))
        assertEquals(listOf("about phone"), DoneCheck.targets("Open Settings, then go to about phone and tell me the android version"))
        assertEquals(emptyList<String>(), DoneCheck.targets("turn on do not disturb"))
        assertEquals(emptyList<String>(), DoneCheck.targets("open chrome and search for kwame nkrumah"))
        assertEquals(emptyList<String>(), DoneCheck.targets("what is my battery level"))
    }

    @Test fun the_settings_list_is_not_the_battery_page_even_though_it_lists_battery() {
        val r = DoneCheck.check("open settings and go to the battery page", "Settings", settingsList + "Battery")
        assertNotNull(r)
        assertEquals("battery", r!!.target); assertEquals("Settings", r.shown)
        assertEquals("The screen in front is \"Settings\", not the battery page. Finish getting there, or say what is in the way.", r.note)
    }

    @Test fun the_battery_page_by_its_title_or_its_heading() {
        assertNull(DoneCheck.check("open settings and go to the battery page", "Battery", listOf("Battery", "100%")))
        assertNull(DoneCheck.check("open settings and go to the battery page", null, listOf("Navigate up", "Battery", "100%")))
        assertNull(DoneCheck.check("go to the battery settings", "Battery usage", listOf("Battery usage")))   // stem
    }

    @Test fun about_phone_on_the_emulator_is_about_emulated_device_and_wifi_matches_its_own_spelling() {
        assertNull(DoneCheck.check("open settings and go to about phone and tell me the android version", "About emulated device", listOf("About emulated device")))
        assertNull(DoneCheck.check("take me to the Wi-Fi settings", "Wi‑Fi", listOf("Internet", "Wi-Fi")))
        assertNotNull(DoneCheck.check("take me to the Wi-Fi settings", "Settings", settingsList))
    }

    @Test fun requests_that_name_no_page_are_never_checked() {
        assertNull(DoneCheck.check("turn on do not disturb", "Settings", settingsList))
        assertNull(DoneCheck.check("send Ama see you at 6", "Ama", listOf("Ama")))
        assertNull(DoneCheck.check("open settings and go to the page", "Settings", settingsList))   // only stop words → nothing to check
    }

    @Test fun the_screen_is_named_by_its_title_else_its_first_label() {
        assertEquals("Settings", DoneCheck.check("go to the battery page", "Settings", listOf("Network"))!!.shown)
        assertEquals("Network & internet", DoneCheck.check("go to the battery page", null, listOf("Network & internet"))!!.shown)
        assertEquals("an unnamed screen", DoneCheck.check("go to the battery page", "", emptyList())!!.shown)
    }
}
