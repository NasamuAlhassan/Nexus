package com.tekyerema.agent

/**
 * A "done" reply may only claim what this task actually did. On the A20e (2026-09-29) "open Chrome and search for Kwame
 * Nkrumah" ran ONE step — open_app — and the planner's reply was "I opened Chrome and searched for Kwame Nkrumah."; the
 * only state-changing step (opening Chrome) had verified, so the claim passed as a completion. Earlier the same evening
 * "Playing the playlist…" was said after a play that failed.
 *
 * Deliberately narrow, so ordinary answers are untouched: a claim counts only when it is the assistant's own (first person
 * — "I searched", "I've sent" — or a reply that opens with the verb: "Sent it to Ama."), it isn't negated ("I couldn't
 * send"), and the REQUEST asked for that kind of action ("…and search for…"). Then a successful step of a matching tool must
 * be in the history. Pure.
 */
object ClaimCheck {
    private class Family(val noun: String, val goal: Regex, val claim: String, val evidence: Set<String>)

    private val I = RegexOption.IGNORE_CASE
    private val FAMILIES = listOf(
        Family("search", Regex("\\b(?:search|look (?:it |this |that )?up|google)\\b", I), "searched|looked (?:it |this |that )?up",
            setOf("web_search", "type", "set_text", "open_url", "search_contacts", "search_messages", "play_music")),
        Family("message", Regex("\\b(?:send|text|message|whatsapp|reply|tell)\\b", I), "sent|texted|messaged|replied",
            setOf("send_sms", "reply_to_notification", "share_text", "type", "set_text", "tap", "momo_send", "mobile_money")),
        Family("call", Regex("\\b(?:call|ring|dial)\\b", I), "called|dialled|dialed|rang",
            setOf("make_call", "tap", "run_ussd")),
        Family("play", Regex("\\bplay\\b", I), "played|playing|started playing",
            setOf("play_music", "tap", "key_event", "media_control")),
        Family("install", Regex("\\b(?:install|download)\\b", I), "installed|downloaded|installing|downloading",
            setOf("tap", "install_app")),
        Family("typing", Regex("\\b(?:type|write)\\b", I), "typed|wrote",
            setOf("type", "set_text")),
        Family("alarm", Regex("\\b(?:alarm|timer|remind)", I), "set (?:an? |the |your )?(?:alarm|timer|reminder)",
            setOf("set_alarm", "set_timer", "create_schedule"))
    )
    private val NEGATION = Regex("\\b(?:couldn'?t|could not|didn'?t|did not|not|unable|failed|wasn'?t|haven'?t|won'?t|can'?t|cannot|without|never)\\b[^.!?]{0,25}$", I)

    data class Unsupported(val noun: String, val said: String) {
        /** What the planner is told (the done is refused once). */
        val note: String get() = "NOT DONE: your reply says you $said, but no step in this task did that — nothing was " +
            "${if (noun == "search") "searched" else "done"} for the '$noun' part. Do it now (look at the screen first), or say plainly what you did and didn't do."
        /** What the user hears if it happens again. */
        val honestLine: String get() = "I didn't finish that — the $noun part didn't happen. Please ask me again."
    }

    /**
     * The first claim in [summary] with no supporting step in [history], or null. [screen] = the text of the screen the
     * reply was written on (title + labels): for a search, results for the asked words already showing count as done — on
     * the A20e the page was still open from an earlier run ("Kwame Nkrumah - Google Search"), and refusing that helped nobody.
     */
    fun check(goal: String, summary: String, history: List<String>, screen: String? = null): Unsupported? {
        if (summary.trim().endsWith("?")) return null
        for (f in FAMILIES) {
            if (!f.goal.containsMatchIn(goal)) continue
            val said = firstPersonClaim(summary, f.claim) ?: continue
            val supported = history.any { h -> f.evidence.any { t -> h.startsWith("$t(") } && h.contains("→ success") } ||
                (f.noun == "search" && searchShowing(goal, screen))
            if (!supported) return Unsupported(f.noun, said.lowercase())
        }
        return null
    }

    private val SEARCH_TERMS = Regex("\\b(?:search(?: the web| google| online)?(?: for)?|look up|google)\\s+(.+)$", I)

    /** Every word (3+ letters) of what the goal asked to search for appears on [screen]. Pure. */
    internal fun searchShowing(goal: String, screen: String?): Boolean {
        val s = screen?.lowercase() ?: return false
        val terms = SEARCH_TERMS.find(goal.trim().trimEnd('.', '!', '?'))?.groupValues?.get(1) ?: return false
        val words = terms.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in setOf("the", "and", "for", "about") }
        return words.isNotEmpty() && words.all { it in s }
    }

    private fun firstPersonClaim(summary: String, verbs: String): String? {
        val firstPerson = Regex("\\bI(?:'ve| have| just| also| then)?\\b[^.!?]{0,60}?\\b($verbs)\\b", I)
        val opening = Regex("(?:^|[.!?]\\s+)(?:okay,?\\s+|done[,.]?\\s+)?($verbs)\\b", I)
        for (re in listOf(firstPerson, opening)) {
            for (m in re.findAll(summary)) {
                val before = summary.substring(0, m.groups[1]!!.range.first)
                if (NEGATION.containsMatchIn(before)) continue
                return m.groups[1]!!.value
            }
        }
        return null
    }
}
