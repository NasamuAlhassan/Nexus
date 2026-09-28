package com.tekyerema.audit

import android.content.Context
import com.tekyerema.core.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * J-12 — a history of every action Tɛkyerɛma took or proposed, in plain words the user can ask for by voice
 * ("what did you just do?", "what have you done today?"). Covers actions DONE and PLANNED (a proposal the user
 * declined or changed), from the fast path, the agent and unattended triggers. Encrypted at rest ([SecureStore]),
 * capped (`audit.max`, 300) and kept for `privacy.retention.historyDays`.
 */
data class AuditEntry(val t: Long, val task: Long, val source: String, val kind: String, val text: String)

object AuditLog {
    const val DONE = "done"; const val PLANNED = "planned"; const val DECLINED = "declined"; const val BLOCKED = "blocked"; const val FAILED = "failed"

    @Volatile private var ctx: Context? = null
    private val entries = ArrayList<AuditEntry>()
    @Volatile private var loaded = false
    /** Groups the entries of one request (a fast-path command or an agent task). */
    @Volatile var currentTask: Long = 0L; private set

    fun init(c: Context) { ctx = c.applicationContext }

    fun newTask(): Long { currentTask = System.currentTimeMillis(); return currentTask }

    private fun file(): File? = ctx?.let { File(it.filesDir, "audit/audit.enc") }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = file()?.let { SecureStore.read(it) } ?: return
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                entries.add(AuditEntry(o.optLong("t"), o.optLong("task"), o.optString("src"), o.optString("kind"), o.optString("text")))
            }
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun record(source: String, kind: String, text: String, task: Long = currentTask) {
        if (text.isBlank() || !com.tekyerema.core.config.FeatureFlags.isEnabled("J-12", true)) return
        ensureLoaded()
        entries.add(AuditEntry(System.currentTimeMillis(), task, source, kind, text.trim().take(160)))
        prune()
        save()
    }

    @Synchronized
    fun recent(limit: Int = 10): List<AuditEntry> { ensureLoaded(); return entries.takeLast(limit).reversed() }

    @Synchronized
    fun since(ms: Long): List<AuditEntry> { ensureLoaded(); return entries.filter { it.t >= ms } }

    /** The entries of the most recent request that did something (newest request first). */
    @Synchronized
    fun lastTask(): List<AuditEntry> {
        ensureLoaded()
        val task = entries.lastOrNull()?.task ?: return emptyList()
        return entries.filter { it.task == task }
    }

    @Synchronized
    fun clear() { entries.clear(); loaded = true; try { file()?.delete() } catch (_: Throwable) {} }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - com.tekyerema.core.Privacy.historyDays() * 86_400_000L
        entries.removeAll { it.t < cutoff }
        val max = com.tekyerema.core.config.AppConfig.int("audit.max", 300)
        while (entries.size > max) entries.removeAt(0)
    }

    private fun save() {
        val f = file() ?: return
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("t", it.t).put("task", it.task).put("src", it.source).put("kind", it.kind).put("text", it.text)) }
        SecureStore.write(f, arr.toString())
    }

    // ---------------------------------------------------------------- speech (pure)

    /** "What did you just do?" — the last request's entries as one or two sentences. */
    fun spokenLast(task: List<AuditEntry>): String {
        if (task.isEmpty()) return "I haven't done anything yet."
        val done = task.filter { it.kind == DONE }.map { it.text }
        val declined = task.filter { it.kind == DECLINED }.map { it.text.trimEnd('?', '.', ' ') }
        val failed = task.filter { it.kind == FAILED || it.kind == BLOCKED }.map { it.text }
        return buildList {
            if (done.isNotEmpty()) add("I " + joinAnd(done.distinct().take(4)) + ".")
            if (declined.isNotEmpty()) add("You said no to: " + declined.distinct().take(2).joinToString("; ") + " — so I didn't.")
            if (failed.isNotEmpty() && done.isEmpty()) add("I tried, but it didn't work: " + failed.distinct().take(2).joinToString("; ") + ".")
        }.joinToString(" ").ifBlank { "I only looked — I didn't change anything." }
    }

    /** "What have you done today?" — a count and the latest few. */
    fun spokenToday(today: List<AuditEntry>): String {
        val done = today.filter { it.kind == DONE }
        if (done.isEmpty()) return "I haven't done anything for you today."
        val latest = done.takeLast(3).reversed().map { it.text }
        return "Today I did ${done.size} thing${if (done.size == 1) "" else "s"}. Most recently, I " + joinAnd(latest) + "."
    }

    internal fun joinAnd(items: List<String>): String = when (items.size) {
        0 -> ""; 1 -> items[0]; else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}

