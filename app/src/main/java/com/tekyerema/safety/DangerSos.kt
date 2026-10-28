package com.tekyerema.safety

import android.view.KeyEvent

/**
 * The manual SOS: a request for help the user starts on purpose, separate from any automatic detection and from the code word (it
 * works whether or not the code word is set up). Two ways in, both leading to the same five-second cancel window of [DangerAlert]:
 *  - a whole utterance that is only a call for help ("SOS", "this is an emergency", "I need help now"). Matched whole, never inside a
 *    longer request, so "help me write a message" is an ordinary request;
 *  - holding volume-up for [SosHold] (4 s by default), which works with the screen off and no speech.
 * Pure → DangerTest.
 */
object DangerSos {
    private val PHRASES = setOf(
        "sos", "s o s", "send sos", "send an sos", "send s o s", "emergency", "this is an emergency", "i have an emergency",
        "i need help now", "i need urgent help", "i need emergency help", "i am in danger", "im in danger", "help me i am in danger",
        "call for help", "send help", "send for help",
    )

    fun matches(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim().removePrefix("please ").trim()
        return t in PHRASES
    }
}

/** Fires once per press when a volume key has been held for [holdMs]. Fed every key event; pure, with explicit times. */
class SosHold(private val keyCode: Int = KeyEvent.KEYCODE_VOLUME_UP, private val holdMs: Long = 4_000L) {
    private var downAt = -1L
    private var fired = false

    /** True when this event is the one that completes the hold. */
    fun onKey(code: Int, down: Boolean, timeMs: Long): Boolean {
        if (code != keyCode) { downAt = -1L; fired = false; return false }
        if (!down) { downAt = -1L; fired = false; return false }
        if (downAt < 0) { downAt = timeMs; fired = false; return false }
        if (!fired && timeMs - downAt >= holdMs) { fired = true; return true }
        return false
    }
}
