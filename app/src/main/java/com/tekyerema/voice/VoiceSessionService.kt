package com.tekyerema.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 *
 * K-04: the notification shows what the agent is doing right now (step, waiting for an OK, secure step), taps
 * back into the app, and carries a Stop action (J-14) — a one-gesture kill switch from the shade, in any app.
 */
class VoiceSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i("Tekyerema", "[SVC] Stop pressed on the notification — stopping the agent")
            android.os.Handler(mainLooper).post { VoiceEngine.cancel() }
            return START_NOT_STICKY
        }
        ensureChannel(this)
        try {
            startForeground(NOTIF_ID, build(this, lastText))
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[SVC] startForeground failed: ${t.message}")
        }
        return START_NOT_STICKY   // we drive the lifecycle explicitly; don't auto-restart on kill
    }

    companion object {
        private const val CHANNEL = "tekyerema_session"
        private const val NOTIF_ID = 4201
        private const val RESULT_ID = 4203
        private const val ACTION_STOP = "com.tekyerema.action.STOP_TASK"
        @Volatile private var running = false
        @Volatile private var lastText = "Working on your request."

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

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, com.tekyerema.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun build(ctx: Context, text: String): Notification {
            val stop = PendingIntent.getService(
                ctx, 1, Intent(ctx, VoiceSessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle("Tɛkyerɛma is working")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openApp(ctx))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
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

        /** K-04: refresh the ongoing notification with what the agent is doing now. */
        fun update(ctx: Context, text: String) {
            if (text == lastText) return
            lastText = text
            if (!running) return
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, build(ctx, text)) } catch (_: Throwable) {}
        }

        /** K-04: the outcome, left in the shade briefly for a task that finished while the user was elsewhere. */
        fun postResult(ctx: Context, text: String) {
            if (text.isBlank()) return
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle("Tɛkyerɛma")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setTimeoutAfter(com.tekyerema.core.config.AppConfig.long("ux.progressNotification.resultTimeoutMs", 60_000L))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(RESULT_ID, n) } catch (_: Throwable) {}
        }

        /** Idempotent stop — called when the session goes idle/terminal. */
        fun stop(ctx: Context) {
            if (!running) return
            running = false
            lastText = "Working on your request."
            try { ctx.stopService(Intent(ctx, VoiceSessionService::class.java)) } catch (_: Throwable) {}
        }
    }
}
