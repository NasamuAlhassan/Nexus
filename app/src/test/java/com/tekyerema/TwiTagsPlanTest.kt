package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.language.EntityTags
import com.tekyerema.language.ReplyLocalizer
import com.tekyerema.language.TranslationProvider
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.ConfirmTemplates
import com.tekyerema.voice.PromptSurface
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's Twi plan of 2 October 2026: A names/amounts/numbers as tags, B amounts and numbers in English (spoken by the
 *  English voice), C Twi confirmation pop-ups, D a yes/no the phone's recogniser can hear. */
class TwiTagsPlanTest {

    // ---------------------------------------------------------------- A: tags

    @Test fun `a Twi request becomes tags - names, amounts, numbers`() {
        val t = EntityTags.protect("Soma 5 cedis ma Ama")
        assertEquals("Soma [A1] ma [P1]", t.text)
        assertEquals(listOf("5 cedis", "Ama"), t.entities.map { it.value })
        assertEquals("frɛ [P1]", EntityTags.protect("frɛ kofi").text)                 // the recogniser writes names in lower case
        assertEquals("Kofi", EntityTags.protect("frɛ kofi").entities.single().value)
        val n = EntityTags.protect("Soma 10 cedis kɔ 0552093690")
        assertEquals("Soma [A1] kɔ [N1]", n.text)
        assertEquals("0552093690", n.entities.first { it.kind == EntityTags.Kind.NUMBER }.value)
        // "ama" is also a Twi word ("has given"): only a name where a person goes.
        assertEquals("na mekenkan ama wo", EntityTags.protect("na mekenkan ama wo").text)
        // A saved contact, two words.
        val c = EntityTags.protect("soma sika ma prince melanin") { if (it.equals("prince melanin", true)) "Prince Melanin" else null }
        assertEquals("soma sika ma [P1]", c.text)
        assertEquals("Prince Melanin", c.entities.single().value)
    }

    @Test fun `tags must come back exactly - or the line is not used`() {
        val t = EntityTags.protect("Soma 5 cedis ma Ama")
        assertTrue(EntityTags.intact(t.text, "Send [A1] to [P1]", t.entities))
        assertFalse(EntityTags.intact(t.text, "Send money to [P1]", t.entities))       // the amount was dropped
        assertFalse(EntityTags.intact(t.text, "Send [A1] to [P1] and [P1]", t.entities))
        assertFalse(EntityTags.intact(t.text, "Send [A1] to [P2]", t.entities))        // an invented tag
        assertEquals("Send 5 cedis to Ama", EntityTags.restore("Send [A1] to [P1]", t.entities))
    }

    @Test fun `the planner's decision gets the real values back - arguments, questions, answers`() {
        val t = EntityTags.protect("Soma 5 cedis ma Ama")
        val call = AgentDecision.CallTool("mobile_money", JSONObject().put("contact", "[P1]").put("amount", "[A1]")
            .put("nested", JSONObject().put("note", "for [P1]")), "send [A1] to [P1]")
        val back = RoutingBrain.TagRestore.restoreTags(call, t.entities) as AgentDecision.CallTool
        assertEquals("Ama", back.args.getString("contact"))
        assertEquals("5 cedis", back.args.getString("amount"))
        assertEquals("for Ama", back.args.getJSONObject("nested").getString("note"))
        val ask = RoutingBrain.TagRestore.restoreTags(AgentDecision.CallTool("ask_user",
            JSONObject().put("question", "Send [A1] to [P1] now?"), null), t.entities) as AgentDecision.CallTool
        assertEquals("Send 5 cedis to Ama now?", ask.args.getString("question"))
        assertEquals("Sent [A1]… to Ama", (RoutingBrain.TagRestore.restoreTags(AgentDecision.Done("Sent [A1]… to [P1]"),
            listOf(t.entities.last())) as AgentDecision.Done).summary)
    }

    /** The PC-side chain check of 2 October 2026 (audit/twi-plan-2026-10-02): what went wrong, fixed. */
    @Test fun `a garbled recipient is never named - and a swallowed ma still finds Ama`() {
        // "send 10 cedis to ziroffvtziro ne tsi sir": a phone number the recogniser destroyed — kept as heard, and never
        // anyone's NAME: the money flow asks for the number again (end-to-end pass, 6 October 2026).
        val g = com.tekyerema.language.TwiCommands.gloss("somatensidisma ziroffvtziro ne tsi sir")!!
        val a = com.tekyerema.money.MoneyAsk.parse(g)!!
        assertEquals(null, a.name); assertEquals(null, a.number); assertEquals("10", a.amount)
        assertEquals("ziroffvtziro ne tsi sir", a.lostNumber)
        // "Soma sidi anum ma Ama" heard as "so masi di anua ama": soma, 5 cedis, Ama.
        assertEquals("send 5 cedis to Ama", com.tekyerema.language.TwiCommands.gloss("so masi di anua ama"))
        assertEquals("soma [A1] [P1]", EntityTags.protect(com.tekyerema.voice.Normalizer.forTranslation("so masi di anua ama")).text)
        // A saved contact is a person; a typed capitalised name is too; a lower-case unknown word is not.
        assertEquals("send 2 cedis to Akos", com.tekyerema.language.TwiCommands.gloss("soma sidi mmienu ma akos") { it == "akos" })
        assertEquals("send 2 cedis to Selasi", com.tekyerema.language.TwiCommands.gloss("Soma sidi mmienu ma Selasi"))
        assertEquals("send 2 cedis", com.tekyerema.language.TwiCommands.gloss("soma sidi mmienu ma blorpt"))
    }

