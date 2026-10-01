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
    /** LOCATE (research brief §B.5): not a question but "open / visit <an external site or portal>" — the target has
     *  to be FOUND on the web before it can be opened. [classify] never returns it (it reads questions); [locate] does. */
    enum class Need { NONE, SINGLE, MULTI, LOCATE }

    // "open", "visit"…: an organisation or a web cue is enough. "go to", "take me to", "show me": a web cue is required —
    // "take me to the hospital" wants directions, not a website.
    private val STRONG_VERB = Regex("^(?:please |hey |can you |could you |kindly )*(?:open|visit|launch|load|bring up|pull up|browse to)(?: up)? (.+)$")
    private val WEAK_VERB = Regex("^(?:please |hey |can you |could you |kindly )*(?:go to|goto|take me to|show me|get me to)(?: the)? (.+)$")
    private val WEB_CUE = Regex("\\b(?:website|web site|site|web page|webpage|homepage|home page|portal|log ?in page|login|sign ?in page)\\b|\\.(?:com|org|net|gov|edu|gh)\\b")
    private val ORG_CUE = Regex("\\b(?:university|college|school|polytechnic|academy|institute|bank|ministry|authority|commission|" +
        "hospital|council|assembly|agency|embassy|board|government|sakai)\\b")
    /** Things on the phone, directions, and apps: never a web research target. */
    private val NOT_WEB = Regex("\\b(?:app|application|settings?|wi-?fi|bluetooth|camera|gallery|contacts?|messages?|notifications?|" +
        "alarms?|clock|calculator|calendar|flash ?light|torch|dialer|keyboard|recent apps|home screen|files|downloads|" +
        "near me|nearby|directions?|the way|route|map|maps|ussd|short ?code)\\b")   // round 2: "open Stanbic USSD" is the phone's

    /** The external thing to find for an "open <site or portal>" request, or null. Pure; the engine also skips it when
     *  an installed app matches (the app wins). */
    fun locate(goal: String): String? {
        val g = goal.trim().lowercase().replace(Regex("[.!?]+$"), "").replace(Regex("\\s+"), " ")
        val strong = STRONG_VERB.matchEntire(g)
        val m = strong ?: WEAK_VERB.matchEntire(g) ?: return null
        val ref = m.groupValues[1].replace(Regex("^(?:the|my) "), "").replace(Regex(" (?:for me|please|now)$"), "").trim()
        if (ref.length < 3 || ref.split(' ').size > 8 || NOT_WEB.containsMatchIn(ref)) return null
        val web = WEB_CUE.containsMatchIn(ref)
        if (!web && (strong == null || !ORG_CUE.containsMatchIn(ref))) return null
        return ref
    }

    /**
     * Offices and titles whose HOLDER changes: who holds one now is a fresh fact, never the model's memory. (A20e audit
     * 2026-10-01, R01: "who is the president of Ghana" was answered by the small direct-answer model with a name that
     * belongs to nobody, while "who is the vice president" happened to be searched and was right.)
     */
    private const val OFFICE = "(?:vice[- ]?president|president|veep|prime minister|deputy minister|minister|" +
        "member of parliament|mp|governor|mayor|chief executive|ceo|managing director|chair(?:man|woman|person)?|" +
        "chief justice|chief of staff|attorney[- ]general|inspector[- ]general|igp|speaker|head coach|coach|manager|captain|" +
        "champions?|winners?|leader|head of state|head|king|queen|asantehene|pope|secretary[- ]general|director[- ]general|" +
        "vice[- ]chancellor|chancellor|ambassador|high commissioner|commissioner|first lady|owner|" +
        "richest (?:man|woman|person)|record holder|title holder)"
    private val HOLDER = listOf(
        // "who is / who's (now) the (current) (Ghana) president", "what is the name of the education minister"
        Regex("\\b(?:who(?:'s| is| are)|(?:what(?:'s| is) )?the name of|name(?= (?:the|our) )) (?:now |currently )?(?:the |our |a |an )?" +
            "(?:[\\p{L}'.-]+ ){0,3}?$OFFICE\\b"),
        // "the president of Ghana" inside any present-tense question ("how old is the president of Ghana")
        Regex("\\b(?:the |our )(?:current |new |sitting |present )?$OFFICE of\\b"),
        Regex("\\bwho (?:currently |now )?(?:leads|runs|heads|governs|rules|coaches|captains|manages|owns|chairs)\\b")
    )
    /** History, not the present: "who WAS the first president", "the president in 1960" stay with what the model knows. */
    private val PAST = Regex("\\b(?:was|were|did|had|used to|first(?! lady)|founding|late)\\b|\\bin (?:1\\d{3}|20[01]\\d)\\b")

    private val FRESH = listOf(
        Regex("\\b(today|tonight|tomorrow|yesterday|(this|last|past) (week|month|year|morning|evening)|right now|currently|at the moment|latest|recent|current|live|so far|these days|nowadays|at present|presently|as of now)\\b"),
        Regex("\\bnow\\s*\\??$"),
        Regex("\\b(news|headlines|weather|forecast|temperature|rain)\\b"),
        Regex("\\b(price|prices|rate|rates|exchange rate|cost|costs|fare|fares|fee|fees|tariff)\\b"),
        Regex("\\bhow much (?:is|are|does|do) (?:a|an|the|one)\\b"),
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
        val fresh = FRESH.any { it.containsMatchIn(q) } || holdsOffice(q)
        if (!fresh) return Need.NONE
        return if (MULTI.any { it.containsMatchIn(q) }) Need.MULTI else Need.SINGLE
    }

    /** Does [q] (lower-case) ask who holds an office or a title NOW? Pure. */
    internal fun holdsOffice(q: String): Boolean = !PAST.containsMatchIn(q) && HOLDER.any { it.containsMatchIn(q) }

    /** The one-line hint the engine puts in the first step's history, or null for NONE. */
    fun hint(need: Need): String? = when (need) {
        Need.NONE -> null
        Need.SINGLE -> "This question needs CURRENT information: call web_search once and answer from it (say where it came from if asked)."
        Need.MULTI -> "This question needs several CURRENT facts: web_search them one at a time, carrying what you found into the " +
            "next search, then answer briefly."
        Need.LOCATE -> "The user wants a website or portal: find its official https address (one web_search) and open it with " +
            "open_intent open_url; ask only if two or more still fit."
    }
}
