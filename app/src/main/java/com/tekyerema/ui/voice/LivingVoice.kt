package com.tekyerema.ui.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tekyerema.ui.theme.Tk
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The living voice interface — Tɛkyerɛma's identity. The brand mark (an open "ear" ring and a
 * cyan pulse) unfolds into four layered rings that breathe and turn, threaded by a horizontal
 * pulse line that carries the user's voice (live microphone level), the assistant's speech, and —
 * while acting — the brand's travelling pulse. Every state has its own motion and colour
 * temperature; nothing here is an orb or a microphone button.
 *
 * Accessibility: the whole visual is one large button. Its label and state description come
 * from the caller; the animation is decorative and freezes under Reduce motion.
 */
@Composable
fun LivingVoice(
    presence: Presence,
    level: Float,                 // live mic level 0..1 (only meaningful while listening)
    reduceMotion: Boolean,
    contentDescription: String,
    stateDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** Clip the touch target to the circle (the arrive-on-top screen treats everything else as "outside"). */
    circularHitArea: Boolean = false
) {
    val target = Params.of(presence)
    val anim = tween<Float>(durationMillis = 650, easing = FastOutSlowInEasing)
    val open by animateFloatAsState(target.open, anim, label = "open")
    val energy by animateFloatAsState(target.energy, anim, label = "energy")
    val warmth by animateFloatAsState(target.warmth, anim, label = "warmth")
    val attention by animateFloatAsState(target.attention, anim, label = "attention")
    val dim by animateFloatAsState(target.dim, anim, label = "dim")

    // Completion: rings close into a full circle and brighten, then relax back to idle.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(presence) {
        if (presence == Presence.DONE) { flash.snapTo(1f); flash.animateTo(0f, tween(1100, easing = FastOutSlowInEasing)) }
    }

    // Time base + the pulse line's memory of recent levels (a slow scroll, like breath on glass).
    var t by remember { mutableFloatStateOf(0f) }
    val history = remember { FloatArray(THREAD_POINTS) }
    var smoothLevel by remember { mutableFloatStateOf(0f) }
    val ripples = remember { ArrayList<Float>() }
    var lastRipple by remember { mutableFloatStateOf(-10f) }
    val currentPresence by rememberUpdatedState(presence)
    val currentLevel by rememberUpdatedState(level)
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) { t = 0f; return@LaunchedEffect }
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                t = (now - start) / 1e9f
                val drive = when (currentPresence) {
                    Presence.LISTENING, Presence.CONFIRM_LISTENING -> currentLevel
                    Presence.SPEAKING -> speechEnvelope(t)
                    else -> 0f
                }
                smoothLevel += (drive - smoothLevel) * (if (drive > smoothLevel) 0.5f else 0.12f)
                // scroll the history right→left, newest at the centre-right so it reads as flowing
                System.arraycopy(history, 1, history, 0, history.size - 1)
                history[history.size - 1] = smoothLevel
                if (currentPresence == Presence.ACTING && t - lastRipple > 1.15f) { ripples.add(t); lastRipple = t }
                ripples.removeAll { t - it > 1.7f }
            }
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

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
        Canvas(Modifier.fillMaxSize()) {
            val time = if (reduceMotion) 0f else t
            drawField(
                time = time, open = open, energy = energy, warmth = warmth, attention = attention,
                dim = dim, flash = flash.value, level = smoothLevel, history = history,
                presence = presence, ripples = ripples, focused = focused, reduceMotion = reduceMotion
            )
        }
    }
}

private const val THREAD_POINTS = 120

