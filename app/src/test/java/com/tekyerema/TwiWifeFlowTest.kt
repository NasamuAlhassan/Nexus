package com.tekyerema

import com.tekyerema.language.TwiCommands
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner's Twi demo, 17 Oct 2026: one person ("me yere" — my wife) all through, as Khaya's recogniser writes it. */
class TwiWifeFlowTest {
    private fun gloss(heard: String) = TwiCommands.gloss(Normalizer.normalize(heard, Lang.TWI))

    @Test fun `read her message, pay her, tell her, call her`() {
        assertEquals("read the message my wife sent", gloss("Kenkan nkra a me yere somaa me no."))
        assertEquals("read the message my wife sent", gloss("Kenkan me yere nkra no."))
        assertEquals("send 5 cedis to my wife", gloss("Soma sidi anum ma me yere."))
        assertEquals("send 5 cedis to my wife", gloss("Soma sidi anum, ma me yere."))
        assertEquals("send 5 cedis to my wife", gloss("Soma sidi enum ma me yere"))
        assertEquals("tell him I've sent it", gloss("Ka kyerɛ no sɛ, masoma."))      // "him" is whoever was paid last
        assertEquals("call my wife", gloss("Frɛ me yere."))
        assertEquals("call my wife", gloss("Frɛ me yere seesei."))                   // was a call to "me yere seesei"
        assertEquals("now call him", gloss("Frɛ no seesei."))
    }

    @Test fun `yes and no as Khaya writes them`() {
        for (y in listOf("Aane.", "Aane!", "Aane aane", "Yoo.", "Ɛyɛ.")) assertEquals(y, Confirmation.Answer.YES, Confirmation.interpret(y))
        for (n in listOf("Daabi.", "Daabi!", "Dabi")) assertEquals(n, Confirmation.Answer.NO, Confirmation.interpret(n))
    }
}
