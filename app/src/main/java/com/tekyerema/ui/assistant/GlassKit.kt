package com.tekyerema.ui.assistant

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.glass.DEFAULT_LIGHT_DEGREES
import com.tekyerema.ui.glass.GlassDepth
import com.tekyerema.ui.glass.GlassShape
import com.tekyerema.ui.glass.LiquidStyles
import com.tekyerema.ui.glass.liquidGlass
import kotlin.math.min

/**
 * The surface's visual kit (docs/assistant-ux/PLAN_V2.md, DECISIONS V2-5): a continuous-corner glass shape, its layers, the
 * state-driven edge light, the quality tier, and the few named motions everything reuses. Every brush and path is built once
 * per size (drawWithCache); per frame only cached objects are drawn, and the only per-frame inputs are state reads.
 */

// ------------------------------------------------------------------ shape

/**
 * A rounded rectangle with continuous corners: each corner eases out of the straight edge over [radius] × 1.36 instead of a
 * circular arc meeting it abruptly. Its 45° point sits where a circular corner of [radius] would (so it reads as the same
 * size), but the edge flows into it — the squircle feel of modern display corners.
 */
class ContinuousShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(continuousPath(size, with(density) { radius.toPx() }))

    override fun equals(other: Any?) = other is ContinuousShape && other.radius == radius
    override fun hashCode() = radius.hashCode()
}

internal fun continuousPath(size: Size, radiusPx: Float, path: Path = Path()): Path {
    val w = size.width; val h = size.height
    val e = min(radiusPx * 1.36f, min(w, h) / 2f)   // where each corner's curve begins
    val c = e * 0.25f                                // its control points sit this close to the corner
    path.reset()
    path.moveTo(e, 0f)
    path.lineTo(w - e, 0f)
    path.cubicTo(w - c, 0f, w, c, w, e)
    path.lineTo(w, h - e)
    path.cubicTo(w, h - c, w - c, h, w - e, h)
    path.lineTo(e, h)
    path.cubicTo(c, h, 0f, h - c, 0f, h - e)
    path.lineTo(0f, e)
    path.cubicTo(0f, c, c, 0f, e, 0f)
    path.close()
    return path
}

// ------------------------------------------------------------------ colours

/**
 * The glass follows the phone's light or dark mode (owner on the A20e, 14 Oct 2026: "it doesn't change colors based on the mode
 * of the whole system"; DECISIONS V2-11): dark tinted glass with light text in dark mode, frosted light glass with dark text in
 * light mode. Both keep strong contrast for low-vision users.
 */
data class GlassPalette(
    val dark: Boolean,
    val top: Color, val bottom: Color,
    val text: Color, val textSecondary: Color, val textTertiary: Color,
    val accent: Color, val warm: Color, val fail: Color,
    /** Pills: the quiet fill, the primary tint (two stops), the chosen tint, the label on the primary. */
    val quietFill: Color, val primaryA: Color, val primaryB: Color, val chosenA: Color, val chosenB: Color, val onPrimary: Color,
    val highlight: Color, val bloom: Color, val field: Color, val idleBar: Color,
    /** Light glass over a light screen needs a soft shadow to separate; dark glass doesn't. */
    val shadow: Boolean
)

val DarkGlass = GlassPalette(
    dark = true,
    top = Color(0xF0182B2D), bottom = Color(0xF50B1517),
    text = Color(0xFFF2F7F6), textSecondary = Color(0xC7F2F7F6), textTertiary = Color(0x85F2F7F6),
    accent = Color(0xFF7FE6D8), warm = Color(0xFFFFB77A), fail = Spectrum.Coral,
    quietFill = Color(0x13FFFFFF), primaryA = Spectrum.Teal.copy(alpha = 0.42f), primaryB = Spectrum.Aqua.copy(alpha = 0.34f),
    chosenA = Spectrum.Teal.copy(alpha = 0.70f), chosenB = Spectrum.Aqua.copy(alpha = 0.62f), onPrimary = Color(0xFFF2F7F6),
    highlight = Color(0x52FFFFFF), bloom = Color.White, field = Color(0x14FFFFFF), idleBar = Color(0x59FFFFFF),
    shadow = false
)

