package com.tekyerema.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How a finished request actually ended (§P1 — a blind user must hear the truth, not a blanket "not completed"). */
enum class Outcome { COMPLETED, UNVERIFIED, CANNOT, FAILED, STOPPED;
    val done: Boolean get() = this == COMPLETED || this == UNVERIFIED   // an action fired (verified or not)
    /** Short honest label shown under the row; empty for a cleanly-verified completion. */
    val label: String get() = when (this) {
        COMPLETED -> ""
        UNVERIFIED -> "Done — but I couldn't confirm it"
        CANNOT -> "Couldn't do that"
        FAILED -> "Didn't work"
        STOPPED -> "Cancelled"
    }
}

/** One finished request: when, a short human title, and how it ended. */
data class HistoryEntry(val timeMs: Long, val title: String, val outcome: Outcome)

/**
 * The History screen's only data: a short list of what Tɛkyerɛma did, newest first. Deliberately
 * not an activity log — no steps, stats or reasoning; those stay in the trace files.
 *
 * J-15: the titles are the user's own requests, so the list is encrypted at rest ([com.tekyerema.core.SecureStore]),
 * kept for `privacy.retention.historyDays` (90) and can be cleared by voice or in Settings. Rows written by older
 * builds to plain SharedPreferences are moved into the encrypted file once, then removed.
 */
object HistoryStore {
    private const val PREFS = "tekyerema_history"
    private const val KEY = "entries"
    private const val MAX = 200

    private var appContext: Context? = null
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _entries.value = prune(load())
    }

    private fun file(): File? = appContext?.let { File(it.filesDir, "history/history.enc") }

    /** The debug build's scripted test scenarios ("[TEST SCENARIO] STOP_MID_TASK") are not the user's history. */
    fun isTestEntry(title: String): Boolean = title.trimStart().startsWith("[TEST SCENARIO]", ignoreCase = true)

    fun record(goal: String, outcome: Outcome, timeMs: Long = System.currentTimeMillis()) {
        if (isTestEntry(goal)) return
        val entry = HistoryEntry(timeMs, titleFor(goal, outcome.done), outcome)
        val next = prune(listOf(entry) + _entries.value)
        _entries.value = next
        save(next)
    }

    /** J-15: "clear my history". */
    fun clear() {
        _entries.value = emptyList()
        try { file()?.delete() } catch (_: Throwable) {}
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(KEY)?.apply()
    }

    private fun prune(list: List<HistoryEntry>): List<HistoryEntry> {
        val cutoff = System.currentTimeMillis() - com.tekyerema.core.Privacy.historyDays() * 86_400_000L
        return list.filter { it.timeMs >= cutoff }.take(MAX)
    }

    /**
     * "call Prince" → "Called Prince" when it completed; otherwise the request as spoken, so the
     * row never claims something happened that did not.
     */
    fun titleFor(goal: String, ok: Boolean): String {
        var g = goal.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (g.isEmpty()) return "—"
        if (ok) {
            val words = g.split(" ")
            val past = PAST[words[0].lowercase()]
            if (past != null) g = (listOf(past) + words.drop(1)).joinToString(" ")
        }
        g = g.replaceFirstChar { it.uppercase() }
        return if (g.length > 72) g.take(70).trimEnd() + "…" else g
    }

    private val PAST = mapOf(
        "call" to "Called", "phone" to "Phoned", "ring" to "Rang", "dial" to "Dialled",
        "open" to "Opened", "read" to "Read", "set" to "Set", "send" to "Sent", "text" to "Texted",
        "message" to "Messaged", "reply" to "Replied", "find" to "Found", "check" to "Checked",
        "turn" to "Turned", "switch" to "Switched", "play" to "Played", "search" to "Searched",
        "increase" to "Increased", "decrease" to "Decreased", "raise" to "Raised", "lower" to "Lowered",
        "mute" to "Muted", "unmute" to "Unmuted", "show" to "Showed", "tell" to "Told", "put" to "Put",
        "start" to "Started", "stop" to "Stopped", "cancel" to "Cancelled", "delete" to "Deleted",
        "add" to "Added", "save" to "Saved", "take" to "Took", "buy" to "Bought", "pay" to "Paid",
        "transfer" to "Transferred", "enable" to "Enabled", "disable" to "Disabled", "go" to "Went",
        "wake" to "Woke", "remind" to "Reminded", "change" to "Changed", "close" to "Closed"
    )

    private fun load(): List<HistoryEntry> {
        val ctx = appContext ?: return emptyList()
        file()?.let { com.tekyerema.core.SecureStore.read(it) }?.let { return parse(it) }
        // One-time move from the plain SharedPreferences an older build wrote.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getString(KEY, null) ?: return emptyList()
        val rows = parse(legacy)
        if (rows.isEmpty() || writeEncrypted(rows)) prefs.edit().remove(KEY).apply()
        return rows
    }

    internal fun parse(text: String): List<HistoryEntry> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // Back-compat: old rows stored only a boolean "ok".
            val outcome = o.optString("outcome").takeIf { it.isNotBlank() }?.let { runCatching { Outcome.valueOf(it) }.getOrNull() }
                ?: if (o.optBoolean("ok", true)) Outcome.COMPLETED else Outcome.FAILED
            HistoryEntry(o.getLong("t"), o.getString("title"), outcome)
        }
    } catch (_: Throwable) { emptyList() }

    private fun save(list: List<HistoryEntry>) { writeEncrypted(list) }   // no key → the list lives in memory only

    private fun writeEncrypted(list: List<HistoryEntry>): Boolean {
        val f = file() ?: return false
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.timeMs).put("title", it.title).put("outcome", it.outcome.name)) }
        return com.tekyerema.core.SecureStore.write(f, arr.toString())
    }
}
