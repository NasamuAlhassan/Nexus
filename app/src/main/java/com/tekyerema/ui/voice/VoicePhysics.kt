package com.tekyerema.ui.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The physics behind the living visual, kept free of Android and Compose so every rule here is unit-tested
 * (UiMotionTest). LivingVoice only DRAWS a [PresenceSim]; everything that moves is decided here.
 *
 * Why springs and not tweens (docs/agent/research/overnight_ui_motion.md): a tween restarts from zero velocity every
 * time the state changes, so a quick LISTENING → THINKING → ACTING run looks like three separate jerks. A spring keeps
 * its velocity when its target moves, so the presence flows from one state into the next the way a real object would.
 * The damping ratios follow Material 3 Expressive (spatial springs may overshoot, effects springs never do); the
 * stiffnesses are lower than the component tokens because the visual is a large, "heavy" object.
 */

/**
 * A damped spring (unit mass, Hooke + viscous damping) integrated in small fixed sub-steps, so it is stable at any
 * stiffness and the same motion plays at 30 or 60 fps.
 */
class Spring(initial: Float, var stiffness: Float, var dampingRatio: Float) {
    var value = initial
    var velocity = 0f
    var target = initial

    fun step(dt: Float) {
        val omega = sqrt(stiffness)
        var left = dt
        while (left > 1e-6f) {
            val h = if (left < MAX_STEP) left else MAX_STEP
            val accel = -stiffness * (value - target) - 2f * dampingRatio * omega * velocity
            velocity += accel * h          // semi-implicit Euler: stable for omega·h < 2 (here ≤ 0.3)
            value += velocity * h
            left -= h
        }
    }

    /** Jump straight to [v] with no motion (reduce motion; first frame). */
    fun snap(v: Float = target) { value = v; target = v; velocity = 0f }

    /** An impulse: a push the spring then rings out from (a lean-in, a bloom, a head-shake). */
    fun kick(impulse: Float) { velocity += impulse }

    val atRest: Boolean get() = abs(value - target) < REST && abs(velocity) < REST

    companion object {
        const val MAX_STEP = 1f / 240f
        const val REST = 1e-3f
    }
}

/**
 * One-pole envelope follower with separate attack and release time constants (seconds). Frame-rate independent:
 * the smoothing factor is derived from dt, not assumed per frame.
 */
class Follower(private val attackSec: Float, private val releaseSec: Float) {
    var value = 0f
    fun step(input: Float, dt: Float): Float {
        val tau = if (input > value) attackSec else releaseSec
        value += (input - value) * (1f - exp(-dt / tau))
        return value
    }
}

/** Physically-motivated gestures played on a state change (see [VisualSpec.kickFor]). */
enum class Kick {
    NONE,
    /** Listening starts: the presence leans toward the user. */
    LEAN_IN,
    /** Thinking starts: it draws in, like an intake of breath. */
    DRAW_IN,
    /** A verified task is done: rings close and a ring of light is released. */
    BLOOM,
    /** Something went wrong: a small damped head-shake ("no"). */
    SHAKE,
    /** It needs your OK: a gentle tap on the shoulder. */
    NUDGE
}

/**
 * Where each state wants the visual to settle. Every field is a target a spring moves toward; nothing snaps.
 *
 * @param open how far the rings open (a gap: listening opens toward you, done closes them)
 * @param energy spin speed and breath depth
 * @param warmth calm blue/white → active cyan
 * @param attention the amber of "needs your OK" (only ever in the confirming states)
 * @param dim errors dim the light instead of flashing it
 * @param scale overall size — listening leans in, thinking draws in
 * @param glow the resting brightness of the aurora behind the rings
 * @param split rings tear into two chasing dashes (thinking)
 * @param wobble how far the rings drift off-centre, like layers of liquid
 * @param reactive the rings follow a voice (the microphone, or the assistant's own speech)
 */
