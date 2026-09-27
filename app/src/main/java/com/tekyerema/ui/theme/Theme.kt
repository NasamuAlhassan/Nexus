package com.tekyerema.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tɛkyerɛma visual system. One deliberate theme (no light/dark switch): deep ink surfaces with
 * high-contrast text, and the two brand hues sampled from the supplied icon — the deep-blue
 * gradient (#111F45 → #1B49C8) and the cyan pulse (#38BDF8).
 *
 * Structure comes from typography and hairlines, not boxes: pages are left-aligned and editorial,
 * lists are flat rows separated by 1 dp lines, and the only "light" is the living visual itself.
 */
object Tk {
    // Surfaces
    val Ink = Color(0xFF070A15)          // base
    val InkTop = Color(0xFF0C1329)       // the faint blue lift at the top of every page
    val Ink2 = Color(0xFF0F1628)         // pressed / selected row
    val Ink3 = Color(0xFF1C2440)         // outlines
    val Hairline = Color(0x2EFFFFFF)     // 18 % white: dividers
    // Text — 16.9:1 / 8.2:1 / 4.9:1 on Ink
    val Text = Color(0xFFF4F6FB)
    val TextSecondary = Color(0xFF9AA6C4)
    val TextTertiary = Color(0xFF6E7A9C)
    // Brand
    val Cyan = Color(0xFF38BDF8)
    val BlueDeep = Color(0xFF111F45)
    val Blue = Color(0xFF1B49C8)
    val BlueBright = Color(0xFF4A7BFF)
    // Semantic
    val Attention = Color(0xFFF5C26B)    // "needs your OK" — warm, never alarming
    val Danger = Color(0xFFFF7A7A)
    val Focus = Color(0xFFFFFFFF)        // focus ring: pure white on ink

    /** The page background: ink with a quiet lift toward the top, so the visual has air behind it. */
    val PageBrush: Brush = Brush.verticalGradient(0f to InkTop, 0.42f to Ink, 1f to Color(0xFF05070F))
}

fun Modifier.pageBackground(): Modifier = background(Tk.PageBrush)

private val Scheme = darkColorScheme(
    primary = Tk.Cyan,
    onPrimary = Tk.Ink,
    secondary = Tk.BlueBright,
    onSecondary = Tk.Text,
    background = Tk.Ink,
    onBackground = Tk.Text,
    surface = Tk.Ink,
    onSurface = Tk.Text,
    surfaceVariant = Tk.Ink2,
    onSurfaceVariant = Tk.TextSecondary,
    outline = Tk.Ink3,
    error = Tk.Danger,
    onError = Tk.Ink
)

private val Sans = FontFamily.Default

/**
 * Type scale. Large sizes throughout (low-vision first), tight tracking on big text, wide
 * tracking on the small overlines — the contrast between the two is what gives pages their shape.
 */
val TkTypography = Typography(
    // Editorial page titles and the first-launch headline.
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    // The assistant's reply: large, calm, readable from a distance.
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 35.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    // Overlines: section names, day headers. Always uppercase, widely tracked, secondary colour.
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
)

val TkShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp)
)

@Composable
fun TekyeremaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = TkTypography, shapes = TkShapes, content = content)
}
