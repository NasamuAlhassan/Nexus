package com.tekyerema

import com.tekyerema.voice.WakeGate
import com.tekyerema.voice.WakeScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake gate's firing contract (the scorer itself needs ONNX Runtime + the models, so it's verified on
 * device / by V2's wake_matrix.py). One "Hey Tɛkyerɛma" must fire exactly once; spikes, the noisy bare head,
 * and high scores on near-silence must never fire.
 */
class WakeWordTest {
    private val loud = 0.05f
    private val hey = mapOf(WakeScorer.FIRE_HEAD to 0.99f, "tekyerema" to 0.99f)
    private val low = mapOf(WakeScorer.FIRE_HEAD to 0.40f, "tekyerema" to 0.30f)

    private fun fires(gate: WakeGate, frames: List<Pair<Float, Map<String, Float>>>) = frames.count { (r, s) -> gate.feed(r, s) }

    @Test fun one_utterance_fires_exactly_once() {
        val g = WakeGate()
        assertEquals(1, fires(g, List(15) { loud to hey }))       // ~1.2 s of high score: refractory holds
    }

    @Test fun fires_on_the_third_consecutive_hit() {
        val g = WakeGate()
        assertFalse(g.feed(loud, hey)); assertFalse(g.feed(loud, hey)); assertTrue(g.feed(loud, hey))
    }

    @Test fun lone_spikes_do_not_fire() {
        val g = WakeGate()
        val frames = List(30) { i -> loud to (if (i % 3 == 0) hey else low) }
        assertEquals(0, fires(g, frames))
    }

    @Test fun bare_head_alone_never_fires() {
        // the bare "tekyerema" head scores ~0.93 on plain noise — it is logged, never trusted
        val g = WakeGate()
        assertEquals(0, fires(g, List(30) { loud to mapOf(WakeScorer.FIRE_HEAD to 0.70f, "tekyerema" to 0.99f) }))
    }

    @Test fun high_score_on_near_silence_does_not_fire() {
        val g = WakeGate()
        assertEquals(0, fires(g, List(30) { 0.001f to hey }))
    }

    @Test fun decaying_peak_carries_the_hit_past_the_phrase_end() {
        // the score lags the audio: the hit frames arrive as the phrase ends and the room goes quiet
        val g = WakeGate()
        g.feed(loud, emptyMap())
        assertEquals(1, fires(g, List(3) { 0.002f to hey }))
    }

    @Test fun a_second_utterance_after_refractory_fires_again() {
        val g = WakeGate()
        assertEquals(1, fires(g, List(5) { loud to hey }))
        fires(g, List(WakeGate.REFRACTORY_FRAMES + 5) { loud to low })
        assertEquals(1, fires(g, List(5) { loud to hey }))
    }

    @Test fun gated_frames_do_not_count() {
        val g = WakeGate()
        assertEquals(0, fires(g, listOf(loud to hey, loud to emptyMap(), loud to hey)))
        // empty frames don't break the streak either (V2 behaviour): a third hit completes it
        assertTrue(g.feed(loud, hey))
    }
}
