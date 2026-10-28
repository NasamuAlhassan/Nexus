package com.tekyerema.live

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * The sound of a finished timer: the phone's alarm tone on the alarm stream, looping, with a vibration — started a moment
 * after the spoken line so the words are heard first. Stops on "stop", the island's Stop, a volume key, or by itself after
 * a minute (a phone left ringing in an empty room helps nobody).
 */
object TimerAlert {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var tone: Ringtone? = null
    @Volatile private var vibrator: Vibrator? = null
    @Volatile var ringing: Boolean = false; private set
    private val autoStop = Runnable { stop() }
    private var pendingStart: Runnable? = null

    fun start(ctx: Context, delayMs: Long = 0L) {
        stop()
        ringing = true
        val r = Runnable { if (ringing) play(ctx.applicationContext) }
        pendingStart = r
        main.postDelayed(r, delayMs)
        main.postDelayed(autoStop, delayMs + 60_000L)
    }

    private fun play(ctx: Context) {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            tone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (_: Throwable) {}
        try {
            vibrator = ctx.getSystemService(Vibrator::class.java)?.also { v ->
                if (Build.VERSION.SDK_INT >= 26) com.tekyerema.core.Haptics.play(ctx, VibrationEffect.createWaveform(longArrayOf(0, 500, 400, 500, 1200), 0),
                    com.tekyerema.core.Haptics.Kind.ALERT)
            }
        } catch (_: Throwable) {}
    }

    fun stop() {
        ringing = false
        pendingStart?.let { main.removeCallbacks(it) }; pendingStart = null
        main.removeCallbacks(autoStop)
        try { tone?.stop() } catch (_: Throwable) {}
        tone = null
        try { vibrator?.cancel() } catch (_: Throwable) {}
        vibrator = null
    }
}
