package com.tekyerema.agent

/**
 * The offline/online capability boundary (directive §33). Pure, deterministic, unit-tested.
 *
 * Two questions the router needs answered without a model:
 *  - [needsInternet]: does this goal REQUIRE live network data (web/news/weather/prices/general
 *    knowledge lookup)? If so and we are offline, the honest answer is "I need an internet connection".
 *  - [isDeviceControl]: is this a phone-operation goal the on-device brain can attempt locally?
 *
 * These are heuristics over the user's words, not a command table — they gate ROUTING, not execution.
 */
object OfflineCapability {

    // Live-data / knowledge intents that genuinely need the network (no local answer is honest).
    private val ONLINE_ONLY = listOf(
        Regex("\\b(search|look\\s*up|google|browse)\\b.*\\b(web|internet|online|for)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsearch\\s+the\\s+(web|internet|net)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(weather|forecast|temperature)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(news|headlines)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(share|stock)\\s+price\\b|\\bexchange\\s+rate\\b|\\bhow much is\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(latest|today'?s|current|live)\\b.*\\b(price|score|result|rate|news|weather)\\b", RegexOption.IGNORE_CASE),
        // I-02: schedules and relative dates only live data can answer; a little Twi (nnɛ = today, ɔkyena = tomorrow, nsɛm = news).
        Regex("\\b(open now|opening hours|closing time|who won|fixtures?|kick-?off|match (today|tonight|tomorrow))\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(nnɛ|nne|ɔkyena|okyena|nsɛm|nsem)\\b", RegexOption.IGNORE_CASE),
        // open-ended general-knowledge questions → need the online reasoning brain
        Regex("^(who|what|when|where|why|how)\\b(?!'?s\\s+(on|my)\\b).*\\?*$", RegexOption.IGNORE_CASE),
        Regex("\\b(tell me about|explain|translate)\\b", RegexOption.IGNORE_CASE)
    )

    // Phrases that keep an otherwise question-shaped goal LOCAL (screen reading, device state).
    private val LOCAL_OVERRIDE = listOf(
        Regex("\\bon\\s+(my|the)\\s+screen\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwhat('?s| is| am i)\\b.*\\b(screen|looking at|showing|open)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(read|describe)\\b.*\\bscreen\\b", RegexOption.IGNORE_CASE),
        // K-01: "where is the send button?", "which app am I in?" are about the phone, not the web.
        Regex("\\bwhere (is|are) (the|my)\\b.*\\b(button|icon|setting|settings|option|menu|tab|field|box|link)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(which|what) app (am i|is this|is open)\\b|\\bwhat screen\\b", RegexOption.IGNORE_CASE)
    )

    fun needsInternet(goal: String): Boolean {
        val g = goal.trim()
        if (g.isEmpty()) return false
        if (LOCAL_OVERRIDE.any { it.containsMatchIn(g) }) return false
        return ONLINE_ONLY.any { it.containsMatchIn(g) }
    }
}
