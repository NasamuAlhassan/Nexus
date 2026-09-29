package com.tekyerema

import com.tekyerema.ui.home.LineMotion
import com.tekyerema.ui.theme.MotionPolicy
import com.tekyerema.ui.voice.Follower
import com.tekyerema.ui.voice.Kick
import com.tekyerema.ui.voice.Presence
import com.tekyerema.ui.voice.PresenceSim
import com.tekyerema.ui.voice.Spring
import com.tekyerema.ui.voice.StrobeGuard
import com.tekyerema.ui.voice.VisualSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The motion system's pure rules: springs, the state → visual mapping, the photosensitivity guard, the line. */
class UiMotionTest {

    private val frame = 1f / 60f

    private fun run(sim: PresenceSim, seconds: Float, level: Float = 0f, calm: Boolean = false, each: (PresenceSim) -> Unit = {}) {
        var t = 0f
        while (t < seconds) { sim.step(frame, level, pressed = false, calm = calm); each(sim); t += frame }
    }

    // ------------------------------------------------------------------------------------------------ springs

    @Test
    fun spring_settles_on_its_target() {
        val s = Spring(0f, 380f, 0.8f).apply { target = 1f }
        repeat(120) { s.step(frame) }
        assertEquals(1f, s.value, 0.002f)
        assertTrue(s.atRest)
    }

    @Test
    fun spatial_springs_overshoot_and_effects_springs_never_do() {
        val spatial = Spring(0f, 800f, 0.6f).apply { target = 1f }
        val effects = Spring(0f, 800f, 1f).apply { target = 1f }
        var spatialMax = 0f; var effectsMax = 0f
        repeat(120) { spatial.step(frame); effects.step(frame); spatialMax = maxOf(spatialMax, spatial.value); effectsMax = maxOf(effectsMax, effects.value) }
        assertTrue("a 0.6-damped spring overshoots", spatialMax > 1.05f)
        assertTrue("a critically damped spring never overshoots", effectsMax <= 1.0005f)
    }

    @Test
    fun a_spring_plays_the_same_at_30_and_60_fps() {
        val a = Spring(0f, 380f, 0.8f).apply { target = 1f }
        val b = Spring(0f, 380f, 0.8f).apply { target = 1f }
        repeat(15) { a.step(1f / 30f) }
        repeat(30) { b.step(1f / 60f) }
        assertEquals(b.value, a.value, 0.01f)
    }

    @Test
    fun a_retargeted_spring_keeps_its_momentum() {
        // The whole point of springs over tweens: changing state mid-flight never restarts from rest.
        val s = Spring(0f, 160f, 0.72f).apply { target = 1f }
        repeat(8) { s.step(frame) }
        val v = s.velocity
        s.target = 0.5f
        assertEquals(v, s.velocity, 0f)
        assertTrue(v > 0f)
    }

    @Test
    fun follower_rises_faster_than_it_falls() {
        val f = Follower(0.05f, 0.4f)
        repeat(6) { f.step(1f, frame) }
        val up = f.value
        repeat(6) { f.step(0f, frame) }
        val down = up - f.value
        assertTrue(up > 0.8f)
        assertTrue(down < up * 0.4f)
    }

    // ------------------------------------------------------------------------------------ state → visual

    @Test
    fun every_state_has_a_distinct_still_shape() {
        // Calm motion keeps shapes still, so the states must differ in shape/colour alone — no two alike.
        val specs = Presence.entries.map { VisualSpec.of(it).copy(reactive = false) }
        assertEquals(Presence.entries.size, specs.toSet().size)
    }

