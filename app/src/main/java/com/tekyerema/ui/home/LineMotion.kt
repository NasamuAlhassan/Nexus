package com.tekyerema.ui.home

import com.tekyerema.ui.voice.Presence

/**
 * How the assistant's line moves (pure; unit-tested in UiMotionTest).
 *
 * - A new line lifts the old one away and rises into place, but the SAME words never re-animate: a reply keeps its
 *   place from SPEAKING through DONE to IDLE, a confirmation question stays put when the mic opens for the answer,
 *   and a live transcript updates in place while the user talks.
 * - While Tɛkyerɛma speaks, its words are revealed at about the pace they are spoken — a little ahead of the voice,
 *   never behind it — so the eye and the ear meet. When speech ends early the rest appears at once.
 */
object LineMotion {
    /** Identity of a line: equal keys update in place, a new key is a new line. */
    fun key(p: Presence, phrase: String): String = if (p == Presence.LISTENING) "listening" else phrase

    /** Only a spoken reply is revealed progressively; status lines ("Thinking…", "Opening WhatsApp…") appear whole. */
    fun reveals(p: Presence): Boolean = p == Presence.SPEAKING

    /**
     * Reveal pace for the speech rate setting (0 slow, 1 normal, 2 fast). A TTS voice speaks ~13–14 characters a
     * second at normal rate; the reveal runs slightly faster so the text leads the voice.
     */
    fun charsPerSecond(rate: Int): Float = when (rate) {
        0 -> 12f
        2 -> 20f
        else -> 16f
    }

    const val MIN_REVEAL_MS = 300
    const val MAX_REVEAL_MS = 25_000

    /** How long revealing [chars] more characters takes at [cps]. Nothing left = no animation. */
    fun revealMillis(chars: Float, cps: Float): Int =
        if (chars <= 0f) 0 else (chars / cps * 1000f).toInt().coerceIn(MIN_REVEAL_MS, MAX_REVEAL_MS)
}
