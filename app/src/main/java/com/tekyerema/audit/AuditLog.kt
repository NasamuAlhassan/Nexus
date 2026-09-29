package com.tekyerema.audit

import android.content.Context
import com.tekyerema.core.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * J-12 — a history of every action Tɛkyerɛma took or proposed, in plain words the user can ask for by voice
 * ("what did you just do?", "what have you done today?"). Covers actions DONE and PLANNED (a proposal the user
 * declined or changed), from the fast path, the agent and unattended triggers. Encrypted at rest ([SecureStore]),
 * capped (`audit.max`) and kept for `privacy.retention.historyDays` (the user may shorten it).
 *
 * Workstream C made it a structured, tamper-evident trail. Each entry records — where they apply — the task and step,
 * the tool, its risk tier and scope, the policy outcome and reason, the payload's provenance, the model or rule that
 * decided, the confirmation number and the user's answer, the verification verdict and the outcome ([AuditHooks] is what
 * the agent loop calls). Entries are hash-chained ([AuditChain]); [verify] detects an edited, removed or reordered entry.
 * Storage is append-only: one encrypted line per entry ("audit/audit.log"), so recording a step never rewrites the file;
 * only the retention/size limit rewrites it, keeping the chain's anchor.
 */
data class AuditEntry(
    val t: Long,
    val task: Long,
    val source: String,
    val kind: String,
    /** Plain words for the user (may name a person or quote a message — personal; redacted in exports by default). */
    val text: String,
    val step: Int = 0,
    val tool: String = "",
    val tier: String = "",
    val scope: String = "",
    /** ALLOW / CONFIRM / BLOCK when this entry is a policy event. */
    val policy: String = "",
    val reason: String = "",
    /** Where the payload came from ("text=untrusted(copied from screen)"), empty when all from the user's goal. */
    val provenance: String = "",
    /** The model ("groq/openai/gpt-oss-120b") or rule ("on-device rules") that chose this action. */
    val decidedBy: String = "",
    /** The confirmation's number (J-02), 0 when none. */
    val confirmSeq: Long = 0,
    val approved: Boolean? = null,
    val verified: Boolean? = null,
    val outcome: String = "",
    val nonce: String = "",
    val prev: String = "",
    val hash: String = ""
)

object AuditLog {
    const val DONE = "done"; const val PLANNED = "planned"; const val DECLINED = "declined"; const val BLOCKED = "blocked"; const val FAILED = "failed"
    /** WS-C: the structured events around one step, and the task around them. */
    const val PROPOSED = "proposed"; const val APPROVED = "approved"; const val VERIFIED = "verified"; const val UNVERIFIED = "unverified"
    const val TASK_START = "task_start"; const val TASK_END = "task_end"
    /** Allowed, but held back just before running (the screen changed, an overlay covered the target…); the model re-plans. */
    const val NOT_RUN = "not_run"
    /** The user changed one of their controls (Settings › Control or by voice): "switched the cloud off". */
    const val SETTING = "setting"
    /** The first entry after the user deleted the log (the chain restarts; nothing personal in it). */
    const val CLEARED = "cleared"

    private const val MAX_TEXT = 200

    @Volatile private var dir: File? = null
    private val entries = ArrayList<AuditEntry>()
    private var anchor = AuditChain.GENESIS
    private var unreadable = 0
    @Volatile private var loaded = false
    /** Test seam: the clock entries are stamped with. */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    /** Groups the entries of one request (a fast-path command or an agent task). */
    @Volatile var currentTask: Long = 0L; private set

    fun init(c: Context) { dir = File(c.applicationContext.filesDir, "audit") }

    /** Unit tests: keep the log in [d] (null = memory only) and start empty. */
    @Synchronized internal fun initDir(d: File?) {
        dir = d; entries.clear(); anchor = AuditChain.GENESIS; unreadable = 0; loaded = false; currentTask = 0L
    }

    fun newTask(): Long { currentTask = clock(); return currentTask }

