package com.tekyerema

import com.tekyerema.voice.TwiRespell
import org.junit.Assert.assertEquals
import org.junit.Test

class TwiRespellTest {
    private val lex = mapOf("mobile money" to "mobaal mɔni", "wife" to "waif", "phone" to "fon")

    @Test fun lexiconFirstThenLetterRules() {
        assertEquals("Menbue mobaal mɔni sɛ wode sidi anum kɔma My waif?", TwiRespell.apply("Menbue Mobile Money sɛ wode sidi anum kɔma My wife?", lex))
        assertEquals("Fa wo fon no.", TwiRespell.apply("Fa wo phone no.", lex))
    }

    @Test fun lettersTheTwiVoiceSkipsAreRewritten() {
        assertEquals("sedis", TwiRespell.letters("cedis"))
        assertEquals("selia", TwiRespell.letters("Zelia"))
        assertEquals("dwoe", TwiRespell.letters("Joe"))
        assertEquals("telesel", TwiRespell.letters("Telecel"))
    }

    @Test fun twiWordsAreNeverTouched() {
        val twi = "Wopɛ sɛ mesoma sidi anum ma Ama? Ka aane anaa daabi."
        assertEquals(twi, TwiRespell.apply(twi, emptyMap()))
    }
}
