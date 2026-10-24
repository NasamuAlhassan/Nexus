package com.tekyerema

import com.tekyerema.voice.Lang
import com.tekyerema.voice.VoiceWarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phone log, 24 Oct 2026: both voices were loaded at start-up, 17 s each, then released at once. */
class VoiceWarmTest {
    @Test fun onlyTheVoiceOfTheLanguageInForceLoads() {
        assertEquals(Lang.TWI, VoiceWarm.voiceToWarm(Lang.TWI, Lang.GH_EN, 1_200, 900))
        assertEquals(Lang.GH_EN, VoiceWarm.voiceToWarm(Lang.GH_EN, Lang.TWI, 1_200, 900))
    }

    @Test fun inMixedModeTheSessionLanguageDecides() {
        assertEquals(Lang.TWI, VoiceWarm.voiceToWarm(null, Lang.TWI, 1_200, 900))
        assertEquals(Lang.GH_EN, VoiceWarm.voiceToWarm(null, null, 1_200, 900))
    }

    @Test fun withLittleMemoryNothingLoadsAhead() {
        assertNull(VoiceWarm.voiceToWarm(Lang.GH_EN, null, 700, 900))
        assertNull(VoiceWarm.voiceToWarm(null, Lang.TWI, 580, 900))
    }

    @Test fun kusaalLoadsNoPhoneVoice() {
        assertNull(VoiceWarm.voiceToWarm(Lang.KUS, null, 2_000, 900))
    }
}
