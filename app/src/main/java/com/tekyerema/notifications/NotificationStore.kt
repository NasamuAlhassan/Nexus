package com.tekyerema.notifications

import android.app.Notification

/** A captured notification. `title`/`text` are UNTRUSTED (app-supplied). */
data class NotificationRecord(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postTime: Long,
    val canReply: Boolean,
    val replyAction: Notification.Action?
)

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
    fun recent(limit: Int = 20): List<NotificationRecord> = items.reversed().take(limit)

    @Synchronized
    fun findReplyable(match: String?): NotificationRecord? =
        items.reversed().firstOrNull {
            it.canReply && (match.isNullOrBlank() ||
                it.title?.contains(match, true) == true ||
                it.packageName.contains(match, true))
        }
}
