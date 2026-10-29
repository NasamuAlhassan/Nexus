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

    /** The owner's five demo recordings (18 Oct 2026, Demo Records/), as OUR OWN Twi model on the Space wrote them. */
    @Test fun `the owner's demo recordings as our own model hears them`() {
        assertEquals("read the message my wife sent", gloss("kenkan nkra a me yere so ma me no"))
        assertEquals("send 5 cedis to my wife", gloss("soma cedi enum ma me yere"))
        assertEquals("call my wife", gloss("frɛ me yere seeseir"))
        assertEquals(Confirmation.Answer.YES, Confirmation.interpret("aduane aane"))
        assertEquals("aduane", com.tekyerema.money.MoneyFlow.referenceOf("aduane"))
        assertEquals(Confirmation.Answer.NO, Confirmation.interpret("daabi se di du"))
    }

    /** The phone's own Twi model, offline (Infinix, 29 Oct 2026): both were "that needs the internet". */
    @Test fun `the message she sent, as the phone hears it offline`() {
        assertEquals("read the message my wife sent", gloss("kenkan nkoraa no a me yere so ma me no"))
        assertEquals("read the message my wife sent", gloss("nkraa no a me yere so ma me no"))      // "kenkan" lost
        assertEquals("read the message my wife sent", gloss("kenkan nkrataa no a me yere soma me no"))
        assertEquals("read the message my wife sent", gloss("kenkan nkra a meyere somaa me"))
        assertEquals("read the message my wife sent", gloss("kenkran nkran no a me yere so ma me no"))
        assertEquals("read my messages", gloss("thinkan me nkra"))
        // the new demo's person (a woman records it): "me kunu" — my husband
        assertEquals("read the message my husband sent", gloss("kenkan nkra a me kunu somaa me no"))
        assertEquals("read the message my husband sent", gloss("kenkan nkoraa no a me kunu so ma me no"))
        assertEquals("read the message my husband sent", gloss("Kenkan me kunu nkra no."))
        assertEquals("send 5 cedis to my husband", gloss("Soma sidi anum ma me kunu."))
        assertEquals("call my husband", gloss("Frɛ me kunu."))
    }
}
