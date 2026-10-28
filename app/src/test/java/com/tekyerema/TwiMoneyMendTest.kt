package com.tekyerema

import com.tekyerema.language.TwiCommands
import com.tekyerema.language.TwiReply
import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner's offline Twi money run on the Infinix, 28 Oct 2026 (as the on-phone recogniser wrote it). */
class TwiMoneyMendTest {
    private val contacts = setOf("richmond")

    @Test fun gluedAndClippedMoneyWordsAreASend() {
        assertEquals("send 5 cedis to my wife", TwiCommands.gloss("somacedie nu ma me yere"))
        assertEquals("send 5 cedis to my wife", TwiCommands.gloss("soma cedi anu ma me yere"))
        assertEquals("send 5 cedis to my wife", TwiCommands.gloss("somacede anu ma me yere"))
        assertEquals("send 5 cedis to my wife", TwiCommands.gloss("so mase di enum ma me yere"))   // already fine: unchanged
    }

    @Test fun theAnswerToWhoLosesItsToWords() {
        assertEquals("ricmund", TwiReply.toEnglish("so makoma ricmund"))
        assertEquals("Richmond", TwiReply.toEnglish("kɔma Richmond"))
        assertEquals("my wife", TwiReply.toEnglish("kɔma me yere"))
    }

    @Test fun aContactNameALetterOffIsStillAPerson() {
        assertEquals("send money to Ricmond", TwiCommands.gloss("soma sika ma ricmond") { it.lowercase() in contacts || it.lowercase() == "ricmond" })
    }

    @Test fun aOneWordNameALetterOffAContactIsAContactPhrase() {
        val v = com.tekyerema.voice.EntityCorrector.Vocab.of(listOf("Richmond Owusu", "Prince"), emptyList())
        assertEquals(true, com.tekyerema.voice.SpeechVocabulary.isContactPhrase("ricmond", v))
        assertEquals(true, com.tekyerema.voice.SpeechVocabulary.isContactPhrase("ricmund", v))
        assertEquals(false, com.tekyerema.voice.SpeechVocabulary.isContactPhrase("aduane", v))
        assertEquals(false, com.tekyerema.voice.SpeechVocabulary.isContactPhrase("majority", v))
    }
}
