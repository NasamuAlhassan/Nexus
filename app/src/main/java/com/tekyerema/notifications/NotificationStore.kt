package com.tekyerema.notifications

import android.app.Notification

/** A captured notification. `title`/`text` are UNTRUSTED (app-supplied), already sanitized and OTP-redacted. */
data class NotificationRecord(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postTime: Long,
    val canReply: Boolean,
    val replyAction: Notification.Action?,
    /** E-15: the app's name as a person says it ("WhatsApp"), not its package id. */
    val appLabel: String? = null,
    /** E-15: for chat notifications (MessagingStyle), who sent the latest message — the title is often the group. */
    val sender: String? = null,
    /** E-17: the notification's buttons ("Mark as read", "Archive", "Reply"…), for notification_action. */
    val actions: List<Notification.Action> = emptyList()
) {
    /** How the notification is named in speech: "WhatsApp — Ama". */
    val spokenSource: String get() = listOfNotNull(appLabel, sender ?: title).distinct().joinToString(" — ").ifEmpty { packageName }
}

/** In-memory ring buffer of recent notifications. Bodies are not persisted to disk (privacy). */
object NotificationStore {
    private val items = ArrayDeque<NotificationRecord>()
    private const val MAX = 50

    @Synchronized
    fun put(r: NotificationRecord) {
        items.removeAll { it.key == r.key }
        items.addLast(r)
        while (items.size > MAX) items.removeFirst()
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    @Synchronized
    fun get(key: String): NotificationRecord? = items.firstOrNull { it.key == key }

    @Synchronized
    fun recent(limit: Int = 20): List<NotificationRecord> = items.reversed().take(limit)

    @Synchronized
    fun findReplyable(match: String?): NotificationRecord? =
        items.reversed().firstOrNull {
            it.canReply && (match.isNullOrBlank() ||
                it.title?.contains(match, true) == true ||
                it.sender?.contains(match, true) == true ||
                it.appLabel?.contains(match, true) == true ||
                it.packageName.contains(match, true))
        }

    /** E-17: the newest notification matching a sender / app / text fragment (null match = the newest). */
    @Synchronized
    fun find(match: String?): NotificationRecord? =
        items.reversed().firstOrNull {
            match.isNullOrBlank() ||
                it.title?.contains(match, true) == true ||
                it.sender?.contains(match, true) == true ||
                it.appLabel?.contains(match, true) == true ||
                it.text?.contains(match, true) == true ||
                it.packageName.contains(match, true)
        }
}
