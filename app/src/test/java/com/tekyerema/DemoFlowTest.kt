package com.tekyerema

import com.tekyerema.money.ussd.TxContext
import com.tekyerema.money.ussd.TxIntent
import com.tekyerema.money.ussd.Boundary
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.voice.ConfirmTemplates
import com.tekyerema.voice.ConfirmTemplates.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Twi demo (owner, 4 October 2026): what the phone says for each demo phrase, word for word, in ONE voice — the
 *  lab's Twi voice says the name and the amount too. These exact lines are recorded once (TwiLabLinesTest →
 *  campaign/twi_lab_clips.py), so they play instantly. */
object DemoFlow {
    const val CALL = "Wopɛ sɛ mefrɛ Kwame? Ka aane anaa daabi."
    const val CALLING = "Merefrɛ Kwame."
    const val OKAY = "Yoo."
    const val SEND = "Menbue Mobile Money sɛ wode sidi baako kɔma Kwame, nɔma a n'awieɛ yɛ mmiɛnsa anan anum nsia? Nɔma yi yɛ foforɔ. Wo ankasa na wobɛhyɛ PIN. Ka aane anaa daabi."
    const val AIRTIME = "Menbue Mobile Money sɛ wobɛtɔ airtime sidi baako? Wo ankasa na wobɛhyɛ PIN. Ka aane anaa daabi."
    /** The PIN line for the airtime, as the voice gets it (Verbalize spells "MTN" out). */
    val AIRTIME_PIN: String get() = com.tekyerema.voice.Verbalize.apply("MTN se: airtime sidi baako ma wo fɔn yi. Wo ankasa na wobɛhyɛ PIN.")

    fun lines(): List<Pair<String, String>> = listOf(CALL to "call read-back", OKAY to "yes ack", CALLING to "call result",
        SEND to "send read-back (new number)", AIRTIME to "airtime read-back", AIRTIME_PIN to "airtime PIN line")
}

class DemoFlowTest {
    @Test fun `the read-backs are one Twi line each - name and amount in Twi words, no English stretch, no digits`() {
        assertEquals(DemoFlow.CALL, ConfirmTemplates.twi(Spec(Spec.Kind.CALL, name = "Kwame", number = "0244123456")))
        assertEquals(DemoFlow.SEND, ConfirmTemplates.twi(Spec(Spec.Kind.SEND_MONEY, name = "Kwame", number = "0244123456", amount = "1", firstTime = true)))
        assertEquals(DemoFlow.AIRTIME, ConfirmTemplates.twi(Spec(Spec.Kind.BUY_AIRTIME, amount = "1")))
        for ((l, _) in DemoFlow.lines()) {
            assertFalse(l, com.tekyerema.language.EntityTags.hasEnglishSpans(l))
            assertFalse(l, l.any { it.isDigit() })
        }
        // a number with no name: Twi digit groups, still one voice
        val bare = ConfirmTemplates.twi(Spec(Spec.Kind.CALL, number = "0552093690"))
        assertEquals("Wopɛ sɛ mefrɛ zero anum anum, mmienu zero nkron, mmiɛnsa nsia nkron zero? Ka aane anaa daabi.", bare)
    }

    @Test fun `amounts in Twi words`() {
        assertEquals("sidi baako", ConfirmTemplates.twiAmount("1"))
        assertEquals("pesewa du", ConfirmTemplates.twiAmount("0.10"))
        assertEquals("sidi du ne pesewa aduonum", ConfirmTemplates.twiAmount("10.50"))
        assertEquals("sidi aduonu anum", ConfirmTemplates.twiAmount("25"))
        assertEquals("sidi ɔha ne aduonum", ConfirmTemplates.twiAmount("150"))
        assertEquals(null, ConfirmTemplates.twiAmount("2500"))
    }

    @Test fun `the PIN lines in a Twi conversation are one Twi sentence`() {
        // airtime for this phone
        val air = TxContext(TxIntent.BUY_AIRTIME, "mtn", amount = "1", confirmedUpFront = true)
        val airPin = MenuModel.read("You are buying airtime of GHS 1.00 for yourself. Fee is GHS 0.00. Enter MM PIN\nCancel", hasInput = true, masked = false)
        assertEquals(MenuModel.Kind.PIN, airPin.kind)
        val v = Boundary.decide(air, airPin, twi = true)
        assertTrue("$v", v is Boundary.Verdict.HandOff)
        assertEquals(DemoFlow.AIRTIME_PIN, com.tekyerema.voice.Verbalize.apply((v as Boundary.Verdict.HandOff).line))
        // a send: the network's registered name, the amount in Twi words (the owner's live 10-pesewa screen, 29 September)
        val real = "Transfer to AMA MENSAH for GHS 0.1 with Reference: Payment. Fee is GHS 0.00, Tax amount is GHS 0.00. " +
            "Total Amount is GHS 0.10. Enter MM # for next\nCancel"
        val send = TxContext(TxIntent.SEND_MONEY, "mtn", recipient = "0553238011", amount = "0.1", confirmedUpFront = true)
        val sv = Boundary.decide(send, MenuModel.read(real, hasInput = true, masked = false), twi = true) as Boundary.Verdict.HandOff
        assertEquals("MTN se: pesewa du kɔma Zelia Awini. Wo ankasa na wobɛhyɛ PIN.", sv.line)
        // English conversations are unchanged
        assertTrue((Boundary.decide(air, airPin, twi = false) as Boundary.Verdict.HandOff).line.contains("enter your PIN"))
    }
}

/** Owner, 5 October 2026: a reference is asked for, never invented; and every prompt is in ONE language, the user's. */
class ReferenceAndOneLanguageTest {
    @Test fun `no reference is ever made up`() {
        assertEquals(null, TxContext(TxIntent.SEND_MONEY, "mtn", recipient = "0552093690", amount = "0.2")
            .slot(com.tekyerema.money.ussd.SlotKind.REFERENCE))
        assertEquals("rent", TxContext(TxIntent.SEND_MONEY, "mtn", reference = "rent").slot(com.tekyerema.money.ussd.SlotKind.REFERENCE))
    }

    @Test fun `the user's reference is typed as said - none means MTN's usual Payment`() {
        val g = com.tekyerema.money.TelcoGuide
        assertEquals("rent", g.referenceOf("Rent.".lowercase()))
        assertEquals("school fees 2", g.referenceOf("school fees 2"))
        assertEquals("Payment", g.referenceOf("None"))
        assertEquals("Payment", g.referenceOf("hwee"))
        assertEquals(null, g.referenceOf("  "))
        assertEquals(30, g.referenceOf("x".repeat(50))!!.length)
    }

    @Test fun `a prompt shows one language - Twi when there is a Twi line, else English`() {
        val p = com.tekyerema.voice.PromptSurface
        assertEquals("Wopɛ sɛ mefrɛ Kwame? Ka aane anaa daabi.", p.confirmationText("Call Kwame?", "Wopɛ sɛ mefrɛ Kwame? Ka aane anaa daabi."))
        assertEquals("Call Kwame?", p.confirmationText("Call Kwame?", null))
        assertEquals(listOf("Daabi", "Aane, yɛ"), p.buttons(com.tekyerema.voice.PromptSurface.Kind.CONFIRM, twi = true))
        assertEquals(listOf("No", "Yes, do it"), p.buttons(com.tekyerema.voice.PromptSurface.Kind.CONFIRM))
    }
}