val LightGlass = GlassPalette(
    dark = false,
    top = Color(0xF7FFFFFF), bottom = Color(0xF2EEF7F4),
    text = Color(0xFF0E1B1C), textSecondary = Color(0xC20E1B1C), textTertiary = Color(0x8A0E1B1C),
    accent = Color(0xFF0B7F71), warm = Color(0xFFB0560C), fail = Color(0xFFC4441C),
    quietFill = Color(0x0F0E1B1C), primaryA = Spectrum.Teal.copy(alpha = 0.24f), primaryB = Spectrum.Aqua.copy(alpha = 0.20f),
    chosenA = Spectrum.Teal.copy(alpha = 0.45f), chosenB = Spectrum.Aqua.copy(alpha = 0.40f), onPrimary = Color(0xFF053F38),
    highlight = Color(0xE6FFFFFF), bloom = Spectrum.Teal, field = Color(0x0D0E1B1C), idleBar = Color(0x660E1B1C),
    shadow = true
)

/** The palette of the glass being drawn (provided by the surface; dark by default). */
val LocalGlass = staticCompositionLocalOf { DarkGlass }

/** Shorthands for the current glass's colours, for composables (draw lambdas read a hoisted [GlassPalette] instead). */
object GlassColors {
    val Text: Color @Composable @ReadOnlyComposable get() = LocalGlass.current.text
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalGlass.current.textSecondary
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalGlass.current.textTertiary
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalGlass.current.accent
    val Warm: Color @Composable @ReadOnlyComposable get() = LocalGlass.current.warm
    val Fail: Color @Composable @ReadOnlyComposable get() = LocalGlass.current.fail
}

// ------------------------------------------------------------------ quality tier

/** FULL: animated edge light, press bloom, entrance highlight. FLAT: the same object, lit statically (low-end, power saver,
 *  reduced or removed animations). Never a broken-looking fallback: FLAT is the designed look without motion in the light. */
enum class GlassTier { FULL, FLAT }

object GlassTiers {
    /** Pure rule (unit-tested). A 3 GB-class phone (Galaxy A20e) is FLAT; so is any phone saving power or without motion. */
    fun of(sdk: Int, totalMemBytes: Long, lowRam: Boolean, powerSave: Boolean, animatorScale: Float, calm: Boolean,
           override: String = "auto"): GlassTier = when (override.lowercase()) {
        "full" -> GlassTier.FULL
        "flat" -> GlassTier.FLAT
        else -> if (lowRam || powerSave || calm || animatorScale <= 0f || totalMemBytes in 1 until FULL_MIN_BYTES || sdk < 26)
            GlassTier.FLAT else GlassTier.FULL
    }

    /** Below this much RAM the light holds still (3.5 GB: above a 3 GB phone's reported total). */
    const val FULL_MIN_BYTES = 3_500_000_000L

    fun detect(context: Context, calm: Boolean): GlassTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { try { am?.getMemoryInfo(it) } catch (_: Throwable) {} }
        val power = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        val override = try { com.tekyerema.core.config.AppConfig.string("ui.glassTier", "auto") } catch (_: Throwable) { "auto" }
        return of(Build.VERSION.SDK_INT, mem.totalMem, am?.isLowRamDevice == true, power,
            com.tekyerema.ui.theme.systemAnimatorScale(context), calm, override)
    }
}

// ------------------------------------------------------------------ motion

