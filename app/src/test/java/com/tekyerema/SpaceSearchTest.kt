package com.tekyerema

import android.content.Context
import com.tekyerema.agent.search.SearchResult
import com.tekyerema.agent.search.SearchProvider
import com.tekyerema.agent.search.Source
import com.tekyerema.agent.search.SpaceSearchParser
import com.tekyerema.agent.search.SpaceSearchProvider
import com.tekyerema.agent.search.WebSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Space as a web_search provider: reply parsing, query-only payload, fall-through on error/429/timeout. Fake HTTP layer. */
class SpaceSearchTest {
    private val url = "https://example-space.hf.space/web/ask"
    private var now = 1_000_000L
    private val sent = mutableListOf<Triple<String, Map<String, String>, JSONObject>>()

    private val goodBody = """{"answer":"The cedi is about 15 to the dollar, says the Bank of Ghana.","searched":true,"ms":4200,
        "sources":[{"title":"Cedi rate","url":"https://www.bog.gov.gh/rates","site":"bog.gov.gh"},
                   {"title":"Markets","url":"https://citinewsroom.com/x","site":"citinewsroom.com"}]}"""

    private fun reply(code: Int, body: String = "", retryMs: Long? = null) = WebSearch.HttpReply(code, body, retryMs)

    private fun fake(code: Int?, body: String = "", retryMs: Long? = null) {
        SpaceSearchProvider.transport = { u, h, j ->
            sent += Triple(u, h, j)
            if (code == null) null else reply(code, body, retryMs)
        }
    }

    @Before fun setUp() { SpaceSearchProvider.reset(); SpaceSearchProvider.clock = { now }; sent.clear() }
    @After fun tearDown() { SpaceSearchProvider.reset(); SpaceSearchProvider.clock = { System.currentTimeMillis() } }

    // ── parsing ──

    @Test fun parses_answer_and_sources() {
        val r = SpaceSearchParser.parse(goodBody)!!
        assertEquals("space", r.provider)
        assertEquals("The cedi is about 15 to the dollar, says the Bank of Ghana.", r.answer)
        assertEquals(listOf(Source("Cedi rate", "https://www.bog.gov.gh/rates"), Source("Markets", "https://citinewsroom.com/x")), r.sources)
    }

    @Test fun cleans_markdown_in_the_answer_and_titles_a_source_by_its_site() {
        val r = SpaceSearchParser.parse("""{"answer":"**Rain** in Accra [1].","searched":true,"sources":[{"title":"","url":"https://x.com/a","site":"x.com"}]}""")!!
        assertEquals("Rain in Accra.", r.answer)
        assertEquals("x.com", r.sources[0].title)
    }

    @Test fun unusable_replies_are_null() {
        assertNull(SpaceSearchParser.parse("not json"))
        assertNull(SpaceSearchParser.parse("""{"answer":"","searched":true,"sources":[{"url":"https://a.com"}]}"""))
        assertNull(SpaceSearchParser.parse("""{"searched":true,"sources":[{"url":"https://a.com"}]}"""))
        assertNull("no source = not a web answer", SpaceSearchParser.parse("""{"answer":"Yes.","searched":true,"sources":[]}"""))
        assertNull("sources without http links", SpaceSearchParser.parse("""{"answer":"Yes.","searched":true,"sources":[{"url":"javascript:x"}]}"""))
        assertNull("searched=false is an unsourced model answer", SpaceSearchParser.parse(
            """{"answer":"Yes.","searched":false,"sources":[{"url":"https://a.com"}]}"""))
    }

    // ── payload and URL ──

    @Test fun request_body_is_the_query_and_mode_only() {
        val b = SpaceSearchProvider.requestBody("cedi rate today", "answer")
        assertEquals(setOf("q", "mode"), b.keys().asSequence().toSet())
        assertEquals("cedi rate today", b.getString("q"))
        assertEquals("answer", b.getString("mode"))
    }

    @Test fun with_an_app_key_the_only_header_is_that_key() = runBlocking {
        fake(200, goodBody)
        val saved = SpaceSearchProvider.appKey
        try {
            SpaceSearchProvider.appKey = { "test-key" }
            SpaceSearchProvider.ask("cedi rate today", url, "results")
            assertEquals(mapOf(SpaceSearchProvider.KEY_HEADER to "test-key"), sent.last().second)
            assertEquals(setOf("q", "mode"), sent.last().third.keys().asSequence().toSet())
        } finally { SpaceSearchProvider.appKey = saved }
    }

    @Test fun the_call_sends_exactly_that_body_to_the_url_with_no_headers() = runBlocking {
        fake(200, goodBody)
        val saved = SpaceSearchProvider.appKey
        SpaceSearchProvider.appKey = { "" }   // no key configured: nothing at all in the headers
        try {
        assertNotNull(SpaceSearchProvider.ask("cedi rate today", url, "results"))
        assertEquals(1, sent.size)
        assertEquals(url, sent[0].first)
        assertTrue("no Authorization, cookie or id header", sent[0].second.isEmpty())
        val body = sent[0].third
        assertEquals(setOf("q", "mode"), body.keys().asSequence().toSet())
        assertEquals("cedi rate today", body.getString("q"))
        assertEquals("results", body.getString("mode"))
        } finally { SpaceSearchProvider.appKey = saved }
    }

