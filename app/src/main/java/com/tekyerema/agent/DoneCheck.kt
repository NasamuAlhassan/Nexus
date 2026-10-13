package com.tekyerema.agent

/**
 * P1-14 (D-017, 13 October 2026) — a navigation request ends on its page. "open Settings and go to the battery page" whose
 * last step verified only that Settings was in front (`foreground_is`) could be reported done from the Settings LIST — the
 * false pass the battery scenario showed in P1-01. Before a "done" is accepted, the LAST page the request named must be in
 * the heading of the final screen: its window title or its first few labels (the Settings list shows a "Battery" row, so the
 * body of the screen does not count). Deliberately narrow: only requests that name a page ([LocalBrain.inAppNavTarget]),
 * only the words of that page, matched as whole words or 4-letter stems ("battery"/"Battery", "about"/"About emulated
 * device", "wi"+"fi"/"Wi-Fi"). Nothing to name → nothing to check. Pure.
 */
object DoneCheck {
    data class NotThere(val target: String, val shown: String) {
        /** What the planner is told, once. */
        val note: String get() = "The screen in front is \"$shown\", not the $target page. Finish getting there, or say what is in the way."
        /** The log's reason when the reply is then marked unconfirmed. */
        val why: String get() = "the request's page '$target' is not on the final screen ('$shown')"
    }

    private val SPLIT = Regex("(?i)\\s*(?:,|;|\\band\\s+then\\b|\\bthen\\b|\\band\\b)\\s*")
    private val WORD = Regex("[^\\p{L}\\p{N}]+")
    private val STOP = setOf("the", "a", "an", "to", "of", "my", "me", "your", "page", "screen", "tab", "section", "menu",
        "setting", "settings", "option", "options", "panel", "phone", "device", "app", "please")

    /** The pages the request names, in order ("open settings and go to the battery page" → ["battery"]). */
    fun targets(goal: String): List<String> =
        goal.split(SPLIT).mapNotNull { c -> c.trim().takeIf { it.isNotEmpty() }?.let { LocalBrain.inAppNavTarget(it) } }.distinct()

    /**
     * Null when the request names no page, or its last page is in the heading; otherwise what is missing and what the
     * screen shows instead. [heading] = the final screen's first labels (only the first three count, with the window title).
     */
    fun check(goal: String, screenTitle: String?, heading: List<String>): NotThere? {
        val target = targets(goal).lastOrNull() ?: return null
        val wanted = tokens(target).filter { it !in STOP }
        if (wanted.isEmpty()) return null
        val shown = (listOfNotNull(screenTitle) + heading.take(3)).flatMap { tokens(it) }.toSet()   // the heading only: the body may list the page
        val there = wanted.any { w -> shown.any { s -> s == w || (w.length >= 4 && s.startsWith(w.take(4))) || (s.length >= 4 && w.startsWith(s.take(4))) } }
        if (there) return null
        val label = screenTitle?.trim()?.takeIf { it.isNotEmpty() } ?: heading.firstOrNull { it.isNotBlank() }?.trim() ?: "an unnamed screen"
        return NotThere(target, label.take(60))
    }

    private fun tokens(s: String): List<String> = s.lowercase().split(WORD).filter { it.length >= 2 }
}
