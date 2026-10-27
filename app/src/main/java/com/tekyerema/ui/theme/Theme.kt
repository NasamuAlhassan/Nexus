package com.tekyerema.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tekyerema.settings.Settings

/**
 * Tɛkyerɛma visual system — "liquid light" (redesign, 2026-09-29).
 *
 * Direction: Apple's Liquid Glass and the new Siri orb, re-made for a low-vision-first Android 11 phone. Content sits
 * on soft translucent GLASS surfaces over a slow field of colored light; the one living thing is the voice orb. Two
 * palettes — light and dark — follow the phone (or the Appearance setting). Liquid Glass's accessibility rules carry
 * over: text is never placed on bare glass without enough backing for contrast (every text colour below meets WCAG AA
 * on its surface, most AAA), Reduce motion stills the light, and no blur is needed (Android 11 has none) — the glass
 * is built from layered gradients and a bright top edge, which is cheap on the Galaxy A20e's GPU.
 */
@Immutable
class TkPalette(
    val dark: Boolean,
    // Page
    val ink: Color, val inkTop: Color, val inkBottom: Color,
    /** The three soft lights behind every page. */
    val field: List<Color>,
    // Glass
    val glass: Color, val glassLow: Color, val glassEdgeTop: Color, val glassEdgeBottom: Color,
    /** A denser glass for floating controls (the tab bar, the input pill) that sit over moving light. */
    val glassStrong: Color,
    val pressed: Color,
    val hairline: Color,
    // Text
    val text: Color, val textSecondary: Color, val textTertiary: Color,
    // Brand and states
    val accent: Color, val onAccent: Color,
    val blue: Color, val blueBright: Color, val blueDeep: Color,
    val attention: Color, val danger: Color, val success: Color,
    val focus: Color,
    /** Icon tiles in lists: a tint and its soft backing. */
    val tileBacking: Float
)

/** The orb's spectrum — the colours of the Tɛkyerɛma mark (aqua → teal → green → lime on ink), the same in both palettes
 *  (it is light, not paint). Owner, 13 October 2026: the old cyan/violet/rose did not fit the logo. */
object Spectrum {
    val Aqua = Color(0xFF38C6F0)
    val Teal = Color(0xFF17C9B4)
    val Green = Color(0xFF3BD37D)
    val Lime = Color(0xFFB5E853)
    val Gold = Color(0xFFF5C542)
    val Coral = Color(0xFFFF8A6B)
}

val DarkPalette = TkPalette(
    dark = true,
    // Ink with a breath of the mark's green in it, not the old navy.
    ink = Color(0xFF050B0D), inkTop = Color(0xFF0A1A1C), inkBottom = Color(0xFF03080A),
    field = listOf(Color(0x3317C9B4), Color(0x2E3BD37D), Color(0x2438C6F0)),
    glass = Color(0x17FFFFFF), glassLow = Color(0x0AFFFFFF),
    glassEdgeTop = Color(0x4DFFFFFF), glassEdgeBottom = Color(0x0FFFFFFF),
    glassStrong = Color(0xE60F1F21),
    pressed = Color(0x24FFFFFF),
    hairline = Color(0x1FFFFFFF),
    // 18.3:1 / 10.6:1 / 7.1:1 on ink (13 Oct 2026)
    text = Color(0xFFF1F7F4), textSecondary = Color(0xFFA9C3BC), textTertiary = Color(0xFF84A19A),
    accent = Color(0xFF1ED1BB), onAccent = Color(0xFF04110F),        // accent 10.3:1 on ink; ink on accent 10:1
    blue = Color(0xFF0E6F66), blueBright = Color(0xFF22B8A6), blueDeep = Color(0xFF0A2A2A),
    attention = Color(0xFFF5C542), danger = Color(0xFFFF8A8A), success = Color(0xFF7BE495),
    focus = Color(0xFFFFFFFF),
    tileBacking = 0.16f
)

val LightPalette = TkPalette(
    dark = false,
    // A warm mint-white page with the mark's lights in the corners.
    ink = Color(0xFFF3F8F5), inkTop = Color(0xFFE4F3EE), inkBottom = Color(0xFFF7F6EC),
    field = listOf(Color(0x4D17C9B4), Color(0x403BD37D), Color(0x33B5E853)),
    glass = Color(0xB8FFFFFF), glassLow = Color(0x94FFFFFF),
    glassEdgeTop = Color(0xFFFFFFFF), glassEdgeBottom = Color(0x1A103B37),
    glassStrong = Color(0xF2FFFFFF),
    pressed = Color(0x140E1F1C),
    hairline = Color(0x1A0E1F1C),
    // 15.9:1 / 7.3:1 / 5.1:1 on ink (13 Oct 2026)
    text = Color(0xFF0E1F1C), textSecondary = Color(0xFF3B5751), textTertiary = Color(0xFF556F69),
    accent = Color(0xFF0A7268), onAccent = Color(0xFFFFFFFF),        // accent 5:1 on the page; white on accent 5.3:1
    blue = Color(0xFF0E6F66), blueBright = Color(0xFF159C8E), blueDeep = Color(0xFF0A2A2A),
    attention = Color(0xFF8A5A00), danger = Color(0xFFB42318), success = Color(0xFF0F7A3E),
    focus = Color(0xFF0E1F1C),
    tileBacking = 0.13f
)

