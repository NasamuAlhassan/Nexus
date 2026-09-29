package com.tekyerema.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A pane of glass: a translucent fill, a bright top edge that fades down its sides (the light catching the rim, as in
 * Liquid Glass), and — in the light palette — a soft, wide shadow so it floats over the page. [strong] is the denser
 * glass for controls that sit over moving light (the tab bar, the input pill): text on it keeps full contrast.
 *
 * No blur: Android 11 has no RenderEffect, and the page behind is soft light, so a gradient fill reads as frosted.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    elevation: Dp = 14.dp,
    tint: Color? = null
): Modifier = composed {
    val p = Tk.palette
    val lift = if (!p.dark && elevation > 0.dp)
        Modifier.shadow(elevation, shape, clip = false, ambientColor = Color(0x331B2A55), spotColor = Color(0x401B2A55))
    else Modifier
    lift.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val top = if (strong) p.glassStrong else p.glass
        val bottom = if (strong) p.glassStrong else p.glassLow
        val fill = Brush.verticalGradient(listOf(top, bottom))
        val wash = tint?.let { Brush.verticalGradient(listOf(it.copy(alpha = it.alpha * 0.9f), it.copy(alpha = it.alpha * 0.5f))) }
        val edge = Brush.verticalGradient(0f to p.glassEdgeTop, 0.55f to p.glassEdgeBottom, 1f to p.glassEdgeBottom)
        val stroke = Stroke(width = 1.dp.toPx())
        onDrawBehind {
            drawOutline(outline, fill)
            if (wash != null) drawOutline(outline, wash)
            drawOutline(outline, edge, style = stroke)
        }
    }
}
