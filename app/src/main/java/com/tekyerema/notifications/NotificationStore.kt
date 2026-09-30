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
    val actions: List<Notification.Action> = emptyList(),
    /** An ongoing notification (a call in progress, "Checking for new messages…") — never read as a message. */
    val ongoing: Boolean = false
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
    fun clearForTest() { items.clear() }

    @Synchronized
    fun get(key: String): NotificationRecord? = items.firstOrNull { it.key == key }

    @Synchronized
    fun recent(limit: Int = 20): List<NotificationRecord> = items.reversed().take(limit)

    @Synchronized
    fun findReplyable(match: String?): NotificationRecord? = replyables(match).firstOrNull()

    /** "Ama" matches Ama, not Amanda: the words of a name, not its letters. */
    private fun word(s: String?, m: String): Boolean =
        s != null && Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(m)}(?![\\p{L}\\p{N}])").containsMatchIn(s)

    /**
     * Every replyable CONVERSATION matching [match], newest first — a name match (sender or conversation title, whole
     * words) beats an app match beats a loose contains. More than one row = ambiguous: the reply tool asks which,
     * instead of sending to whichever messaged last (audit 2026-09-30, half 2 #1 — "Ama" went to Amanda, or to a
     * group where Ama spoke last, or to whoever wrote during the confirmation wait).
     */
    @Synchronized
    fun replyables(match: String?): List<NotificationRecord> = tiered(items.reversed().filter { it.canReply && !it.ongoing }, match)

    /** Every notification matching [match] the same tiered way (name beats app beats loose), for notification_action. */
    @Synchronized
    fun findAll(match: String?): List<NotificationRecord> = tiered(items.reversed(), match)

    private fun tiered(records: List<NotificationRecord>, match: String?): List<NotificationRecord> {
        // "never touch" holds for records captured before the rule was set (audit half 2 #9).
        val all = records.filterNot { com.tekyerema.core.Redact.deniedPackage(it.packageName) }
        if (match.isNullOrBlank()) return all.take(1)
        val byName = all.filter { word(it.sender, match) || word(it.title, match) }
        val byApp = all.filter { it.appLabel?.equals(match, true) == true || it.packageName.contains(match, true) }
        val loose = all.filter {
            it.title?.contains(match, true) == true || it.sender?.contains(match, true) == true ||
                it.appLabel?.contains(match, true) == true || it.text?.contains(match, true) == true ||
                it.packageName.contains(match, true)
        }
        return byName.ifEmpty { byApp.ifEmpty { loose } }.distinctBy { it.key }
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