val LocalTkPalette = staticCompositionLocalOf { DarkPalette }

/**
 * The palette tokens, read in composition. Kept under the old names so screens read naturally ("Tk.Text"); a draw
 * lambda (not composable) takes the palette first: `val p = Tk.palette`.
 */
object Tk {
    val palette: TkPalette @Composable @ReadOnlyComposable get() = LocalTkPalette.current
    val Dark: Boolean @Composable @ReadOnlyComposable get() = LocalTkPalette.current.dark
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.ink
    val Ink2: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.pressed
    val Ink3: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.hairline
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.hairline
    val Text: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.text
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.textSecondary
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.textTertiary
    val Cyan: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.onAccent
    val Blue: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.blue
    val BlueBright: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.blueBright
    val BlueDeep: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.blueDeep
    val Attention: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.attention
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.danger
    val Success: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.success
    val Focus: Color @Composable @ReadOnlyComposable get() = LocalTkPalette.current.focus
}

/**
 * The page background: the palette's base with a lift toward the top, and three large soft lights (teal, green,
 * aqua) resting in the corners — the "liquid" behind the glass. Drawn once per size; nothing here moves (the orb is
 * the only thing alive, and a full-screen animation would cost the A20e battery for no information).
 */
fun Modifier.pageBackground(): Modifier = composed {
    val p = Tk.palette
    drawWithCache {
        val w = size.width; val h = size.height
        val base = Brush.verticalGradient(0f to p.inkTop, 0.45f to p.ink, 1f to p.inkBottom)
        val lights = listOf(
            Triple(Offset(w * 0.05f, h * 0.10f), w * 0.95f, p.field[0]),
            Triple(Offset(w * 1.00f, h * 0.38f), w * 0.85f, p.field[1]),
            Triple(Offset(w * 0.15f, h * 0.95f), w * 0.90f, p.field[2])
        ).map { (c, r, col) -> Brush.radialGradient(0f to col, 1f to col.copy(alpha = 0f), center = c, radius = r) }
        if (simpleUi()) return@drawWithCache onDrawBehind { drawRect(p.ink) }     // simple mode: one flat colour, no gradients or lights
        onDrawBehind {
            drawRect(base)
            lights.forEach { drawRect(it) }
        }
    }
}

private val Sans = FontFamily.Default

/**
 * Type scale. Large sizes throughout (low-vision first), tight tracking on big text, wide tracking on the small
 * overlines. Titles are a touch bolder than before: on glass, weight carries hierarchy that hairlines used to.
 */
val TkTypography = Typography(
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    // The assistant's reply: large, calm, readable from a distance.
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 27.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    // Overlines: section names, day headers. Always uppercase, widely tracked, secondary colour.
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
)

val TkShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp)
)

private fun schemeOf(p: TkPalette) = if (p.dark) darkColorScheme(
    primary = p.accent, onPrimary = p.onAccent, secondary = p.blueBright, onSecondary = p.text,
    background = p.ink, onBackground = p.text, surface = p.ink, onSurface = p.text,
    surfaceVariant = p.pressed, onSurfaceVariant = p.textSecondary, outline = p.hairline, error = p.danger, onError = p.ink
) else lightColorScheme(
    primary = p.accent, onPrimary = p.onAccent, secondary = p.blueBright, onSecondary = Color.White,
    background = p.ink, onBackground = p.text, surface = p.ink, onSurface = p.text,
    surfaceVariant = p.pressed, onSurfaceVariant = p.textSecondary, outline = p.hairline, error = p.danger, onError = Color.White
)

/** Light or dark for this screen: the Appearance setting, or the phone's own when it is "system". */
@Composable
fun rememberDarkTheme(): Boolean {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    val version by settings.changes.collectAsState()
    val choice = remember(version) { settings.appearance }
    val system = isSystemInDarkTheme()
    return when (choice) {
        Settings.APPEARANCE_LIGHT -> false
        Settings.APPEARANCE_DARK -> true
        else -> system
    }
}

/**
 * The theme also decides, once for every screen under it, whether motion is calm ([LocalCalmMotion]): the in-app
 * Reduce motion setting or the system's "Remove animations", both followed live. [forceDark] keeps a surface dark
 * whatever the appearance (the reply panel drawn over other apps).
 */
@Composable
fun TekyeremaTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    val version by settings.changes.collectAsState()
    val userReduce = remember(version) { settings.reduceMotion }
    // Simple mode (ui.simple, default on) is always calm: no decorative motion; the system's setting still counts too.
    val calm = simpleUi() || MotionPolicy.calm(userReduce, rememberSystemAnimatorScale())
    val palette = if (forceDark || rememberDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalCalmMotion provides calm, LocalTkPalette provides palette) {
        MaterialTheme(colorScheme = schemeOf(palette), typography = TkTypography, shapes = TkShapes, content = content)
    }
}