    private fun logFile(): File? = dir?.let { File(it, "audit.log") }
    private fun legacyFile(): File? = dir?.let { File(it, "audit.enc") }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = logFile()
        if (f != null && f.exists()) {
            try {
                f.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val o = decode(line)
                    when {
                        o == null -> unreadable++
                        o.has("anchor") -> anchor = o.optString("anchor", AuditChain.GENESIS)
                        else -> entries.add(AuditJson.fromJson(o))
                    }
                }
            } catch (_: Throwable) {}
        } else migrateLegacy()
        prune(force = true)
    }

    /** The v1 log (one encrypted JSON array, no chain) becomes the start of the chain, once. */
    private fun migrateLegacy() {
        val old = legacyFile() ?: return
        val text = SecureStore.read(old) ?: return
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                val e = AuditEntry(o.optLong("t"), o.optLong("task"), o.optString("src"), o.optString("kind"), o.optString("text"),
                    outcome = "migrated", nonce = AuditChain.newNonce())
                entries.add(AuditChain.seal(e, entries.lastOrNull()?.hash ?: anchor))
            }
            if (rewrite()) old.delete()
        } catch (_: Throwable) {}
    }

    /** J-12 (unchanged API): an entry in plain words. Blank text is ignored. */
    @Synchronized
    fun record(source: String, kind: String, text: String, task: Long = currentTask) {
        if (text.isBlank()) return
        // The fast path runs only reversible device controls, each through the same policy (FastGate, PolicyEngine).
        val fast = source == "fast"
        append(AuditEntry(0, task, source, kind, text, policy = if (fast) "ALLOW" else "",
            decidedBy = if (fast) "on-device fast path" else ""))
    }

    /** Add [draft] to the chain (time, nonce, link and hash are filled in here). Null when auditing is switched off. */
    @Synchronized
    fun append(draft: AuditEntry): AuditEntry? {
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled("J-12", true)) return null
        ensureLoaded()
        val e = AuditChain.seal(draft.copy(
            t = if (draft.t == 0L) clock() else draft.t,
            text = draft.text.trim().take(MAX_TEXT),
            reason = draft.reason.take(MAX_TEXT),
            nonce = AuditChain.newNonce()
        ), entries.lastOrNull()?.hash ?: anchor)
        entries.add(e)
        appendLine(e)
        prune(force = false)
        return e
    }

    @Synchronized
    fun recent(limit: Int = 10): List<AuditEntry> { ensureLoaded(); return entries.takeLast(limit).reversed() }

    @Synchronized
    fun since(ms: Long): List<AuditEntry> { ensureLoaded(); return entries.filter { it.t >= ms } }

    /** Every kept entry, oldest first. */
    @Synchronized
    fun all(): List<AuditEntry> { ensureLoaded(); return entries.toList() }

    @Synchronized
    fun count(): Int { ensureLoaded(); return entries.size }

    /** The hash the first kept entry links to (see [AuditChain]). */
    @Synchronized
    fun anchor(): String { ensureLoaded(); return anchor }

    /** The entries of the most recent request that did something (newest request first). */
    @Synchronized
    fun lastTask(): List<AuditEntry> {
        ensureLoaded()
        val task = entries.lastOrNull { it.kind != CLEARED }?.task ?: return emptyList()
        return entries.filter { it.task == task && it.kind != CLEARED }
    }

    /** Is the kept log exactly as it was written? */
    @Synchronized
    fun verify(): AuditChain.Check { ensureLoaded(); return AuditChain.verify(entries, anchor).copy(unreadable = unreadable) }

    /** Delete the log. The new chain starts with one "cleared" entry, so a missing past is visible rather than silent. */
    @Synchronized
    fun clear() {
        entries.clear(); anchor = AuditChain.GENESIS; unreadable = 0; loaded = true
        try { logFile()?.delete(); legacyFile()?.delete() } catch (_: Throwable) {}
        append(AuditEntry(0, 0, "user", CLEARED, "The activity log was cleared."))
    }

    /** Unit tests only: replace entry [i] as if the stored log had been edited. */
    @Synchronized internal fun tamperForTest(i: Int, edit: (AuditEntry) -> AuditEntry) { ensureLoaded(); entries[i] = edit(entries[i]) }

    // ---------------------------------------------------------------- storage
    private fun prune(force: Boolean) {
        val cutoff = clock() - com.tekyerema.core.Privacy.historyDays() * 86_400_000L
        val max = com.tekyerema.core.config.AppConfig.int("audit.max", 2000).coerceAtLeast(50)
        var old = 0
        while (old < entries.size && entries[old].t < cutoff) old++
        val over = entries.size - max
        // Retention is applied when the log is loaded (once per start); the size limit in batches, so an ordinary
        // append never rewrites the file.
        val drop = maxOf(if (force) old else 0, if (over >= PRUNE_BATCH || (force && over > 0)) over else 0)
        if (drop <= 0) return
        anchor = entries[drop - 1].hash
        entries.subList(0, drop).clear()
        rewrite()
    }

    private const val PRUNE_BATCH = 50

    private fun appendLine(e: AuditEntry) {
        val f = logFile() ?: return
        val line = encode(AuditJson.toJson(e)) ?: return   // no key: the entry lives in memory only (never in the clear)
        try {
            f.parentFile?.mkdirs()
            if (!f.exists() && anchor != AuditChain.GENESIS) { rewrite(); return }   // a fresh file must carry the anchor
            FileOutputStream(f, true).use { out ->
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                try { out.fd.sync() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** Write the whole log (anchor line + entries) atomically. */
    private fun rewrite(): Boolean {
        val f = logFile() ?: return false
        return try {
            val sb = StringBuilder()
            if (anchor != AuditChain.GENESIS) sb.append(encode(JSONObject().put("anchor", anchor)) ?: return false).append('\n')
            for (e in entries) sb.append(encode(AuditJson.toJson(e)) ?: return false).append('\n')
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            FileOutputStream(tmp).use { out -> out.write(sb.toString().toByteArray(Charsets.UTF_8)); try { out.fd.sync() } catch (_: Throwable) {} }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            true
        } catch (_: Throwable) { false }
    }

    private fun encode(o: JSONObject): String? =
        SecureStore.encrypt(o.toString().toByteArray(Charsets.UTF_8))?.let { java.util.Base64.getEncoder().encodeToString(it) }

    private fun decode(line: String): JSONObject? = try {
        SecureStore.decrypt(java.util.Base64.getDecoder().decode(line.trim()))?.let { JSONObject(it.toString(Charsets.UTF_8)) }
    } catch (_: Throwable) { null }

    // ---------------------------------------------------------------- speech (pure)

    /** "What did you just do?" — the last request's entries as one or two sentences. */
    fun spokenLast(task: List<AuditEntry>): String {
        if (task.isEmpty()) return "I haven't done anything yet."
        val done = task.filter { it.kind == DONE || it.kind == SETTING }.map { it.text }
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
            "web_search" -> "searched the web"
            else -> tool.replace('_', ' ')
        }
    }

    /**
     * WS-C: the same action as a PLAN, for proposals the user may not have allowed ("text Ama", "tap “Send”") — a
     * proposal that was blocked or declined must not read as something that happened.
     */
    fun proposal(tool: String, args: JSONObject, element: String? = null): String {
        fun a(k: String) = args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        val who = a("contact") ?: a("sender") ?: a("number")?.let { spaced(it) }
        return when (tool) {
            "make_call" -> "call ${who ?: "someone"}"
            "send_sms" -> "text ${who ?: "someone"}"
            "reply_to_notification" -> "reply to ${who ?: "a message"}"
            "open_app" -> "open ${a("app") ?: "an app"}"
            "set_alarm" -> "set an alarm"
            "set_timer" -> "set a timer"
            "set_volume", "volume_up", "volume_down" -> "change the volume"
            "open_setting" -> "change ${a("setting") ?: "a setting"}"
            "momo_send" -> "send ${a("amount")?.let { "$it cedis" } ?: "money"} to ${a("number")?.let { spaced(it) } ?: "a number"}"
            "mobile_money" -> "open Mobile Money"
            "run_ussd" -> "dial a USSD code"
            "emergency" -> "call and text your emergency contact"
            "create_contact" -> "save a contact"
            "calendar" -> if (a("action") == "add") "add a calendar event" else "check your calendar"
            "share_text" -> "share some text"
            "notification_action" -> "press a notification button"
            "call_control" -> if (a("action") == "end") "end the call" else "answer the call"
            "tap" -> "tap " + (element?.let { "“${it.take(40)}”" } ?: a("label")?.let { "“${it.take(40)}”" } ?: "the screen")
            "long_press" -> "press and hold " + (element ?: a("label") ?: "an item").take(40)
            "type" -> "type some text"
            "scroll", "swipe" -> "scroll"
            "key_event" -> "press " + (a("key")?.replace('_', ' ') ?: "a key")
            "web_search" -> "search the web"
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
