package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig

/**
 * H-11 — a small cache of answers to general-knowledge questions (the direct-answer path, I-01).
 *
 *  - Online, a question asked again while its answer is FRESH is answered at once — no round trip, no quota (the
 *    free Groq tier is per-minute and per-day). Fresh = a week for settled facts ("what is the capital of Ghana"), half
 *    an hour for anything that needs current data (weather, prices, scores — [RetrievalNeed] decides).
 *  - Offline (or on a stalled link), an OLDER answer is still offered, with its age said out loud ("when I checked
 *    about 3 hours ago: …"), within `cache.answers.staleServeMs` — better than "I can't" for "what's the exchange rate".
 *
 * Only self-contained questions are cached: never one that leans on the conversation ("what about him?") or asks about
 * the user's own things — the direct path already excludes those ([DirectAnswer]). A clarifying question back ("Which
 * Accra?") or a non-answer ("I couldn't find …") is never stored. Memory only (lost when the app restarts): the
 * questions are general knowledge, but the cache is kept off disk so "clear my history" never has to know about it.
 * Pure (clock injected) → unit-tested.
 */
class AnswerCache(
    private val maxEntries: Int = AppConfig.int("cache.answers.maxEntries", 64),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val answer: String, val at: Long, val fresh: Boolean)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    private fun enabled() = com.tekyerema.core.config.FeatureFlags.isEnabled("H-11", true) && AppConfig.bool("cache.answers.enabled", true)

    /** The answer to [question] if it is still fresh enough to repeat as-is, else null. */
    @Synchronized
    fun fresh(question: String): String? {
        if (!enabled()) return null
        val k = key(question) ?: return null
        val e = map[k] ?: return null
        return e.answer.takeIf { clock() - e.at <= ttl(e.fresh) }
    }

    /** An older answer to offer when the internet is out: (answer, its age in ms), or null. */
    @Synchronized
    fun stale(question: String): Pair<String, Long>? {
        if (!enabled()) return null
        val k = key(question) ?: return null
        val e = map[k] ?: return null
        val age = clock() - e.at
        val maxAge = if (e.fresh) AppConfig.long("cache.answers.staleServeFreshMs", 24 * HOUR)
            else AppConfig.long("cache.answers.staleServeMs", 30 * 24 * HOUR)
        return if (age <= maxAge) e.answer to age else null
    }

    @Synchronized
    fun put(question: String, answer: String) {
        if (!enabled()) return
        val k = key(question) ?: return
        val a = answer.trim()
        if (a.length < 2 || a.length > AppConfig.int("cache.answers.maxChars", 800) || a.endsWith("?") || NON_ANSWER.containsMatchIn(a)) return
        if (TIME_OF_DAY.containsMatchIn(k)) return   // wrong within minutes: never kept
        map[k] = Entry(a, clock(), RetrievalNeed.classify(question) != RetrievalNeed.Need.NONE || CLOCK.containsMatchIn(k))
    }

    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size

    private fun ttl(fresh: Boolean) = if (fresh) AppConfig.long("cache.answers.freshTtlMs", 30 * MINUTE)
        else AppConfig.long("cache.answers.staticTtlMs", 7 * 24 * HOUR)

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE

        /** The process-wide cache the router uses. */
        val shared by lazy { AnswerCache() }

        // Words that make a question depend on the conversation or the moment it was asked in.
        private val CONTEXTUAL = Regex("\\b(it|its|he|she|him|her|his|hers|they|them|their|this|that|these|those|there|then|again|else|more|another)\\b")
        // Asked for variety: the same joke twice is a worse answer than none.
        private val VARIETY = Regex("\\b(joke|jokes|story|stories|poem|riddle|random|surprise|quote|fun fact)\\b")
        // Answers that change with the clock carry no web marker, so they'd be kept a week and offered offline for a month.
        // The time of day ("what time is it in London") is never kept; the rest ("how many days until Christmas", "how
        // old is the president", "…today", "how many years since 1957", "when is Eid") are kept as FRESH answers (30 min,
        // offered offline for a day at most). Most of these are answered on the phone now ([LocalDates], [WorldClock]);
        // the ones that still reach the model must not be repeated from a week-old answer.
        private val TIME_OF_DAY = Regex("\\b(what time|time is it|time in|the time|clock|time difference)\\b")
        private val CLOCK = Regex("\\b(days?|weeks?|months?|hours?|minutes?|how long|countdown|until|till|how old|age|" +
            "today|tonight|tomorrow|yesterday|now|current|currently|this (?:week|month|year)|next|last|latest|recent|recently|" +
            "ago|since|left|when is|when's|when will|when does|what year)\\b")
        private val NON_ANSWER = Regex("(?i)\\b(i couldn'?t|i could not|i can'?t|i cannot|i don'?t know|i'?m not sure|sorry|try again|no results)\\b")

        /** Normalized cache key, or null when the question shouldn't be cached at all. Pure. */
        fun key(question: String): String? {
            val q = question.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim()
                .removePrefix("please ").removeSuffix(" please").trim()
            if (q.length < 6 || q.split(' ').size > 25) return null
            if (CONTEXTUAL.containsMatchIn(q) || VARIETY.containsMatchIn(q)) return null
            return q
        }

        /** "a few minutes ago" / "about 3 hours ago" / "yesterday" / "4 days ago" — for the spoken disclaimer. Pure. */
        fun spokenAge(ageMs: Long): String {
            val min = ageMs / MINUTE
            val hours = ageMs / HOUR
            val days = ageMs / (24 * HOUR)
            return when {
                min < 2 -> "just now"
                min < 15 -> "a few minutes ago"
                min < 60 -> "about $min minutes ago"
                hours < 2 -> "about an hour ago"
                hours < 24 -> "about $hours hours ago"
                days < 2 -> "yesterday"
                else -> "$days days ago"
            }
        }
    }
}
