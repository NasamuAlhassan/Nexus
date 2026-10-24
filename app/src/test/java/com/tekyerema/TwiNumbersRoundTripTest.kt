package com.tekyerema

import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What our Space's Twi recogniser wrote for our own Twi voice's amounts (24 Oct 2026, Temp/num_rt: 24 lines spoken by the
 * Space voice, heard by the Space recogniser). The ones the app can mend without guessing; the ambiguous ones stay unread.
 */
class TwiNumbersRoundTripTest {
    private fun twi(s: String) = Normalizer.normalize(s, Lang.TWI)

    @Test fun cidiIsACedi() {
        assertEquals("wo sika aka yɛɛ cedi 1", twi("wo sika aka yɛɛ cidi baako"))
        assertEquals("wo sika a aka yɛ cedi 50", twi("wo sika a aka yɛ cidi aduonum"))
    }

    @Test fun yeRunIntoTheAmount() {
        assertTrue(twi("wo sika aka yɛsidi aduasa").endsWith("yɛ cedi 30"))
    }

    @Test fun pesewaAndItsNumeralShareAVowel() {
        assertEquals("wo sika a aka yɛ cedi 10 ne pesewa 50", twi("wo sika a aka yɛ sidi du ne pesewaduonum"))
    }

    @Test fun theHundredsTheVoiceSaysAreHeard() {
        assertEquals("cedi 500", twi("sidi ahanum"))
        assertEquals("cedi 550", twi("sidi ahanum ne aduonum"))
        assertEquals("cedi 1500", twi("sidi apem ne ahanum"))
        assertEquals("cedi 300", twi("sidi ahasa"))
    }

    /** "aduomu" could be 20 (aduonu) or 50 (aduonum): never guessed — the read-back asks. */
    @Test fun anAmbiguousNumeralIsNotGuessed() {
        assertEquals("wo sika aka yɛ cedi aduomu", twi("wo sika aka yɛ sidi aduomu"))
    }
}
