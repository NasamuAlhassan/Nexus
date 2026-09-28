package com.tekyerema.agent

import org.json.JSONObject

/**
 * K-15 — change-only narration. Whenever Tɛkyerɛma describes a screen it remembers what it said; "what changed?"
 * then speaks only the difference (a new message row, a dialog that appeared, an item that went) instead of
 * re-reading the whole screen. Labels only — no content is persisted, and it is forgotten when the process dies.
 */
object ScreenMemory {
    @Volatile var app: String? = null; private set
    @Volatile var labels: List<String> = emptyList(); private set

    fun remember(app: String?, labels: List<String>) {
        this.app = app
        this.labels = labels.distinct()
    }

    fun clear() { app = null; labels = emptyList() }

    /** From a compact screen JSON (what LocalBrain reads). */
    fun rememberJson(screen: JSONObject) {
        val els = screen.optJSONArray("elements")
        val ls = if (els == null) emptyList() else (0 until els.length()).mapNotNull { i ->
            els.optJSONObject(i)?.optString("label")?.trim()?.takeIf { it.length in 2..64 }
        }
        remember(screen.optString("appName").ifBlank { screen.optString("app") }.ifBlank { null }, ls)
    }
}

/** K-15: a short spoken summary of what differs between two readings of the screen. Pure → unit-tested. */
object ScreenDiff {
    fun describe(beforeApp: String?, before: List<String>, afterApp: String?, after: List<String>, max: Int = 4): String {
        if (before.isEmpty() && beforeApp == null) return "I haven't described this screen yet — ask me what's on it."
        if (afterApp != null && beforeApp != null && afterApp != beforeApp) return "You're now in $afterApp."
        val b = before.map { it.lowercase() }.toSet()
        val a = after.map { it.lowercase() }.toSet()
        val added = after.filter { it.lowercase() !in b }.distinct()
        val gone = before.filter { it.lowercase() !in a }.distinct()
        if (added.isEmpty() && gone.isEmpty()) return "Nothing has changed."
        val parts = ArrayList<String>()
        if (added.isNotEmpty()) parts.add("New: " + added.take(max).joinToString(", ") + if (added.size > max) ", and ${added.size - max} more" else "")
        if (gone.isNotEmpty()) parts.add("Gone: " + gone.take(max).joinToString(", ") + if (gone.size > max) ", and ${gone.size - max} more" else "")
        return parts.joinToString(". ") + "."
    }
}
