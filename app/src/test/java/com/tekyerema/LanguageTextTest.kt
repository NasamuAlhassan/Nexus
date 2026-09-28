package com.tekyerema

import com.tekyerema.language.LanguageSession
import com.tekyerema.language.ReplyLocalizer
import com.tekyerema.language.TranslationProvider
import com.tekyerema.voice.Lang
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The language layer's policy half: which replies may be machine-translated (B-11) and per-turn language state (B-05). */
class LanguageTextTest {

    // ---------------------------------------------------------------- reply localization (MT policy)

    private class FakeMt(val answer: (String) -> String?) : TranslationProvider {
        override val name = "fake-mt"
        var calls = 0
        override suspend fun translate(text: String, from: String, to: String): String? { calls++; return answer(text) }
    }

    private fun localizer(mt: FakeMt, on: Boolean = true) = ReplyLocalizer(mt, enabled = { on }, maxChars = { 400 })

    // Fake translator outputs are tagged placeholders, not Twi: nothing here pretends to be a translation.
    @Test fun localizer_translates_plain_english_replies_and_keeps_names() = runTest {
        val mt = FakeMt { "[tw] WhatsApp" }
        assertEquals("[tw] WhatsApp", localizer(mt).toTwi("I have opened WhatsApp for you"))
        val mangled = FakeMt { "[tw] wasap" }
        assertNull("the app name was lost → speak the original", localizer(mangled).toTwi("I have opened WhatsApp for you"))
        val names = FakeMt { "[tw] Ama" }
        assertNull(localizer(names).toTwi("I will call Kwabena now"))
    }

    @Test fun localizer_never_translates_quantities_twi_or_when_disabled() = runTest {
        val mt = FakeMt { "anything" }
        assertNull(localizer(mt).toTwi("You have five new messages"))
        assertNull(localizer(mt).toTwi("Sent GH₵ 20 to Ama"))
        assertNull(localizer(mt).toTwi("Dial star one seven zero hash"))
        assertNull("the app's own Twi", localizer(mt).toTwi("Mabue WhatsApp no."))
        assertNull("short Twi lines without ɛ/ɔ are not English either", localizer(mt).toTwi("Mante. San ka bio."))
        assertNull(localizer(mt).toTwi("Yoo."))
        assertNull(localizer(mt, on = false).toTwi("I have opened it for you"))
        assertEquals("the translator was never asked", 0, mt.calls)
        val rambling = FakeMt { "x ".repeat(200) }
        assertNull(localizer(rambling).toTwi("Done"))
        assertNull(ReplyLocalizer(null).toTwi("I have opened it for you"))
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
