package com.tekyerema

import com.tekyerema.language.LanguageSession
import com.tekyerema.language.ReplyLocalizer
import com.tekyerema.language.TranslationProvider
import com.tekyerema.voice.Lang
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The language layer's policy half: which replies may be machine-translated (B-11) and per-turn language state (B-05). */
class LanguageTextTest {

    // ---------------------------------------------------------------- reply localization (MT policy)

    private class FakeMt(val answer: (String) -> String?) : TranslationProvider {
        override val name = "fake-mt"
        var calls = 0
        private var last = ""
        fun lastInput() = last
        override suspend fun translate(text: String, from: String, to: String): String? { calls++; last = text; return answer(text) }
    }

    private fun localizer(mt: FakeMt, on: Boolean = true) = ReplyLocalizer(mt, enabled = { on }, maxChars = { 400 })

    // Fake translator outputs are tagged placeholders, not Twi: nothing here pretends to be a translation.
    // Since 2 October 2026 names, app names, amounts and numbers reach the translator as TAGS ([P1], [A1]) and come back
    // verbatim (language/EntityTags): a translator that keeps the tag keeps the name; one that drops it is not used.
    @Test fun localizer_translates_plain_english_replies_and_keeps_names() = runTest {
        val mt = FakeMt { "[tw] " + Regex("\\[(?:P|A|N)\\d+\\]").findAll(it).joinToString(" ") { m -> m.value } }
        assertEquals("[tw] WhatsApp", localizer(mt).toTwi("I have opened WhatsApp for you"))
        val mangled = FakeMt { "[tw] wasap" }
        assertNull("the app name's tag was lost → speak the original", localizer(mangled).toTwi("I have opened WhatsApp for you"))
        val wrongName = FakeMt { "[tw] Ama" }
        assertNull(localizer(wrongName).toTwi("I will call Kwabena now"))
        // An amount travels as a tag too, and comes back in English for the English voice.
        assertEquals("[tw] ⟦en:20 Ghana cedis⟧ Ama", localizer(mt).toTwi("Sent GH₵ 20 to Ama"))
    }

    @Test fun localizer_never_translates_quantities_twi_or_when_disabled() = runTest {
        val mt = FakeMt { "anything" }
        // A quantity the tagger can't take (a lone "half") still keeps the line in English — the translator never sees it.
        assertNull(localizer(mt).toTwi("Your battery is at half"))
        assertNull("the app's own Twi", localizer(mt).toTwi("Mabue WhatsApp no."))
        assertNull("short Twi lines without ɛ/ɔ are not English either", localizer(mt).toTwi("Mante. San ka bio."))
        assertNull(localizer(mt).toTwi("Yoo."))
        assertNull(localizer(mt, on = false).toTwi("I have opened it for you"))
        assertEquals("the translator was never asked", 0, mt.calls)
        val rambling = FakeMt { "x ".repeat(200) }
        assertNull(localizer(rambling).toTwi("Done"))
        assertNull(ReplyLocalizer(null).toTwi("I have opened it for you"))
    }

    // A20e, 4 October 2026, Twi mode: "hwan na ɔyɛ ɔbarima a ne ho yɛ ntɛm sen biara" (who is the fastest man) was answered
    // "Usain Bolt is the fastest man, holding the 100‑metre world record." — and the ENGLISH voice read all of it, because one
    // number kept the whole reply out of the translator. Every number now travels as a tag and is said in English inside the
    // Twi line (owner's option B, 2 October); the translator never sees one.
    @Test fun a_reply_with_any_number_is_still_said_in_Twi_with_the_number_in_English() = runTest {
        val keepTags = FakeMt { "[tw] " + Regex("\\[(?:P|A|N|Q)\\d+\\]").findAll(it).joinToString(" ") { m -> m.value } }
        val bolt = com.tekyerema.voice.Verbalize.apply("Usain Bolt is the fastest man, holding the 100‑metre world record.")
        val out = localizer(keepTags).toTwi(bolt)
        assertNotNull("the line must go to Twi, not stay English: $bolt", out)
        assertTrue(out, out!!.contains("Usain Bolt"))
        assertTrue(out, Regex("⟦en:[^⟧]*hundred[^⟧]*metre⟧").containsMatchIn(out))
        val sent = keepTags.lastInput()
        assertFalse("no number reached the translator: $sent", sent.replace(Regex("\\[(?:P|A|N|Q)\\d+\\]"), "").contains(Regex("(?i)hundred|\\d")))
        assertTrue(sent, sent.startsWith("[P1] is the fastest man"))
        // counts, times, percentages and USSD codes the same way
        assertEquals("[tw] ⟦en:five⟧", localizer(keepTags).toTwi("You have five new messages"))
        assertEquals("[tw] ⟦en:star one seven zero hash⟧", localizer(keepTags).toTwi("Dial star one seven zero hash to check your balance"))
        assertEquals("[tw] ⟦en:eighty-five percent⟧", localizer(keepTags).toTwi("Your battery is at eighty-five percent"))
        // a lone "one" is a word, not a number
        assertEquals("[tw] Prince Melanin", localizer(keepTags).toTwi("Which one did you mean, Prince Melanin?"))
        // a translator that drops the number's tag is not used — the English is spoken
        assertNull(localizer(FakeMt { "[tw] nothing" }).toTwi("You have five new messages"))
    }

    @Test fun protected_terms_are_names_apps_and_quotes() {
        val terms = ReplyLocalizer.protectedTerms("I couldn't find 'Mum' in WhatsApp. Ask Kofi.")
        assertTrue(terms.containsAll(listOf("Mum", "WhatsApp", "Kofi")))
        assertFalse("I" in terms); assertFalse("Ask" in terms)
        assertTrue(ReplyLocalizer.hasQuantity("It is 5 o'clock"))
        assertFalse(ReplyLocalizer.hasQuantity("Opening the camera"))
    }