/**
 * Plain past-tense words for an action, from its tool name and arguments (pure; unit-tested). Message and typed text
 * are never repeated — only who it went to.
 */
object ActionWords {
    fun describe(tool: String, args: JSONObject, element: String? = null): String {
        fun a(k: String) = args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        val who = a("contact") ?: a("sender") ?: a("number")?.let { spaced(it) }
        return when (tool) {
            "make_call" -> "called ${who ?: "someone"}"
            "send_sms" -> "texted ${who ?: "someone"}"
            "reply_to_notification" -> "replied to ${who ?: "a message"}"
            "open_app" -> "opened ${a("app") ?: "an app"}"
            "set_alarm" -> "set an alarm" + (a("time")?.let { " for $it" } ?: a("hour")?.let { " for $it:${a("minute")?.padStart(2, '0') ?: "00"}" } ?: "")
            "set_timer" -> "set a timer"
            "set_volume", "volume_up", "volume_down" -> "changed the volume"
            "open_setting" -> "changed ${a("setting") ?: "a setting"}"
            "momo_send" -> "started sending ${a("amount")?.let { "$it cedis" } ?: "money"} to ${a("number")?.let { spaced(it) } ?: "a number"}"
            "mobile_money" -> "opened Mobile Money"
            "run_ussd" -> "dialled a USSD code"
            "emergency" -> "called and texted your emergency contact"
            "create_contact" -> "saved a contact" + (a("name")?.let { " for $it" } ?: "")
            "calendar" -> if (a("action") == "add") "added a calendar event" else "checked your calendar"
            "share_text" -> "shared some text"
            "clipboard" -> "used the clipboard"
            "notification_action" -> "pressed a notification button"
            "call_control" -> if (a("action") == "end") "ended the call" else "answered the call"
            "play_music" -> "played music"
            "tap" -> "tapped " + (element?.let { "“${it.take(40)}”" } ?: a("label")?.let { "“${it.take(40)}”" } ?: "the screen")
            "long_press" -> "pressed and held " + (element ?: a("label") ?: "an item").take(40)
            "type" -> "typed some text"
            "scroll", "swipe" -> "scrolled"
            "key_event" -> "pressed " + (a("key")?.replace('_', ' ') ?: "a key")
            "node_action" -> "used ${a("action")?.removePrefix("custom:") ?: "an action"}"
            else -> tool.replace('_', ' ')
        }
    }

    /** J-13: what an irreversible action is called in "I can't undo …". */
    fun noun(tool: String, args: JSONObject): String {
        fun a(k: String) = args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        val who = a("contact") ?: a("sender") ?: a("number")?.let { spaced(it) }
        return when (tool) {
            "make_call" -> "the call to ${who ?: "that number"}"
            "send_sms", "reply_to_notification" -> "the message to ${who ?: "them"}"
            "momo_send", "mobile_money" -> "the money transfer"
            "emergency" -> "the emergency call and text"
            "run_ussd" -> "the USSD request"
            "create_contact" -> "the new contact"
            "share_text" -> "what I shared"
            else -> "what I did with ${tool.replace('_', ' ')}"
        }
    }

    /** A phone number spoken digit by digit. */
    private fun spaced(n: String) = n.filter { it.isDigit() || it == '+' }.toList().joinToString(" ")
}