data class VisualSpec(
    val open: Float,
    val energy: Float,
    val warmth: Float,
    val attention: Float,
    val dim: Float,
    val scale: Float,
    val glow: Float,
    val split: Float,
    val wobble: Float,
    val reactive: Boolean
) {
    companion object {
        fun of(p: Presence): VisualSpec = when (p) {
            Presence.IDLE -> VisualSpec(0.12f, 0.15f, 0.15f, 0f, 0f, 1.00f, 0.30f, 0f, 0.40f, false)
            Presence.LISTENING -> VisualSpec(0.45f, 0.55f, 1.00f, 0f, 0f, 1.05f, 0.60f, 0f, 1.00f, true)
            Presence.THINKING -> VisualSpec(0.62f, 1.00f, 0.60f, 0f, 0f, 0.93f, 0.50f, 1f, 0.50f, false)
            Presence.ACTING -> VisualSpec(0.30f, 0.60f, 0.85f, 0f, 0f, 1.00f, 0.55f, 0f, 0.60f, false)
            Presence.CONFIRMING -> VisualSpec(0.18f, 0.25f, 0.30f, 1f, 0f, 1.00f, 0.45f, 0f, 0.35f, false)
            Presence.CONFIRM_LISTENING -> VisualSpec(0.45f, 0.55f, 0.30f, 1f, 0f, 1.04f, 0.55f, 0f, 1.00f, true)
            Presence.SPEAKING -> VisualSpec(0.28f, 0.50f, 0.90f, 0f, 0f, 1.02f, 0.60f, 0f, 0.80f, true)
            Presence.DONE -> VisualSpec(0.00f, 0.30f, 1.00f, 0f, 0f, 1.00f, 0.70f, 0f, 0.30f, false)
            Presence.ERROR -> VisualSpec(0.12f, 0.10f, 0.00f, 0f, 0.6f, 0.96f, 0.15f, 0f, 0.20f, false)
        }

        fun listening(p: Presence) = p == Presence.LISTENING || p == Presence.CONFIRM_LISTENING

        /** The gesture for a change of state — only real changes move; a repeat is silent. */
        fun kickFor(from: Presence, to: Presence): Kick = when {
            from == to -> Kick.NONE
            to == Presence.ERROR -> Kick.SHAKE
            to == Presence.DONE -> Kick.BLOOM
            listening(to) -> if (listening(from)) Kick.NONE else Kick.LEAN_IN
            to == Presence.CONFIRMING -> if (from == Presence.CONFIRM_LISTENING) Kick.NONE else Kick.NUDGE
            to == Presence.THINKING -> Kick.DRAW_IN
            else -> Kick.NONE
        }
    }
}

/**
 * Photosensitivity guard (WCAG 2.3.1 "Three Flashes", Android's own guidance): the voice may move the rings as fast
 * as it likes (movement is not a flash), but the LIGHT it drives is smoothed so its brightness can't swing more than
 * about once a second, and every rhythmic pulse the visual plays is well under 3 per second.
 */
object StrobeGuard {
    const val MAX_FLASH_HZ = 3f
    /** Glow brightness follows the voice slowly: ~0.12 s up, ~0.3 s down (a low-pass near 1 Hz). */
    const val GLOW_ATTACK_SEC = 0.12f
    const val GLOW_RELEASE_SEC = 0.30f
    /** How much of the glow the voice may modulate at most (the rest is the state's resting light). */
    const val GLOW_DEPTH = 0.22f
    /** Every periodic brightness change in the visual, in Hz (idle breath, confirm heartbeat, acting ripples). */
    const val IDLE_BREATH_HZ = 1f / 4.2f
    const val CONFIRM_BEAT_HZ = 0.5f
    const val RIPPLE_HZ = 1f / 1.15f
    val PULSES_HZ = floatArrayOf(IDLE_BREATH_HZ, CONFIRM_BEAT_HZ, RIPPLE_HZ)
}

/** A speech-like amplitude envelope for SPEAKING (syllabic rhythm, not a metronome). The TTS gives us no level. */
fun speechEnvelope(t: Float): Float {
    val syllable = abs(sin(t * 7.3f)) * 0.7f + abs(sin(t * 11.1f + 1.3f)) * 0.3f
    val phrase = 0.55f + 0.45f * abs(sin(t * 1.35f))
    return (0.18f + syllable * phrase * 0.7f).coerceIn(0f, 1f)
}

/**
 * The whole living visual as a small physical system. [setPresence] moves the targets (and plays a [Kick]);
 * [step] advances time. Under [calm] (Reduce motion, or the system's "Remove animations") geometry never moves:
 * shapes snap to each state, nothing spins, shakes or ripples, and only colour and light cross-fade — so every state
 * still looks different, just still.
 */
