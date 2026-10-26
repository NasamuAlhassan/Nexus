package com.tekyerema.money.ussd

import com.tekyerema.core.config.AppConfig

/**
 * The free-form menu walk (TxIntent.GOAL) used to ask the cloud brain about EVERY menu screen — one slow call each, and
 * nothing at all offline. Most screens are obvious: one row carries the goal's own words ("Yello Save", "School Fees").
 * This picks such a row on the phone, in milliseconds, with no network; the cloud (and the user) keep every screen that is
 * not obvious. Two levels, both only ever naming a row that is really on the screen:
 *
 *  1. WORDS — the goal's distinctive words (not "send", "my", "cedis", the amount…) against the rows' words, spelling
 *     tolerant ("yellow" ~ "yello"). A row that is the only one naming one of them wins.
 *  2. MEANING — only when the cloud can't answer (offline, no key, a stalled link): the phone's own sentence embedder
 *     (the one the semantic matcher uses) ranks the rows against the goal, and only a clear winner is pressed.
 *
 * A row about cash-out, loans, insurance, agents or banks is never picked unless the goal itself says so, and navigation
 * rows (Back, More…) never win. Pressing a menu row moves no money: the network's own confirmation and the user's PIN
 * still come after it. Pure but for the injected embedder → GoalRowsTest.
 */
object GoalRows {
    private val GENERIC = setOf(
        "send", "sending", "pay", "paying", "buy", "buying", "transfer", "deposit", "put", "move", "give", "get", "make", "check", "see", "show",
        "cedi", "cedis", "ghs", "pesewa", "pesewas", "money", "momo", "mobile", "wallet", "account", "mtn", "telecel", "airteltigo", "network",
        "the", "and", "for", "from", "with", "into", "onto", "that", "this", "some", "please", "want", "need", "like", "would", "could", "can",
        "you", "your", "his", "her", "their", "our", "mine", "myself", "now", "today", "also", "just", "then", "have", "has", "had", "use", "using")
    private val NAV_ROW = Regex("(?i)^(?:back|more|next|previous|exit|quit|main menu|home)\\W*$|^#")
    private val RISKY = Regex("(?i)\\b(cash ?out|withdraw\\w*|loans?|borrow\\w*|insurance|agent|bank|reversal)\\b")

    /** Letters-only words of [s], lower case. */
    private fun words(s: String): List<String> = s.lowercase().replace('&', ' ').split(Regex("[^\\p{L}]+")).filter { it.length >= 2 }

    /** The goal's distinctive words: no verbs of paying, no money words, no politeness, no numbers. */
    internal fun distinctive(goal: String): List<String> = words(goal).filter { it.length >= 3 && it !in GENERIC }.distinct()

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    /** "save" / "saves" / "saving" / "savings" share a stem. */
    private fun stem(w: String): String = w.removeSuffix("ings").removeSuffix("ing").removeSuffix("es").removeSuffix("s").removeSuffix("e")

    /** The two words are the same word as the recogniser may spell it ("yellow" / "yello", "savings" / "saving"). */
    internal fun alike(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) < 4) return false
        if (a.startsWith(b) || b.startsWith(a)) return true
        stem(a).let { sa -> if (sa.length >= 3 && sa == stem(b)) return true }
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length) >= 0.78
    }

    private fun usable(goal: String, rows: List<Pair<String, String>>): List<Pair<String, String>> {
        val g = goal.lowercase()
        return rows.filter { (_, w) ->
            !NAV_ROW.matches(w.trim()) && RISKY.findAll(w).none { m -> !g.contains(m.value.lowercase().take(4)) }
        }
    }

    /** Level 1: the one row that names the goal's own words, or null when none does or several do. */
    fun pickByWords(goal: String, rows: List<Pair<String, String>>): String? {
        val want = distinctive(goal)
        if (want.isEmpty()) return null
        val scored = usable(goal, rows).map { (n, w) ->
            val rw = words(w)
            n to want.count { g -> rw.any { alike(g, it) } }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val top = scored.firstOrNull() ?: return null
        if (scored.size > 1 && scored[1].second >= top.second) return null     // two rows name it: not obvious — the cloud or the user decides
        return top.first
    }

    /** Level 2 (offline): the row whose meaning is clearly closest to the goal, or null when none is clearly closest. */
    fun pickByMeaning(goal: String, rows: List<Pair<String, String>>, embed: ((String) -> FloatArray)?,
                      minScore: Double = AppConfig.double("ussd.goal.localMinScore", 0.62),
                      minMargin: Double = AppConfig.double("ussd.goal.localMinMargin", 0.12)): String? {
        if (embed == null || !AppConfig.bool("ussd.goal.localMeaning", true)) return null
        val ask = distinctive(goal).joinToString(" ").ifBlank { return null }
        val g = try { embed(ask) } catch (_: Throwable) { return null }
        val scored = usable(goal, rows).mapNotNull { (n, w) ->
            val v = try { embed(words(w).joinToString(" ")) } catch (_: Throwable) { return@mapNotNull null }
            n to v.indices.sumOf { (g[it] * v[it]).toDouble() }
        }.sortedByDescending { it.second }
        val top = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0.0
        return top.first.takeIf { top.second >= minScore && top.second - second >= minMargin }
    }
}