    @Test fun url_comes_from_config_else_the_space_host_https_only() {
        val n = SpaceSearchProvider::normalizeUrl
        assertEquals("https://a.hf.space/web/ask", n("", "https://a.hf.space"))
        assertEquals("https://a.hf.space/web/ask", n(null, "https://a.hf.space/"))
        assertEquals("https://b.example/web/ask", n("https://b.example/", "https://a.hf.space"))
        assertEquals("https://b.example/web/ask", n("https://b.example/web/ask", null))
        assertNull(n("", null))
        assertNull(n("http://plain.example", null))
        assertNull(n("  ", "  "))
    }

    // ── fall-through ──

    @Test fun success_returns_the_result_and_does_not_cool() = runBlocking {
        fake(200, goodBody)
        assertEquals("space", SpaceSearchProvider.ask("q", url)!!.provider)
        assertFalse(SpaceSearchProvider.cooling())
    }

    @Test fun http_429_returns_null_and_cools_for_the_retry_after() = runBlocking {
        fake(429, """{"error":"busy"}""", retryMs = 120_000L)
        assertNull(SpaceSearchProvider.ask("q", url))
        assertTrue(SpaceSearchProvider.cooling())
        now += 119_000L; assertTrue(SpaceSearchProvider.cooling())
        now += 2_000L; assertFalse(SpaceSearchProvider.cooling())
    }

    @Test fun http_429_without_retry_after_cools_five_minutes() = runBlocking {
        fake(429)
        assertNull(SpaceSearchProvider.ask("q", url))
        now += 299_000L; assertTrue(SpaceSearchProvider.cooling())
        now += 2_000L; assertFalse(SpaceSearchProvider.cooling())
    }

    @Test fun server_error_network_failure_and_bad_json_return_null_and_cool_a_minute() = runBlocking {
        for (f in listOf<() -> Unit>({ fake(503, """{"error":"loading"}""") }, { fake(500) }, { fake(null) }, { fake(200, "<html>") })) {
            SpaceSearchProvider.reset(); f()
            assertNull(SpaceSearchProvider.ask("q", url))
            assertTrue(SpaceSearchProvider.cooling())
            now += 61_000L; assertFalse(SpaceSearchProvider.cooling()); now -= 61_000L
        }
    }

    @Test fun a_slow_space_times_out_to_null() = runBlocking {
        SpaceSearchProvider.transport = { _, _, _ -> delay(5_000); reply(200, goodBody) }
        SpaceSearchProvider.timeoutOverrideMs = 50L
        assertNull(SpaceSearchProvider.ask("q", url))
        assertTrue(SpaceSearchProvider.cooling())
    }

    // ── the ladder ──

    private class Fake(override val name: String, val result: SearchResult?, val boom: Boolean = false) : SearchProvider {
        var calls = 0
        override suspend fun search(query: String, ctx: Context): SearchResult? = null   // the ladder is driven through call=
        fun run(): SearchResult? { calls++; if (boom) error("boom"); return result }
    }

    private fun ladder(vararg ps: SearchProvider): SearchResult? = runBlocking {
        WebSearch.runLadder(ps.toList(), 60_000L, 8_000L) { p ->
            if (p === SpaceSearchProvider) SpaceSearchProvider.ask("q", url) else (p as Fake).run()
        }
    }

    private val groqAnswer = SearchResult("Groq says fifteen cedis.", listOf(Source("BBC", "https://bbc.com/x")), "groq")

    @Test fun space_is_the_only_default_search_provider_and_has_a_longer_timeout() {
        assertEquals(listOf("space"), WebSearch.DEFAULT_ORDER)   // 2 Nov 2026: Groq / Gemini / Tavily search are off by default
        assertEquals(13L, SpaceSearchProvider.timeoutSec)   // 12 s default + 1 s: ask() times out (and cools) first
    }

    @Test fun a_good_space_answer_wins_and_the_next_provider_is_not_asked() {
        fake(200, goodBody)
        val groq = Fake("groq", groqAnswer)
        assertEquals("space", ladder(SpaceSearchProvider, groq)!!.provider)
        assertEquals(0, groq.calls)
    }

    @Test fun a_429_from_the_space_falls_to_the_next_provider() {
        fake(429)
        val groq = Fake("groq", groqAnswer)
        assertEquals("groq", ladder(SpaceSearchProvider, groq)!!.provider)
        assertEquals(1, groq.calls)
    }

    @Test fun an_error_timeout_or_empty_answer_falls_through_and_a_throwing_provider_does_not_stop_the_ladder() {
        fake(null)
        val broken = Fake("gemini", null, boom = true)
        val groq = Fake("groq", groqAnswer)
        assertEquals("groq", ladder(SpaceSearchProvider, broken, groq)!!.provider)
        assertEquals(1, broken.calls)
        SpaceSearchProvider.reset()
        fake(200, """{"answer":"","searched":true,"sources":[]}""")
        assertEquals("groq", ladder(SpaceSearchProvider, groq)!!.provider)
    }

    @Test fun every_provider_failing_gives_null() {
        fake(500)
        assertNull(ladder(SpaceSearchProvider, Fake("groq", null)))
    }
}