/** Per-state targets. Tuned so idle is calm, listening is alive, thinking is quick and light. */
private data class Params(val open: Float, val energy: Float, val warmth: Float, val attention: Float, val dim: Float) {
    companion object {
        fun of(p: Presence) = when (p) {
            Presence.IDLE -> Params(0.12f, 0.15f, 0.15f, 0f, 0f)
            Presence.LISTENING -> Params(0.45f, 0.55f, 1f, 0f, 0f)
            Presence.THINKING -> Params(0.72f, 1f, 0.6f, 0f, 0f)
            Presence.ACTING -> Params(0.30f, 0.6f, 0.85f, 0f, 0f)
            Presence.CONFIRMING -> Params(0.18f, 0.25f, 0.3f, 1f, 0f)
            Presence.CONFIRM_LISTENING -> Params(0.45f, 0.55f, 0.3f, 1f, 0f)   // open like listening, amber
            Presence.SPEAKING -> Params(0.28f, 0.5f, 0.9f, 0f, 0f)
            Presence.DONE -> Params(0f, 0.3f, 1f, 0f, 0f)
            Presence.ERROR -> Params(0.12f, 0.1f, 0f, 0f, 0.6f)
        }
    }
}

/** A speech-like amplitude envelope for the SPEAKING state (syllabic rhythm, not a metronome). */
private fun speechEnvelope(t: Float): Float {
    val syllable = abs(sin(t * 7.3f)) * 0.7f + abs(sin(t * 11.1f + 1.3f)) * 0.3f
    val phrase = 0.55f + 0.45f * abs(sin(t * 1.35f))
    return (0.18f + syllable * phrase * 0.7f).coerceIn(0f, 1f)
}