class PresenceSim {
    var presence = Presence.IDLE
        private set
    private var spec = VisualSpec.of(Presence.IDLE)

    // Geometry — spatial springs (may overshoot a little: that is what makes them feel physical).
    val open = Spring(spec.open, 160f, 0.72f)
    val split = Spring(0f, 160f, 0.72f)
    val scale = Spring(1f, 420f, 0.5f)
    val wobble = Spring(spec.wobble, 60f, 1f)
    // Light and colour — effects springs (never overshoot).
    val energy = Spring(spec.energy, 60f, 1f)
    val warmth = Spring(spec.warmth, 90f, 1f)
    val attention = Spring(0f, 90f, 1f)
    val dim = Spring(0f, 90f, 1f)
    val glow = Spring(spec.glow, 90f, 1f)
    /** How much each thread mode shows, cross-faded so the pulse line never jumps between shapes. */
    val voiceMix = Spring(0f, 90f, 1f)
    val actMix = Spring(0f, 90f, 1f)
    val thinkMix = Spring(0f, 90f, 1f)
    /** The four rings as a chain of springs: the inner ring hears the voice first, the outer ones follow later. */
    val rings = arrayOf(Spring(0f, 520f, 0.45f), Spring(0f, 360f, 0.45f), Spring(0f, 250f, 0.45f), Spring(0f, 170f, 0.45f))
    /** Horizontal head-shake offset (units of the ring radius × 0.06). */
    val shake = Spring(0f, 700f, 0.18f)
    /** A finger pressing the circle squashes it slightly. */
    val press = Spring(1f, 800f, 0.55f)
    /** First appearance: the rings unfold from the brand mark. */
    val appear = Spring(0f, 110f, 0.7f)

    /** The voice driving the geometry (fast) and the light (slow, strobe-guarded). */
    val voice = Follower(0.03f, 0.18f)
    val voiceGlow = Follower(StrobeGuard.GLOW_ATTACK_SEC, StrobeGuard.GLOW_RELEASE_SEC)

    /** 1 → 0 after a BLOOM. */
    var bloom = 0f
        private set
    /** Motion clock (seconds); frozen while calm. */
    var time = 0f
        private set
    /** Each ring's rotation, integrated from its speed — so a change of speed never makes a ring jump. */
    val angles = floatArrayOf(0f, 61f, 122f, 183f)
    /** Recent voice levels for the pulse line, oldest first. */
    val history = FloatArray(THREAD_POINTS)
    /** Spawn times of the acting ripples (a small ring buffer; NaN = empty). */
    val ripples = FloatArray(3) { Float.NaN }
    private var nextRipple = 0
    private var lastRippleAt = -10f
    private var started = false
    private val all = arrayOf(open, split, scale, wobble, energy, warmth, attention, dim, glow, voiceMix, actMix, thinkMix)

    fun setPresence(p: Presence, calm: Boolean) {
        if (!started) {                                   // the very first state: no gesture, no transition
            started = true; presence = p; spec = VisualSpec.of(p); settleAll()
            if (calm) appear.snap(1f)                     // calm: no unfolding — it is simply there
            return
        }
        if (p == presence) return
        val kick = VisualSpec.kickFor(presence, p)
        presence = p
        spec = VisualSpec.of(p)
        retarget()
        if (calm) {
            // Shapes snap; colour/light still cross-fade (a fade is not motion — WCAG 2.3.3).
            open.snap(); split.snap(); scale.snap(); wobble.snap(); shake.snap(0f)
            if (kick == Kick.BLOOM) bloom = 1f
            return
        }
        when (kick) {
            Kick.LEAN_IN -> scale.kick(0.9f)
            Kick.DRAW_IN -> scale.kick(-0.6f)
            Kick.BLOOM -> { bloom = 1f; scale.kick(1.1f) }
            Kick.SHAKE -> shake.kick(26f)
            Kick.NUDGE -> scale.kick(0.45f)
            Kick.NONE -> {}
        }
    }

