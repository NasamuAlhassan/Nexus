package com.tekyerema.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Every buzz Tɛkyerɛma gives, with the right USAGE (owner, 28 Oct 2026: "the vibrations of the Infinix, could you check it").
 * The Infinix has touch vibration switched off (haptic_feedback_enabled = 0) and Android dropped every one of our buzzes —
 * the PIN pad's digit buzzes included — as "ignored_for_settings, usage: TOUCH": a plain `vibrate(effect)` is filed as a
 * touch effect. A blind user's feedback is not a touch nicety: [Kind.FEEDBACK] goes as an ACCESSIBILITY vibration
 * (`haptics.usage` = accessibility | notification | alarm | touch picks another without a build), [Kind.ALERT] as an alarm.
 */
object Haptics {
    enum class Kind { FEEDBACK, ALERT }

    fun vibrator(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    } catch (_: Throwable) { null }

    fun waveform(ctx: Context?, pattern: LongArray, kind: Kind = Kind.FEEDBACK) {
        if (ctx == null || Build.VERSION.SDK_INT < 26) return
        play(ctx, VibrationEffect.createWaveform(pattern, -1), kind)
    }

    fun play(ctx: Context, effect: VibrationEffect, kind: Kind = Kind.FEEDBACK, usageOverride: String? = null) {
        val v = vibrator(ctx) ?: return
        val usage = usageOverride ?: if (kind == Kind.ALERT) "alarm" else
            try { com.tekyerema.core.config.AppConfig.string("haptics.usage", "accessibility") } catch (_: Throwable) { "accessibility" }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val u = when (usage) {
                    "alarm" -> android.os.VibrationAttributes.USAGE_ALARM
                    "notification" -> android.os.VibrationAttributes.USAGE_NOTIFICATION
                    "touch" -> android.os.VibrationAttributes.USAGE_TOUCH
                    else -> android.os.VibrationAttributes.USAGE_ACCESSIBILITY
                }
                v.vibrate(effect, android.os.VibrationAttributes.createForUsage(u))
            } else {
                val u = when (usage) {
                    "alarm" -> android.media.AudioAttributes.USAGE_ALARM
                    "notification" -> android.media.AudioAttributes.USAGE_NOTIFICATION
                    "touch" -> android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                    else -> android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                }
                @Suppress("DEPRECATION")
                v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(u).build())
            }
        } catch (_: Throwable) {
            try { v.vibrate(effect) } catch (_: Throwable) {}
        }
    }
}
