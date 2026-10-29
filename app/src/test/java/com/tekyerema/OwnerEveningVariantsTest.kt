package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.language.TwiCommands
import com.tekyerema.language.TwiKeywords
import com.tekyerema.money.MoneyAsk
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the recognisers wrote on the owner's evening on the Infinix (29 Oct 2026, 15:40–17:45), each missed then. */
class OwnerEveningVariantsTest {
    private fun gloss(heard: String) = TwiCommands.gloss(Normalizer.normalize(heard, Lang.TWI))

    @Test fun `my husband as the Twi recogniser writes it`() {
        assertEquals("send a message to my husband saying “Ɔmfrɛ me”", gloss("ka kyerɛ meko no se ɔmfrɛ me"))
        assertEquals("call my husband", gloss("frɛ me kono"))
        assertEquals("read the message my husband sent", TwiKeywords.guess("kenkan nkra a meko no de somaa me"))
    }

    @Test fun `tell someone, with se for sɛ`() {
        assertEquals("send a message to my wife saying “Ɔfrɛ me”", gloss("kakyerɛ me yere sɛ ɔfrɛ me"))
        assertTrue(gloss("ka kyerɛ me yere se ma wɔmfrɛ me")!!.startsWith("send a message to my wife saying"))
    }

    @Test fun `tesa is sesa`() {
        assertEquals(FastIntent.Preference("language", "english"), FastIntentClassifier.classify("tesa kɔ borɔfo"))
        assertEquals(FastIntent.Preference("language", "twi"), FastIntentClassifier.classify("tesa kɔ twi"))
    }

    @Test fun `momma and mono balance are MoMo`() {
        for (s in listOf("What's my momma balance?", "What's my Mono balance?"))
            assertEquals(s, MoneyAsk.Kind.BALANCE_MOMO, MoneyAsk.parse(s)?.kind)
    }

    @Test fun `tell me what you can see opens the camera`() {
        FastIntentClassifier.classify("I want you to tell me what you can see.").let { assertTrue("got $it", it is FastIntent.Camera) }
        assertTrue(FastIntentClassifier.classify("tell me what you see on my screen") !is FastIntent.Camera)
    }
}
