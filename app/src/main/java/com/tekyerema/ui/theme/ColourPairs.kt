package com.tekyerema.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Reading colours for low vision (design round 2, 2 Nov 2026, docs/assistant-ux/REDESIGN_2026-11-02.md §7).
 *
 * Why several and not one "best": people with low vision differ — Legge et al. (1989) found some read white-on-black faster (most
 * of them with cloudy media), others the reverse; RNIB and teachers of the visually impaired report yellow-on-black, white-on-black
 * and black on a soft yellow/cream as the common favourites, chosen person by person. Bright white on black can "halate" (glow and
 * blur) for some eyes, so the white here is a softer white, and the cream is a soft cream, not canary yellow (Perkins).
 *
 * Every pair keeps body text ≥ 15:1, secondary text ≥ 9:1 and the accent ≥ 7:1 against its page (WCAG AAA is 7:1); the values
 * below were computed with the WCAG relative-luminance formula. A pair draws every surface with the high-contrast material
 * (opaque, a real edge in the text colour, no glass light), so nothing behind ever lowers that contrast.
 */
@Immutable
data class ColourPair(
    val id: String,
    val label: String,
    val description: String,
    val dark: Boolean,
    /** The page, and cards on it (cards are set apart by their edge, not by a second shade). */
    val page: Color,
    val card: Color,
    val text: Color, val textSecondary: Color, val textTertiary: Color,
    /** Links and the primary button. */
    val accent: Color, val onAccent: Color,
    val attention: Color, val danger: Color, val success: Color,
    /** The edge round every surface. */
    val edge: Color,
)

object ColourPairs {
    const val STANDARD = "standard"
    const val YELLOW_ON_BLACK = "yellow_black"
    const val WHITE_ON_BLACK = "white_black"
    const val BLACK_ON_CREAM = "black_cream"

    // Contrast against the page (WCAG): text 16.1, secondary 12.7, tertiary 9.8, accent 16.1, attention 21, danger 10.6, success 15.4.
    val YellowOnBlack = ColourPair(
        id = YELLOW_ON_BLACK, label = "Yellow on black",
        description = "Yellow words on black. Many people with low vision find this the easiest.",
        dark = true, page = Color(0xFF000000), card = Color(0xFF000000),
        text = Color(0xFFFFE14D), textSecondary = Color(0xFFE6C840), textTertiary = Color(0xFFCCB038),
        accent = Color(0xFFFFE14D), onAccent = Color(0xFF000000),
        attention = Color(0xFFFFFFFF), danger = Color(0xFFFF9E9E), success = Color(0xFF9BEFAE),
        edge = Color(0xFFFFE14D)
    )

    // Text 17.1 (a softer white than #FFF, against halation), secondary 12.6, tertiary 9.0, accent 14.2, attention 14.9.
    val WhiteOnBlack = ColourPair(
        id = WHITE_ON_BLACK, label = "White on black",
        description = "Soft white words on black, with less glare.",
        dark = true, page = Color(0xFF000000), card = Color(0xFF000000),
        text = Color(0xFFE8E8E8), textSecondary = Color(0xFFC8C8C8), textTertiary = Color(0xFFAAAAAA),
        accent = Color(0xFF7FE6D8), onAccent = Color(0xFF000000),
        attention = Color(0xFFFFD54A), danger = Color(0xFFFF9E9E), success = Color(0xFF9BEFAE),
        edge = Color(0xFFE8E8E8)
    )

    // Text 18.9, secondary 13.3, tertiary 9.0, accent 7.1 (white on it 7.9), attention 7.6, danger 8.1, success 7.1.
    val BlackOnCream = ColourPair(
        id = BLACK_ON_CREAM, label = "Black on cream",
        description = "Black words on a soft cream page, like paper.",
        dark = false, page = Color(0xFFFBF3D5), card = Color(0xFFFBF3D5),
        text = Color(0xFF000000), textSecondary = Color(0xFF2B281E), textTertiary = Color(0xFF474232),
        accent = Color(0xFF0A5C54), onAccent = Color(0xFFFFFFFF),
        attention = Color(0xFF6B4500), danger = Color(0xFF8F1A10), success = Color(0xFF0B5E30),
        edge = Color(0xFF000000)
    )

    val all = listOf(YellowOnBlack, WhiteOnBlack, BlackOnCream)

    fun of(id: String): ColourPair? = all.firstOrNull { it.id == id }

    /** The page palette for a pair: one flat page colour, no coloured lights, the pair's words. */
    fun palette(p: ColourPair) = TkPalette(
        dark = p.dark,
        ink = p.page, inkTop = p.page, inkBottom = p.page,
        field = listOf(Color.Transparent, Color.Transparent, Color.Transparent),
        glass = p.card, glassLow = p.card, glassEdgeTop = Color.Transparent, glassEdgeBottom = Color.Transparent,
        glassStrong = p.card,
        pressed = p.text.copy(alpha = 0.16f),
        hairline = p.textTertiary.copy(alpha = 0.7f),
        text = p.text, textSecondary = p.textSecondary, textTertiary = p.textTertiary,
        accent = p.accent, onAccent = p.onAccent,
        blue = p.accent, blueBright = p.accent, blueDeep = p.page,
        attention = p.attention, danger = p.danger, success = p.success,
        focus = p.text,
        tileBacking = 0.22f
    )

    /** The glass palette (assistant card, island, prompts) for a pair. */
    fun glass(p: ColourPair) = com.tekyerema.ui.assistant.GlassPalette(
        dark = p.dark,
        top = p.card, bottom = p.card,
        text = p.text, textSecondary = p.textSecondary, textTertiary = p.textTertiary,
        accent = p.accent, warm = p.attention, fail = p.danger,
        quietFill = p.text.copy(alpha = 0.08f), primaryA = p.accent, primaryB = p.accent, chosenA = p.accent, chosenB = p.accent,
        onPrimary = p.onAccent,
        highlight = Color.Transparent, bloom = p.accent, field = p.text.copy(alpha = 0.06f), idleBar = p.text.copy(alpha = 0.5f),
        shadow = false
    )
}

/** Answer size: a multiplier on the words of answers, questions and the line under the circle (on top of the phone's font size). */
object AnswerSizes {
    const val STANDARD = 1.0f
    const val LARGE = 1.3f
    const val LARGEST = 1.6f
    data class Size(val scale: Float, val name: String, val description: String)
    val all = listOf(
        Size(STANDARD, "Standard", "Answers and questions at the usual size."),
        Size(LARGE, "Large", "Answers and questions a third bigger."),
        Size(LARGEST, "Largest", "Answers and questions over half again as big.")
    )
}
