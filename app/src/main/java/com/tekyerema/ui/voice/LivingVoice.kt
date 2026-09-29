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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
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
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkPalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The living voice — a liquid-glass orb (redesign 2026-09-29, after the new Siri orb and Apple's Liquid Glass).
 *
 * A sphere of glass holding swirling coloured light. Four soft lights (cyan, azure, violet, rose) drift inside it and
 * glow around it; the user's voice — and the assistant's own speech — becomes a multicolour waveform across its
 * middle; thinking sends a highlight racing around the rim; a request for the user's OK warms everything to gold; an
 * error greys it. The glass itself is drawn from gradients: a dark (or pearl) core, a bright rim catching the light
 * top-left, and a specular highlight — no blur, so it holds 60 fps on the Galaxy A20e.
 *
 * Everything that moves is a spring in [PresenceSim], unchanged: states flow into each other with momentum and each
 * change plays a small gesture (lean in, draw in, bloom, shake, nudge). Drawing happens in the draw phase only — one
 * frame clock, no recomposition per frame.
 *
 * Accessibility: the whole visual is one large button; label and state come from the caller, the animation is
 * decorative. [reduceMotion] (in-app or the system's "Remove animations") makes it calm: nothing swirls, the light
 * only cross-fades, and nothing is drawn per frame once it has settled.
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
    val palette = Tk.palette
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
                // Built once per size and palette: every gradient is drawn tinted and transformed, never re-created.
                val kit = OrbKit(
                    glow = Brush.radialGradient(
                        0f to Color.White, 0.3f to Color.White.copy(alpha = 0.6f), 0.65f to Color.White.copy(alpha = 0.16f),
                        1f to Color.Transparent, center = Offset.Zero, radius = UNIT
                    ),
                    body = if (palette.dark) Brush.radialGradient(
                        0f to Color(0xFF26337A), 0.65f to Color(0xFF121B45), 1f to Color(0xFF0A1028),
                        center = Offset(-0.18f * UNIT, -0.22f * UNIT), radius = UNIT * 1.25f
                    ) else Brush.radialGradient(
                        0f to Color(0xFFFFFFFF), 0.65f to Color(0xFFEFF3FF), 1f to Color(0xFFDCE4FA),
                        center = Offset(-0.18f * UNIT, -0.22f * UNIT), radius = UNIT * 1.25f
                    ),
                    // The glass's weight: a soft darkening toward the lower edge, so it reads as a sphere, not a disc.
                    depth = Brush.radialGradient(
                        0f to Color.Transparent, 0.72f to Color.Transparent,
                        1f to (if (palette.dark) Color(0x8C000000) else Color(0x331B2A55)),
                        center = Offset(0f, -0.12f * UNIT), radius = UNIT * 1.08f
                    ),
                    rim = Brush.sweepGradient(
                        0f to Color.White.copy(alpha = 0.10f), 0.30f to Color.White.copy(alpha = 0.05f),
                        0.55f to Color.White.copy(alpha = 0.30f), 0.62f to Color.White.copy(alpha = 0.95f),
                        0.72f to Color.White.copy(alpha = 0.35f), 1f to Color.White.copy(alpha = 0.10f),
                        center = Offset.Zero
                    ),
                    spec = Brush.radialGradient(
                        0f to Color.White.copy(alpha = if (palette.dark) 0.55f else 0.85f), 1f to Color.Transparent,
                        center = Offset.Zero, radius = UNIT
                    ),
                    rimStroke = Stroke(width = 1.6.dp.toPx()),
                    waveStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    waveGlow = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    ripple = Stroke(width = 1.5.dp.toPx()),
                    bloom = Stroke(width = 2.5.dp.toPx()),
                    focus = Stroke(width = 3.dp.toPx()),
                    clip = Path(), wave = Path(),
                    blend = if (palette.dark) BlendMode.Plus else BlendMode.SrcOver
                )
                onDrawBehind {
                    if (frame == Long.MIN_VALUE) return@onDrawBehind     // reading it subscribes this draw to the frame clock
                    drawOrb(sim, kit, palette, focused, reduceMotion)
                }
            }
        )
    }
}

/** Everything the draw pass reuses frame to frame. */
private class OrbKit(
    val glow: Brush, val body: Brush, val depth: Brush, val rim: Brush, val spec: Brush,
    val rimStroke: Stroke, val waveStroke: Stroke, val waveGlow: Stroke, val ripple: Stroke, val bloom: Stroke,
    val focus: Stroke, val clip: Path, val wave: Path, val blend: BlendMode
)