    @Test
    fun amber_means_only_needs_your_ok_and_dimming_means_only_error() {
        for (p in Presence.entries) {
            val s = VisualSpec.of(p)
            assertEquals(p.name, p == Presence.CONFIRMING || p == Presence.CONFIRM_LISTENING, s.attention > 0f)
            assertEquals(p.name, p == Presence.ERROR, s.dim > 0f)
            assertEquals(p.name, p == Presence.THINKING, s.split > 0f)
            assertEquals(p.name, p == Presence.LISTENING || p == Presence.CONFIRM_LISTENING || p == Presence.SPEAKING, s.reactive)
            assertTrue(p.name, s.scale in 0.9f..1.06f)
        }
        assertTrue("listening leans in", VisualSpec.of(Presence.LISTENING).scale > VisualSpec.of(Presence.IDLE).scale)
        assertTrue("thinking draws in", VisualSpec.of(Presence.THINKING).scale < VisualSpec.of(Presence.IDLE).scale)
        assertEquals("done closes the rings", 0f, VisualSpec.of(Presence.DONE).open, 0f)
    }

    @Test
    fun each_change_of_state_has_its_gesture() {
        assertEquals(Kick.LEAN_IN, VisualSpec.kickFor(Presence.IDLE, Presence.LISTENING))
        assertEquals(Kick.DRAW_IN, VisualSpec.kickFor(Presence.LISTENING, Presence.THINKING))
        assertEquals(Kick.BLOOM, VisualSpec.kickFor(Presence.SPEAKING, Presence.DONE))
        assertEquals(Kick.SHAKE, VisualSpec.kickFor(Presence.THINKING, Presence.ERROR))
        assertEquals(Kick.NUDGE, VisualSpec.kickFor(Presence.ACTING, Presence.CONFIRMING))
        // The mic opening for the answer leans in; it closing again is not a new moment (no second nudge).
        assertEquals(Kick.LEAN_IN, VisualSpec.kickFor(Presence.CONFIRMING, Presence.CONFIRM_LISTENING))
        assertEquals(Kick.NONE, VisualSpec.kickFor(Presence.CONFIRM_LISTENING, Presence.CONFIRMING))
        assertEquals(Kick.NONE, VisualSpec.kickFor(Presence.LISTENING, Presence.CONFIRM_LISTENING))
        for (p in Presence.entries) assertEquals(Kick.NONE, VisualSpec.kickFor(p, p))
    }

    // ------------------------------------------------------------------------------------ the simulation

    @Test
    fun rings_never_jump_when_the_spin_speed_changes() {
        // Regression: rotation used to be speed(t) × t, so a state change spun the rings hundreds of degrees at once.
        val sim = PresenceSim()
        sim.setPresence(Presence.IDLE, calm = false)
        run(sim, 30f)
        var worst = 0f
        var prev = sim.angles.copyOf()
        sim.setPresence(Presence.THINKING, calm = false)
        run(sim, 2f) { s ->
            for (i in prev.indices) {
                var d = abs(s.angles[i] - prev[i]); if (d > 180f) d = 360f - d
                worst = maxOf(worst, d)
            }
            prev = s.angles.copyOf()
        }
        assertTrue("worst per-frame turn $worst°", worst < 2f)     // ≤ 94 °/s at 60 fps
    }

    @Test
    fun the_voice_ripples_outward_through_the_rings() {
        val sim = PresenceSim()
        sim.setPresence(Presence.LISTENING, calm = false)
        run(sim, 0.08f, level = 1f)
        assertTrue(sim.rings[0].value > sim.rings[1].value)
        assertTrue(sim.rings[1].value > sim.rings[3].value)
    }

    @Test
    fun done_blooms_once_and_fades() {
        val sim = PresenceSim()
        sim.setPresence(Presence.SPEAKING, calm = false)
        run(sim, 0.5f)
        sim.setPresence(Presence.DONE, calm = false)
        assertEquals(1f, sim.bloom, 0f)
        run(sim, PresenceSim.BLOOM_SEC + 0.05f)
        assertEquals(0f, sim.bloom, 0f)
    }

