package com.tekyerema

import com.tekyerema.money.PinPad
import com.tekyerema.money.PinPad.Counter.Event
import com.tekyerema.money.PinPad.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen as a PIN pad (owner, 26 Oct 2026): tap = volume down, long press = volume up, two-finger tap = the held key. */
class PinTouchPadTest {
    private fun classify(durationMs: Long, pointers: Int = 1, moved: Float = 0f) =
        PinPad.classify(durationMs, pointers, moved, slopPx = 16f, longPressMs = 600L)

    @Test fun a_short_still_touch_is_a_tap_and_two_fingers_are_the_hold() {
        assertEquals(Gesture.TAP, classify(90))
        assertEquals(Gesture.TAP, classify(599))
        assertEquals(Gesture.TWO_FINGER_TAP, classify(120, pointers = 2))
    }

    /** A long touch is decided by the clock while the finger is down, never by its release; a swipe is nothing. */
    @Test fun a_long_touch_a_drag_and_a_swipe_classify_as_nothing_on_release() {
        assertNull(classify(600))
        assertNull(classify(1_500, pointers = 2))
        assertNull(classify(80, moved = 40f))
        assertNull(classify(120, pointers = 3))   // three fingers: the screen goes back to the dialog, not a delete
        assertEquals(Gesture.TAP, classify(80, moved = 10f))   // a finger's wobble is still a tap
    }

    /** The same counter the volume keys drive: three taps and a long press key in a 3; a long press alone is a 0. */
    @Test fun the_gestures_count_like_the_volume_keys() {
        val c = PinPad.Counter(maxDigits = 4)
        repeat(3) { assertEquals(Event.Tick(it + 1), PinPad.apply(c, Gesture.TAP)) }
        assertEquals(Event.Digit(1), PinPad.apply(c, Gesture.LONG_PRESS))
        assertEquals(Event.Digit(2), PinPad.apply(c, Gesture.LONG_PRESS))   // nothing counted: a 0
        assertEquals("30", c.text())
        // two fingers: drop the digit being counted first, else the last digit
        PinPad.apply(c, Gesture.TAP)
        assertEquals(Event.Removed(2), PinPad.apply(c, Gesture.TWO_FINGER_TAP))
        assertEquals("30", c.text())
        assertEquals(Event.Removed(1), PinPad.apply(c, Gesture.TWO_FINGER_TAP))
        assertEquals("3", c.text())
        // a tenth tap for one digit starts that digit again, and a fifth digit is too many
        repeat(9) { PinPad.apply(c, Gesture.TAP) }
        assertEquals(Event.TooMany, PinPad.apply(c, Gesture.TAP))
        c.wipe()
        assertTrue(c.text().isEmpty())
    }

    /** The pad's own rule, kept: nothing is counted unless the pad is armed on the network's PIN screen. */
    @Test fun the_touch_pad_does_nothing_when_not_armed() {
        assertEquals(false, PinPad.handles())
    }
}
