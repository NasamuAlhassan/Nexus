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
    val base = LiquidStyles.of(dark, glassMode())
    val style = if (strong || base.border != null) base else base.copy(fill = base.fill.copy(alpha = if (dark) 0.62f else 0.58f))
    liquidGlass(
        style = style,
        shape = GlassShape.Of(shape),
        depth = if (elevation > 0.dp) GlassDepth.CONTROL else GlassDepth.FLAT,
        tint = tint?.let { t -> if (style.border != null) null else t.copy(alpha = (t.alpha * 0.85f).coerceAtMost(0.85f)).compositeOver(style.fill) }
    )
}

/**
 * Simple mode (config `ui.simple`, default TRUE; owner 27 Oct 2026: no Liquid Glass, no decorative animation, for blind and
 * low-vision users): every glass surface draws as a flat, opaque, bordered panel and all motion snaps. One switch; set
 * `"ui": {"simple": false}` in a config file to bring the glass look back.
 */
fun simpleUi(): Boolean = try { com.tekyerema.core.config.AppConfig.bool("ui.simple", true) } catch (_: Throwable) { true }

/** The user's glass setting: tinted (default), solid (less see-through) or high contrast (settings `ui.glassMode`). */
fun glassMode(): com.tekyerema.ui.glass.GlassMode = when (try {
    com.tekyerema.core.config.AppConfig.string("ui.glassMode", "tinted").lowercase()
} catch (_: Throwable) { "tinted" }) {
    "solid" -> com.tekyerema.ui.glass.GlassMode.SOLID
    "contrast", "high_contrast", "high-contrast" -> com.tekyerema.ui.glass.GlassMode.HIGH_CONTRAST
    else -> com.tekyerema.ui.glass.GlassMode.TINTED
}
