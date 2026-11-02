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
            activeNotifications?.forEach { sbn ->
                val rec = put(sbn) ?: return@forEach
                // H-09 too (A20e 2026-09-30: after an app update the WhatsApp messages in the shade were in the live store but
                // not the index, so "read my last WhatsApp message" said there were none). The index keeps one row per
                // conversation and text, so a re-post is not a duplicate. Triggers and alerts stay live-only.
                if (sbn.notification?.flags?.and(Notification.FLAG_ONGOING_EVENT) == 0)
                    try { MessageIndex.add(rec) } catch (_: Throwable) {}
            }
        } catch (t: Throwable) { /* best-effort backfill; live onNotificationPosted still works */ }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val rec = sbn?.let { put(it) } ?: return
        // H-09: remember it (encrypted, 7 days) so it can be found after it's dismissed — live posts only, not ongoing ones.
        if (sbn?.notification?.flags?.and(android.app.Notification.FLAG_ONGOING_EVENT) == 0)
            try { com.tekyerema.notifications.MessageIndex.add(rec) } catch (_: Throwable) {}
        // A-12: the user's own "tell me when Ama messages" rules — live posts only, never the reconnect backfill.
        try { com.tekyerema.agent.triggers.NotificationTriggers.onPosted(applicationContext, rec) } catch (_: Throwable) {}
        // Phone care: money-received / missed-call alerts the user switched on — live posts only, on its own thread.
        try { com.tekyerema.phonecare.PhoneAlerts.onPosted(applicationContext, rec, sbn?.notification) } catch (_: Throwable) {}
        // #37: a one-time code while a code box waits — offered, typed only on "enter the code" (OtpRelay). The raw text is
        // read once here and never kept; the record above already has the code hidden.
        try {
            val ex = sbn?.notification?.extras
            val raw = (ex?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString()
            val from = (rec.sender ?: rec.title)?.takeIf { !com.tekyerema.phonecare.MoneyAlerts.looksLikeNumber(it) }
            OtpRelay.onPosted(applicationContext, rec.packageName, from, raw)
        } catch (_: Throwable) {}
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
            actions = n.actions?.toList().orEmpty(),
            ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        )
        NotificationStore.put(rec)
        // H-09 (audit 2026-09-30, half 2 #8): a burst Android folded into one update carries EVERY message in
        // EXTRA_MESSAGES — index the earlier ones too (their own words, senders and times), not only the newest.
        // The index dedupes on (app, conversation, text), so re-posts of the same words are one row.
        if (!rec.ongoing) for ((who, what, at) in earlierMessages(extras)) {
            val w = clean(what) ?: continue
            try {
                MessageIndex.add(rec.copy(sender = clean(who), text = w, postTime = if (at > 0) at else sbn.postTime))
            } catch (_: Throwable) {}
        }
        return rec
    }

    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun labelOf(pkg: String): String? = labels[pkg] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            .takeIf { it.isNotBlank() && it != pkg }?.also { labels[pkg] = it }
    } catch (_: Throwable) { null }

    private fun senderOf(m: android.os.Bundle): CharSequence? {
        val person = if (android.os.Build.VERSION.SDK_INT >= 28)
            (m.getParcelable<android.app.Person>("sender_person"))?.name else null
        return person ?: m.getCharSequence("sender")
    }

    /**
     * The newest OTHER person's (sender, text) of a MessagingStyle notification, or (null, null). A message with no
     * sender is the USER'S OWN reply (Android's model) — it used to be captured with the title as the sender, so
     * "read my last WhatsApp message" read the user's own words back as Ama's (audit 2026-09-30, half 2 #7).
     */
    private fun latestMessage(extras: android.os.Bundle?): Pair<CharSequence?, CharSequence?> = try {
        val msgs = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<android.os.Bundle>()
        val last = msgs?.lastOrNull { !senderOf(it).isNullOrBlank() } ?: msgs?.lastOrNull()
        if (last == null) null to null else senderOf(last) to last.getCharSequence("text")
    } catch (_: Throwable) { null to null }

    /** Every message BEFORE the newest with its sender and time — the burst the shade folded into one update. */
    private fun earlierMessages(extras: android.os.Bundle?): List<Triple<CharSequence?, CharSequence?, Long>> = try {
        val msgs = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<android.os.Bundle>().orEmpty()
        msgs.dropLast(1).filter { !senderOf(it).isNullOrBlank() }
            .map { Triple(senderOf(it), it.getCharSequence("text"), it.getLong("time", 0L)) }
    } catch (_: Throwable) { emptyList() }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    companion object {
        /** E-17: the connected listener, for dismissing a notification (cancelNotification lives on the service). */
        @Volatile var instance: TekyeremaNotificationListener? = null
            private set

        /** The ongoing notifications [pkg] shows now, as their words (channel, title, text); null when the listener isn't
         *  connected. Used to check that "stop the timer" really stopped one (agent/TimerNotice). */
        fun ongoingWords(pkg: String): List<String>? {
            val l = instance ?: return null
            return try {
                l.activeNotifications.orEmpty().filter { it.packageName == pkg && it.isOngoing }.map { sbn ->
                    val n = sbn.notification
                    listOfNotNull(n.channelId, n.category, n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                        n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()).joinToString(" ")
                }
            } catch (_: Throwable) { null }
        }

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
