package com.tekyerema.ui.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tekyerema.ui.theme.Tk
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The living voice interface — Tɛkyerɛma's identity. The brand mark (an open "ear" ring and a cyan pulse) unfolds
 * into four layered rings over a slow aurora of light, threaded by a horizontal pulse line that carries the user's
 * voice (live microphone level), the assistant's speech, and — while acting — the brand's travelling pulse.
 *
 * Everything that moves is a spring in [PresenceSim]: states flow into one another with momentum, the rings form a
 * chain that the voice ripples through (inner ring first), and each change of state plays a small physical gesture
 * (lean in, draw in, bloom, head-shake, nudge). Drawing happens in the draw phase only — one frame clock, no
 * recomposition per frame — so it holds 60 fps on the Galaxy A20e.
 *
 * Accessibility: the whole visual is one large button. Its label and state description come from the caller; the
 * animation is decorative. [reduceMotion] (the in-app setting OR the system's "Remove animations") makes it calm:
 * shapes snap to each state and stay still, only colour and light cross-fade, and nothing is drawn per frame once
 * it has settled.
 */
@Composable
fun LivingVoice(
    presence: Presence,
    /** Live mic level 0..1, read once per frame (a lambda so the level never recomposes the screen). */
    level: () -> Float,
    reduceMotion: Boolean,
    contentDescription: String,
    stateDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** Clip the touch target to the circle (the arrive-on-top screen treats everything else as "outside"). */
    circularHitArea: Boolean = false
) {
    val sim = remember { PresenceSim().also { it.setPresence(presence, reduceMotion) } }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val currentPresence by rememberUpdatedState(presence)
    val currentPressed by rememberUpdatedState(pressed)
    val currentLevel by rememberUpdatedState(level)
    // Bumped once per simulated frame and read only inside the draw lambda: a redraw, never a recomposition.
    var frame by remember { mutableLongStateOf(0L) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sim, reduceMotion, lifecycle) {
        // Only while the screen is visible: a task running with our screen in the background costs no frames.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = -1L
            while (true) {
                sim.setPresence(currentPresence, reduceMotion)
                if (reduceMotion && sim.atRest(true)) {
                    // Calm and settled: nothing to draw. Poll gently for the next state instead of every vsync.
                    delay(100); last = -1L; continue
                }
                withFrameNanos { now ->
                    val dt = if (last < 0) 0f else (now - last) / 1e9f
                    last = now
                    sim.step(dt, currentLevel(), currentPressed, reduceMotion)
                    frame = now
                }
            }
        }
    }

    Box(
        modifier = modifier
            .then(if (circularHitArea) Modifier.clip(CircleShape) else Modifier)
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onTap
            )
    ) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                // Built once per size: the gradients are drawn tinted and transformed, never re-created per frame.
                val min = size.minDimension
                val threadWidth = min * BASE_R * 2.7f
                val threadX0 = size.width / 2f - threadWidth / 2f
                val kit = DrawKit(
                    glow = Brush.radialGradient(
                        0f to Color.White, 0.35f to Color.White.copy(alpha = 0.55f), 0.7f to Color.White.copy(alpha = 0.14f),
                        1f to Color.Transparent, center = Offset.Zero, radius = UNIT
                    ),
                    thread = Brush.horizontalGradient(
                        0f to Color.Transparent, 0.2f to Color.White, 0.8f to Color.White, 1f to Color.Transparent,
                        startX = threadX0, endX = threadX0 + threadWidth
                    ),
                    threadX0 = threadX0, threadWidth = threadWidth,
                    threadGlow = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    threadCore = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    ringStrokes = Array(4) { k -> Stroke(width = RING_STROKE_DP[k].dp.toPx(), cap = StrokeCap.Round) },
                    haloStroke = Stroke(width = RING_STROKE_DP[3].dp.toPx() * 2.6f, cap = StrokeCap.Round),
                    ripple = Stroke(width = 1.5.dp.toPx()),
                    bloom = Stroke(width = 2.5.dp.toPx()),
                    focus = Stroke(width = 3.dp.toPx()),
                    path = Path()
                )
                onDrawBehind {
                    if (frame == Long.MIN_VALUE) return@onDrawBehind     // reading it subscribes this draw to the frame clock
                    drawPresence(sim, kit, focused, reduceMotion)
                }
            }
        )
    }
}