    private fun retarget() {
        open.target = spec.open; split.target = spec.split; scale.target = spec.scale; wobble.target = spec.wobble
        energy.target = spec.energy; warmth.target = spec.warmth; attention.target = spec.attention
        dim.target = spec.dim; glow.target = spec.glow
        voiceMix.target = if (spec.reactive) 1f else 0f
        actMix.target = if (presence == Presence.ACTING) 1f else 0f
        thinkMix.target = if (presence == Presence.THINKING) 1f else 0f
    }

    private fun settleAll() {
        retarget()
        for (s in all) s.snap()
        appear.target = 1f
    }

    /**
     * Advance by [dt] seconds. [micLevel] is the live microphone level 0..1. Returns true while anything is still
     * changing (so a calm screen can stop asking for frames once it has settled).
     */
    fun step(dt: Float, micLevel: Float, pressed: Boolean, calm: Boolean): Boolean {
        val d = dt.coerceIn(0f, 0.05f)                   // a long stall (app paused) must not fling the springs
        if (calm) {
            appear.snap(1f); press.snap(1f); shake.snap(0f)
            for (r in rings) r.snap(0f)
        } else {
            time += d
            appear.target = 1f
            press.target = if (pressed) 0.955f else 1f
        }
        for (s in all) s.step(d)
        if (!calm) { appear.step(d); press.step(d); shake.step(d) }

        // What the voice is: the microphone while listening, a speech envelope while it talks, silence otherwise.
        val drive = when {
            VisualSpec.listening(presence) -> micLevel.coerceIn(0f, 1f)
            presence == Presence.SPEAKING -> if (calm) 0.3f else speechEnvelope(time)
            else -> 0f
        }
        voice.step(drive, d)
        voiceGlow.step(drive, d)
        if (!calm) {
            rings[0].target = voice.value
            for (i in 1 until rings.size) rings[i].target = rings[i - 1].value
            for (r in rings) r.step(d)
            // Rotation is INTEGRATED from speed: ~1 turn a minute at rest, brisk while thinking.
            val speed = 4f + energy.value * energy.value * 90f
            for (i in angles.indices) {
                val dir = if (i % 2 == 0) 1f else -1f
                angles[i] = (angles[i] + dir * speed * (1f - 0.12f * i) * d) % 360f
            }
            System.arraycopy(history, 1, history, 0, history.size - 1)
            history[history.size - 1] = voice.value
            if (actMix.value > 0.5f && time - lastRippleAt > 1f / StrobeGuard.RIPPLE_HZ) {
                ripples[nextRipple] = time; nextRipple = (nextRipple + 1) % ripples.size; lastRippleAt = time
            }
        }
        if (bloom > 0f) bloom = (bloom - d / BLOOM_SEC).coerceAtLeast(0f)
        return !atRest(calm)
    }

    /** Nothing left to animate (only meaningful when calm — in full motion the visual always breathes). */
    fun atRest(calm: Boolean): Boolean =
        // Calm and only SPEAKING, the drive is a fixed 0.3: nothing changes, so nothing is redrawn (UI audit, 20 Oct 2026 —
        // it repainted the whole window 30-60 times a second with identical frames while the reply card was up).
        calm && (!spec.reactive || !VisualSpec.listening(presence)) && bloom == 0f && all.all { it.atRest } &&
            appear.value == 1f && press.value == 1f && shake.value == 0f     // (a calm step snaps these three)

    /** Age 0..1 of ripple [i], or -1 if it is not showing. */
    fun rippleAge(i: Int): Float {
        val t0 = ripples[i]
        if (t0.isNaN()) return -1f
        val age = (time - t0) / RIPPLE_LIFE_SEC
        return if (age in 0f..1f) age else -1f
    }

    /** The resting light plus the (strobe-guarded) voice, with the confirming heartbeat folded in. */
    fun light(): Float {
        val beat = if (attention.value > 0.01f) 0.10f * attention.value *
            (0.5f + 0.5f * sin(2f * PI.toFloat() * StrobeGuard.CONFIRM_BEAT_HZ * time)) else 0f
        return (glow.value + StrobeGuard.GLOW_DEPTH * voiceGlow.value + beat + 0.35f * bloom).coerceIn(0f, 1.2f)
    }

    companion object {
        const val THREAD_POINTS = 120
        const val BLOOM_SEC = 1.1f
        const val RIPPLE_LIFE_SEC = 1.7f
    }
}
