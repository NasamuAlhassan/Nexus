package com.tekyerema.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tekyerema.R

/**
 * The home-screen widget (design round 2, 2 Nov 2026): one large "Talk" button and a smaller "Look" button, so nobody has to find
 * and open the app first. Talk opens the arrive-on-top listening screen (ui/VoiceActivity) exactly as the Quick Settings tile does;
 * Look opens the camera describing what is in front of it (camera/CameraReadActivity, as Home's camera button).
 *
 * Deliberately no "last answer" on it: a home screen is seen by anyone nearby, and answers carry balances and messages.
 * Static RemoteViews (no Glance — the build is offline and the widget never changes), so nothing runs to keep it up to date.
 */
class TalkWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        private const val REQ_TALK = 7101
        private const val REQ_LOOK = 7102

        fun views(context: Context): RemoteViews = RemoteViews(context.packageName, R.layout.widget_talk).apply {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val talk = Intent(context, com.tekyerema.ui.VoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)   // the same launch as the Quick Settings tile (voice/TalkTileService)
            setOnClickPendingIntent(R.id.widget_talk, PendingIntent.getActivity(context, REQ_TALK, talk, flags))
            val look = com.tekyerema.camera.CameraReadActivity.intent(context, com.tekyerema.camera.CameraReadActivity.MODE_DESCRIBE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setOnClickPendingIntent(R.id.widget_look, PendingIntent.getActivity(context, REQ_LOOK, look, flags))
        }
    }
}
