package com.tekyerema

import com.tekyerema.ime.DeviceTag
import com.tekyerema.ime.LocalLanguages
import com.tekyerema.ime.LocalVoiceLines
import com.tekyerema.ime.ReadBack
import com.tekyerema.ime.SendMode
import com.tekyerema.language.SpaceVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local languages through the keyboard, Phase 1 (docs/agent/KEYBOARD_LANGUAGES.md): the pure parts. */
class LocalVoiceTest {

    @Test fun every_language_with_a_microphone_is_offered_and_dagbani_is_not() {
        assertEquals(listOf("twi", "ewe", "kus", "hau"), LocalLanguages.ALL.map { it.code })
        assertNull("Dagbani has no speech recognition on the Space", LocalLanguages.of("dag"))
        assertEquals(LocalLanguages.TWI, LocalLanguages.of("twi"))
        assertNull(LocalLanguages.of(null))
        assertEquals("Hausa may mishear often for now.", LocalLanguages.HAUSA.warning)
        assertNull(LocalLanguages.TWI.warning)
    }

    @Test fun send_mode_defaults_to_asking() {
        assertEquals(SendMode.ASK, SendMode.of(null))
        assertEquals(SendMode.ASK, SendMode.of("nonsense"))
        assertEquals(SendMode.MINE, SendMode.of("mine"))
        assertEquals(SendMode.ENGLISH, SendMode.of("english"))
    }

    @Test fun the_device_tag_is_64_lowercase_hex_and_never_the_id_itself() {
        val t = DeviceTag.hash("3f1c2d9e-0000-4000-8000-123456789abc")
        assertTrue(t, DeviceTag.valid(t))
        assertEquals(t, DeviceTag.hash("3f1c2d9e-0000-4000-8000-123456789abc"))
        assertNotEquals(t, DeviceTag.hash("another install"))
        assertFalse(DeviceTag.valid("ABC"))
        assertFalse(DeviceTag.valid(t.uppercase()))
        // Renewed each calendar month (UTC).
        assertEquals(202611, DeviceTag.month(1_793_620_000_000L))   // 2 Nov 2026
        assertNotEquals(DeviceTag.month(1_793_620_000_000L), DeviceTag.month(1_793_620_000_000L + 40L * 86_400_000L))
    }

    @Test fun the_twi_read_back_says_figures_as_words_and_others_read_as_typed() {
        val twi = ReadBack.forVoice("Frɛ 0244123456", LocalLanguages.TWI)
        assertTrue("the Twi voice skips digits, so none are sent: $twi", twi.none { it.isDigit() })
        assertTrue(twi.startsWith("Frɛ"))
        assertEquals("Ame do 5", ReadBack.forVoice(" Ame do 5 ", LocalLanguages.EWE))
        assertEquals(ReadBack.MAX, ReadBack.forVoice("a".repeat(900), LocalLanguages.EWE).length)
        assertTrue(ReadBack.tooLong("a".repeat(ReadBack.MAX + 1)))
        assertFalse(ReadBack.tooLong("Me pɛ sɛ me kɔ fie"))
    }

    @Test fun the_space_address_is_https_without_a_path() {
        assertEquals("https://x.hf.space", SpaceVoice.normalize("https://x.hf.space/web/ask/"))
        assertEquals("https://x.hf.space", SpaceVoice.normalize(" https://x.hf.space "))
        assertNull(SpaceVoice.normalize("http://x.hf.space"))
        assertNull(SpaceVoice.normalize(""))
        assertNull(SpaceVoice.normalize(null))
    }

    @Test fun the_status_line_names_the_language() {
        assertEquals("Online — Twi", LocalVoiceLines.online(LocalLanguages.TWI))
        assertEquals("In Ewe or in English?", LocalVoiceLines.ask(LocalLanguages.EWE))
    }
}
