package com.tekyerema

import com.tekyerema.agent.search.GeminiGroundingParser
import com.tekyerema.agent.search.GroqSearchParser
import com.tekyerema.agent.search.SearchText
import com.tekyerema.agent.search.Source
import com.tekyerema.agent.search.TavilyParser
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.WebSearchTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web search: speakable cleaning, source naming, and defensive parsing of each provider's reply. Pure — no network. */
class WebSearchTest {

    // ── SearchText.clean ──

    @Test fun clean_strips_markdown_citations_and_urls() {
        assertEquals("Ghana beat Mali 2–1 on 27 September 2026.",
            SearchText.clean("**Ghana** beat Mali 2–1 on 27 September 2026 [1][2]. Read more: https://www.bbc.com/sport/football/123"))
        assertEquals("According to Graphic Online, fuel prices rose by 5% this week.",
            SearchText.clean("According to [Graphic Online](https://www.graphic.com.gh/news/x.html), fuel prices rose by 5%【3†L4-L9】 this week."))
        assertEquals("Prices rose and fell.", SearchText.clean("Prices rose [1, 2] and fell [3-4][^5]."))
        assertEquals("The market opens at 8 am.", SearchText.clean("The market opens at 8 am (https://example.com/hours)."))
        assertEquals("It will rain in Kumasi tomorrow.", SearchText.clean("It will rain in Kumasi tomorrow. Source: www.accuweather.com"))
        assertEquals("Note: it is very hot today", SearchText.clean("_Note_: it is **very** hot ![map](https://x.com/m.png) today"))
    }

    @Test fun clean_turns_structure_into_sentences_and_collapses_whitespace() {
        assertEquals("Latest news. The Black Stars won. Kick-off was at 7 pm. More later",
            SearchText.clean("## Latest news\n- The Black Stars won\n- *Kick-off* was at 7 pm\n\nMore later"))
        assertEquals("First point. Second point", SearchText.clean("1. First point\n2. Second point"))
        // a soft-wrapped sentence is joined, not split
        assertEquals("The rate is 15.2 cedis to the dollar.", SearchText.clean("The rate is\n15.2 cedis to the dollar."))
        assertEquals("Accra: 31°C, sunny.", SearchText.clean("Accra:   31°C,\t sunny   ."))
        assertEquals("", SearchText.clean("   \n\n  "))
    }

    @Test fun shorten_keeps_whole_sentences_or_cuts_at_a_word() {
        assertEquals("First sentence here. Second sentence is here.",
            SearchText.shorten("First sentence here. Second sentence is here. Third one goes on and on.", 50))
        assertEquals("word word word…", SearchText.shorten("word word word word word word word word word word word word word word", 20))
        assertEquals("Short.", SearchText.shorten("Short.", 400))
        val long = SearchText.finish("The cedi rose today. ".repeat(40))
        assertTrue(long.length <= 400)
        assertTrue(long.endsWith("today."))
    }

    // ── SearchText.sourcesLine ──

    @Test fun sources_line_names_sites_from_hosts_and_caps_at_max() {
        val s = listOf(
            Source("BBC Sport", "https://www.bbc.com/sport/football/1"),
            Source("", "https://www.graphic.com.gh/news/2"),
            Source("Citi", "https://citinewsroom.com/3")
        )
        assertEquals("Sources: BBC, Graphic Online.", SearchText.sourcesLine(s))
        assertEquals("Sources: BBC, Graphic Online, Citi Newsroom.", SearchText.sourcesLine(s, max = 3))
        assertEquals("Source: BBC.", SearchText.sourcesLine(s, max = 1))
        assertEquals("", SearchText.sourcesLine(s, max = 0))
        assertEquals("", SearchText.sourcesLine(emptyList()))
    }

