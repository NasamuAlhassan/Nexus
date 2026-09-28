package com.tekyerema

import android.view.KeyEvent
import com.tekyerema.accessibility.KeyPattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W5: A-01 / J-14 hardware-key gesture detection. */
class ActivationW5Test {
    private val down = KeyEvent.KEYCODE_VOLUME_DOWN
    private val up = KeyEvent.KEYCODE_VOLUME_UP

    @Test fun double_press_within_the_window_fires_once() {
        val k = KeyPattern(keyCode = { down }, windowMs = { 450L })
        assertFalse(k.onKeyUp(down, 1_000))
        assertTrue(k.onKeyUp(down, 1_300))
        assertFalse("a third press starts a new gesture", k.onKeyUp(down, 1_500))
        assertTrue(k.onKeyUp(down, 1_700))
    }

    @Test fun slow_presses_and_other_keys_do_not_fire() {
        val k = KeyPattern(keyCode = { down }, windowMs = { 450L })
        assertFalse(k.onKeyUp(down, 1_000))
        assertFalse(k.onKeyUp(down, 1_600))          // too slow: just two volume presses
        assertFalse(k.onKeyUp(up, 1_700))            // another key resets
        assertFalse(k.onKeyUp(down, 1_800))
        assertTrue(k.onKeyUp(down, 2_000))
    }

    @Test fun key_is_configurable() {
        assertTrue(KeyPattern.keyFor("volume_up") == up)
        assertTrue(KeyPattern.keyFor("anything else") == down)
    }
}
