package com.tekyerema

import com.tekyerema.agent.triggers.ScheduleParser
import com.tekyerema.safety.DangerCodeWord
import com.tekyerema.ui.Brand
import com.tekyerema.voice.KwsGate
import com.tekyerema.voice.KwsWake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Nexus" wake word (8 Nov 2026). The keyword spotter itself needs the native sherpa-onnx library and the model, so it is
 * judged by the Kaggle eval (campaign/kaggle/wake-nexus-kws) and on the phone; here: the rules around it.
 */
class KwsWakeTest {
    private val loud = 0.05f

    @Test fun one_detection_is_one_wake_and_the_refractory_holds() {
        val g = KwsGate(minRms = 0.0035f)
        assertTrue(g.feed(loud, true))
        // "Nexus… Nexus" in one breath, ~2 s: no second wake
        assertEquals(0, (1..KwsWake.REFRACTORY_FRAMES).count { g.feed(loud, true) })
        assertTrue("after the refractory a new wake", g.feed(loud, true))
    }

    @Test fun no_detection_never_wakes() {
        val g = KwsGate(minRms = 0.0035f)
        assertEquals(0, (1..200).count { g.feed(loud, false) })
    }

    @Test fun a_detection_on_near_silence_does_not_wake() {
        val g = KwsGate(minRms = 0.0035f)
        assertFalse(g.feed(0.0005f, true))
    }

    @Test fun the_loudness_floor_decays_so_the_frame_after_speech_still_counts() {
        val g = KwsGate(minRms = 0.0035f)
        g.feed(0.02f, false)                     // the word itself
        assertTrue(g.feed(0.004f, true))         // the spotter decodes it a frame later, in the quieter tail
    }

    @Test fun reset_clears_the_refractory() {
        val g = KwsGate(minRms = 0.0035f)
        assertTrue(g.feed(loud, true)); g.reset()
        assertTrue(g.feed(loud, true))
    }

    @Test fun engine_is_the_spotter_unless_oww_is_asked_for() {
        assertEquals(KwsWake.ENGINE_KWS, KwsWake.engine(null))
        assertEquals(KwsWake.ENGINE_KWS, KwsWake.engine("kws"))
        assertEquals(KwsWake.ENGINE_KWS, KwsWake.engine("kwz"))      // a typo must not deafen the phone
        assertEquals(KwsWake.ENGINE_OWW, KwsWake.engine(" OWW "))
    }

    @Test fun keywords_string_has_tokens_boost_threshold_and_phrase() {
        assertEquals("▁NE X US :2.0 #0.05 @NEXUS", KwsWake.keywords(0.05f, 2.0f))
        assertEquals("clamped", "▁NE X US :5.0 #0.01 @NEXUS", KwsWake.keywords(0f, 99f))
    }

    @Test fun the_app_tells_the_user_the_word_it_listens_for() {
        assertTrue(Brand.WAKE.contains("Nexus"))
        assertEquals(KwsWake.ENGINE_KWS, KwsWake.DEFAULT_ENGINE)
    }

    @Test fun a_danger_code_word_cannot_contain_the_wake_word() {
        assertNotNull(DangerCodeWord.problem("hey nexus"))
        assertNotNull(DangerCodeWord.problem("purple nexus bridge"))
        assertNotNull(DangerCodeWord.problem("hey tekyerema"))
        assertNull(DangerCodeWord.problem("red kente cloth"))
    }

    @Test fun a_schedule_said_after_the_wake_word_drops_it() {
        assertEquals("read my calendar", ScheduleParser.parse("Hey Nexus, every day at 7 read my calendar")?.goal)
        assertEquals("read my calendar", ScheduleParser.parse("Nexus every day at 7 read my calendar")?.goal)
    }
}
