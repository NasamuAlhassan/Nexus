package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig

/**
 * I-03 / C-05 — web-first answers. A self-contained question that needs ONE fresh fact ("what's the weather in Accra
 * today", "cedi to dollar rate today") goes straight to `web_search` with the question as the query, and the search's
 * own short, sourced answer is what the user hears. No planner call before it (to decide to search) and none after it
 * (to repeat the answer) — one model round-trip instead of three.
 *
 * Why: the planner route cost ~8k tokens and three sequential cloud calls for one fact (A20e trace 08:18: 1.6 s plan →
 * 3.4 s search → 0.6 s restate), and on the stalled Wi-Fi of 2026-09-28 night the FIRST of them hung for 80 s, so the
 * weather question never even reached web_search (device_reports/20260928-233111, ws_weather_accra). Adaptive-RAG
 * (Jeong et al., NAACL 2024) shows the "does this need retrieval, and how much" decision can be made up front by a
 * cheap classifier — [RetrievalNeed] already makes it; this acts on it.
 *
 * Deliberately narrow: only a SINGLE lookup, only when the question stands on its own (no "it/that/there" that needs
 * the conversation to resolve), not Twi (the search model reads English best — the planner interprets Twi), and only a
 * sensible length. Anything else keeps the planner route. Pure → unit-tested.
 */
object WebFirst {
    /** Words that point back into the conversation — the planner must resolve them before anything is searched. */
    private val REFERS_BACK = Regex("(?i)\\b(it|its|it's|that|this|those|these|there|them|they|their|he|she|him|her|his|same|again|instead|also|too)\\b")
    private val TWI_LETTERS = Regex("[ɛɔƐƆŋ]")
    private val POLITE = Regex("(?i)^(?:please\\s+|hey\\s+|ok(?:ay)?\\s+|so\\s+)*(?:can|could|would) you (?:please )?(?:tell me|find out|check|look up)\\s+")

    fun enabled(): Boolean = com.tekyerema.core.config.FeatureFlags.isEnabled("I-03", true) &&
        AppConfig.bool("answers.search.webFirst", true)

    /** May this direct question skip the planner and go straight to the web? */
    fun eligible(goal: String, need: RetrievalNeed.Need): Boolean {
        if (need != RetrievalNeed.Need.SINGLE) return false
        val g = goal.trim()
        val words = g.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 3 || words.size > AppConfig.int("answers.search.webFirstMaxWords", 20)) return false
        if (TWI_LETTERS.containsMatchIn(g)) return false
        // "is it going to rain…", "will it be hot…": a weather "it" refers to nothing earlier.
        return !REFERS_BACK.containsMatchIn(g.replace(WEATHER_IT, " "))
    }

    private val WEATHER_IT = Regex("(?i)\\b(?:is|will|does|did|was) it (?:going to |gonna )?(?:rain|be|snow|get)\\b")

    /** The search query: the user's own question, minus a polite lead-in and the trailing question mark. */
    fun query(goal: String): String =
        goal.trim().replace(POLITE, "").trim().trimEnd('?', '.', '!', ' ').take(200).ifBlank { goal.trim().take(200) }

    /** What the user hears: the search's own answer (never its source list — "where did you get that?" reads those). */
    fun spoken(answer: String?): String? = answer?.trim()?.takeIf { it.length >= 2 }
}