    // ---------------------------------------------------------------- per-turn language state

    private class Clock { var now = 0L }

    private fun session(store: Array<String>, clock: Clock) = LanguageSession(
        loadHabitual = { store[0] }, saveHabitual = { store[0] = it }, clock = { clock.now },
        conversationTtlMs = { 300_000L }, switchAfterTurns = { 2 })

    @Test fun replies_follow_the_last_turn_while_the_conversation_is_live() {
        val store = arrayOf(Lang.TWI); val clock = Clock()
        val s = session(store, clock)
        assertEquals(Lang.TWI, s.current())
        s.onTurn(Lang.GH_EN)
        assertEquals("reply in the language I just spoke", Lang.GH_EN, s.current())
        assertEquals("one English turn doesn't change the habit", Lang.TWI, store[0])
        clock.now += 301_000
        assertEquals("after a pause, the habitual language is the fallback", Lang.TWI, s.current())
    }

    @Test fun the_habitual_language_moves_only_after_a_streak() {
        val store = arrayOf(Lang.GH_EN); val clock = Clock()
        val s = session(store, clock)
        s.onTurn(Lang.TWI); s.onTurn(Lang.GH_EN); s.onTurn(Lang.TWI)
        assertEquals("interrupted streak", Lang.GH_EN, store[0])
        s.onTurn(Lang.TWI)
        assertEquals(Lang.TWI, store[0])
        s.onTurn(Lang.AUTO)
        assertEquals("non-language values are ignored", Lang.TWI, s.current())
        assertEquals(Lang.GH_EN, session(arrayOf("garbage"), clock).habitual())
    }
}

/** A20e, 4 October 2026: the app's own patience bounds were charged to the translator as failures, so after a few slow
 *  turns it was rested and every Twi line went English; and "aane aane" heard as "honey honey" declined a message. */
class TwiVoiceRoundTest {
    @org.junit.Test fun `a caller's own timeout is never charged to the provider`() = kotlinx.coroutines.test.runTest {
        val health = com.tekyerema.language.ProviderHealth("space_mt")
        val guard = com.tekyerema.language.ProviderGuard(health, 32_000L)
        repeat(3) { kotlinx.coroutines.withTimeoutOrNull(3_500L) { guard.run { kotlinx.coroutines.delay(10_000L); "late" } } }
        org.junit.Assert.assertTrue("rested by our own patience: ${health.status()}", health.available())
        org.junit.Assert.assertNull(health.lastFailure)
    }

    @org.junit.Test fun `the provider's own backstop still counts`() = kotlinx.coroutines.test.runTest {
        val health = com.tekyerema.language.ProviderHealth("space_mt")
        val guard = com.tekyerema.language.ProviderGuard(health, 1_000L)
        val failure = runCatching { guard.run { kotlinx.coroutines.delay(10_000L); "late" } }.exceptionOrNull()
        org.junit.Assert.assertTrue("$failure", failure is com.tekyerema.language.ProviderFailure)
        org.junit.Assert.assertEquals(com.tekyerema.language.FailureKind.TRANSIENT, health.lastFailure)
        org.junit.Assert.assertEquals("fine", guard.run { "fine" })   // one failure doesn't rest it
    }

    @org.junit.Test fun `aane and daabi as the phone's English recogniser writes them`() {
        val c = com.tekyerema.voice.Confirmation
        org.junit.Assert.assertEquals(com.tekyerema.voice.Confirmation.Answer.YES, c.interpret("honey honey"))
        org.junit.Assert.assertEquals(com.tekyerema.voice.Confirmation.Answer.YES, c.interpret("Honey"))
        org.junit.Assert.assertEquals(com.tekyerema.voice.Confirmation.Answer.NO, c.interpret("dobby"))
        org.junit.Assert.assertEquals(com.tekyerema.voice.Confirmation.Answer.YES, c.interpret("yes"))
        org.junit.Assert.assertEquals(com.tekyerema.voice.Confirmation.Answer.NO, c.interpret("no"))
    }
}

class TwiModeSwitchLineTest {
    /** The "speak Twi" reply: the recorded Twi acknowledgement by the Twi voice, the guidance by the English voice — never
     *  sent to the translator (it was, at the moment the warm-up was waking it, and always came out English). */
    @org.junit.Test fun `the switch-to-Twi reply is a recorded Twi clip and an English note`() = kotlinx.coroutines.test.runTest {
        val line = "Yoo, mɛsesa. " + com.tekyerema.language.EntityTags.EN_OPEN +
            "My Twi hearing is still learning — if I get it wrong, say it again, or type it. Say 'speak English' to switch back." +
            com.tekyerema.language.EntityTags.EN_CLOSE
        val spoken = com.tekyerema.voice.Verbalize.apply(line)
        val pieces = com.tekyerema.language.EntityTags.segments(spoken)
        org.junit.Assert.assertEquals("Yoo, mɛsesa." to false, pieces[0])
        org.junit.Assert.assertTrue(pieces[1].second)
        org.junit.Assert.assertFalse(com.tekyerema.language.ReplyLocalizer.englishForVoice(spoken))
        val neverAsked = object : com.tekyerema.language.TranslationProvider { override val name = "x"
            override suspend fun translate(text: String, from: String, to: String): String? = throw AssertionError("translated: $text") }
        org.junit.Assert.assertNull(com.tekyerema.language.ReplyLocalizer(neverAsked, enabled = { true }).toTwi(spoken))
        org.junit.Assert.assertFalse(com.tekyerema.language.EntityTags.display(line).contains("⟦"))
    }
}