/** Everything the draw pass reuses frame to frame. */
private class DrawKit(
    val glow: Brush, val thread: Brush, val threadX0: Float, val threadWidth: Float,
    val threadGlow: Stroke, val threadCore: Stroke, val ringStrokes: Array<Stroke>, val haloStroke: Stroke,
    val ripple: Stroke, val bloom: Stroke, val focus: Stroke, val path: Path
)

private const val UNIT = 100f
/** Ring radius as a share of the smaller side; everything else scales from it. Stays inside the circular clip. */
private const val BASE_R = 0.335f
private val RING_FACTORS = floatArrayOf(1.12f, 0.97f, 0.82f, 0.66f)     // outermost first
private val RING_STROKE_DP = floatArrayOf(2.2f, 3f, 4.5f, 7f)
private val RING_ALPHAS = floatArrayOf(0.32f, 0.5f, 0.7f, 0.95f)
private val AURORA_ALPHAS = floatArrayOf(0.34f, 0.28f, 0.22f)
private val Indigo = Color(0xFF2A3A8F)
private val Violet = Color(0xFF8B7CFF)
private val ErrorGrey = Color(0xFF56607A)
/** The pulse line's taper toward its ends (sin^1.4), computed once. Clamped: sin(π) is a tiny negative → NaN. */
private val THREAD_WINDOW = FloatArray(PresenceSim.THREAD_POINTS) { j ->
    sin(PI.toFloat() * j / (PresenceSim.THREAD_POINTS - 1f)).coerceAtLeast(0f).pow(1.4f)
}

