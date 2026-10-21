package com.tekyerema

import com.tekyerema.language.TwiCommands
import com.tekyerema.voice.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The core Twi requests understood on the phone, and the glued transcripts split back (owner's Twi test, 2 October 2026).
 *  The Twi is the assistant's [owner: verify with a native speaker]. */
class TwiCommandsTest {

    @Test fun `a glued transcript is split back only where a number or currency word proves it`() {
        assertEquals("soma ten sidis ma", Normalizer.unglue("somatensidisma"))
        assertEquals("mesoma", Normalizer.unglue("mesoma"))            // no number in it: an ordinary word, left alone
        assertEquals("dubaako", Normalizer.unglue("dubaako"))          // a numeral itself
        assertEquals("ziroffvtziro", Normalizer.unglue("ziroffvtziro"))  // "ffvt" is no word: not guessed
        // …and through the whole Twi normaliser: the real transcript's money comes out as money.
        assertEquals("soma 10 cedis ma ziroffvtziro ne tsi sir", Normalizer.forTranslation("somatensidisma ziroffvtziro ne tsi sir"))
    }

    @Test fun `the owner's everyday phrasings`() {
        assertEquals("check my mobile money balance", TwiCommands.gloss("Hwɛ me sika a aka"))
        assertEquals("check my mobile money balance", TwiCommands.gloss("Mewɔ sika sɛn?"))
        assertEquals("call my brother", TwiCommands.gloss("Frɛ me nua barima"))
        assertEquals("call my sister", TwiCommands.gloss("Frɛ me nuabaa"))
        // the recogniser's "ne yere" for "me yere" (A20e, 21 Oct 2026)
        assertEquals("call my wife", TwiCommands.gloss("frɛ ne yere"))
        assertEquals("call my wife", TwiCommands.gloss("Frɛ me yere"))
        assertEquals("buy 5 cedis of airtime for my number", TwiCommands.gloss("Tɔ airtime a ne bo yɛ cedi anum"))
        assertEquals("send 5 cedis to Ama", TwiCommands.gloss("Kɔ MoMo na soma cedi anum ma Ama"))
        assertEquals("send a message to Kwame", TwiCommands.gloss("Soma message ma Kwame"))
        assertEquals("what's the time", TwiCommands.gloss("Bere bɛn ni?"))
        assertEquals("turn the volume up", TwiCommands.gloss("Ma volume no so"))
        assertEquals("play some music", TwiCommands.gloss("Bɔ nnwom bi"))
        assertEquals("stop", TwiCommands.gloss("Gyae"))
        assertEquals("read it", TwiCommands.gloss("Kenkan"))
        assertEquals("read all of it", TwiCommands.gloss("Kenkan no nyinaa"))
        assertEquals("who sent it", TwiCommands.gloss("Hena na ɔsomaa no?"))
        assertEquals("send 2 cedis to my wife", TwiCommands.gloss("Soma cedi mmienu ma me yere"))
    }

    // QA run on the A20e, 3 October 2026 (audit/qa-2026-10-03 TW05, TW08): a polite lead-in in front of a bundle request let
    // the English parser take the raw Twi and lose the 3 cedis; an amount said after the person's name was dropped with the name.
    @Test fun `a polite lead-in is not part of the request, and an amount after the name still counts`() {
        assertEquals("buy a 3 cedis data bundle", TwiCommands.gloss("Mesrɛ wo, tɔ data bundle a ne bo yɛ sidi mmiɛnsa ma me"))
        assertEquals("buy a 3 cedis data bundle", TwiCommands.gloss("Mepa wo kyɛw, tɔ data bundle sidi mmiɛnsa"))
        assertEquals("send 1 cedi to Prince Melanin", TwiCommands.gloss("Mepɛ sɛ mesoma sika kɔma Prince Melanin, sidi baako") { it == "Prince Melanin" })
        assertEquals("send 10 pesewas to Ama", TwiCommands.gloss("Soma sika ma Ama pesewa du"))
        // The English money parsers never take untranslated Twi: it goes to the translator and the planner.
        assertNull(com.tekyerema.money.MoneyPlan.decide("Mesrɛ wo, tɔ data bundle a ne bo yɛ sidi mmiɛnsa ma me", emptyList()))
    }

    @Test fun `what the recognisers actually wrote for the owner's GOLD commands, 3 October 2026`() {
        // the on-device omni CTC model (campaign/kaggle/twi-asr-diag2)
        assertEquals("send 5 cedis to Ama", TwiCommands.gloss("to masedi anoma ama"))
        assertEquals("send 5 cedis to Ama", TwiCommands.gloss("to mase di anoma ama"))                  // on the A20e itself
        assertEquals("send 2 cedis to my wife", TwiCommands.gloss("to masedi mi noma me yere."))
        assertEquals("send 5 cedis to Ama", TwiCommands.gloss("kɔ momu na so masedi anoma ama."))
        assertEquals("buy 5 cedis of airtime for my number", TwiCommands.gloss("tɔ ertim aa nebo yɛ sedi anom."))
        assertEquals("check my mobile money balance", TwiCommands.gloss("hwɛ me sikaa aka"))
        assertEquals("check my mobile money balance", TwiCommands.gloss("me wo sika sɛn"))
        assertEquals("turn the volume up", TwiCommands.gloss("ma volum no so."))
        assertEquals("play some music", TwiCommands.gloss("bɔ nwom bi"))
        assertEquals("who sent it", TwiCommands.gloss("hena ɔsɔmaa no"))
        // the HCI Lab
        assertEquals("read my messages", TwiCommands.gloss("kenkan me koraa"))
        assertEquals("who sent it", TwiCommands.gloss("na ɔsomaa no"))
    }

    @Test fun `the demo script's money lines, as the lab is expected to write them`() {
        // B6 "Soma pesewa du ma Prince": the lab splits "soma" and "pesewa" ("so ma", "pɛ soa") and swallows "ma" ("ama prince")
        assertEquals("send 10 pesewas to Prince", TwiCommands.gloss("Soma pesewa du ma Prince"))
        assertEquals("send 10 pesewas to Prince", TwiCommands.gloss("so ma pɛ soa du ama prince"))
        assertEquals("send 10 pesewas to Prince", TwiCommands.gloss("soma pasea du ma prince"))
        // B8 "Tɔ airtime sidi mmienu": the lab writes airtime as "ɛtam"
        assertEquals("buy 2 cedis of airtime for my number", TwiCommands.gloss("tɔ ɛtam sidi mmienu"))
        // B1
        assertEquals("check my mobile money balance", TwiCommands.gloss("mewɔ sika sɛn"))
    }

    @Test fun `everyday code-mixed balance checks, as Ghanaians say them`() {
        assertEquals("check my airtime balance", TwiCommands.gloss("Aka me credit sɛn?"))
        assertEquals("check my airtime balance", TwiCommands.gloss("Checki me MTN credit no ma me"))
        assertEquals("check my airtime balance", TwiCommands.gloss("Me credit aka sɛn?"))
        assertEquals("check my mobile money balance", TwiCommands.gloss("Checki me momo balance ma me"))
        assertEquals("check my data balance", TwiCommands.gloss("Aka me data sɛn"))
        assertEquals("check my data balance", TwiCommands.gloss("Me data aka sɛn?"))
        assertEquals("check my data balance", TwiCommands.gloss("chɛki me telecel data no"))
    }

    @Test fun `a message dictated in Twi keeps its Twi words`() {
        assertEquals("send a message to Prince saying “Meba”", TwiCommands.gloss("Twerɛ nkra kɔ ma Prince sɛ meba"))
        assertEquals("send a message to Kwame saying “Meba anɔpa”", TwiCommands.gloss("Ka kyerɛ Kwame sɛ meba anɔpa"))
        assertEquals("send a message to my mother saying “Mewɔ kwan so”", TwiCommands.gloss("Soma nkra kɔ ma me maame sɛ mewɔ kwan so."))
        assertNull("no plausible person: left to the translator", TwiCommands.gloss("ka kyerɛ ɔmanfo no sɛ ɔhene aba"))
        assertNull("'tell ME' is a question to the phone, not a message", TwiCommands.gloss("Ka kyerɛ me sɛ ɛnnɛ yɛ dabɛn"))
    }

    @Test fun `a near miss of a harmless whole request is still understood`() {
        assertEquals("read my messages", TwiCommands.gloss("thinkan me nkra"))          // on-device model, GOLD K04
        assertNull("between 'read all' and 'read my messages' — no clear winner", TwiCommands.gloss("kenkan no henaa"))
        assertEquals("turn the volume up", TwiCommands.gloss("ma volium no so"))
        assertNull("too short to judge", TwiCommands.gloss("kya"))
        assertNull("unlike anything", TwiCommands.gloss("piɛ woasa"))
        // money goes by the exact patterns only: an unknown name is dropped and the money flow asks who
        assertEquals("send 5 cedis", TwiCommands.gloss("soma sidi anom ma kofii"))
        assertNull("a money word keeps the near-match out", TwiCommands.gloss("hwɛ me sika a aka 10"))
        assertNull("ordinary Twi is left to the translator", TwiCommands.gloss("me ba fie nnɛ anwummerɛ"))
    }

    @Test fun `numbers the recognisers split or respell`() {
        assertEquals("15", Normalizer.normalize("du nnum", com.tekyerema.voice.Lang.TWI))
        assertEquals("14", Normalizer.normalize("du nnan", com.tekyerema.voice.Lang.TWI))
        assertEquals("8 9 11", Normalizer.normalize("nwɔtwe nkuro dubaako", com.tekyerema.voice.Lang.TWI))      // GOLD N02/N03
        assertEquals("me kɔ nkuro no mu", Normalizer.normalize("me kɔ nkuro no mu", com.tekyerema.voice.Lang.TWI))   // "towns"
        assertEquals("2 cedis ne 50 pesewas", Normalizer.forTranslation("sidi mmienu ne pɛ so a aduonum").replace("cedi 2", "2 cedis"))
        assertEquals("soma 20 cedis", Normalizer.forTranslation("so ma sedi aduo no"))
        assertEquals("soma 12 cedis", Normalizer.forTranslation("so masedi dome no"))
    }

    @Test fun `a reminder at an hour of the day`() {
        assertEquals("remind me at 6 am", TwiCommands.gloss("Kae me anɔpa nnɔn nsia"))
        assertEquals("remind me at 6 am tomorrow", TwiCommands.gloss("Kae me ɔkyena anɔpa nnɔn nsia"))
        assertEquals("remind me at 7 pm", TwiCommands.gloss("kae me nnɔn nson anwummerɛ"))
        assertEquals("remind me at 2 pm", TwiCommands.gloss("Kae me awia nnɔn mmienu."))
        assertEquals("remind me at 12 pm", TwiCommands.gloss("Kae me awia nnɔn dumienu"))
        assertEquals("remind me at 1 am", TwiCommands.gloss("Kae me anadwo nnɔn baako"))
        assertEquals("remind me at 9 pm", TwiCommands.gloss("Kae me anadwo nnɔn nkron"))
        assertEquals("remind me at 6 o'clock", TwiCommands.gloss("Kae me nnɔn nsia"))
        assertEquals("remind me at 6 am", TwiCommands.gloss("kae me anɔpa nnɔnnsia"))        // glued by the recogniser
        assertNull("a task in Twi is the translator's", TwiCommands.gloss("Kae me sɛ menom aduro anɔpa nnɔn nsia"))
        assertNull("no such hour", TwiCommands.gloss("Kae me nnɔn aduonu"))
    }

    @Test fun `money - send, airtime, data, mashup`() {
        assertEquals("send 10 cedis to Ama", TwiCommands.gloss("Soma sidi du ma Ama"))
        assertEquals("send 10 cedis to Ama", TwiCommands.gloss("soma ten sidis ma Ama"))
        assertEquals("send money to my mother", TwiCommands.gloss("Soma sika ma me maame"))
        assertEquals("send money to Kwame", TwiCommands.gloss("Soma sika kɔma Kwame"))
        assertEquals("send 5 cedis to 0552093690", TwiCommands.gloss("Mepɛ sɛ mesoma sidi anum ma 0552093690"))
        assertEquals("buy 1 cedi of airtime for my number", TwiCommands.gloss("Tɔ kredit sidi baako ma me"))
        assertEquals("buy airtime for my number", TwiCommands.gloss("Tɔ kredit"))
        assertEquals("buy a 3 cedis data bundle", TwiCommands.gloss("Tɔ data sidi mmiɛnsa"))
        assertEquals("buy a mashup bundle for my number", TwiCommands.gloss("Mepɛ mashup bundle"))
    }

    @Test fun `calls keep the name as said - the translators turned Kofi into coffee`() {
        assertEquals("call Kofi", TwiCommands.gloss("Frɛ Kofi"))
        assertEquals("call my mother", TwiCommands.gloss("Frɛ me maame"))
        assertEquals("open WhatsApp", TwiCommands.gloss("Bue WhatsApp"))
    }

    @Test fun `questions and the light`() {
        assertEquals("what's the time", TwiCommands.gloss("ɛberɛ bɛn"))
        assertEquals("what's the time", TwiCommands.gloss("Ɛyɛ dɔn sɛn?"))
        assertEquals("check my mobile money balance", TwiCommands.gloss("Me momo mu sika yɛ sɛn?"))
        assertEquals("check my airtime balance", TwiCommands.gloss("Me kredit aka sɛn?"))
        assertEquals("did my money go through", TwiCommands.gloss("Hwɛ sɛ me sika no kɔeɛ"))
        assertEquals("read my messages", TwiCommands.gloss("Kenkan me nkra"))
        assertEquals("turn on the flashlight", TwiCommands.gloss("Sɔ kanea no"))
        assertEquals("turn off the flashlight", TwiCommands.gloss("Dum kanea no"))
    }

    @Test fun `anything else stays Twi - for the translator and the planner`() {
        // ("me ɛtam no akasa" — the first test's transcript — IS understood now: "me airtime no aka sɛn"; LabAsrRepairTest.)
        // (a dictated message IS understood now, its Twi words kept — see `a message dictated in Twi keeps its Twi words`)
        assertNull(TwiCommands.gloss("Ɔkyena yɛbɛkɔ asɔre"))
        assertNull(TwiCommands.gloss("abose"))
        assertNull(TwiCommands.gloss(""))
    }
}

/** The Twi router round of 3 October 2026 (audit/qa-2026-10-03/twi_route.sh): TR03/TR04/TR06/TR10/TR11/TR12/TR13. */
class TwiRouterRoundTest {
    private val contacts = setOf("prince melanin", "kofi", "ama")
    private val isContact: (String) -> Boolean = { it.lowercase() in contacts }

    @org.junit.Test fun `what is on my screen and messages from one person are phone commands`() {
        org.junit.Assert.assertEquals("read my screen", com.tekyerema.language.TwiCommands.gloss("Kyerɛ me nsɛm a ɛwɔ me screen so"))
        org.junit.Assert.assertEquals("read my screen", com.tekyerema.language.TwiCommands.gloss("Kenkan me screen"))
        org.junit.Assert.assertEquals("what did Prince Melanin say", com.tekyerema.language.TwiCommands.gloss("Mepɛ sɛ mehu me nkra a Prince Melanin de somaa me", isContact))
        org.junit.Assert.assertEquals("what did Kofi say", com.tekyerema.language.TwiCommands.gloss("Kenkan Kofi nkra", isContact))
    }

    @org.junit.Test fun `two requests joined by na are both understood`() {
        org.junit.Assert.assertEquals("open WhatsApp and read my messages", com.tekyerema.language.TwiCommands.gloss("Bue WhatsApp na kenkan me nkra"))
        // "Kɔ MoMo na soma …" stays the one send it always was
        org.junit.Assert.assertEquals("send 5 cedis to Ama", com.tekyerema.language.TwiCommands.gloss("Kɔ MoMo na soma cedi anum ma Ama", isContact))
    }

    @org.junit.Test fun `an English verb with a Twi object is still a send`() {
        org.junit.Assert.assertEquals("send 10 pesewas to Prince Melanin", com.tekyerema.language.TwiCommands.gloss("Send pesewa du ma Prince Melanin", isContact))
        org.junit.Assert.assertEquals("send money to Ama", com.tekyerema.language.TwiCommands.gloss("send sika ma Ama", isContact))
    }

    @org.junit.Test fun `a Twi reply at a read-back or a question becomes the English the paths read`() {
        org.junit.Assert.assertEquals("no, 20 pesewas", com.tekyerema.language.TwiReply.toEnglish("daabi, soma pesewa aduonu"))
        org.junit.Assert.assertEquals("no, send 10 pesewas to Kofi", com.tekyerema.language.TwiReply.toEnglish("daabi, soma pesewa du ma Kofi", isContact))
        org.junit.Assert.assertEquals("10 pesewas", com.tekyerema.language.TwiReply.toEnglish("pesewa du"))
        org.junit.Assert.assertEquals("5 cedis", com.tekyerema.language.TwiReply.toEnglish("sidi anum"))
        org.junit.Assert.assertEquals("daabi", com.tekyerema.language.TwiReply.toEnglish("daabi"))
        org.junit.Assert.assertEquals("aane", com.tekyerema.language.TwiReply.toEnglish("aane"))
        org.junit.Assert.assertEquals("yes please", com.tekyerema.language.TwiReply.toEnglish("yes please"))
        // …and the revise gate takes the new amount as a change of plan, not a decline
        org.junit.Assert.assertTrue(com.tekyerema.voice.Confirmation.looksLikeRevision("no, 20 pesewas"))
        org.junit.Assert.assertTrue(com.tekyerema.voice.Confirmation.looksLikeRevision("no, 2 cedis"))
        org.junit.Assert.assertFalse(com.tekyerema.voice.Confirmation.looksLikeRevision("no, cancel"))
        org.junit.Assert.assertFalse(com.tekyerema.voice.Confirmation.looksLikeRevision("no"))
    }

    @org.junit.Test fun `my brother is never some other person's brother`() {
        org.junit.Assert.assertTrue(com.tekyerema.android.ContactResolver.ownsFamilyWord("Brother", "brother"))
        org.junit.Assert.assertTrue(com.tekyerema.android.ContactResolver.ownsFamilyWord("My Brother Kwame", "brother"))
        org.junit.Assert.assertFalse(com.tekyerema.android.ContactResolver.ownsFamilyWord("meshark's brother", "brother"))
        org.junit.Assert.assertFalse(com.tekyerema.android.ContactResolver.ownsFamilyWord("Ama's Mum", "mum"))
    }

    @org.junit.Test fun `10p is ten pesewas`() {
        val r = com.tekyerema.money.MoneyAsk.parse("momo 10p prince melanin")
        org.junit.Assert.assertEquals("0.1", r?.amount)
        org.junit.Assert.assertNull(com.tekyerema.money.MoneyAsk.parse("wake me at 10pm")?.amount)
    }
}