private const val UNIT = 100f
/** Orb radius as a share of the smaller side; the aura reaches ~1.55× that, inside the square. */
private const val BASE_R = 0.30f
private val LIGHTS = arrayOf(Spectrum.Cyan, Spectrum.Azure, Spectrum.Violet, Spectrum.Rose)
private val ErrorGrey = Color(0xFF6B7490)
/** The waveform's taper toward the rim (sin^1.6). Clamped: sin(π) is a tiny negative → NaN. */
private val WAVE_WINDOW = FloatArray(PresenceSim.THREAD_POINTS) { j ->
    sin(PI.toFloat() * j / (PresenceSim.THREAD_POINTS - 1f)).coerceAtLeast(0f).pow(1.6f)
}

private fun DrawScope.drawOrb(s: PresenceSim, kit: OrbKit, pal: TkPalette, focused: Boolean, calm: Boolean) {
    val min = size.minDimension
    val appear = s.appear.value
    val cx = size.width / 2f + s.shake.value * min * 0.02f
    val cy = size.height / 2f
    val rBase = min * BASE_R
    val r = rBase * s.scale.value * s.press.value * (0.84f + 0.16f * appear) *
        (1f + 0.04f * s.rings[0].value.coerceIn(-1f, 1.5f))          // the voice swells the glass a little
    val attention = s.attention.value.coerceIn(0f, 1f)
    val dim = s.dim.value.coerceIn(0f, 1f)
    val alphaScale = (1f - 0.55f * dim) * appear.coerceIn(0f, 1f)
    val time = s.time
    val light = s.light()
    val bloom = s.bloom
    val energy = s.energy.value
    val twoPi = 2f * PI.toFloat()

    // Colour: attention (needs your OK) warms every light to gold; an error greys it down.
    fun tone(c: Color): Color = lerp(lerp(c, Spectrum.Gold, attention * 0.8f), ErrorGrey, dim * 0.7f)

    // Swirl speed: slow drift at rest, quicker while thinking/acting, lively with a voice. Calm: no swirl.
    val swirl = if (calm) 0f else time * (0.22f + 0.9f * energy * energy + 0.5f * s.voiceGlow.value)

    // 1. The aura — four soft lights orbiting behind the glass, brighter and wider with the voice.
    val auraAlpha = if (pal.dark) 0.30f else 0.26f
    for (b in 0 until 4) {
        val phase = b * (twoPi / 4f)
        val ang = swirl * (if (b % 2 == 0) 1f else -0.8f) + phase
        val orbit = r * (0.28f + 0.10f * sin(time * 0.33f + phase) * (if (calm) 0f else 1f))
        val radius = r * (1.18f + 0.07f * sin(time * 0.5f + phase * 1.3f) + 0.30f * s.voiceGlow.value).coerceAtMost(r * 1.6f)
        val a = ((0.35f + light) * auraAlpha * alphaScale).coerceIn(0f, 1f)
        if (a <= 0.003f) continue
        withTransform({
            translate(cx + orbit * cos(ang), cy + orbit * sin(ang))
            scale(radius / UNIT, radius / UNIT, Offset.Zero)
        }) {
            drawCircle(kit.glow, radius = UNIT, center = Offset.Zero, alpha = a,
                colorFilter = ColorFilter.tint(tone(LIGHTS[b])), blendMode = kit.blend)
        }
    }

    // 2. Acting: ripples reaching out into the phone (decelerating, like a real ripple).
    if (!calm && s.actMix.value > 0.01f) {
        for (i in 0 until 3) {
            val age = s.rippleAge(i)
            if (age < 0f) continue
            val eased = 1f - (1f - age) * (1f - age)
            drawCircle(tone(Spectrum.Cyan).copy(alpha = (0.45f * (1f - age) * (1f - age) * alphaScale * s.actMix.value).coerceIn(0f, 1f)),
                radius = r * (1.02f + 0.5f * eased), center = Offset(cx, cy), style = kit.ripple)
        }
    }

    // 3. The glass body.
    withTransform({ translate(cx, cy); scale(r / UNIT, r / UNIT, Offset.Zero) }) {
        drawCircle(kit.body, radius = UNIT, center = Offset.Zero, alpha = appear.coerceIn(0f, 1f))
    }

    // 4. The liquid inside — clipped to the sphere.
    kit.clip.reset()
    kit.clip.addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r))
    clipPath(kit.clip) {
        for (b in 0 until 4) {
            val phase = b * (twoPi / 4f) + 0.6f
            val ang = swirl * 1.4f * (if (b % 2 == 0) -1f else 1f) + phase
            val orbit = r * (0.42f + 0.12f * sin(time * 0.41f + phase) * (if (calm) 0f else 1f))
            val radius = r * (0.86f + 0.14f * s.voiceGlow.value)
            val a = ((0.48f + 0.5f * light) * (if (pal.dark) 0.92f else 0.62f) * alphaScale).coerceIn(0f, 1f)
            withTransform({
                translate(cx + orbit * cos(ang), cy + orbit * sin(ang) + r * 0.08f)
                scale(radius / UNIT, radius / UNIT, Offset.Zero)
            }) {
                drawCircle(kit.glow, radius = UNIT, center = Offset.Zero, alpha = a,
                    colorFilter = ColorFilter.tint(tone(LIGHTS[(b + 1) % 4])), blendMode = kit.blend)
            }
        }
        // 5. The waveform: the user's voice (or the reply being spoken) as three coloured strands.
        drawWaves(s, kit, cx, cy, r, time, alphaScale, ::tone)
        // The glass's weight toward its lower edge.
        withTransform({ translate(cx, cy); scale(r / UNIT, r / UNIT, Offset.Zero) }) {
            drawCircle(kit.depth, radius = UNIT, center = Offset.Zero)
        }
    }

    // 6. The rim — light catching the edge of the glass. While thinking, the bright spot races around it.
    val rimTurn = 215f + (if (calm) 0f else s.thinkMix.value * time * 260f + s.actMix.value * time * 90f)
    withTransform({ translate(cx, cy) }) {
        rotate(rimTurn, Offset.Zero) {
            drawCircle(kit.rim, radius = r, center = Offset.Zero, style = kit.rimStroke,
                alpha = ((if (pal.dark) 0.9f else 1f) * appear).coerceIn(0f, 1f))
        }
    }
    // A faint outline so the sphere keeps its edge on the pale page.
    if (!pal.dark) drawCircle(Color(0x221B2A55), radius = r, center = Offset(cx, cy), style = kit.ripple)

    // 7. The specular highlight, top-left.
    withTransform({
        translate(cx - r * 0.36f, cy - r * 0.52f)
        scale(r * 0.40f / UNIT, r * 0.20f / UNIT, Offset.Zero)
    }) {
        drawCircle(kit.spec, radius = UNIT, center = Offset.Zero, alpha = (0.9f * appear).coerceIn(0f, 1f))
    }

    // 8. Done: a ring of light is released outward.
    if (!calm && bloom > 0f) {
        val e = 1f - bloom
        val eased = 1f - (1f - e) * (1f - e)
        drawCircle(tone(Spectrum.Cyan).copy(alpha = (0.65f * bloom * bloom * alphaScale).coerceIn(0f, 1f)),
            radius = r * (1.0f + 0.6f * eased), center = Offset(cx, cy), style = kit.bloom)
    }

    // 9. Keyboard / switch-access focus: an unmistakable ring outside everything else.
    if (focused) drawCircle(pal.focus, radius = rBase * 1.5f, center = Offset(size.width / 2f, cy), style = kit.focus)
}

