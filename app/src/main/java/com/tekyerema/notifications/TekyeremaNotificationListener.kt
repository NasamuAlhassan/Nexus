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
        instance = this
        try {
            activeNotifications?.forEach { sbn -> put(sbn) }
        } catch (t: Throwable) { /* best-effort backfill; live onNotificationPosted still works */ }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val rec = sbn?.let { put(it) } ?: return
        // A-12: the user's own "tell me when Ama messages" rules — live posts only, never the reconnect backfill.
        try { com.tekyerema.agent.triggers.NotificationTriggers.onPosted(applicationContext, rec) } catch (_: Throwable) {}
    }

    private fun put(sbn: StatusBarNotification): NotificationRecord? {
        val n = sbn.notification ?: return null
        // J-10: authenticator apps are never captured; one-time codes/PINs in any other app are hidden.
        if (com.tekyerema.core.Redact.deniedPackage(sbn.packageName)) return null
        val extras = n.extras
        // J-07: notification text is app-supplied (untrusted): strip hidden-channel characters like screen text.
        fun clean(s: CharSequence?) = com.tekyerema.core.Redact.secrets(com.tekyerema.core.Sanitize.label(s?.toString()))
        val title = clean(extras?.getCharSequence(Notification.EXTRA_TITLE))
        val text = clean(extras?.getCharSequence(Notification.EXTRA_TEXT))
        val replyAction = findReplyAction(n)
        // E-15: chat apps (MessagingStyle) put the group/conversation in the title — the latest SENDER and their
        // words are in EXTRA_MESSAGES. Fall back to title/text for everything else.
        val (sender, lastText) = latestMessage(extras)
        val rec = NotificationRecord(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = clean(lastText) ?: text,
            postTime = sbn.postTime,
            canReply = replyAction != null,
            replyAction = replyAction,
            appLabel = labelOf(sbn.packageName),
            sender = clean(sender),
            actions = n.actions?.toList().orEmpty()
        )
        NotificationStore.put(rec)
        return rec
    }

    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun labelOf(pkg: String): String? = labels[pkg] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            .takeIf { it.isNotBlank() && it != pkg }?.also { labels[pkg] = it }
    } catch (_: Throwable) { null }

    /** The newest (sender, text) of a MessagingStyle notification, or (null, null). */
    private fun latestMessage(extras: android.os.Bundle?): Pair<CharSequence?, CharSequence?> = try {
        val msgs = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = msgs?.lastOrNull() as? android.os.Bundle
        if (last == null) null to null
        else {
            val person = if (android.os.Build.VERSION.SDK_INT >= 28)
                (last.getParcelable<android.app.Person>("sender_person"))?.name else null
            (person ?: last.getCharSequence("sender")) to last.getCharSequence("text")
        }
    } catch (_: Throwable) { null to null }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    companion object {
        /** E-17: the connected listener, for dismissing a notification (cancelNotification lives on the service). */
        @Volatile var instance: TekyeremaNotificationListener? = null
            private set

        /** E-17: dismiss the notification with [key]; false when the listener isn't connected. */
        fun dismiss(key: String): Boolean {
            val l = instance ?: return false
            return try { l.cancelNotification(key); true } catch (_: Throwable) { false }
        }
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
