package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.language.TwiCommands
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.ConfirmTemplates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The owner's A20e run, 21 Oct 2026 (03:51-04:01): every line here came from the phone's log. */
class OwnerNightBugsTest {
    @Before fun setUp() = AppConfig.loadFrom(emptyList())
    @After fun tearDown() = AppConfig.loadFrom(emptyList())

    private val momo = "Send 5 cedis to My wife, ending 3 6 9 0, an MTN number, with Mobile Money, reference “transport”?"

    @Test fun twiSendPhrasingsAreUnderstoodOnThePhone() {
        // the Space heard it; the phone sent it to the planner, which asked for the wife's phone number
        val a = TwiCommands.gloss("mepɛsɛ wosoma 5 cedis ama me yere")
        assertNotNull(a); assertTrue(a, a!!.contains("5 cedis") && a.contains("wife"))
        assertTrue(TwiCommands.gloss("soma 5 cedis kɔma me yere")!!.contains("5 cedis"))
        // the amount lost by the recogniser: money to my wife with no amount (the money flow asks how much)
        val noAmount = TwiCommands.gloss("mepɛsɛ wosoma m cedis kɔma me yere")
        assertNotNull(noAmount); assertTrue(noAmount, noAmount!!.contains("wife"))
    }

    @Test fun correctionsGoToTheBrainNotToADecline() {
        assertTrue(Confirmation.looksLikeRevision("no let the reference be food", momo))
        assertTrue(Confirmation.looksLikeRevision("change the reference to call me", momo))
        assertTrue(Confirmation.looksLikeRevision("no you send 50 pesos rather and change the reference to food", momo))
        assertTrue(Confirmation.looksLikeRevision("daabi fa ma Kofi", momo))
        assertTrue(Confirmation.looksLikeRevision("daabi fasiidiidum", momo))   // the Twi recogniser glued "fa sidi du"
        // a decline stays a decline
        assertFalse(Confirmation.looksLikeRevision("no", momo))
        assertFalse(Confirmation.looksLikeRevision("I said no", momo))
        assertFalse(Confirmation.looksLikeRevision("no thanks", momo))
        assertFalse(Confirmation.looksLikeRevision("no cancel it", momo))
        assertFalse(Confirmation.looksLikeRevision("no don't send it", momo))
        assertTrue(Confirmation.isBareNo("daabi")); assertTrue(Confirmation.isBareNo("No.")); assertFalse(Confirmation.isBareNo("no send 10"))
    }

    @Test fun theTwiReadBackSaysTheNumbersEnding() {
        assertEquals(", nɔma a n'awieɛ yɛ mmiɛnsa nsia nkron sero", ConfirmTemplates.endingTwi("My wife", "0552093690"))
        assertEquals("", ConfirmTemplates.endingTwi(null, "0552093690"))
        assertEquals("", ConfirmTemplates.endingTwi("Ama", null))
    }

    @Test fun twiNumberVariants() {
        assertTrue(com.tekyerema.voice.Normalizer.normalize("soma sidi dunum ma Ama", com.tekyerema.voice.Lang.TWI).contains("15"))
    }

    @Test fun theAppsOwnLinesHaveFixedTwi() {
        AppConfig.loadFrom(listOf("locales.json" to """{"locales":{"twFixed":{"Cancelled. Nothing was sent.":"Magyae. Mansoma hwee."}}}"""))
        assertEquals("Magyae. Mansoma hwee.", com.tekyerema.language.ReplyLocalizer.fixedTwi("Cancelled. Nothing was sent."))
    }
}
