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
}
