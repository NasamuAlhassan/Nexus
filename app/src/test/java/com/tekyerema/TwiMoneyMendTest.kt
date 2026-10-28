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

    @Test fun howMuchMoneyIsLeftIsTheMoMoBalance() {
        assertEquals("check my mobile money balance", TwiCommands.gloss("me sika arka sɛn"))
        assertEquals("check my mobile money balance", TwiCommands.gloss("me sika aka sɛn"))
    }

    @Test fun aTwiAmountCorrectionAtTheReadBackApplies() {
        val args = org.json.JSONObject().put("amount", "5").put("contact", "my wife").put("reference", "aduane")
        for (said in listOf("e cedi bko", "sidi baako", "yɛ no sidi baako")) {
            val r = com.tekyerema.agent.ReviseTurn.patch("ussd_navigate", args, said)
                ?: com.tekyerema.agent.ReviseTurn.patch("ussd_navigate", args, TwiReply.toEnglish(said))
            assertEquals(said, "1", r?.args?.optString("amount"))
        }
    }

    @Test fun changeToEnglishSaidInTwi() {
        for (said in listOf("sesakɔ borɔfo", "sesa kɔ borɔfo", "sesa kasa no kɔ borɔfo"))
            assertEquals(said, com.tekyerema.agent.FastIntent.Preference("language", "english"), com.tekyerema.agent.FastIntentClassifier.classify(said))
        assertEquals(com.tekyerema.agent.FastIntent.Preference("language", "twi"), com.tekyerema.agent.FastIntentClassifier.classify("sesa kɔ twi"))
    }

    /** The phone's English recogniser on the Infinix, 28 Oct 2026, offline. */
    @Test fun misheardMoneyWordsInEnglish() {
        assertEquals(com.tekyerema.money.MoneyAsk.Kind.BALANCE_MOMO, com.tekyerema.money.MoneyAsk.parse("Where's my normal balance?")?.kind)
        assertEquals(com.tekyerema.money.MoneyAsk.Kind.BALANCE_MOMO, com.tekyerema.money.MoneyAsk.parse("what's my mom balance")?.kind)
        val a = com.tekyerema.money.MoneyAsk.parse("I want you to back once this airtime for 0552093690")
        assertEquals(com.tekyerema.money.MoneyAsk.Kind.AIRTIME, a?.kind)
        assertEquals("1", a?.amount); assertEquals("0552093690", a?.number)
        // not about airtime: "once" and "back" stay as said
        assertEquals("call me back once this is done", com.tekyerema.voice.GhanaWords.fix("call me back once this is done"))
        assertEquals("my mom called", com.tekyerema.voice.GhanaWords.fix("my mom called"))
    }

    /** "Twi" as the phone's English recogniser wrote it on the Infinix, 28 Oct 2026. */
    @Test fun switchingToTwiWhenTwiIsMisheard() {
        val twi = com.tekyerema.agent.FastIntent.Preference("language", "twi")
        val eng = com.tekyerema.agent.FastIntent.Preference("language", "english")
        for (said in listOf("switch the language to tree", "10 the language to cheat", "Change the language to the truth.", "change the language to Twi",
                "switch to tree", "speak tree"))
            assertEquals(said, twi, com.tekyerema.agent.FastIntentClassifier.classify(said))
        assertEquals(eng, com.tekyerema.agent.FastIntentClassifier.classify("change the language to English"))
        // not a switch
        for (said in listOf("what language to use", "translate this language to French", "what language is spoken in Ghana"))
            assertEquals(said, false, com.tekyerema.agent.FastIntentClassifier.classify(said) is com.tekyerema.agent.FastIntent.Preference)
    }

    @Test fun airtimeAsTheRecogniserWroteIt() {
        for (said in listOf("Buy 1 CD8 time for 055 2093 690", "I want you to buy 1 series of airtime to 0552093690")) {
            val a = com.tekyerema.money.MoneyAsk.parse(said)
            assertEquals(said, com.tekyerema.money.MoneyAsk.Kind.AIRTIME, a?.kind)
            assertEquals(said, "1", a?.amount)
        }
        assertEquals(true, com.tekyerema.money.MtnApp.GOAL.containsMatchIn("how much money do I have"))
    }
}
