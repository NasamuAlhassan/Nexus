package com.tekyerema.agent

import com.tekyerema.history.HistoryEntry
import com.tekyerema.history.Outcome

/**
 * H-10 — "where did I leave off?" / "where was I?". A call, a low battery or a knock on the door cuts a blind user off
 * mid-task, and nothing on the phone tells them afterwards what they were doing: no mainstream assistant answers this
 * (the closest, Siri's personal context, searches your messages — not your own last request). The phone already knows:
 * the History list (how the last request ended), the paused screen reading, and the app that was in front. This puts
 * them into one honest sentence and says how to go on. It never re-runs anything by itself — a request that was
 * stopped at a confirmation must be asked for (and confirmed) again. Pure, unit-tested.
 */
object LeftOff {
    private val PHRASES = Regex("^(?:(?:hey |okay |ok )?(?:tekyerema|tɛkyerɛma)[, ]+)?(?:" +
        "where (?:did|do) (?:i|we) leave off|where (?:was i|were we)|what was i doing|what were we doing|" +
        "where did we stop|where did i stop|what did i ask (?:you )?last|what was my last request|" +
        "(?:pick up|carry on|continue) (?:from )?where (?:i|we) (?:left off|stopped)" +
        ")(?: please)?$")

    fun matches(g: String): Boolean = PHRASES.matches(g)

    /**
     * [last] = the newest History entry (null when there is none), [readingPending] = a screen reading was cut into
     * parts and more remains, [lastApp] = the app the user was in before Tɛkyerɛma came up (null when unknown).
     */
    fun line(last: HistoryEntry?, readingPending: Boolean, lastApp: String?, nowMs: Long, resumable: String? = null): String {
        val parts = ArrayList<String>()
        if (lastApp != null) parts.add("You were in $lastApp.")
        if (readingPending) parts.add("I was partway through reading the screen to you — say “continue” to hear the rest.")
        // WS-A: a paused or interrupted task on file (TaskJournal) is what they were doing — named first, with how to go on.
        if (resumable != null) return (parts + resumable).joinToString(" ")
        if (last != null) {
            val ago = ago(nowMs - last.timeMs)
            parts.add(if (last.outcome.done) {
                val tail = if (last.outcome == Outcome.UNVERIFIED) ", but I couldn't confirm it worked" else ""
                "The last thing I did, $ago: ${lowerFirst(last.title)}$tail."
            } else {
                val how = when (last.outcome) {
                    Outcome.STOPPED -> "it was stopped before it finished"
                    Outcome.CANNOT -> "I couldn't do it"
                    Outcome.PAUSED -> "it was paused before it finished"
                    Outcome.INTERRUPTED -> "it was interrupted before it finished"
                    else -> "it didn't work"
                }
                "Your last request, $ago, was “${last.title}” — $how. Say it again and I'll try again."
            })
        }
        return if (parts.isEmpty()) "There's nothing to pick up — you haven't asked me to do anything recently." else parts.joinToString(" ")
    }

    private fun lowerFirst(s: String) = if (s.length > 1 && s[1].isLowerCase()) s.replaceFirstChar { it.lowercaseChar() } else s

    fun ago(ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min == 1L -> "a minute ago"
            min < 60 -> "$min minutes ago"
            min < 120 -> "an hour ago"
            min < 24 * 60 -> "${min / 60} hours ago"
            min < 48 * 60 -> "yesterday"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}
