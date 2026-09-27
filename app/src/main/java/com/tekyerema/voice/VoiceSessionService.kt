package com.tekyerema.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * §C.1 — a foreground service that keeps the voice session (mic capture + agent loop) alive while a task is
 * in flight, so Tɛkyerɛma does NOT silently die when the screen changes or the launching activity is gone.
 * For a user who can't see the screen, an assistant that stops existing mid-task is unusable.
 *
 * It hosts nothing itself — VoiceEngine remains the process-scoped owner of the mic/agent — the service just
 * pins the process (and shows the required ongoing notification) for the duration of a session. VoiceEngine
 * starts it when the agent becomes active and stops it when the session goes idle.
 */
class VoiceSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Tɛkyerɛma is listening")
            .setContentText("Working on your request — tap to return.")
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            startForeground(NOTIF_ID, n)
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[SVC] startForeground failed: ${t.message}")
        }
        return START_NOT_STICKY   // we drive the lifecycle explicitly; don't auto-restart on kill
    }

    companion object {
        private const val CHANNEL = "tekyerema_session"
        private const val NOTIF_ID = 4201
        @Volatile private var running = false

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Voice session", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Keeps Tɛkyerɛma running while it handles your request."
                        setShowBadge(false)
                    }
                )
            }
        }

        /** Idempotent start — safe to call on every "active" transition. */
        fun start(ctx: Context) {
            if (running) return
            running = true
            val i = Intent(ctx, VoiceSessionService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (t: Throwable) { running = false; Log.w("Tekyerema", "[SVC] start failed: ${t.message}") }
        }

        /** Idempotent stop — called when the session goes idle/terminal. */
        fun stop(ctx: Context) {
            if (!running) return
            running = false
            try { ctx.stopService(Intent(ctx, VoiceSessionService::class.java)) } catch (_: Throwable) {}
        }
    }
}