    /** The owner's own test on the phone, 2 October 2026, 22:31 — the HCI Lab's real transcripts. */
    @Test fun `ma Prince heard as ama prince, and nnum`() {
        assertEquals("send 5 cedis to Prince", com.tekyerema.language.TwiCommands.gloss("somasidi enum ama prince"))
        assertEquals("send 5 cedis to Prince", com.tekyerema.language.TwiCommands.gloss("soma sidi nnum ma prince"))
        // …unless "Ama Prince" really is a saved contact.
        assertEquals("send 5 cedis to Ama Prince",
            com.tekyerema.language.TwiCommands.gloss("somasidi enum ama prince") { it.equals("ama prince", true) })
    }

    // ---------------------------------------------------------------- B: English amounts and numbers in a Twi line

    @Test fun `an English line is tagged with its spelled amounts, numbers and names`() {
        val line = "Send zero point one cedis to Zelia Awini, on zero five five, two zero nine, three six nine zero, with Mobile Money?"
        val t = EntityTags.protectEnglish(line, ReplyLocalizer.protectedTerms(line))
        assertFalse(t.text, t.text.contains("zero"))
        assertTrue(t.text, t.text.contains("[A1]") && t.text.contains("[N1]"))
        assertEquals("0552093690", t.entities.first { it.kind == EntityTags.Kind.NUMBER }.value)
        assertTrue(t.entities.any { it.kind == EntityTags.Kind.PERSON && it.value == "Zelia" })
    }

    private val copyTags = object : TranslationProvider {   // a translator that keeps tags, like ours did 11 of 11
        override val name = "fake"
        override suspend fun translate(text: String, from: String, to: String): String? =
            text.replace("Do you want to send", "Wopɛ sɛ mesoma").replace(" to ", " ma ").replace("?", "?")
    }

    @Test fun `a reply in Twi keeps the name and says the amount in English`() = runTest {
        val out = ReplyLocalizer(copyTags, enabled = { true }).toTwi("Do you want to send five cedis to Ama?")!!
        assertEquals("Wopɛ sɛ mesoma ⟦en:five Ghana cedis⟧ ma Ama?", out)
        assertEquals("Wopɛ sɛ mesoma five Ghana cedis ma Ama?", EntityTags.display(out))
        assertEquals(listOf("Wopɛ sɛ mesoma" to false, "five Ghana cedis" to true, "ma Ama?" to false), EntityTags.segments(out))
        // A translator that loses a tag: the English is spoken instead.
        val lossy = object : TranslationProvider { override val name = "lossy"
            override suspend fun translate(text: String, from: String, to: String) = "Wopɛ sɛ mesoma sika ma [P1]?" }
        assertNull(ReplyLocalizer(lossy, enabled = { true }).toTwi("Do you want to send five cedis to Ama?"))
    }

    // Owner, 4 October 2026: one voice — the lab's Twi voice says the amount (Twi words) and the name; it skipped digits.
    @Test fun `the Twi templates say amounts and names in Twi words, in one voice`() {
        val money = ConfirmTemplates.twi(ConfirmTemplates.Spec(ConfirmTemplates.Spec.Kind.SEND_MONEY, name = "Ama", amount = "5"))
        assertTrue(money, money.contains("wode sidi anum kɔma Ama?"))
        assertFalse(money, EntityTags.hasEnglishSpans(money) || money.any { it.isDigit() })
        val small = ConfirmTemplates.twi(ConfirmTemplates.Spec(ConfirmTemplates.Spec.Kind.SEND_MONEY, name = "Ama", amount = "0.1"))
        assertTrue(small, small.contains("wode pesewa du kɔma Ama?"))
        val call = ConfirmTemplates.twi(ConfirmTemplates.Spec(ConfirmTemplates.Spec.Kind.CALL, name = "Kofi", number = "0552093690"))
        assertEquals("Wopɛ sɛ mefrɛ Kofi? Ka aane anaa daabi.", call)
    }

    // ---------------------------------------------------------------- C: the pop-up

    // Owner, 5 October 2026: ONE language on the pop-up — the Twi line alone in a Twi conversation, never the English under it.
    @Test fun `the pop-up shows the Twi line alone`() {
        val twi = "Wopɛ sɛ mesoma ⟦en:five Ghana cedis⟧ ma Ama? Ka aane anaa daabi."
        val shown = PromptSurface.confirmationText("Send 5 cedis to Ama, with Mobile Money?", twi)!!
        assertEquals("Wopɛ sɛ mesoma five Ghana cedis ma Ama? Ka aane anaa daabi.", shown)
        assertFalse(shown, shown.contains("⟦") || shown.contains("Send 5 cedis"))
        assertEquals("Send it?", PromptSurface.confirmationText("Send it?", null))
    }

    // ---------------------------------------------------------------- D: yes / no

    @Test fun `aane and daabi as the phone's recogniser writes them`() {
        assertEquals(Confirmation.Answer.YES, Confirmation.interpret("Anne"))
        assertEquals(Confirmation.Answer.YES, Confirmation.interpret("aane"))
        assertEquals(Confirmation.Answer.NO, Confirmation.interpret("Darby"))
        assertEquals(Confirmation.Answer.NO, Confirmation.interpret("daabi"))
        assertEquals(Confirmation.Answer.YES, Confirmation.interpret("yes"))
        assertNotNull(Confirmation.interpret("so"))   // the HCI Lab's "so" is NOT taken as a yes
        assertFalse(Confirmation.interpret("so") == Confirmation.Answer.YES)
    }
}
