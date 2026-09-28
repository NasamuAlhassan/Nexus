package com.tekyerema.agent.triggers

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import com.tekyerema.notifications.NotificationRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * A-12 — one notification rule. [appOrSender] is an app (package id or the name people say, "WhatsApp"), a sender
 * ("Ama"), or "*" for any; [contains] optionally narrows to notifications whose text/title contains a phrase.
 * [action] is only ever "announce" (who/which app) or "read" (also speak the already-redacted text) — there is no
 * action that DOES anything with a notification. [allowQuiet] lets it speak inside quiet hours.
 */
data class NotificationRule(
    val id: String,
    val appOrSender: String,
    val contains: String?,
    val action: String,
    val allowQuiet: Boolean = false
)

/**
 * A-12 — notification-triggered announcements. Rules come from config `triggers.notification` (JSON array) plus the
 * user's own rules in SharedPreferences "tekyerema_triggers"; the default set is EMPTY (the user opts in by voice).
 * On a match — flag A-12 on, outside quiet hours (unless the rule allows), not during a task / voice turn / call,
 * at most one announcement per sender per `triggers.notificationCooldownMs` (60 s) — it SPEAKS one line and never
 * acts on the notification's content (untrusted, J-07).
 */
object NotificationTriggers {
    const val FEATURE = "A-12"
    const val ANNOUNCE = "announce"
    const val READ = "read"
    private const val KEY_RULES = "notification_rules"