private fun DrawScope.drawPresence(s: PresenceSim, kit: DrawKit, focused: Boolean, calm: Boolean) {
    val min = size.minDimension
    val appear = s.appear.value
    val cx = size.width / 2f + s.shake.value * min * 0.02f
    val cy = size.height / 2f
    val rBase = min * BASE_R
    val r = rBase * s.scale.value * s.press.value * (0.82f + 0.18f * appear)
    val warmth = s.warmth.value.coerceIn(0f, 1f)
    val attention = s.attention.value.coerceIn(0f, 1f)
    val dim = s.dim.value.coerceIn(0f, 1f)
    val alphaScale = (1f - 0.6f * dim) * appear.coerceIn(0f, 1f)
    val time = s.time
    val light = s.light()
    val bloom = s.bloom

    // Colour temperature: calm blue → active cyan; attention warms toward amber; an error greys it down.
    fun tone(base: Color, active: Color): Color =
        lerp(lerp(lerp(base, active, warmth), Tk.Attention, attention * 0.85f), ErrorGrey, dim * 0.5f)

    // 1. The aurora — three soft lights drifting behind the rings, brighter with the voice (strobe-guarded).
    //    Additive, so where they overlap the light deepens like a real glow; no blur, so it is cheap on old GPUs.
    for (b in 0 until 3) {
        val phase = b * 2.094f
        val dir = if (b == 1) -1f else 1f
        val orbitAngle = time * (0.21f + 0.07f * b) * dir + phase
        val orbit = r * (0.16f + 0.08f * sin(time * 0.37f + phase)) * (0.6f + 0.6f * s.wobble.value)
        val radius = r * (1.02f + 0.06f * sin(time * 0.5f + phase * 1.3f) + 0.22f * s.voiceGlow.value)
            .coerceAtMost(1.38f)
        val color = when (b) {
            0 -> tone(Tk.Blue, Tk.Cyan)
            1 -> tone(Tk.BlueDeep, Tk.BlueBright)
            else -> tone(Indigo, Violet)
        }
        val a = (light * AURORA_ALPHAS[b] * alphaScale).coerceIn(0f, 1f)
        if (a <= 0.003f) continue
        withTransform({
            translate(cx + orbit * cos(orbitAngle), cy + orbit * sin(orbitAngle))
            scale(radius / UNIT, radius / UNIT, Offset.Zero)
        }) {
            drawCircle(kit.glow, radius = UNIT, center = Offset.Zero, alpha = a,
                colorFilter = ColorFilter.tint(color), blendMode = BlendMode.Plus)
        }
    }

    // 2. Acting: ripples reaching out into the phone (decelerating, like a real ripple).
    if (!calm && s.actMix.value > 0.01f) {
        for (i in 0 until 3) {
            val age = s.rippleAge(i)
            if (age < 0f) continue
            val eased = 1f - (1f - age) * (1f - age)
            drawCircle(
                color = Tk.Cyan.copy(alpha = (0.38f * (1f - age) * (1f - age) * alphaScale * s.actMix.value).coerceIn(0f, 1f)),
                radius = r * (0.95f + 0.45f * eased), center = Offset(cx, cy), style = kit.ripple
            )
        }
    }

    // 3. The pulse thread (under the rings, so the inner ring reads as the ear on top).
    drawThread(s, kit, cx, cy, rBase, time, warmth, attention, alphaScale, bloom)

    // 4. Four layered rings, outermost first. The innermost is the brand's ear: thick and white.
    val open = s.open.value.coerceIn(0f, 1f)
    val split = s.split.value.coerceIn(0f, 1f)
    val wobble = s.wobble.value
    for (k in 0 until 4) {
        val i = 3 - k                                            // 0 = innermost
        val chain = s.rings[i].value
        val breath = 1f +
            0.018f * sin(2f * PI.toFloat() * StrobeGuard.IDLE_BREATH_HZ * time + i * 0.9f) * (0.4f + s.energy.value) +
            chain * 0.12f * (1.1f - 0.2f * i) + bloom * 0.04f * (1f - 0.25f * i)
        val radius = r * RING_FACTORS[k] * breath
        // Off-centre drift: the layers slosh like liquid, more when a voice moves them.
        val drift = r * (0.022f * wobble + 0.03f * chain.coerceIn(0f, 1f))
        val rcx = cx + drift * sin(time * (0.9f + 0.23f * i) + i * 1.7f)
        val rcy = cy + drift * cos(time * (0.7f + 0.19f * i) + i * 2.3f)
        // Unfold on arrival: the inner ring first, then outward.
        val unfold = ((appear - i * 0.08f) / 0.76f).coerceIn(0f, 1f)
        val sweep = ((360f * (1f - open * (0.45f + 0.15f * i)) * (1f - bloom) + 360f * bloom) * unfold).coerceAtMost(360f)
        val color = when (i) {
            0 -> tone(Color.White, lerp(Color.White, Tk.Cyan, 0.18f))
            1 -> tone(Color(0xFFDCE6FF), lerp(Color.White, Tk.Cyan, 0.55f))
            2 -> tone(Tk.BlueBright, Tk.Cyan)
            else -> tone(Tk.Blue, Tk.Cyan)
        }
        val a = ((RING_ALPHAS[k] + 0.3f * bloom).coerceAtMost(1f) * alphaScale).coerceIn(0f, 1f)
        val stroke = kit.ringStrokes[k]
        if (i == 0) drawDashes(color.copy(alpha = 0.10f * a), rcx, rcy, radius, s.angles[i], sweep, split, kit.haloStroke)
        drawDashes(color.copy(alpha = a), rcx, rcy, radius, s.angles[i], sweep, split, stroke)
    }

    // 5. Thinking: one bright thought orbits the ear ring, trailing a short tail.
    val think = s.thinkMix.value
    if (!calm && think > 0.01f) {
        val rr = r * RING_FACTORS[3]
        val head = time * 3.5f
        for (k in 0 until 5) {
            val ang = head - k * 0.13f
            val fade = (1f - k / 5f)
            drawCircle(Color.White.copy(alpha = (0.9f * think * fade * fade * alphaScale).coerceIn(0f, 1f)),
                radius = (4.5f - 0.6f * k).dp.toPx(), center = Offset(cx + rr * cos(ang), cy + rr * sin(ang)))
        }
        drawCircle(Tk.Cyan.copy(alpha = (0.25f * think * alphaScale).coerceIn(0f, 1f)), radius = 11.dp.toPx(),
            center = Offset(cx + rr * cos(head), cy + rr * sin(head)))
    }

    // 6. Done: a ring of light is released outward.
    if (!calm && bloom > 0f) {
        val e = 1f - bloom
        val eased = 1f - (1f - e) * (1f - e)
        drawCircle(Color.White.copy(alpha = (0.55f * bloom * bloom * alphaScale).coerceIn(0f, 1f)),
            radius = r * (0.7f + 0.7f * eased), center = Offset(cx, cy), style = kit.bloom)
    }

    // 7. Keyboard / switch-access focus: an unmistakable white ring outside everything else.
    if (focused) drawCircle(Tk.Focus, radius = rBase * 1.28f, center = Offset(size.width / 2f, cy), style = kit.focus)
}

