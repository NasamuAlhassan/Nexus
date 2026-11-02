package com.tekyerema.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tekyerema.ui.glass.GlassDepth
import com.tekyerema.ui.glass.GlassShape
import com.tekyerema.ui.glass.LiquidStyles
import com.tekyerema.ui.glass.liquidGlass

/**
 * A pane of glass on our own pages — the same liquid glass as the assistant's surfaces (ui/glass/LiquidGlass), one material
 * everywhere. On our own pages what lies behind is known (the soft page light), so the glass may be clearer than over other
 * apps; [strong] keeps the denser body for controls that sit over moving light (the tab bar, the input pill).
 * [tint] washes the body with a colour (a selected or primary control).
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    elevation: Dp = 14.dp,
    tint: Color? = null
): Modifier = composed {
    val dark = Tk.palette.dark
    // [strong] = a floating control (the tab bar, the ask field): glass by default. Anything else holds text you read: solid by
    // default, so nothing behind it ever shows through its words (Look, redesign 2 Nov 2026).
    val style = LiquidStyles.of(dark, if (strong) Look.controlMode() else Look.readingMode())
    liquidGlass(
        style = style,
        shape = GlassShape.Of(shape),
        depth = if (elevation > 0.dp) GlassDepth.CONTROL else GlassDepth.FLAT,
        tint = tint?.let { t -> if (style.border != null) null else t.copy(alpha = (t.alpha * 0.85f).coerceAtMost(0.85f)).compositeOver(style.fill) }
    )
}

/**
 * Simple mode (config `ui.simple`): every glass surface draws as a flat, opaque, bordered panel and all motion snaps. It was the
 * default from 27 Oct 2026 (owner: no Liquid Glass, no decorative animation, for blind and low-vision users); since the 2 Nov
 * redesign the same needs are met without grey borders — reading surfaces are opaque (Look.readingMode) and motion is calm
 * by default ([calmUi]) — so it is off unless a config turns it on.
 */
fun simpleUi(): Boolean = try { com.tekyerema.core.config.AppConfig.bool("ui.simple", false) } catch (_: Throwable) { false }

/** Calm motion: simple mode, or the default calm look (Look.fullMotion off). The orb ignores it (owner, 27 Oct 2026). */
fun calmUi(): Boolean = simpleUi() || Look.calm()

/** The glass of a floating control (Look.controlMode): kept for the island and the panels over other apps. */
fun glassMode(): com.tekyerema.ui.glass.GlassMode = Look.controlMode()
