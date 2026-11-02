package com.tekyerema.ime

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb

/**
 * The keyboard's colours (owner, 2 Nov 2026: "the keyboard too, the light mode and dark mode"). It follows the app's own
 * Look: a reading-colour pair (Settings › Accessibility › Look) when one is chosen, otherwise Settings › Accessibility ›
 * "Light or dark" — and with that on "system", the phone's dark theme. Read each time the keyboard's view is made, so a
 * change applies at the next field (and at once when the phone's theme changes: the system remakes the view).
 *
 * Contrast (WCAG, computed): light — letters 17.8:1 on a key, 14.6:1 on a function key, secondary 5:1+ everywhere,
 * white on the teal action key 6.4:1, keys 1.5:1 against the tray (the dark keyboard's own is 1.3:1). Dark — the
 * palette the keyboard always had (text 14:1+ on a key).
 */
data class KeyboardPalette(
    val dark: Boolean,
    val bg: Int, val key: Int, val fnKey: Int, val stripKey: Int, val pressed: Int,
    val accent: Int, val onAccent: Int, val trail: Int,
    val text: Int, val textSecondary: Int
) {
    companion object {
        val DARK = KeyboardPalette(
            dark = true,
            bg = 0xFF050B0D.toInt(), key = 0xFF14262A.toInt(), fnKey = 0xFF0C1A1D.toInt(), stripKey = 0xFF0A1518.toInt(),
            pressed = 0xFF0E6F66.toInt(), accent = 0xFF1ED1BB.toInt(), onAccent = 0xFF050B0D.toInt(), trail = 0xB31ED1BB.toInt(),
            text = 0xFFF1F7F4.toInt(), textSecondary = 0xFF9DBDB5.toInt()
        )
        val LIGHT = KeyboardPalette(
            dark = false,
            bg = 0xFFC9D5D2.toInt(), key = 0xFFFFFFFF.toInt(), fnKey = 0xFFE3EAE8.toInt(), stripKey = 0xFFEAF0EE.toInt(),
            pressed = 0xFFA8DDD4.toInt(), accent = 0xFF0B6B62.toInt(), onAccent = 0xFFFFFFFF.toInt(), trail = 0xB30B6B62.toInt(),
            text = 0xFF0A1A1B.toInt(), textSecondary = 0xFF3F5A56.toInt()
        )

        /** A reading-colour pair (ui/theme/ColourPairs), as keys: the pair's page for the tray, its card for every key, its
         *  own words and accent; a pressed key is the accent mixed into the card, so the letter on it stays readable. */
        fun of(pair: com.tekyerema.ui.theme.ColourPair): KeyboardPalette {
            fun argb(c: androidx.compose.ui.graphics.Color) = c.toArgb()
            val card = argb(pair.card)
            val accent = argb(pair.accent)
            return KeyboardPalette(
                dark = pair.dark, bg = argb(pair.page), key = card, fnKey = card, stripKey = card,
                pressed = mix(accent, card, 0.35f), accent = accent, onAccent = argb(pair.onAccent),
                trail = (accent and 0x00FFFFFF) or (0xB3 shl 24),
                text = argb(pair.text), textSecondary = argb(pair.textSecondary)
            )
        }

        /** Pure: which palette for the app's settings and the phone's theme. */
        fun choose(appearance: String, phoneDark: Boolean, pair: com.tekyerema.ui.theme.ColourPair?): KeyboardPalette = when {
            pair != null -> of(pair)
            appearance == com.tekyerema.settings.Settings.APPEARANCE_LIGHT -> LIGHT
            appearance == com.tekyerema.settings.Settings.APPEARANCE_DARK -> DARK
            else -> if (phoneDark) DARK else LIGHT
        }

        fun choose(ctx: Context): KeyboardPalette = try {
            com.tekyerema.ui.theme.Look.init(ctx)
            val phoneDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            choose(com.tekyerema.settings.Settings(ctx).appearance, phoneDark, com.tekyerema.ui.theme.Look.pair())
        } catch (_: Throwable) { DARK }

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(x: Int, s: Int) = (x shr s) and 0xFF
            fun m(s: Int) = (ch(a, s) * t + ch(b, s) * (1 - t)).toInt().coerceIn(0, 255) shl s
            return (0xFF shl 24) or m(16) or m(8) or m(0)
        }
    }
}
