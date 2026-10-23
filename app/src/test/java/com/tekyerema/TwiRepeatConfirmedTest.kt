package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.ConfirmTemplates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwiRepeatConfirmedTest {
    @Test fun aConfirmedFirstPaymentIsSaidAsCertainInTwi() {
        AppConfig.loadFrom(emptyList())
        val spec = ConfirmTemplates.Spec(ConfirmTemplates.Spec.Kind.SEND_MONEY, name = "Ama", number = "0552093690", amount = "10",
            repeat = true, repeatConfirmed = true)
        val line = ConfirmTemplates.twi(spec)
        assertFalse(line, line.contains("Sɛ ɛkɔɔ so a"))
        assertTrue(line, line.contains("na ɛkɔɔ so"))
        assertTrue(ConfirmTemplates.twi(spec.copy(repeatConfirmed = false)).contains("Sɛ ɛkɔɔ so a"))
    }
}
