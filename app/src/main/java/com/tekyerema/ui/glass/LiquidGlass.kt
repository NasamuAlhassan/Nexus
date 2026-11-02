package com.tekyerema.ui.glass

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.random.Random

/**
 * Liquid glass, one material for every surface (owner, 15 Oct 2026: "it's not glassmorphism, liquid glass … your borderlines,
 * I don't like them. I want it to blend"). Built from the research in docs/liquid-glass/DESIGN.md (Apple's WWDC25 "Meet
 * Liquid Glass" and HIG Materials, adapted for an Android 11 phone where nothing behind an overlay can be sampled):
 *
 *  - no outline: the edge is defined by LIGHT — a rim that is bright toward the light and fades to nothing along the sides,
 *    a soft refraction band inside the edge, a curved specular sheen and a glint, and shadows drawn only OUTSIDE the shape
 *    (so the see-through body is never darkened by its own shadow);
 *  - the "tinted" end of liquid glass by default — translucent, but never so clear that text over a busy app fails 4.5:1
 *    (dark fill ≥ 0.56 guarantees it over any background; we use more);
 *  - a primary control is tinted glass (the colour is in the body, Apple's rule), never a solid block;
 *  - Solid and High-contrast modes for low-vision users (the platform's Reduce Transparency / Increase Contrast).
 *
 * Everything is built once per size (drawWithCache); per frame only the light angle, a press and a glow are read.
 */

@Immutable
data class LiquidStyle(
    val dark: Boolean,
    /** The body, alpha included. */
    val fill: Color,
    /** The glass's thickness: lighter at the top, a touch darker at the bottom. */
    val thickTop: Color, val thickBottom: Color,
    /** White alpha of the soft refraction band just inside the edge. */
    val edge: Float,
    /** The curved sheen across the top, and the glint near the light. */
    val specular: Float, val glint: Float,
    /** The rim: at the light, and the fainter far edge (light travelling round the glass). The sides are ~0: no outline. */
    val rimPeak: Float, val rimFar: Float,
    val grain: Float,
    val shadowKey: Float, val shadowAmbient: Float,
    /** High contrast only: a real border, opaque body, no light effects. */
    val border: Color? = null,
)

object LiquidStyles {
    val Dark = LiquidStyle(
        dark = true, fill = Color(0xFF0E1419).copy(alpha = 0.80f),
        thickTop = Color.White.copy(alpha = 0.07f), thickBottom = Color.Black.copy(alpha = 0.12f),
        edge = 0.10f, specular = 0.14f, glint = 0.20f, rimPeak = 0.62f, rimFar = 0.14f,
        grain = 0.035f, shadowKey = 0.32f, shadowAmbient = 0.20f)
    val Light = LiquidStyle(
        dark = false, fill = Color(0xFFF6F8F9).copy(alpha = 0.78f),
        thickTop = Color.White.copy(alpha = 0.24f), thickBottom = Color.Black.copy(alpha = 0.05f),
        edge = 0.34f, specular = 0.42f, glint = 0.50f, rimPeak = 0.95f, rimFar = 0.32f,
        grain = 0.028f, shadowKey = 0.16f, shadowAmbient = 0.09f)

    /** Like the platform's "Reduce transparency": nearly opaque, no grain or glint. */
    fun solid(s: LiquidStyle) = s.copy(fill = s.fill.copy(alpha = 1f), grain = 0f, glint = s.glint * 0.5f)

    /** Like "Increase contrast": opaque black or white with a 2 dp border, no gradients. */
    fun highContrast(dark: Boolean): LiquidStyle {
        // A reading-colour pair (ui/theme/ColourPairs) gives the body and the edge its own colours: yellow edges on black, black on cream.
        val pair = com.tekyerema.ui.theme.Look.pair()
        return LiquidStyle(
            dark = pair?.dark ?: dark, fill = pair?.card ?: if (dark) Color.Black else Color.White,
            thickTop = Color.Transparent, thickBottom = Color.Transparent,
            edge = 0f, specular = 0f, glint = 0f, rimPeak = 0f, rimFar = 0f, grain = 0f, shadowKey = 0f, shadowAmbient = 0f,
            border = pair?.edge ?: if (dark) Color.White else Color.Black)
    }

