package com.tekyerema

import com.tekyerema.camera.CurrencyAnnouncer
import com.tekyerema.camera.ObjectCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The object crop (campaign/evals/cedi): find the note/coin, window it from the camera frame, and combine two readings safely. */
class ObjectCropTest {
    private val side = ObjectCrop.GRID

    /** A [side]² grid of one background colour with a filled rectangle / disc of another. */
    private fun grid(bg: Triple<Float, Float, Float>, fg: Triple<Float, Float, Float>, inside: (Int, Int) -> Boolean): FloatArray {
        val out = FloatArray(side * side * 3)
        for (y in 0 until side) for (x in 0 until side) {
            val c = if (inside(x, y)) fg else bg
            val i = (y * side + x) * 3
            out[i] = c.first; out[i + 1] = c.second; out[i + 2] = c.third
        }
        return out
    }
    private val dark = Triple(25f, 25f, 28f)
    private val note = Triple(210f, 120f, 110f)

    @Test fun a_small_coin_on_plain_cloth_is_found_with_a_box_around_it() {
        // a disc of radius 7 at the centre: ~6 % of the grid, like a coin held in the camera's square
        val g = grid(dark, Triple(190f, 190f, 195f)) { x, y -> (x - 48) * (x - 48) + (y - 48) * (y - 48) <= 49 }
        val b = ObjectCrop.find(g, side)
        assertNotNull(b)
        assertTrue("box ${b}", b!!.x0 in 38f..44f && b.x1 in 52f..58f && b.y0 in 38f..44f && b.y1 in 52f..58f)
        assertTrue(ObjectCrop.plausible(b))
    }

    @Test fun a_note_off_centre_is_found_and_a_plain_frame_has_no_object() {
        val g = grid(dark, note) { x, y -> x in 30..70 && y in 20..60 }
        val b = ObjectCrop.find(g, side)!!
        assertTrue("box $b", b.x0 in 28f..34f && b.x1 in 66f..72f && b.y0 in 18f..24f && b.y1 in 56f..62f)
        assertNull(ObjectCrop.find(grid(dark, dark) { _, _ -> false }, side))
    }

    @Test fun an_object_filling_the_picture_gets_a_window_that_is_nearly_the_whole_square() {
        val g = grid(dark, note) { x, y -> x in 3..92 && y in 3..92 }
        val box = ObjectCrop.find(g, side)!!
        assertTrue("frac ${box.frac}", box.frac > 0.7f)
        assertTrue(ObjectCrop.window(box, 640, 480).size >= 440)      // the crop is about the whole square: no harm done
    }

    @Test fun the_window_is_a_square_inside_the_frames_centre_square() {
        val box = ObjectCrop.Box(40f, 40f, 56f, 56f, 0.03f)
        val w = ObjectCrop.window(box, frameW = 640, frameH = 480)
        // centre square of a 640x480 frame: x 80..560, y 0..480; the box is 16 grid px = 80 frame px, margin 1.25 = 100
        assertEquals(100, w.size)
        assertTrue(w.x >= 80 && w.x + w.size <= 560 && w.y >= 0 && w.y + w.size <= 480)
        val edge = ObjectCrop.window(ObjectCrop.Box(0f, 0f, 20f, 20f, 0.04f), 640, 480)      // an object in the corner stays inside
        assertTrue(edge.x >= 80 && edge.y >= 0)
        val huge = ObjectCrop.window(ObjectCrop.Box(0f, 0f, 96f, 96f, 1f), 640, 480)
        assertEquals(480, huge.size)
    }

    private val gateOf = { c: String -> CurrencyAnnouncer.gateFor(c) }

    @Test fun the_two_views_combine_without_ever_costing_an_answer_or_adding_a_wrong_one() {
        // no crop found: the whole square, as before
        assertEquals("coin_10_pesewas" to 0.9, ObjectCrop.decide("coin_10_pesewas", 0.9, null, 0.0, gateOf))
        // the whole square leans to "1 cedi" (not sure: that class needs 0.97), the crop is sure of 10 pesewas: the crop
        assertEquals("coin_10_pesewas" to 0.99, ObjectCrop.decide("coin_1_cedi", 0.90, "coin_10_pesewas", 0.99, gateOf))
        // only the whole square is sure (a crop on the wrong region): the whole square — the crop costs no answer
        assertEquals("note_5" to 0.95, ObjectCrop.decide("note_5", 0.95, "not_money", 0.6, gateOf))
        // both sure and different: silence, with the crop's class so "I can see money" still coaches
        assertEquals("note_20" to 0.0, ObjectCrop.decide("note_10", 0.92, "note_20", 0.93, gateOf))
        // neither sure: the more confident, which the announcer then refuses (below its gate)
        val (cls, conf) = ObjectCrop.decide("note_10", 0.5, "note_20", 0.6, gateOf)
        assertEquals("note_20", cls); assertTrue(conf < gateOf(cls))
        // the per-class table is the announcer's own
        assertEquals(0.97, CurrencyAnnouncer.gateFor("coin_1_cedi"), 1e-9)
        assertEquals(0.85, CurrencyAnnouncer.gateFor("note_5"), 1e-9)
    }
}
