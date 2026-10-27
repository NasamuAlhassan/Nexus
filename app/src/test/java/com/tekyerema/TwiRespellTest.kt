package com.tekyerema

import com.tekyerema.voice.TwiRespell
import org.junit.Assert.assertEquals
import org.junit.Test

class TwiRespellTest {
    private val lex = mapOf("mobile money" to "mobaal mɔni", "wife" to "waif", "phone" to "fon")

    @Test fun lexiconFirstThenLetterRules() {
        assertEquals("Menbue mobaal mɔni sɛ wode sidi anum kɔma wo yere?", TwiRespell.apply("Menbue Mobile Money sɛ wode sidi anum kɔma My wife?", lex))
        assertEquals("Fa ma ne waif.", TwiRespell.apply("Fa ma ne wife.", lex))
        assertEquals("Fa wo fon no.", TwiRespell.apply("Fa wo phone no.", lex))
    }

    @Test fun lettersTheTwiVoiceSkipsAreRewritten() {
        assertEquals("sedis", TwiRespell.letters("cedis"))
        assertEquals("selia", TwiRespell.letters("Zelia"))
        assertEquals("dwoe", TwiRespell.letters("Joe"))
        assertEquals("telesel", TwiRespell.letters("Telecel"))
    }

    /** Owner, 21 Oct 2026: "the ending number like 3 6 9 0, the ending isn't translated". */
    @Test fun aNumbersEndingIsSaidInTwi() {
        val twiEnding = "nɔma a n'awieɛ yɛ mmiɛnsa nsia nkron sero"   // "zero" then the letter rule (z → s)
        assertEquals("Mefrɛ wo yere, $twiEnding?", TwiRespell.apply("Mefrɛ My yere, ending 3 6 9 0?", emptyMap()))
        assertEquals("Mefrɛ wo yere, $twiEnding?", TwiRespell.apply("Mefrɛ My yere, ending mmiɛnsa nsia nkron sero?", emptyMap()))
        assertEquals("Ama, $twiEnding na ɛyɛ MTN.", TwiRespell.apply("Ama, ending three six nine zero na ɛyɛ MTN.", emptyMap()))
        // not a number's ending
        assertEquals("Ending no yɛ fɛ.", TwiRespell.apply("Ending no yɛ fɛ.", emptyMap()))
    }

    /** Judge's review of take A1 (21 Oct 2026): "Ɛfiri My yere hɔ" left the English "My" inside the Twi line. */
    @Test fun aKinContactIsSaidInTwi() {
        assertEquals("Ɛfiri wo yere hɔ.", TwiRespell.apply("Ɛfiri My yere hɔ.", emptyMap()))
        assertEquals("Menbue Mobile Money sɛ wode sidi anum kɔma wo yere?", TwiRespell.apply("Menbue Mobile Money sɛ wode sidi anum kɔma My wife?", emptyMap()))
        assertEquals("Wo maame afrɛ wo.", TwiRespell.apply("My mum afrɛ wo.", emptyMap()))
    }

    @Test fun twiWordsAreNeverTouched() {
        val twi = "Wopɛ sɛ mesoma sidi anum ma Ama? Ka aane anaa daabi."
        assertEquals(twi, TwiRespell.apply(twi, emptyMap()))
    }
}