    private val cooldown = Cooldown()
    /** Notification key → signature of the words last announced, so an app re-posting the same message is not news. */
    private val lastSpoken = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 128
    }
    @Volatile private var userCache: List<NotificationRule>? = null

    // ---------------------------------------------------------------- matching (pure)

    private fun wordIn(term: String, field: String?): Boolean {
        if (field.isNullOrBlank()) return false
        return Regex("(?i)(?:^|[^\\p{L}\\p{N}])" + Regex.escape(term) + "(?:$|[^\\p{L}\\p{N}])").containsMatchIn(field)
    }

    /**
     * Does [rule] apply to [rec]? The who-part matches the package id exactly, or the app name / sender / title as a
     * whole word ("Ama" matches "Ama Mensah", not "Amadou"); "*" matches any. [NotificationRule.contains] then must
     * appear (case-insensitive) in the text or title.
     */
    fun matches(rule: NotificationRule, rec: NotificationRecord): Boolean {
        val term = rule.appOrSender.trim()
        if (term.isEmpty()) return false
        val who = term == "*" || term.equals(rec.packageName, ignoreCase = true) ||
            wordIn(term, rec.appLabel) || wordIn(term, rec.sender) || wordIn(term, rec.title)
        if (!who) return false
        val c = rule.contains?.trim().orEmpty()
        if (c.isEmpty()) return true
        return rec.text?.contains(c, ignoreCase = true) == true || rec.title?.contains(c, ignoreCase = true) == true
    }

    private fun senderOf(rec: NotificationRecord): String? = (rec.sender ?: rec.title)?.trim()?.takeIf { it.isNotEmpty() }

    /** "New message from Ama on WhatsApp." (degrades gracefully when the sender or app name is unknown). */
    fun announceLine(rec: NotificationRecord): String {
        val who = senderOf(rec)
        val app = rec.appLabel?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            who != null && app != null && !who.equals(app, true) ->
                AppConfig.phrase("triggers.phrases.newMessage", "New message from {sender} on {app}.", "sender" to who, "app" to app)
            who != null -> AppConfig.phrase("triggers.phrases.newMessageFrom", "New message from {sender}.", "sender" to who)
            app != null -> AppConfig.phrase("triggers.phrases.newNotificationFrom", "New notification from {app}.", "app" to app)
            else -> AppConfig.phrase("triggers.phrases.newNotification", "New notification.")
        }
    }

    /** "Ama on WhatsApp says: are you coming?" — the text is already sanitized and OTP-redacted by the listener. */
    fun readLine(rec: NotificationRecord, maxChars: Int = AppConfig.int("triggers.readMaxChars", 280)): String {
        val text = rec.text?.trim()?.takeIf { it.isNotEmpty() } ?: return announceLine(rec)
        val sender = senderOf(rec)
        val app = rec.appLabel?.trim()?.takeIf { it.isNotEmpty() }
        val who = when {
            sender != null && app != null && !sender.equals(app, true) -> "$sender on $app"
            sender != null -> sender
            app != null -> app
            else -> "A notification"
        }
        val body = if (text.length > maxChars) text.take(maxChars).trimEnd() + "…" else text
        return AppConfig.phrase("triggers.phrases.readMessage", "{who} says: {text}", "who" to who, "text" to body)
    }

    fun lineFor(rule: NotificationRule, rec: NotificationRecord): String =
        if (rule.action.equals(READ, true)) readLine(rec) else announceLine(rec)

    // ---------------------------------------------------------------- rules

    /** Tolerant parse; accepts `appOrSender` (or `app` / `sender`). Unknown actions become "announce". */
    fun parseRules(arr: JSONArray?, idPrefix: String = ""): List<NotificationRule> {
        if (arr == null) return emptyList()
        val out = ArrayList<NotificationRule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val who = listOf("appOrSender", "app", "sender").map { o.optString(it, "").trim() }.firstOrNull { it.isNotEmpty() } ?: continue
            val action = o.optString("action", ANNOUNCE).trim().lowercase().let { if (it == READ) READ else ANNOUNCE }
            val id = o.optString("id", "").trim().ifEmpty { "$idPrefix$i" }
            val contains = if (o.has("contains") && !o.isNull("contains")) o.optString("contains").trim().ifEmpty { null } else null
            out.add(NotificationRule(id, who, contains, action, o.optBoolean("allowQuiet", false)))
        }
        return out
    }

    fun encodeRules(list: List<NotificationRule>): String = JSONArray().apply {
        for (r in list) put(
            JSONObject().put("id", r.id).put("appOrSender", r.appOrSender).put("contains", r.contains ?: JSONObject.NULL)
                .put("action", r.action).put("allowQuiet", r.allowQuiet)
        )
    }.toString()

    fun configRules(): List<NotificationRule> = parseRules(AppConfig.node("triggers.notification") as? JSONArray, "cfg:")

    @Synchronized
    fun userRules(ctx: Context): List<NotificationRule> =
        userCache ?: parseRules(TriggerGate.prefs(ctx).getString(KEY_RULES, null)?.let { try { JSONArray(it) } catch (_: Throwable) { null } }, "user:")
            .also { userCache = it }

    fun rules(ctx: Context): List<NotificationRule> = configRules() + userRules(ctx)

    /** Add or replace (same id) a user rule — e.g. from "tell me when Ama messages me". False if the rule is unusable. */
    @Synchronized
    fun addRule(ctx: Context, rule: NotificationRule): Boolean {
        if (rule.id.isBlank() || rule.appOrSender.isBlank()) return false
        val clean = rule.copy(action = if (rule.action.equals(READ, true)) READ else ANNOUNCE)
        val list = userRules(ctx).filter { it.id != rule.id } + clean
        TriggerGate.prefs(ctx).edit().putString(KEY_RULES, encodeRules(list)).apply()
        userCache = list
        return true
    }

    @Synchronized
    fun removeRule(ctx: Context, id: String): Boolean {
        val all = userRules(ctx)
        if (all.none { it.id == id }) return false
        val list = all.filter { it.id != id }
        TriggerGate.prefs(ctx).edit().putString(KEY_RULES, encodeRules(list)).apply()
        userCache = list
        return true
    }

    // ---------------------------------------------------------------- event

    /**
     * Call from TekyeremaNotificationListener.onNotificationPosted AFTER NotificationStore.put (not from the
     * onListenerConnected backfill — old notifications are not news). Cheap; returns quickly on the listener thread.
     */
    fun onPosted(ctx: Context, rec: NotificationRecord) {
        try {
            if (!FeatureFlags.isEnabled(FEATURE, true)) return
            if (rec.packageName == ctx.packageName) return   // never our own result/progress notifications (loop)
            val rule = rules(ctx).firstOrNull { matches(it, rec) } ?: return
            if (!rule.allowQuiet && QuietHours.isQuiet(System.currentTimeMillis())) return
            if (TriggerGate.userBusy(ctx)) return             // never over a task, a voice turn or a call — and no retry: stale news
            val sig = ((rec.text ?: "") + "\u0000" + (rec.title ?: "")).hashCode()
            synchronized(lastSpoken) { if (lastSpoken[rec.key] == sig) return }
            val senderKey = (rec.packageName + "|" + (senderOf(rec) ?: "")).lowercase()
            if (!cooldown.tryAcquire(senderKey, SystemClock.elapsedRealtime(), AppConfig.long("triggers.notificationCooldownMs", 60_000L))) return
            synchronized(lastSpoken) { lastSpoken[rec.key] = sig }
            Log.i(TriggerGate.TAG, "[TRIGGER] A-12 rule ${rule.id} matched ${rec.packageName} → ${rule.action}")   // never the text
            TriggerGate.speak(ctx, lineFor(rule, rec), post = false)
        } catch (t: Throwable) {
            Log.w(TriggerGate.TAG, "[TRIGGER] A-12 onPosted failed: ${t.message}")
        }
    }
}