/**
 * A ring arc that tears into two chasing dashes as [split] rises. Below a small threshold it is one arc (two round
 * caps touching would overlap into a bright bead); past it the dashes part and slide to opposite sides.
 */
private fun DrawScope.drawDashes(color: Color, cx: Float, cy: Float, radius: Float, start: Float, sweep: Float,
                                 split: Float, stroke: Stroke) {
    if (sweep <= 0.5f || color.alpha <= 0.003f) return
    val topLeft = Offset(cx - radius, cy - radius)
    val size = Size(radius * 2f, radius * 2f)
    val capGap = (stroke.width / radius) * (180f / PI.toFloat()) + 2f
    if (split < 0.06f || sweep > 360f - 2f * capGap) {
        drawArc(color, start, sweep, false, topLeft, size, style = stroke)
        return
    }
    val len = sweep / 2f * (1f - 0.25f * split)
    val gap = capGap + split * (180f - len - capGap).coerceAtLeast(0f)
    drawArc(color, start, len, false, topLeft, size, style = stroke)
    drawArc(color, start + len + gap, len, false, topLeft, size, style = stroke)
}

private fun DrawScope.drawThread(
    s: PresenceSim, kit: DrawKit, cx: Float, cy: Float, rBase: Float, time: Float,
    warmth: Float, attention: Float, alphaScale: Float, bloom: Float
) {
    val n = PresenceSim.THREAD_POINTS
    val path = kit.path
    path.reset()
    val idleRipple = 1.5.dp.toPx()
    val twoPi = 2f * PI.toFloat()
    val energy = s.energy.value
    val voiceMix = s.voiceMix.value
    val actMix = s.actMix.value
    val thinkMix = s.thinkMix.value
    val pulsePos = ((time * 0.55f) % 1.3f) - 0.15f          // acting: the brand pulse travels left→right
    val x0 = kit.threadX0 + (cx - size.width / 2f)          // follows the head-shake
    for (j in 0 until n) {
        val u = j / (n - 1f)
        val x = x0 + u * kit.threadWidth
        val window = THREAD_WINDOW[j]
        var y = idleRipple * sin(twoPi * (u * 1.3f + time / 6f)) * (0.5f + energy)
        if (voiceMix > 0.01f) {
            val amp = rBase * (0.05f * energy + 0.42f * s.history[j])
            val texture = sin(twoPi * (u * 5f - time * 2.5f)) * (0.75f + 0.25f * sin(twoPi * (u * 11f + time * 3.1f)))
            y += voiceMix * window * amp * texture
        }
        if (actMix > 0.01f) {
            val d = (u - pulsePos) / 0.055f
            y += actMix * -rBase * 0.20f * exp(-d * d) * sin(twoPi * (u - pulsePos) * 11f) * window.coerceAtLeast(0.35f)
        }
        if (thinkMix > 0.01f) y += thinkMix * window * rBase * 0.02f * sin(twoPi * (u * 3f + time * 0.8f))
        if (j == 0) path.moveTo(x, cy + y) else path.lineTo(x, cy + y)
    }
    val base = lerp(lerp(Tk.Cyan, Color.White, 0.25f * warmth), Tk.Attention, attention)
    val a = ((0.55f + 0.35f * warmth + 0.4f * s.voice.value + 0.3f * bloom).coerceAtMost(1f) * alphaScale *
        (1f - 0.55f * thinkMix)).coerceIn(0f, 1f)
    val tint = ColorFilter.tint(base)
    // Faded at both ends (the gradient brush), so the line emerges from and dissolves into the dark.
    drawPath(path, kit.thread, alpha = 0.18f * a, style = kit.threadGlow, colorFilter = tint)
    drawPath(path, kit.thread, alpha = a, style = kit.threadCore, colorFilter = tint)
}
