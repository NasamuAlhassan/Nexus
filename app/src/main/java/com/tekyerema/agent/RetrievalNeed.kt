package com.tekyerema.agent

/**
 * C-05 — does a question need fresh information from the web, and how much (Adaptive-RAG's three classes)?
 *
 *  - NONE:   the model's own knowledge is enough ("what is the capital of Ghana", "explain inflation").
 *  - SINGLE: one web lookup ("cedi to dollar rate today", "who won the match", "weather in Kumasi").
 *  - MULTI:  several lookups, each building on the last ("compare MTN and Telecel data prices", "is the cedi stronger
 *            than last month") — search step by step, carrying what was found forward.
 *
 * The engine uses this on a direct question (I-01): SINGLE/MULTI put `web_search` first with a one-line hint, NONE
 * answers from knowledge (and still may search). Pure heuristics over the words → unit-tested.
 */
object RetrievalNeed {
    enum class Need { NONE, SINGLE, MULTI }

    private val FRESH = listOf(
        Regex("\\b(today|tonight|tomorrow|yesterday|(this|last|past) (week|month|year|morning|evening)|right now|currently|at the moment|latest|recent|current|live|so far)\\b"),
        Regex("\\b(news|headlines|weather|forecast|temperature|rain)\\b"),
        Regex("\\b(price|prices|rate|rates|exchange rate|cost|costs|fare|fares|fee|fees|tariff)\\b"),
        Regex("\\b(score|scores|result|results|who won|fixtures?|kick-?off|standings|table)\\b"),
        Regex("\\b(open now|opening hours|closing time|is .* open)\\b"),
        Regex("\\b(nnɛ|nne|ɔkyena|okyena|nsɛm|nsem)\\b")
    )
    private val MULTI = listOf(
        Regex("\\b(compare|comparison|versus|vs\\.?|difference between|better|cheaper|cheapest|more expensive|stronger|weaker)\\b"),
        Regex("\\b(than last|since last|change[ds]? (from|since)|trend|over the (last|past))\\b"),
        Regex("\\band\\b.*\\b(price|rate|weather|score|news)\\b.*\\b(price|rate|weather|score|news)\\b")
    )

    fun classify(question: String): Need {
        val q = question.trim().lowercase()
        if (q.isEmpty()) return Need.NONE
        val fresh = FRESH.any { it.containsMatchIn(q) }
        if (!fresh) return Need.NONE
        return if (MULTI.any { it.containsMatchIn(q) }) Need.MULTI else Need.SINGLE
    }

    /** The one-line hint the engine puts in the first step's history, or null for NONE. */
    fun hint(need: Need): String? = when (need) {
        Need.NONE -> null
        Need.SINGLE -> "This question needs CURRENT information: call web_search once and answer from it (say where it came from if asked)."
        Need.MULTI -> "This question needs several CURRENT facts: web_search them one at a time, carrying what you found into the " +
            "next search, then answer briefly."
    }
}
