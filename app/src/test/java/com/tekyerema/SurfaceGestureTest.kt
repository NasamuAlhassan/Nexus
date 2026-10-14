package com.tekyerema

import com.tekyerema.ui.assistant.SurfaceGesture
import com.tekyerema.ui.assistant.SurfaceGesture.Rest
import com.tekyerema.ui.assistant.SurfaceGesture.Settle
import com.tekyerema.ui.assistant.SurfaceModel.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The surface's gestures (docs/assistant-ux/PLAN_V2.md): where a release settles, the rubber band, what may be dismissed. */
class SurfaceGestureTest {
    private val result = SurfaceGesture.allow(Mode.PRESENTING, expandable = true)
    private val waiting = SurfaceGesture.allow(Mode.WAITING, expandable = false)
    private val working = SurfaceGesture.allow(Mode.WORKING, expandable = true)
    private val h = 240f

    @Test fun a_fling_decides_by_velocity_and_a_slow_drag_by_distance() {
        assertEquals("a quick flick up dismisses a finished result", Settle.DISMISS,
            SurfaceGesture.settle(Rest.COMPACT, dragDp = -20f, velocityDp = -1500f, heightDp = h, allow = result))
        assertEquals("a small slow push springs back", Settle.STAY,
            SurfaceGesture.settle(Rest.COMPACT, dragDp = -30f, velocityDp = -100f, heightDp = h, allow = result))
        assertEquals("a long slow push dismisses", Settle.DISMISS,
            SurfaceGesture.settle(Rest.COMPACT, dragDp = -120f, velocityDp = 0f, heightDp = h, allow = result))
        assertEquals("pull down opens the follow-up field", Settle.OPEN_INPUT,
            SurfaceGesture.settle(Rest.COMPACT, dragDp = 70f, velocityDp = 0f, heightDp = h, allow = result))
        assertEquals("a flick down opens it too", Settle.OPEN_INPUT,
            SurfaceGesture.settle(Rest.COMPACT, dragDp = 10f, velocityDp = 1400f, heightDp = h, allow = result))
        assertEquals("a short slow pull springs back", Settle.STAY,
            SurfaceGesture.settle(Rest.COMPACT, dragDp = 30f, velocityDp = 200f, heightDp = h, allow = result))
    }

    @Test fun the_finger_turning_back_cancels() {
        // Pulled down 80 dp, then flicked up on release: the user changed their mind — nothing opens.
        assertEquals(Settle.STAY, SurfaceGesture.settle(Rest.COMPACT, dragDp = 80f, velocityDp = -1200f, heightDp = h,
            allow = SurfaceGesture.allow(Mode.WORKING, expandable = false)))
        // Pushed up a little, then flicked down: no dismissal; the flick down opens the field.
        assertEquals(Settle.OPEN_INPUT, SurfaceGesture.settle(Rest.COMPACT, dragDp = -40f, velocityDp = 1300f, heightDp = h, allow = result))
    }

    @Test fun a_working_or_waiting_surface_is_never_dismissed_by_a_gesture() {
        for (allow in listOf(waiting, working)) {
            for (rest in Rest.values()) {
                for (v in listOf(-5000f, -1500f, 0f)) {
                    val s = SurfaceGesture.settle(rest, dragDp = -h, velocityDp = v, heightDp = h, allow = allow)
                    assertTrue("$rest v=$v: never DISMISS, got $s", s != Settle.DISMISS)
                }
            }
        }
        assertFalse(waiting.dismiss); assertFalse(working.dismiss)
        assertFalse("no follow-up field while a prompt waits", waiting.followUp)
        assertEquals("a pull on a prompt stays", Settle.STAY,
            SurfaceGesture.settle(Rest.COMPACT, dragDp = 120f, velocityDp = 2000f, heightDp = h, allow = waiting))
        // A long timeline may grow while working, but never dismiss.
        assertEquals(Settle.EXPAND, SurfaceGesture.settle(Rest.COMPACT, dragDp = 80f, velocityDp = 0f, heightDp = h, allow = working))
    }

    @Test fun from_expanded_a_push_collapses_and_a_strong_one_dismisses() {
        assertEquals(Settle.COLLAPSE, SurfaceGesture.settle(Rest.EXPANDED, dragDp = -100f, velocityDp = 0f, heightDp = h, allow = result))
        assertEquals(Settle.COLLAPSE, SurfaceGesture.settle(Rest.INPUT, dragDp = -10f, velocityDp = -1200f, heightDp = h, allow = result))
        assertEquals(Settle.DISMISS, SurfaceGesture.settle(Rest.EXPANDED, dragDp = -20f, velocityDp = -2500f, heightDp = h, allow = result))
        assertEquals(Settle.DISMISS, SurfaceGesture.settle(Rest.INPUT, dragDp = -170f, velocityDp = 0f, heightDp = h, allow = result))
        assertEquals("a working timeline only collapses", Settle.COLLAPSE,
            SurfaceGesture.settle(Rest.EXPANDED, dragDp = -20f, velocityDp = -2500f, heightDp = h, allow = working))
        assertEquals(Rest.COMPACT, SurfaceGesture.next(Rest.INPUT, Settle.COLLAPSE))
        assertEquals(Rest.INPUT, SurfaceGesture.next(Rest.EXPANDED, Settle.OPEN_INPUT))
        assertEquals(Rest.EXPANDED, SurfaceGesture.next(Rest.EXPANDED, Settle.DISMISS))
    }

    @Test fun the_rubber_band_resists_and_never_passes_its_limit() {
        assertEquals(0f, SurfaceGesture.rubberBand(0f, 48f), 0.001f)
        val a = SurfaceGesture.rubberBand(40f, 48f); val b = SurfaceGesture.rubberBand(400f, 48f)
        assertTrue("moves less than the finger", a < 40f)
        assertTrue("further pulls move less and less", b > a && b < 48f)
        // Allowed directions follow the finger 1:1; a disallowed one only stretches.
        assertEquals(-60f, SurfaceGesture.visualOffset(-60f, allowUp = true, allowDown = true), 0.001f)
        assertTrue(SurfaceGesture.visualOffset(-300f, allowUp = false, allowDown = true) > -SurfaceGesture.BAND_DP)
        assertEquals(50f, SurfaceGesture.visualOffset(50f, allowUp = true, allowDown = true), 0.001f)
        assertTrue("a long pull slows down", SurfaceGesture.visualOffset(400f, allowUp = true, allowDown = true) < 400f)
    }

    @Test fun a_haptic_tick_marks_the_line_and_back_collapses_first() {
        assertFalse(SurfaceGesture.crossed(Rest.COMPACT, -40f, h, result))
        assertTrue(SurfaceGesture.crossed(Rest.COMPACT, -100f, h, result))
        assertFalse("a prompt has no line to cross upward", SurfaceGesture.crossed(Rest.COMPACT, -200f, h, waiting))
        assertTrue(SurfaceGesture.backCollapses(Rest.INPUT)); assertFalse(SurfaceGesture.backCollapses(Rest.COMPACT))
    }
}