private fun DrawScope.drawField(
    time: Float, open: Float, energy: Float, warmth: Float, attention: Float, dim: Float,
    flash: Float, level: Float, history: FloatArray, presence: Presence, ripples: List<Float>,
    focused: Boolean, reduceMotion: Boolean
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = minOf(size.width, size.height) * 0.335f
    val alphaScale = 1f - 0.6f * dim

    // Colour temperature: calm white/blue → active cyan; attention warms toward amber.
    fun tone(base: Color, active: Color): Color =
        lerp(lerp(base, active, warmth), Tk.Attention, attention * 0.85f)

    // Soft centre glow — the only "light" in the interface, kept well below neon.
    val glow = (0.05f + 0.10f * warmth + 0.22f * level + 0.18f * flash) * alphaScale
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(tone(Tk.BlueBright, Tk.Cyan).copy(alpha = glow), Color.Transparent),
            center = Offset(cx, cy), radius = r * 1.35f
        ),
        radius = r * 1.35f, center = Offset(cx, cy)
    )

    // Ripples while acting: reaching out into the phone.
    for (t0 in ripples) {
        val age = ((time - t0) / 1.7f).coerceIn(0f, 1f)
        val rr = r * (0.95f + 0.62f * age)
        drawCircle(
            color = Tk.Cyan.copy(alpha = 0.38f * (1f - age) * (1f - age) * alphaScale),
            radius = rr, center = Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx())
        )
    }

    // The pulse thread (drawn under the rings so the inner ring reads as the ear on top).
    drawThread(cx, cy, r, time, energy, warmth, attention, level, history, presence, alphaScale, flash, reduceMotion)

    // Four layered rings, outermost first. The innermost is the brand's ear: thick and white.
    val factors = floatArrayOf(1.12f, 0.97f, 0.82f, 0.66f)
    val strokes = floatArrayOf(2.2f, 3f, 4.5f, 7f)
    val alphas = floatArrayOf(0.32f, 0.5f, 0.7f, 0.95f)
    for (k in 0 until 4) {
        val i = 3 - k                                            // 0 = innermost
        val dir = if (i % 2 == 0) 1f else -1f
        val speed = 4f + energy * energy * 90f                   // deg/s: ~1 turn/min idle → brisk thinking
        val phase = i * 61f
        val angle = phase + dir * speed * time
        val breath = 1f + 0.018f * sin(2f * PI.toFloat() * time / 4.2f + i * 0.9f) * (0.4f + energy) +
            level * 0.12f * (1.1f - 0.2f * i) + flash * 0.04f * (1f - 0.25f * i)
        val radius = r * factors[k] * breath
        val sweep = 360f * (1f - open * (0.45f + 0.15f * i)) * (1f - flash) + 360f * flash
        val color = when (i) {
            0 -> tone(Color.White, lerp(Color.White, Tk.Cyan, 0.18f))
            1 -> tone(Color(0xFFDCE6FF), lerp(Color.White, Tk.Cyan, 0.55f))
            2 -> tone(Tk.BlueBright, Tk.Cyan)
            else -> tone(Tk.Blue, Tk.Cyan)
        }
        val a = (alphas[k] + 0.3f * flash).coerceAtMost(1f) * alphaScale
        val w = strokes[k].dp.toPx()
        val segments = if (open > 0.6f) 2 else 1               // thinking: the rings break into travelling dashes
        val segSweep = sweep / segments
        for (sgm in 0 until segments) {
            val start = angle + sgm * (360f / segments)
            if (i == 0) {
                // halo under the ear ring
                drawArc(
                    color = color.copy(alpha = 0.10f * a), startAngle = start, sweepAngle = segSweep, useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius), size = Size(radius * 2, radius * 2),
                    style = Stroke(width = w * 2.6f, cap = StrokeCap.Round)
                )
            }
            drawArc(
                color = color.copy(alpha = a), startAngle = start, sweepAngle = segSweep, useCenter = false,
                topLeft = Offset(cx - radius, cy - radius), size = Size(radius * 2, radius * 2),
                style = Stroke(width = w, cap = StrokeCap.Round)
            )
        }
    }

    // Keyboard / switch-access focus: an unmistakable white ring outside everything else.
    if (focused) {
        drawCircle(
            color = Tk.Focus, radius = r * 1.28f, center = Offset(cx, cy),
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

private fun DrawScope.drawThread(
    cx: Float, cy: Float, r: Float, time: Float, energy: Float, warmth: Float, attention: Float,
    level: Float, history: FloatArray, presence: Presence, alphaScale: Float, flash: Float, reduceMotion: Boolean
) {
    val width = r * 2.7f
    val x0 = cx - width / 2f
    val n = THREAD_POINTS
    val path = Path()
    val idleRipple = 1.5.dp.toPx()
    val twoPi = 2f * PI.toFloat()
    val pulsePos = ((time * 0.55f) % 1.3f) - 0.15f          // acting: the brand pulse travels left→right
    for (j in 0 until n) {
        val u = j / (n - 1f)
        val x = x0 + u * width
        val window = sin(PI.toFloat() * u).coerceAtLeast(0f).pow(1.4f)   // clamp: sin(π) is a tiny negative → NaN
        var y = idleRipple * sin(twoPi * (u * 1.3f + time / 6f)) * (0.5f + energy)
        when (presence) {
            Presence.LISTENING, Presence.SPEAKING, Presence.CONFIRM_LISTENING -> {
                val h = history[j]
                val amp = r * (0.05f * energy + 0.42f * h)
                val texture = sin(twoPi * (u * 5f - time * 2.5f)) * (0.75f + 0.25f * sin(twoPi * (u * 11f + time * 3.1f)))
                y += window * amp * texture
            }
            Presence.ACTING -> {
                val d = (u - pulsePos) / 0.055f
                y += -r * 0.20f * exp(-d * d) * sin(twoPi * (u - pulsePos) * 11f) * window.coerceAtLeast(0.35f)
            }
            Presence.THINKING -> y += window * r * 0.02f * sin(twoPi * (u * 3f + time * 0.8f))
            else -> {}
        }
        if (j == 0) path.moveTo(x, cy + y) else path.lineTo(x, cy + y)
    }
    val base = lerp(lerp(Tk.Cyan, Color.White, 0.25f * warmth), Tk.Attention, attention)
    val a = (0.55f + 0.35f * warmth + 0.4f * level + 0.3f * flash).coerceAtMost(1f) * alphaScale *
        (if (presence == Presence.THINKING) 0.45f else 1f)
    drawPath(path, color = base.copy(alpha = 0.18f * a), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color = base.copy(alpha = a), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

    // Thinking: a single bright thought travels the thread.
    if (presence == Presence.THINKING && !reduceMotion) {
        val u = 0.5f + 0.5f * sin(time * 1.7f)
        val x = x0 + u * width
        drawCircle(color = Color.White.copy(alpha = 0.9f * alphaScale), radius = 4.5.dp.toPx(), center = Offset(x, cy))
        drawCircle(color = Tk.Cyan.copy(alpha = 0.25f * alphaScale), radius = 11.dp.toPx(), center = Offset(x, cy))
    }
}