    @Test
    fun an_error_shakes_its_head_and_comes_to_rest() {
        val sim = PresenceSim()
        sim.setPresence(Presence.THINKING, calm = false)
        run(sim, 0.3f)
        sim.setPresence(Presence.ERROR, calm = false)
        var peak = 0f
        run(sim, 0.3f) { peak = maxOf(peak, abs(it.shake.value)) }
        assertTrue("it shakes ($peak)", peak > 0.3f)
        assertTrue("but not wildly ($peak)", peak < 1.5f)
        run(sim, 1.2f)
        assertEquals(0f, sim.shake.value, 0.02f)
    }

    @Test
    fun the_first_state_is_placed_without_a_gesture() {
        val sim = PresenceSim()
        sim.setPresence(Presence.DONE, calm = false)
        assertEquals(0f, sim.bloom, 0f)
        assertEquals(VisualSpec.of(Presence.DONE).open, sim.open.value, 0f)
    }

    @Test
    fun calm_motion_snaps_shapes_and_keeps_everything_still() {
        val sim = PresenceSim()
        sim.setPresence(Presence.IDLE, calm = true)
        run(sim, 0.5f, calm = true)
        val angles = sim.angles.copyOf()
        sim.setPresence(Presence.THINKING, calm = true)
        assertEquals("shape snaps", VisualSpec.of(Presence.THINKING).open, sim.open.value, 0f)
        assertEquals(VisualSpec.of(Presence.THINKING).split, sim.split.value, 0f)
        run(sim, 1f, calm = true)
        assertEquals("the clock is frozen", 0f, sim.time, 0f)
        assertTrue("nothing spins", angles.contentEquals(sim.angles))
        sim.setPresence(Presence.ERROR, calm = true)
        run(sim, 0.5f, calm = true)
        assertEquals("no head-shake", 0f, sim.shake.value, 0f)
        run(sim, 2f, calm = true)
        assertTrue("a settled calm screen asks for no more frames", sim.atRest(calm = true))
    }

    @Test
    fun a_calm_screen_is_whole_from_its_first_frame() {
        // Regression: a calm screen never steps once settled, so the arrival "unfold" must not start at 0.
        val sim = PresenceSim()
        sim.setPresence(Presence.IDLE, calm = true)
        assertEquals(1f, sim.appear.value, 0f)
        assertTrue(sim.atRest(calm = true))
        // Turning calm on mid-arrival: not at rest until one calm step has snapped it whole.
        val mid = PresenceSim()
        mid.setPresence(Presence.IDLE, calm = false)
        run(mid, 0.05f)
        assertFalse(mid.atRest(calm = true))
        mid.step(frame, 0f, false, calm = true)
        assertEquals(1f, mid.appear.value, 0f)
        assertTrue(mid.atRest(calm = true))
    }

    @Test
    fun calm_motion_still_cross_fades_colour() {
        val sim = PresenceSim()
        sim.setPresence(Presence.THINKING, calm = true)
        sim.setPresence(Presence.CONFIRMING, calm = true)
        sim.step(frame, 0f, false, calm = true)
        assertTrue("amber fades in, not snaps", sim.attention.value in 0.01f..0.9f)
        run(sim, 1.5f, calm = true)
        assertEquals(1f, sim.attention.value, 0.01f)
    }

    @Test
    fun the_pulse_line_cross_fades_between_its_shapes() {
        val sim = PresenceSim()
        sim.setPresence(Presence.LISTENING, calm = false)
        run(sim, 1f, level = 0.5f)
        sim.setPresence(Presence.ACTING, calm = false)
        var prev = sim.voiceMix.value
        var worst = 0f
        run(sim, 1f) { worst = maxOf(worst, abs(it.voiceMix.value - prev)); prev = it.voiceMix.value }
        assertTrue("largest per-frame change $worst", worst < 0.2f)
        assertEquals(0f, sim.voiceMix.value, 0.02f)
        assertEquals(1f, sim.actMix.value, 0.02f)
    }

    // ------------------------------------------------------------------------------- photosensitivity

