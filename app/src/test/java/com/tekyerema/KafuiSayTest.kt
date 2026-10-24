package com.tekyerema

import com.tekyerema.voice.KafuiSay
import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner's by-ear picks for the Kafui English voice (24 Oct 2026). */
class KafuiSayTest {
    private fun say(line: String) = KafuiSay.apply(line, KafuiSay.DEFAULT)

    @Test fun ownWordsAreRespelled() {
        assertEquals("Hello, I am Tetchy rehma.", say("Hello, I am Tɛkyerɛma."))
        assertEquals("Hello, I am Tetchy rehma.", say("Hello, I am Tekyerema."))
        assertEquals("Your M T N number.", say("Your MTN number."))
        assertEquals("Send ten seedies fifty pesswess to Kwame.", say("Send ten cedis fifty pesewas to Kwame."))
        assertEquals("one seedy and one pessweh", say("one cedi and one pesewa"))
    }

    @Test fun onlyWholeWords() {
        assertEquals("MTNX and Tekyeremas stay", say("MTNX and Tekyeremas stay"))
        assertEquals("Tetchy rehma's voice", say("Tɛkyerɛma's voice"))
    }

    @Test fun aLineWithoutThemIsUnchanged() {
        assertEquals("Calling Kofi.", say("Calling Kofi."))
    }

    @Test fun configAddsAndOverrides() {
        assertEquals("Open Mobile Money.", KafuiSay.apply("Open MoMo.", KafuiSay.DEFAULT + ("momo" to "Mobile Money")))
        assertEquals("Your em tee en number.", KafuiSay.apply("Your MTN number.", KafuiSay.DEFAULT + ("mtn" to "em tee en")))
    }
}
