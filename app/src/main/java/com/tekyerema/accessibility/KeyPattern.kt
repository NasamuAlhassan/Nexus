package com.tekyerema.accessibility

import android.view.KeyEvent
import com.tekyerema.core.config.AppConfig

/**
 * A-01 / J-14 — a hardware-key gesture that starts a voice turn from anywhere, and stops a running task (the kill
 * switch). Default: press volume-down twice quickly. The key presses are NOT swallowed (the volume keys keep working
 * normally); the two volume steps the gesture cost are restored after it fires. Samsung's own accessibility shortcut
 * (hold BOTH volume keys) is deliberately not used. Pure → unit-tested with explicit timestamps.
 *
 * Config: `activation.keys.enabled`, `activation.keys.key` ("volume_down" | "volume_up"), `activation.keys.windowMs`.
 */
class KeyPattern(
    private val keyCode: () -> Int = { keyFor(AppConfig.string("activation.keys.key", "volume_down")) },
    private val windowMs: () -> Long = { AppConfig.long("activation.keys.windowMs", 450L) }
) {
    private var lastUpMs = -1L

    /** Feed every KEY_UP; true when this press completes the gesture. */
    fun onKeyUp(code: Int, timeMs: Long): Boolean {
        if (code != keyCode()) { lastUpMs = -1L; return false }
        val fired = lastUpMs >= 0 && timeMs - lastUpMs <= windowMs()
        lastUpMs = if (fired) -1L else timeMs
        return fired
    }

    companion object {
        fun keyFor(name: String): Int = when (name.trim().lowercase()) {
            "volume_up" -> KeyEvent.KEYCODE_VOLUME_UP
            else -> KeyEvent.KEYCODE_VOLUME_DOWN
        }
    }
}