/** The surface's few named motions, reused everywhere (DECISIONS V2-7). Settles are critically damped: no wobble. */
object SurfaceMotion {
    /** A released drag finding its rest. */
    val Settle: FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
    /** The object changing size between states. */
    val Grow: FiniteAnimationSpec<IntSize> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f, visibilityThreshold = IntSize(1, 1))
    /** Arriving from the top: soft, barely-there give. */
    val Arrive: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 320f, visibilityThreshold = IntOffset(1, 1))
    val ArriveScale: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 320f)
    /** Leaving upward: quick and clean. */
    val Leave: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f, visibilityThreshold = IntOffset(1, 1))
    /** A button's press and release. */
    val Press: FiniteAnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)
    val Release: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 700f)
    const val FADE_MS = 180
    const val STAGGER_MS = 45
    /** Calm motion: short cross-fades only. */
    val CalmFade: FiniteAnimationSpec<Float> = tween(140)
}

// ------------------------------------------------------------------ the glass itself

/** What the light around the glass says. Only WORKING and LISTENING move, and only while that is really happening. */
enum class LightMood { CALM, WORKING, LISTENING, SUCCESS, FAILURE }

/**
 * The surface's glass (owner, 15 Oct 2026: liquid glass, no border lines): the one material of ui/glass/LiquidGlass — the
 * tinted body, light-defined rim, refraction band, sheen, glint, grain and outside-only shadow. The state shows as LIGHT,
 * not as a coloured outline: while the agent works or listens the light slowly travels round the glass ([sweep], FULL tier
 * only) and a soft teal glows from inside; a finished task glows warm, a failure faintly coral. [light] (0..1) is that
 * glow's strength. Read in the draw phase only: a moving light redraws the layer without recomposing anything.
 */
fun Modifier.glassBody(
    radius: Dp,
    palette: GlassPalette,
    mood: () -> LightMood,
    light: () -> Float,
    sweep: () -> Float,
    @Suppress("UNUSED_PARAMETER") glowShift: () -> Float,
    tier: GlassTier,
    @Suppress("UNUSED_PARAMETER") arrival: () -> Float = { 1f },
    /** The light's angle from the phone's tilt (ui/glass/rememberTiltLight); the default holds it still. */
    tilt: () -> Float = { DEFAULT_LIGHT_DEGREES }
): Modifier = this.liquidGlass(
    // A card over another app is a "large" glass (Apple: more opaque) — words on it never read as text on top of text.
    style = LiquidStyles.of(palette.dark, com.tekyerema.ui.theme.glassMode()).let { s ->
        if (s.border != null) s else s.copy(fill = s.fill.copy(alpha = if (palette.dark) 0.93f else 0.92f))
    },
    shape = GlassShape.Rounded(radius),
    depth = GlassDepth.FLOATING,
    lightDegrees = {
        val m = mood()
        if (tier == GlassTier.FULL && (m == LightMood.WORKING || m == LightMood.LISTENING)) tilt() + sweep() else tilt()
    },
    glow = {
        val l = light().coerceIn(0f, 1f)
        when (mood()) {
            LightMood.WORKING, LightMood.LISTENING -> Spectrum.Teal.copy(alpha = (if (palette.dark) 0.20f else 0.14f) * l)
            LightMood.SUCCESS -> palette.warm.copy(alpha = 0.14f * l)
            LightMood.FAILURE -> palette.fail.copy(alpha = 0.14f * l)
            LightMood.CALM -> Color.Transparent
        }
    }
)

private fun Color.toArgbInt(): Int = this.toArgb()

/** The small handle at the bottom of a finished result: "pull me". Drawn, not a node (the gestures have semantic actions). */
fun Modifier.handle(color: Color, visible: () -> Boolean): Modifier = this.drawWithCache {
    val w = 36.dp.toPx(); val h = 4.dp.toPx()
    val tl = Offset((size.width - w) / 2f, size.height - h - 7.dp.toPx())
    onDrawWithContent {
        drawContent()
        if (visible()) drawRoundRect(color, tl, Size(w, h), CornerRadius(h / 2f))
    }
}

