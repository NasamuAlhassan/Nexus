package com.tekyerema.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Reads notifications and exposes any free-form Direct-Reply action for later use. */
class TekyeremaNotificationListener : NotificationListenerService() {

    /**
     * `onNotificationPosted` only fires for notifications posted WHILE this listener is
     * connected — it does not backfill anything already active when the service (re)binds
     * (e.g. after an app reinstall/restart, or if the listener was briefly disconnected). That
     * gap meant a real message sent to the device could sit there, fully visible to Android,
     * while NotificationStore correctly-but-uselessly reported empty. Standard fix: on connect,
     * pull the current snapshot via getActiveNotifications() and seed the store from it, using
     * the same record-building logic as the live path. Generic — not tied to any app.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { sbn -> put(sbn) }
        } catch (t: Throwable) { /* best-effort backfill; live onNotificationPosted still works */ }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { put(it) }
    }

    private fun put(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val replyAction = findReplyAction(n)
        NotificationStore.put(
            NotificationRecord(
                key = sbn.key,
                packageName = sbn.packageName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                canReply = replyAction != null,
                replyAction = replyAction
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { NotificationStore.remove(it) }
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        for (a in actions) {
            val inputs = a.remoteInputs ?: continue
            if (inputs.any { it.allowFreeFormInput }) return a
        }
        return null
    }
}