/** Three coloured strands across the orb's middle, shaped by the voice history; a hairline hum at rest. */
private fun DrawScope.drawWaves(
    s: PresenceSim, kit: OrbKit, cx: Float, cy: Float, r: Float, time: Float, alphaScale: Float, tone: (Color) -> Color
) {
    val n = PresenceSim.THREAD_POINTS
    val voiceMix = s.voiceMix.value
    val thinkMix = s.thinkMix.value
    val energy = s.energy.value
    val twoPi = 2f * PI.toFloat()
    val x0 = cx - r * 0.98f
    val w = r * 1.96f
    val colors = arrayOf(Spectrum.Cyan, Spectrum.Violet, Spectrum.Rose)
    for (k in 0 until 3) {
        val path = kit.wave
        path.reset()
        val phase = k * 2.1f
        val freq = 1.6f + 0.55f * k
        val dir = if (k == 1) -1f else 1f
        for (j in 0 until n) {
            val u = j / (n - 1f)
            val x = x0 + u * w
            val window = WAVE_WINDOW[j]
            // At rest: a slow, shallow hum so the glass never looks switched off.
            var y = r * 0.025f * sin(twoPi * (u * 1.2f + dir * time / 5f) + phase) * (0.5f + energy)
            if (voiceMix > 0.01f) {
                val amp = r * (0.05f * energy + 0.55f * s.history[j]) * (1f - 0.18f * k)
                y += voiceMix * window * amp * sin(twoPi * (u * freq - dir * time * (1.6f + 0.4f * k)) + phase)
            }
            if (thinkMix > 0.01f) y += thinkMix * window * r * 0.05f * sin(twoPi * (u * 2.5f + time * 0.9f) + phase)
            if (j == 0) path.moveTo(x, cy + y) else path.lineTo(x, cy + y)
        }
        val base = (0.07f + 0.85f * voiceMix + 0.25f * thinkMix).coerceAtMost(1f)
        val a = (base * alphaScale * (1f - 0.25f * k)).coerceIn(0f, 1f)
        val c = tone(colors[k])
        drawPath(path, c, alpha = 0.22f * a, style = kit.waveGlow, blendMode = kit.blend)
        drawPath(path, lerp(c, Color.White, 0.25f), alpha = a, style = kit.waveStroke)
    }
}
