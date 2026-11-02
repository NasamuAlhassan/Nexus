package com.tekyerema.ui.home

import com.tekyerema.history.HistoryEntry
import com.tekyerema.history.Outcome

/**
 * "Your usual": the two or three things this person asks for again and again, as one-tap tiles on Home (design round 2,
 * 2 Nov 2026, docs/assistant-ux/REDESIGN_2026-11-02.md §7). For the trotro, the market, a quiet room — wherever talking out loud
 * is hard or private.
 *
 * What the research on adaptive menus says, and what follows from it here:
 *  - adaptation helps only when its guesses are right (Findlater et al.: faster at ~79 % accuracy, not at 50 %) → a tile needs
 *    the same request completed at least [MIN_COUNT] times in [WINDOW_DAYS] days, verified, not merely tried;
 *  - moving things people know where to find costs more than it saves (Gajos et al. 2006, Findlater & Gajos 2009: spatial
 *    stability; high-frequency adaptation hurts) → a tile never moves during the day: new ones may join at the end, the order is
 *    redone once a day ([settle]);
 *  - people want a say (Findlater & McGrenere 2004: most wanted a personalised menu) → any tile can be removed, and stays removed.
 *
 * Safety: nothing that spends money with an amount, and nothing destructive, ever becomes one tap. Pure, unit-tested.
 */
object Usuals {
    const val WINDOW_DAYS = 30
    const val MIN_COUNT = 2
    const val MAX = 3
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Usual(val key: String, val label: String, val count: Int, val lastMs: Long)

    private val MONEY_WORD = Regex("\\b(send|sent|transfer|pay|paid|momo|mobile money|airtime|bundle|buy|withdraw|cash ?out|loan|cedi|cedis|ghs|gh₵|₵)\\b",
        RegexOption.IGNORE_CASE)
    private val DIGIT = Regex("\\d")
    private val DESTRUCTIVE = Regex("\\b(delete|erase|uninstall|remove|reset|wipe|clear|format|block|forget)\\b", RegexOption.IGNORE_CASE)
    /** Words that answer something rather than ask for something: never a tile. */
    private val NOT_A_REQUEST = setOf("yes", "no", "ok", "okay", "stop", "cancel", "again", "more", "repeat", "slower", "thanks",
        "thank you", "hello", "hi", "done")

    /** One request however it was punctuated or capitalised: "Check my balance." == "check my  balance". */
    fun key(title: String): String = title.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Safe to run from one tap: no money with an amount, nothing destructive, and really a request. */
    fun safe(title: String): Boolean {
        val k = key(title)
        if (k.length < 4 || k in NOT_A_REQUEST) return false
        if (MONEY_WORD.containsMatchIn(title) && DIGIT.containsMatchIn(title)) return false
        if (DESTRUCTIVE.containsMatchIn(title)) return false
        return true
    }

    /** The tile's words: the latest wording, a capital first letter, no full stop. */
    fun label(title: String): String = title.trim().trimEnd('.', '!', ' ').replaceFirstChar { it.uppercaseChar() }

    /** Candidates, best first: verified completions in the window, asked at least [MIN_COUNT] times, not hidden by the user. */
    fun rank(entries: List<HistoryEntry>, nowMs: Long, hidden: Set<String> = emptySet()): List<Usual> {
        val from = nowMs - WINDOW_DAYS * DAY_MS
        return entries.asSequence()
            .filter { it.outcome == Outcome.COMPLETED && it.timeMs in from..nowMs && safe(it.title) }
            .groupBy { key(it.title) }
            .filterKeys { it !in hidden }
            .map { (k, list) ->
                val last = list.maxBy { it.timeMs }
                Usual(k, label(last.title), list.size, last.timeMs)
            }
            .filter { it.count >= MIN_COUNT }
            .sortedWith(compareByDescending<Usual> { it.count }.thenByDescending { it.lastMs })
    }

    /**
     * Today's tiles from yesterday's (keys, in order). [newDay]: the order is redone from [ranked]. Otherwise every tile keeps its
     * place (if it is still a candidate — a removed one goes), and new candidates may only join at the end.
     */
    fun settle(previous: List<String>, ranked: List<Usual>, newDay: Boolean): List<Usual> {
        if (newDay || previous.isEmpty()) return ranked.take(MAX)
        val byKey = ranked.associateBy { it.key }
        val kept = previous.mapNotNull { byKey[it] }
        val added = ranked.filter { it.key !in previous }
        return (kept + added).take(MAX)
    }
}
