package com.tekyerema

import com.tekyerema.voice.TwiRespell
import org.junit.Assert.assertEquals
import org.junit.Test

class TwiRespellQuotedMessageTest {
    @Test fun aMessagesOwnWordsAreReadBackAsTheyWillBeSent() {
        assertEquals("Wopɛ sɛ mesoma nkra kɔma wo yere? Nkra no se: “My mother is sick”.",
            TwiRespell.kinNames("Wopɛ sɛ mesoma nkra kɔma My wife? Nkra no se: “My mother is sick”."))
    }
}
