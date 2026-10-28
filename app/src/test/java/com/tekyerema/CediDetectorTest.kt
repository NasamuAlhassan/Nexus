package com.tekyerema

import com.tekyerema.camera.CediDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CediDetectorTest {
    private fun b(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y1)
    private fun s(note: Float, coin: Float) = floatArrayOf(1f - note - coin, note, coin)

    @Test fun weakBoxesGoAndOverlapsOfOneClassMerge() {
        val boxes = arrayOf(b(0.1f, 0.1f, 0.5f, 0.3f), b(0.11f, 0.1f, 0.5f, 0.31f), b(0.6f, 0.6f, 0.7f, 0.7f), b(0.8f, 0.1f, 0.9f, 0.2f))
        val scores = arrayOf(s(0.9f, 0.0f), s(0.8f, 0.0f), s(0.0f, 0.85f), s(0.3f, 0.1f))
        val out = CediDetector.post(boxes, scores, 0.5f)
        assertEquals(2, out.size)
        assertEquals(0.9f, out[0].score, 1e-6f)
        assertTrue(out[1].coin)
    }

    @Test fun oneThingSeenAsBothANoteAndACoinIsCountedOnce() {
        val boxes = arrayOf(b(0.4f, 0.4f, 0.5f, 0.5f), b(0.4f, 0.4f, 0.5f, 0.51f))
        val scores = arrayOf(s(0.0f, 0.9f), s(0.6f, 0.0f))
        val out = CediDetector.post(boxes, scores, 0.5f)
        assertEquals(1, out.size)
        assertTrue(out[0].coin)
    }

    @Test fun fannedNotesThatOnlyPartlyOverlapAreKeptApart() {
        val boxes = arrayOf(b(0.1f, 0.2f, 0.6f, 0.5f), b(0.25f, 0.2f, 0.75f, 0.5f))      // IoU 0.54: one note seen twice
        val scores = arrayOf(s(0.9f, 0f), s(0.88f, 0f))
        assertEquals(1, CediDetector.post(boxes, scores, 0.5f).size)
        val apart = arrayOf(b(0.1f, 0.2f, 0.6f, 0.5f), b(0.35f, 0.2f, 0.85f, 0.5f))      // IoU ~0.33
        assertEquals(2, CediDetector.post(apart, scores, 0.5f).size)
    }

    @Test fun theCropIsASquareAroundTheBoxInsideTheFrame() {
        val box = CediDetector.Box(0.9f, 0.4f, 1.0f, 0.6f, coin = true, score = 0.9f)
        val w = CediDetector.window(box, 640, 480, 1.25f)
        assertEquals(120, w.size)                                  // 0.2 * 480 * 1.25
        assertTrue(w.x + w.size <= 640 && w.y + w.size <= 480 && w.x >= 0 && w.y >= 0)
        val huge = CediDetector.window(CediDetector.Box(0f, 0f, 1f, 1f, false, 0.9f), 640, 480, 1.7f)
        assertEquals(480, huge.size)
    }

    @Test fun notesLyingOnEachOtherAreCrowdedNotesApartAreNot() {
        val a = CediDetector.Box(0.1f, 0.1f, 0.5f, 0.3f, false, 0.9f)
        val fanned = CediDetector.Box(0.2f, 0.12f, 0.6f, 0.32f, false, 0.9f)       // covers most of the first
        val apart = CediDetector.Box(0.55f, 0.1f, 0.95f, 0.3f, false, 0.9f)
        val coinOnNote = CediDetector.Box(0.3f, 0.15f, 0.36f, 0.23f, true, 0.9f)   // a coin lying on a note
        assertTrue(CediDetector.crowded(listOf(a, fanned)))
        assertTrue(!CediDetector.crowded(listOf(a, apart)))
        assertTrue(CediDetector.crowded(listOf(a, coinOnNote)))
    }
}
