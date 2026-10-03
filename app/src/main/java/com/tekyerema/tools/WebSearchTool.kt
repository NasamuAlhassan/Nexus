package com.tekyerema.tools

import com.tekyerema.agent.search.SearchText
import com.tekyerema.agent.search.WebSearch
import com.tekyerema.core.NetworkMonitor
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live answers from the web (news, prices, exchange rates, results, weather, opening hours). Read-only, so TIER0;
 * the search runs server-side at the provider (see [WebSearch]) and comes back as a short speakable answer with
 * its sources. Web text is untrusted third-party content: it is cleaned and capped, never acted on.
 *
 * Failures are NOT recoverable: opening a browser and reading result pages through the UI would be slow for a blind
 * user and burn the same quota that just ran out, so the planner is told plainly that search isn't available.
 */
object WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Search the web for current information (news, prices, results, weather, opening hours). " +
        "Returns a short answer and its sources."
    override val scope = CapabilityScope.WEB
    override val riskTier = RiskTier.TIER0
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("query", "string", "What to look up, as a short search question, e.g. 'cedi to dollar rate today'.")
    )

    const val OFFLINE_MESSAGE = "I'm offline, so I can't search the web right now."

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isBlank()) return ToolResult(false, "No search query provided.")
        // The time and date HERE are the phone's clock, never a web page: "what time is it in Accra" came back from a
        // stale result as "4:04 pm, Tuesday 29 September" at 18:22 on Friday 2 October 2026 (the owner's Twi test).
        localNow(query)?.let { return ToolResult(true, it, channel = "native") }
        if (!NetworkMonitor.isOnline()) return ToolResult(false, OFFLINE_MESSAGE, recoverable = false)
        if (WebSearch.providers(ctx.appContext).isEmpty())
            return ToolResult(false, "Web search isn't set up on this phone, so I can't look that up.", recoverable = false)

        val r = WebSearch.search(query, ctx.appContext)
            ?: return ToolResult(false, "I couldn't get an answer from the web right now. Please try again later.",
                recoverable = false, channel = "native")

        com.tekyerema.agent.search.LastSearch.remember(r.sources)   // I-07: "where did you get that?"
        val sources = JSONArray()
        r.sources.forEach { sources.put(JSONObject().put("title", it.title).put("url", it.url)) }
        val data = JSONObject().put("answer", r.answer).put("sources", sources).put("provider", r.provider)
        return ToolResult(true, message(r.answer, SearchText.sourcesLine(r.sources)), data = data, channel = "native")
    }

    /** "<answer> (Sources: BBC, Graphic Online)", or just the answer when no source could be named. */
    fun message(answer: String, sourcesLine: String): String =
        if (sourcesLine.isBlank()) answer else "$answer (${sourcesLine.trim().removeSuffix(".")})"

    /** A search for the time or date NOW here (Ghana / Accra / "now" / "today", or no place at all) — answered from the
     *  phone's clock; null for anything else (another city's time, a date in history). Internal: unit-tested. */
    internal fun localNow(query: String, now: java.util.Calendar = java.util.Calendar.getInstance()): String? {
        val q = query.lowercase()
        val time = TIME_NOW.containsMatchIn(q)
        val date = DATE_NOW.containsMatchIn(q)
        if (!time && !date) return null
        // Another place than here is a real question for the web ("time in London").
        Regex("\\bin ([a-z][a-z .'-]{1,30})").find(q)?.groupValues?.get(1)?.trim()?.let { place ->
            if (!Regex("^(?:ghana|accra|kumasi|tamale|takoradi|cape coast|here|gmt|my area|my location)\\b").containsMatchIn(place)) return null
        }
        val clock = java.text.SimpleDateFormat("h:mm a", java.util.Locale.UK).format(now.time).lowercase()
        val day = java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.UK).format(now.time)
        return when {
            time && date -> "It's $clock on $day, by this phone's clock."
            time -> "It's $clock, by this phone's clock."
            else -> "Today is $day."
        }
    }

    private val TIME_NOW = Regex("\\b(?:current|local|exact)\\s+time\\b|\\btime\\s+(?:now|right now|today|is it)\\b|\\bwhat\\s+time\\b|^time\\s+in\\b|^time$")
    private val DATE_NOW = Regex("\\btoday'?s\\s+date\\b|\\bdate\\s+today\\b|\\bwhat\\s+(?:is\\s+)?(?:the\\s+)?date\\b|\\bcurrent\\s+date\\b|\\bwhat\\s+day\\s+is\\s+(?:it|today)\\b")
}