    @Test
    fun no_rhythmic_pulse_reaches_three_per_second() {
        for (hz in StrobeGuard.PULSES_HZ) assertTrue("$hz Hz", hz < StrobeGuard.MAX_FLASH_HZ)
        // The acting ripple period is its spawn interval; the thinking comet and the voice move, they do not flash.
    }

    @Test
    fun a_flickering_voice_cannot_make_the_light_flash() {
        // Drive the mic with a 10 Hz on/off square wave (the worst case for WCAG 2.3.1) and measure the light.
        val sim = PresenceSim()
        sim.setPresence(Presence.LISTENING, calm = false)
        var t = 0f
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        while (t < 4f) {
            val level = if (((t * 10f).toInt() % 2) == 0) 1f else 0f
            sim.step(frame, level, false, calm = false)
            if (t > 3f) { lo = minOf(lo, sim.light()); hi = maxOf(hi, sim.light()) }
            t += frame
        }
        val swing = hi - lo
        // A WCAG flash is a ≥10 % swing in relative luminance. The aurora turns light into at most 0.84 alpha
        // (its three lights overlapping) of a colour whose luminance is ≤0.45 (cyan), so keep 4× under the line.
        val luminanceSwing = swing * 0.84f * 0.45f
        assertTrue("light swing $swing (≈${luminanceSwing * 100} % luminance) under a 10 Hz flicker", luminanceSwing < 0.10f / 4f)
        assertTrue("…far below what the raw voice would do", swing < StrobeGuard.GLOW_DEPTH / 3f)
    }

    // ------------------------------------------------------------------------------------ the line

    @Test
    fun the_same_words_never_re_animate() {
        // A reply keeps its place from speaking, through done, to idle.
        val reply = "It's 10:42."
        assertEquals(LineMotion.key(Presence.SPEAKING, reply), LineMotion.key(Presence.DONE, reply))
        assertEquals(LineMotion.key(Presence.DONE, reply), LineMotion.key(Presence.IDLE, reply))
        // A confirmation question stays put when the mic opens for the answer.
        val q = "Send Kofi: “On my way”?"
        assertEquals(LineMotion.key(Presence.CONFIRMING, q), LineMotion.key(Presence.CONFIRM_LISTENING, q))
        // A live transcript updates in place.
        assertEquals(LineMotion.key(Presence.LISTENING, "call"), LineMotion.key(Presence.LISTENING, "call Kofi"))
        // A new step is a new line.
        assertFalse(LineMotion.key(Presence.ACTING, "Opening WhatsApp…") == LineMotion.key(Presence.ACTING, "Typing…"))
    }

    @Test
    fun only_a_spoken_reply_is_revealed_at_speaking_pace() {
        for (p in Presence.entries) assertEquals(p.name, p == Presence.SPEAKING, LineMotion.reveals(p))
        assertTrue(LineMotion.charsPerSecond(0) < LineMotion.charsPerSecond(1))
        assertTrue(LineMotion.charsPerSecond(1) < LineMotion.charsPerSecond(2))
        assertEquals(0, LineMotion.revealMillis(0f, 16f))
        assertEquals(LineMotion.MIN_REVEAL_MS, LineMotion.revealMillis(2f, 16f))
        assertEquals(2500, LineMotion.revealMillis(40f, 16f))
        assertEquals(LineMotion.MAX_REVEAL_MS, LineMotion.revealMillis(5000f, 16f))
        assertTrue(LineMotion.revealMillis(80f, LineMotion.charsPerSecond(2)) < LineMotion.revealMillis(80f, LineMotion.charsPerSecond(0)))
    }

    @Test
    fun calm_motion_follows_the_setting_or_the_system() {
        assertTrue(MotionPolicy.calm(userReduceMotion = true, systemAnimatorScale = 1f))
        assertTrue(MotionPolicy.calm(userReduceMotion = false, systemAnimatorScale = 0f))
        assertFalse(MotionPolicy.calm(userReduceMotion = false, systemAnimatorScale = 1f))
        assertFalse("slower animations are not no animations", MotionPolicy.calm(false, 0.5f))
    }
}
