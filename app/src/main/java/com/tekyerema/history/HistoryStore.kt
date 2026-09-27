package com.tekyerema.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

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
 */
object HistoryStore {
    private const val PREFS = "tekyerema_history"
    private const val KEY = "entries"
    private const val MAX = 200

    private var prefs: android.content.SharedPreferences? = null
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _entries.value = load()
    }

    fun record(goal: String, outcome: Outcome, timeMs: Long = System.currentTimeMillis()) {
        val entry = HistoryEntry(timeMs, titleFor(goal, outcome.done), outcome)
        val next = (listOf(entry) + _entries.value).take(MAX)
        _entries.value = next
        save(next)
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

    private fun load(): List<HistoryEntry> = try {
        val arr = JSONArray(prefs?.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // Back-compat: old rows stored only a boolean "ok".
            val outcome = o.optString("outcome").takeIf { it.isNotBlank() }?.let { runCatching { Outcome.valueOf(it) }.getOrNull() }
                ?: if (o.optBoolean("ok", true)) Outcome.COMPLETED else Outcome.FAILED
            HistoryEntry(o.getLong("t"), o.getString("title"), outcome)
        }
    } catch (_: Throwable) { emptyList() }

    private fun save(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.timeMs).put("title", it.title).put("outcome", it.outcome.name)) }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}