    @Test fun sources_line_dedupes_and_names_unknown_hosts_sensibly() {
        assertEquals("Source: BBC.", SearchText.sourcesLine(listOf(
            Source("", "https://www.bbc.com/news/a"), Source("", "https://www.bbc.co.uk/news/b"))))
        assertEquals("Sources: CNN, Examplepaper.", SearchText.sourcesLine(listOf(
            Source("", "https://edition.cnn.com/x"), Source("", "https://news.examplepaper.com.gh/y"))))
        assertEquals("Source: ABC.", SearchText.sourcesLine(listOf(Source("", "https://www.abc.net.au/news"))))
        assertEquals("Source: Daily Guide Network.", SearchText.sourcesLine(listOf(Source("", "https://www.daily-guide-network.com/x"))))
        // Gemini grounding: the uri is a Google redirect and the title is the site's domain
        assertEquals("Source: Reuters.", SearchText.sourcesLine(listOf(
            Source("reuters.com", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AbC"))))
    }

    // ── Groq (chat completions + browser_search) ──
    // Samples mirror Groq's chat-completions reply as of 2026-09-28: `choices[0].message.content` plus the
    // `executed_tools` array Groq returns for its server-side tools. The browser-search page tells callers to "check the
    // executed_tools field" but publishes no schema for it, so the `search_results.results[] {title,url,content,score}`
    // shape below is the one Groq has used for server-side search results; the parser also accepts other forms.

    private val groqWithExecutedTools = """
        {"id":"chatcmpl-123","object":"chat.completion","model":"openai/gpt-oss-20b",
         "choices":[{"index":0,"finish_reason":"stop","message":{
           "role":"assistant",
           "content":"**Ghana** beat Mali 2–1 on 27 September 2026【0†L3-L7】.",
           "reasoning":"The user wants the result. Searching.",
           "executed_tools":[{"index":0,"type":"search","arguments":"{\"query\": \"Ghana vs Mali result\"}","output":"...",
             "search_results":{"results":[
               {"title":"Ghana 2-1 Mali - BBC Sport","url":"https://www.bbc.com/sport/football/123","content":"...","score":0.93},
               {"title":"Black Stars beat Mali","url":"https://www.graphic.com.gh/sports/456","content":"...","score":0.88}]}}]}}]}
    """.trimIndent()

    @Test fun groq_reply_with_executed_tools_gives_clean_answer_and_sources() {
        val r = GroqSearchParser.parse(groqWithExecutedTools)!!
        assertEquals("Ghana beat Mali 2–1 on 27 September 2026.", r.answer)
        assertEquals("groq", r.provider)
        assertEquals(2, r.sources.size)
        assertEquals("https://www.bbc.com/sport/football/123", r.sources[0].url)
        assertEquals("Ghana 2-1 Mali - BBC Sport", r.sources[0].title)
        assertEquals("Sources: BBC, Graphic Online.", SearchText.sourcesLine(r.sources))
    }

    @Test fun groq_reply_without_structured_results_falls_back_to_urls_in_the_text() {
        val body = """{"choices":[{"message":{"role":"assistant",
            "content":"The cedi is at 15.2 to the US dollar as of 26 September 2026 (https://www.bog.gov.gh/rates)."}}]}"""
        val r = GroqSearchParser.parse(body)!!
        assertEquals("The cedi is at 15.2 to the US dollar as of 26 September 2026.", r.answer)
        assertEquals(listOf("https://www.bog.gov.gh/rates"), r.sources.map { it.url })
        assertEquals("Source: Bank of Ghana.", SearchText.sourcesLine(r.sources))
    }

    @Test fun groq_parser_rejects_empty_or_broken_replies() {
        assertNull(GroqSearchParser.parse("not json"))
        assertNull(GroqSearchParser.parse("""{"choices":[]}"""))
        assertNull(GroqSearchParser.parse("""{"choices":[{"message":{"role":"assistant","content":null}}]}"""))
        assertNull(GroqSearchParser.parse("""{"choices":[{"message":{"role":"assistant","content":"【0†L1-L2】 https://x.com"}}]}"""))
    }

    @Test fun groq_long_answer_is_capped() {
        val body = """{"choices":[{"message":{"content":"${"The cedi rose today. ".repeat(40)}"}}]}"""
        assertTrue(GroqSearchParser.parse(body)!!.answer.length <= 400)
    }

    // ── Gemini + Grounding with Google Search ──
    // Interactions API sample mirrors the example response in https://ai.google.dev/gemini-api/docs/google-search as of
    // 2026-09-28 (steps: thought → google_search_call → google_search_result → model_output with url_citation annotations).

    private val geminiInteractions = """
        {"id":"int_123","status":"completed","steps":[
          {"type":"thought","summary":[{"type":"text","text":"Looking up the match."}],"signature":"abc"},
          {"type":"google_search_call","arguments":{"queries":["Ghana vs Mali result"]}},
          {"type":"google_search_result","call_id":"search_001","result":[{"search_suggestions":"<!-- HTML -->"}]},
          {"type":"model_output","content":[{"type":"text","text":"Ghana beat Mali 2–1 on Saturday, 27 September 2026.",
            "annotations":[
              {"type":"url_citation","url":"https://www.bbc.com/sport/football/123","title":"bbc.com","start_index":0,"end_index":22},
              {"type":"url_citation","url":"https://www.myjoyonline.com/black-stars","title":"myjoyonline.com","start_index":0,"end_index":22}]}]}]}
    """.trimIndent()

    // Classic generateContent sample mirrors the documented `candidates[0].groundingMetadata` shape (groundingChunks[].web
    // {uri,title} where uri is a vertexaisearch redirect and title the domain) — kept for `answers.search.gemini.api = generateContent`.
    private val geminiGenerateContent = """
        {"candidates":[{
          "content":{"role":"model","parts":[{"text":"Fuel prices in Ghana rose by about 5% on 16 September 2026."}]},
          "finishReason":"STOP",
          "groundingMetadata":{
            "webSearchQueries":["Ghana fuel prices September 2026"],
            "searchEntryPoint":{"renderedContent":"<!-- HTML -->"},
            "groundingChunks":[
              {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AbC1","title":"graphic.com.gh"}},
              {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/XyZ2","title":"citinewsroom.com"}}],
            "groundingSupports":[{"segment":{"startIndex":0,"endIndex":58,"text":"Fuel prices in Ghana rose"},"groundingChunkIndices":[0,1]}]}}]}
    """.trimIndent()

    @Test fun gemini_interactions_reply_gives_model_output_and_citations_not_thoughts() {
        val r = GeminiGroundingParser.parse(geminiInteractions)!!
        assertEquals("Ghana beat Mali 2–1 on Saturday, 27 September 2026.", r.answer)
        assertFalse(r.answer.contains("Looking up"))
        assertEquals("gemini", r.provider)
        assertEquals("Sources: BBC, MyJoyOnline.", SearchText.sourcesLine(r.sources))
    }

    @Test fun gemini_generate_content_reply_uses_grounding_chunks() {
        val r = GeminiGroundingParser.parse(geminiGenerateContent)!!
        assertEquals("Fuel prices in Ghana rose by about 5% on 16 September 2026.", r.answer)
        assertEquals(2, r.sources.size)
        assertEquals("Sources: Graphic Online, Citi Newsroom.", SearchText.sourcesLine(r.sources))
    }

    @Test fun gemini_parser_rejects_blocked_or_empty_replies() {
        assertNull(GeminiGroundingParser.parse("<html>oops</html>"))
        assertNull(GeminiGroundingParser.parse("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        assertNull(GeminiGroundingParser.parse("""{"status":"completed","steps":[{"type":"google_search_call","arguments":{"queries":["x"]}}]}"""))
    }

    // ── Tavily ── sample mirrors https://docs.tavily.com/documentation/api-reference/endpoint/search as of 2026-09-28.

    @Test fun tavily_reply_gives_answer_and_results() {
        val body = """{"query":"cedi dollar rate","answer":"The Ghana cedi traded at about 15.2 per US dollar on 26 September 2026.",
            "results":[{"title":"Exchange rates | Bank of Ghana","url":"https://www.bog.gov.gh/treasury-and-the-markets/rates/",
            "content":"...","score":0.81}],"response_time":1.67}"""
        val r = TavilyParser.parse(body)!!
        assertEquals("The Ghana cedi traded at about 15.2 per US dollar on 26 September 2026.", r.answer)
        assertEquals("tavily", r.provider)
        assertEquals("Source: Bank of Ghana.", SearchText.sourcesLine(r.sources))
        assertNull(TavilyParser.parse("""{"query":"x","answer":null,"results":[]}"""))
    }

    // ── The tool ──

    @Test fun web_search_tool_is_a_read_only_web_accelerator() {
        val t = WebSearchTool
        assertEquals("web_search", t.name)
        assertEquals(CapabilityScope.WEB, t.scope)
        assertEquals(RiskTier.TIER0, t.riskTier)
        assertTrue(t.isAccelerator)
        assertTrue(t.description.length <= 160)
        assertEquals(listOf("query"), t.params.map { it.name })
        assertTrue(t.params.single().required)
        assertEquals("A. (Sources: BBC, Graphic Online)", WebSearchTool.message("A.", "Sources: BBC, Graphic Online."))
        assertEquals("A.", WebSearchTool.message("A.", ""))
    }
}
