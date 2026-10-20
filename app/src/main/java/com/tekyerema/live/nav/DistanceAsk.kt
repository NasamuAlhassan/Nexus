package com.tekyerema.live.nav

/**
 * "How far is the Atlantic Mall (from here)?" — recognised on the phone, so it never waits for the cloud planner (owner, 20 Oct
 * 2026: the planner asked "From which location?", a web search timed out, 88 s in all). The answer is [WalkGuide.distanceTo], from
 * the phone's own location. Pure (unit-tested).
 *
 * Only the wordings that mean "from where I am": "how far is X", "how far is X from here / from me / from where I am", "how far
 * to X", "how long does it take to get to X", "distance to X". "How far is Kumasi from Accra" names another start, so it is NOT
 * ours (the planner / a web answer), and neither are planets, parts of the phone, or "it" / "that" (a walk already running
 * answers those, see [NavSpeech]).
 */
object DistanceAsk {
    private const val LEAD = "^(?:(?:ok(?:ay)?|please|so|and|hey|tell me|can you tell me|could you tell me)\\s+)*"
    private const val HERE = "(?:\\s+(?:away\\s+)?from\\s+(?:here|me|where\\s+i\\s+am|my\\s+(?:location|position|place)|where\\s+i\\s+am\\s+(?:now|standing)))?"
    private val ASKS = listOf(
        Regex("$LEAD(?:how\\s+far\\s+to|how\\s+far(?:\\s+away)?(?:\\s+is|\\s+are)?|what(?:'s|\\s+is)\\s+the\\s+distance\\s+(?:to|from\\s+here\\s+to|between\\s+here\\s+and))\\s+(.+?)$HERE\\??$"),
        Regex("$LEAD(?:how\\s+long\\s+(?:does\\s+it\\s+take|would\\s+it\\s+take|will\\s+it\\s+take|is\\s+the\\s+walk)\\s+(?:me\\s+)?to\\s+(?:get|walk|go|reach)(?:\\s+to)?)\\s+(.+?)$HERE\\??$"),
        Regex("$LEAD(?:distance\\s+(?:to|from\\s+here\\s+to))\\s+(.+?)\\??$")
    )
    private val NOT_A_PLACE = Regex("^(?:it|that|this|there|here|home\\s+screen|the\\s+(?:moon|sun|earth|sky|stars?|planet|ocean|sea\\s+floor))$|\\b(?:moon|sun|mars|venus|jupiter|saturn|planet|galaxy|universe|light\\s+years?)\\b|\\bfrom\\b")
    private val ARTICLE = Regex("^(?:the\\s+)")

    /** The place asked about, or null when this is not a "how far (from here)" question. */
    fun parse(said: String): String? {
        val s = said.trim().lowercase().replace('’', '\'').trimEnd('.', '!', '?').trim()
        if (s.isEmpty()) return null
        for (re in ASKS) {
            val place = re.matchEntire(s)?.groupValues?.get(1)?.trim()?.trimEnd(',') ?: continue
            if (place.length < 3 || NOT_A_PLACE.containsMatchIn(place)) continue
            if (com.tekyerema.agent.FastIntentClassifier.isPhoneThing(place)) continue
            return place.replace(ARTICLE, "").trim().ifEmpty { null }
        }
        return null
    }
}