    fun of(dark: Boolean, mode: GlassMode = GlassMode.TINTED): LiquidStyle = when (mode) {
        GlassMode.TINTED -> if (dark) Dark else Light
        GlassMode.SOLID -> solid(if (dark) Dark else Light)
        GlassMode.HIGH_CONTRAST -> highContrast(dark)
    }
}

enum class GlassMode { TINTED, SOLID, HIGH_CONTRAST }

/** The outline: a capsule (radius = height / 2) or continuous corners of a radius (concentric with what they hold). */
sealed interface GlassShape {
    data object Capsule : GlassShape
    data class Rounded(val radius: Dp) : GlassShape
    /** Any Compose shape: its corner radius is read from the outline (a 50% rounded shape is a capsule). */
    data class Of(val shape: androidx.compose.ui.graphics.Shape) : GlassShape
}

/** How far the glass floats: a card or the island (deep, soft shadow), a control on a page (short), or inside glass (none —
 *  glass on glass is never drawn as glass, Apple's layering rule). */
enum class GlassDepth { FLOATING, CONTROL, FLAT }

/** The light comes from the upper left by default (225° in screen degrees, y down). */
const val DEFAULT_LIGHT_DEGREES = 225f

fun Modifier.liquidGlass(
    style: LiquidStyle,
    shape: GlassShape = GlassShape.Rounded(28.dp),
    depth: GlassDepth = GlassDepth.FLOATING,
    /** A tinted control (the primary action): its colour IS the body. Null = the neutral glass. */
    tint: Color? = null,
    lightDegrees: () -> Float = { DEFAULT_LIGHT_DEGREES },
    press: () -> Float = { 0f },
    pressAt: () -> Offset = { Offset.Unspecified },
    /** A state glowing from inside (working / listening teal, success warm), 0 alpha = none. */
    glow: () -> Color = { Color.Transparent },
): Modifier = this.drawWithCache {
    val w = size.width; val h = size.height
    val radius = when (shape) {
        GlassShape.Capsule -> min(w, h) / 2f
        is GlassShape.Rounded -> shape.radius.toPx()
        is GlassShape.Of -> when (val o = shape.shape.createOutline(size, layoutDirection, this)) {
            is androidx.compose.ui.graphics.Outline.Rounded -> o.roundRect.topLeftCornerRadius.x
            is androidx.compose.ui.graphics.Outline.Rectangle -> 0f
            else -> 24.dp.toPx()
        }
    }
    val path = liquidPath(size, radius)
    val inset = 0.6.dp.toPx()
    val rimPath = liquidPath(Size(w - 2 * inset, h - 2 * inset), (radius - inset).coerceAtLeast(0f)).apply { translate(Offset(inset, inset)) }

    val hc = style.border != null
    val lite = glassLite()
    val body = tint ?: style.fill
    if (com.tekyerema.ui.theme.simpleUi()) {
        // Simple mode (ui.simple): a flat, opaque panel with a clear border (≥ 3:1 against the body), no light effects, no
        // shadow, no animation inputs read. The one switch for the whole app's glass.
        // A tint is a see-through WASH (white at 14 %, the primary at 30 %…) meant to lie over the style's body. Made opaque on its own
        // it became solid white behind light text — the yes/no buttons had no readable words (owner, 27 Oct 2026). So the wash is
        // composited over the style's own opaque body: the text colours chosen for that style stay readable on it.
        val flatBody = body.compositeOver(style.fill.copy(alpha = 1f))
        val flatBorder = style.border ?: if (style.dark) Color(0xFF9DB0AD) else Color(0xFF5A6B69)
        val flatStroke = Stroke(width = 1.5.dp.toPx())
        return@drawWithCache onDrawBehind {
            drawPath(path, flatBody)
            drawPath(rimPath, flatBorder, style = flatStroke)
        }
    }
    val thickness = Brush.verticalGradient(0f to style.thickTop, 0.45f to Color.Transparent, 1f to style.thickBottom)
    // The refraction band: light gathered just inside the edge, strongest at the top, a faint caustic at the bottom.
    val edgeBrush = Brush.verticalGradient(0f to Color.White, 0.5f to Color.White.copy(alpha = 0.30f), 1f to Color.White.copy(alpha = 0.62f))
    // Wide, soft steps (no thin first step): a 3 dp step read as a line round the glass in dark mode (owner: no borderlines).
    val edgeSteps = listOf(8.dp.toPx() to 0.26f, 16.dp.toPx() to 0.16f, 26.dp.toPx() to 0.09f, 38.dp.toPx() to 0.05f)
    // The sheen: an ellipse of light hugging the top edge.
    val sheenR = w * 0.56f
    val sheenScaleY = (h * 0.55f / sheenR).coerceAtMost(1f)
    val sheen = Brush.radialGradient(0f to Color.White.copy(alpha = style.specular), 1f to Color.Transparent,
        center = Offset(w / 2f, 0f), radius = sheenR)
    val glintC = Offset(min(w * 0.22f, radius * 1.6f + 8.dp.toPx()), h * 0.20f)
    val glintR = (h * 0.45f).coerceAtLeast(12.dp.toPx())
    val glint = Brush.radialGradient(0f to Color.White.copy(alpha = style.glint), 1f to Color.Transparent, center = glintC, radius = glintR)
    val grain = if (style.grain > 0f) ShaderBrush(ImageShader(Noise.tile, TileMode.Repeated, TileMode.Repeated)) else null
    // The rim: light only where it strikes — a short arc (±30°) at the light and a faint one opposite, exactly 0 between:
    // the silhouette comes from light, never from a line round the glass (owner, 15 Oct 2026: "your borderlines, I don't like them").
    val rimShader = android.graphics.SweepGradient(w / 2f, h / 2f,
        intArrayOf(white(style.rimPeak), white(style.rimPeak * 0.25f), white(0f), white(0f), white(style.rimFar), white(0f), white(0f),
            white(style.rimPeak * 0.25f), white(style.rimPeak)),
        floatArrayOf(0f, 1f / 12f, 1f / 6f, 0.42f, 0.5f, 0.58f, 5f / 6f, 11f / 12f, 1f))
    val rimMatrix = android.graphics.Matrix()
    // On a pane taller than it is wide the light's ±30° arc, measured from the centre, ran a long way down the left side and read
    // as a line (settings and history panes, emulator 2 Nov 2026). The angle is measured as if the pane were square: the light
    // stays at the upper-left corner and fades before the side. Wide shapes (pills, the island, cards) are unchanged.
    val tallSquash = if (h > w && h > 0f) w / h else 1f
    val rimBrush = ShaderBrush(rimShader)
    val rimStroke = Stroke(width = 1.dp.toPx())
    val borderStroke = Stroke(width = 2.dp.toPx())
    // A soft shadow OUTSIDE the shape only (the see-through body is never darkened by its own shadow): rings of the outline,
    // wider and fainter, a little below it — a blur made of strokes (Paint.setShadowLayer left a pale rectangle round the
    // shape in an overlay window on the Android 11 emulator).
    val shadowRings: List<Pair<Stroke, Float>> = if (hc || depth == GlassDepth.FLAT) emptyList() else {
        val a = style.shadowKey * (if (depth == GlassDepth.FLOATING) 1f else 0.6f)
        val reach = if (depth == GlassDepth.FLOATING) 1f else 0.45f
        listOf(2f to 0.30f, 5f to 0.20f, 9f to 0.14f, 14f to 0.10f, 20f to 0.07f, 28f to 0.045f)
            .map { (w, k) -> Stroke(width = (w * reach).dp.toPx()) to (a * k) }
    }
    val shadowDy = (if (depth == GlassDepth.FLOATING) 5.dp else 2.dp).toPx()

    if (lite && !hc) {
        // The light version (owner, 20 Oct 2026: the glass lags on the A20e). The same look from far away, a handful of fills and
        // no clip: one soft shadow, the thickness, a plain sheen, the light rim. No refraction strokes, glint, grain or ellipse.
        val liteShadow = if (depth == GlassDepth.FLAT) emptyList() else {
            val a = style.shadowKey * (if (depth == GlassDepth.FLOATING) 1f else 0.6f)
            listOf(3.dp.toPx() to a * 0.30f, 12.dp.toPx() to a * 0.12f).map { (wd, al) -> Stroke(width = wd) to al }
        }
        val liteSheen = Brush.radialGradient(0f to Color.White.copy(alpha = style.specular), 1f to Color.Transparent,
            center = Offset(w / 2f, 0f), radius = sheenR)
        val rimShaderLite = rimShader
        return@drawWithCache onDrawBehind {
            // Drawn before the body and unclipped: the body (≥ 58 % opaque) hides the inner half, and a clip on a smoothed path
            // costs a software path clip every redraw below Android 13 (UI audit, 21 Oct 2026).
            if (liteShadow.isNotEmpty()) translate(top = shadowDy) {
                liteShadow.forEach { (st, alpha) -> drawPath(path, Color.Black, alpha = alpha, style = st) }
            }
            drawPath(path, body)
            drawPath(path, thickness)
            val g = glow()
            if (g.alpha > 0.001f) drawPath(path, Brush.radialGradient(0f to g, 1f to Color.Transparent, center = Offset(w / 2f, 0f), radius = w * 0.7f))
            drawPath(path, liteSheen)
            val p = press(); val at = pressAt()
            if (p > 0.001f && at != Offset.Unspecified)
                drawPath(path, Brush.radialGradient(0f to Color.White.copy(alpha = 0.18f * p), 1f to Color.Transparent, center = at, radius = h * 1.5f))
            rimMatrix.setRotate(lightDegrees(), w / 2f, h / 2f)
            if (tallSquash < 1f) rimMatrix.postScale(1f, 1f / tallSquash, w / 2f, h / 2f)
            rimShaderLite.setLocalMatrix(rimMatrix)
            drawPath(rimPath, rimBrush, style = rimStroke)
        }
    }

    onDrawBehind {
        if (shadowRings.isNotEmpty()) clipPath(path, ClipOp.Difference) {
            translate(top = shadowDy) {
                shadowRings.forEach { (st, alpha) -> drawPath(path, Color.Black, alpha = alpha, style = st) }
            }
        }
        drawPath(path, body)
        if (hc) { drawPath(path, style.border!!, style = borderStroke); return@onDrawBehind }
        clipPath(path) {
            drawPath(path, thickness)
            val g = glow()
            if (g.alpha > 0.001f) drawCircle(Brush.radialGradient(0f to g, 1f to Color.Transparent, center = Offset(w / 2f, 0f), radius = w * 0.7f),
                radius = w * 0.7f, center = Offset(w / 2f, 0f))
            edgeSteps.forEach { (sw, a) -> drawPath(path, edgeBrush, alpha = style.edge * a * 2f, style = Stroke(width = sw)) }
            scale(1f, sheenScaleY, pivot = Offset(w / 2f, 0f)) { drawCircle(sheen, radius = sheenR, center = Offset(w / 2f, 0f)) }
            drawCircle(glint, radius = glintR, center = glintC)
            grain?.let { drawRect(it, alpha = style.grain) }
            val p = press()
            val at = pressAt()
            if (p > 0.001f && at != Offset.Unspecified)
                drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.18f * p), 1f to Color.Transparent, center = at, radius = h * 1.5f),
                    radius = h * 1.5f, center = at)
        }
        rimMatrix.setRotate(lightDegrees(), w / 2f, h / 2f)
        if (tallSquash < 1f) rimMatrix.postScale(1f, 1f / tallSquash, w / 2f, h / 2f)
        rimShader.setLocalMatrix(rimMatrix)
        drawPath(rimPath, rimBrush, style = rimStroke)
    }
}

/** `ui.glassLite` (default true): draw the glass with a few fills instead of the full stack of strokes and clips. Read once per size. */
fun glassLite(): Boolean = try { com.tekyerema.core.config.AppConfig.string("ui.glassLite", "true").lowercase() != "false" } catch (_: Throwable) { true }

/** The glass outline as a path (continuous corners; a capsule when [radius] ≥ half the short side). */
fun liquidPath(size: Size, radius: Float): Path = com.tekyerema.ui.assistant.continuousPath(size, radius)

private fun white(alpha: Float): Int = Color.White.copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()

/** A 64 px tile of fine monochrome grain — real glass is never perfectly smooth. Made once. */
internal object Noise {
    val tile: ImageBitmap by lazy {
        val n = 64
        val rnd = Random(7)
        val px = IntArray(n * n) { val v = rnd.nextInt(0, 256); android.graphics.Color.argb(255, v, v, v) }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
