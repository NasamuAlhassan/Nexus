package com.tekyerema

import com.tekyerema.language.TwiCommands
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Khaya's Twi recogniser really writes (A20e and its own voice, 16 Oct 2026), and what the phone makes of it. */
class TwiKhayaHeardTest {
    private fun gloss(heard: String) = TwiCommands.gloss(Normalizer.normalize(heard, Lang.TWI))

    @Test fun `Khaya's commas and spellings still give the command`() {
        assertEquals("send 5 cedis to Prɛns Melani", gloss("Soma Sidi anum, Ma Prɛns, Melani."))
        assertEquals("send 5 cedis to Prins Melani", gloss("Soma Sidi enum ma Prins Melani."))
        assertEquals("send 5 cedis to 0552093690", gloss("Soma 5seed is ma 0552093690."))
    }

    @Test fun `call him now as Khaya hears it is never a call to a contact named no se sei`() {
        assertEquals("now call him", gloss("Frɛ no sɛ, sei!"))
        assertEquals("now call him", gloss("Frɛ no seesei."))
    }

    @Test fun `abo sen asks the time`() {
        assertEquals("what's the time", gloss("Abɔ sɛn?"))
        assertEquals("what's the time", gloss("Seesei abɔ sɛn?"))
        assertNull(gloss("Toa so."))                       // an answer, not a command
    }
}
