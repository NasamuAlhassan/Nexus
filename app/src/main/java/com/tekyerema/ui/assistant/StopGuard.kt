package com.tekyerema.ui.assistant

import android.os.SystemClock
import android.util.Log

/**
 * Stop by touch takes two taps (owner, 29 Oct 2026, testing by feel: the task and its listening kept ending — the log showed the panel's
 * Stop pill and the question card's Stop pressed again and again, a stray touch while reaching for the panel). For someone who can't
 * see the pill, one tap is easy to land by accident; the first tap only says "Tap again to stop", a second within [WINDOW_MS] stops.
 * The volume keys and a spoken "stop" stop at once, as before. `ui.stop.twoTaps` false: one tap stops.
 */
object StopGuard {
    const val WINDOW_MS = 3_000L
    @Volatile private var armedAt = 0L

    /** True when this press should stop now; false when it only armed the stop. Pure apart from the clock. */
    internal fun shouldStop(now: Long, twoTaps: Boolean): Boolean {
        if (!twoTaps || (armedAt != 0L && now - armedAt <= WINDOW_MS)) { armedAt = 0L; return true }
        armedAt = now
        return false
    }

    fun press(stop: () -> Unit) {
        val twoTaps = try { com.tekyerema.core.config.AppConfig.bool("ui.stop.twoTaps", true) } catch (_: Throwable) { true }
        if (shouldStop(SystemClock.elapsedRealtime(), twoTaps)) { stop(); return }
        Log.i("Tekyerema", "[STOP] first tap on Stop — waiting for a second")
        val twi = try { com.tekyerema.voice.VoiceEngine.language() == com.tekyerema.voice.Lang.TWI } catch (_: Throwable) { false }
        // [owner to listen: the Twi line]
        try { com.tekyerema.voice.VoiceEngine.announce(if (twi) "Mia bio na magyae." else "Tap again to stop.") } catch (_: Throwable) {}
    }
}
