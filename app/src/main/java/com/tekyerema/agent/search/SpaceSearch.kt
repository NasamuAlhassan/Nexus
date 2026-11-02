package com.tekyerema.agent.search

import android.content.Context
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Our own Hugging Face Space as a free `web_search` provider (2 Nov 2026: Groq's browser_search keeps hitting its limits).
 * `POST <space>/web/ask {"q", "mode"}` → `{answer, sources[{title,url,site}], searched, ms}`: the Space plans, searches the
 * web keylessly (Ghana newsrooms first, Open-Meteo weather), reads the top pages and answers with a small model that must
 * cite or say the results don't say. Mode "results" is search only, no model.
 *
 * Privacy: the body is the query text and the mode, nothing else — no contacts, screen text, memory, device or user id,
 * and no header beyond the standard ones. The query is never logged.
 *
 * Any failure (offline, timeout, 429 from the Space's limiter, 5xx, an unreadable or unsourced reply) returns null so the
 * ladder moves to the next provider, and the Space is then skipped for a while (a 429 for the time it says, else a minute)
 * so a sleeping or busy Space doesn't add its wait to every question.
 *
 * Config (`answers.search.space.*`): enabled (default true), url (default: SPEECH_SPACE_URL from local.properties, the
 * host the app's other Space calls already use, plus /web/ask), mode ("answer"), timeoutSec (12). Ladder position:
 * `answers.search.providers` (default first).
 */
object SpaceSearchProvider : SearchProvider {
    override val name = "space"
    private const val PATH = "/web/ask"
    private const val DEFAULT_COOL_MS = 60_000L
    private const val DEFAULT_429_COOL_MS = 5 * 60_000L

    override val timeoutSec: Long get() = timeoutMs() / 1000L + 1L   // ask() times out first and cools the Space

    @Volatile private var coolUntil = 0L

    /** The HTTP layer; tests swap it for a fake. */
    internal var transport: suspend (url: String, headers: Map<String, String>, body: JSONObject) -> WebSearch.HttpReply? =
        { url, headers, body -> WebSearch.post(url, headers, body) }
    internal var online: () -> Boolean = { try { com.tekyerema.core.NetworkMonitor.isOnline() } catch (_: Throwable) { true } }
    internal var clock: () -> Long = { System.currentTimeMillis() }
    /** The app's own key for the Space (owner, 2 Nov 2026: "secret key for the app"): the Space gives a keyed caller its own,
     *  higher limit. It names the APP, never the user — no account, id or cookie is ever sent. Debug builds only (BuildConfig,
     *  from local.properties SPACE_APP_KEY); a release build sends none and gets the public limits. Tests swap it. */
    internal var appKey: () -> String = { try { com.tekyerema.BuildConfig.SPACE_APP_KEY } catch (_: Throwable) { "" } }
    const val KEY_HEADER = "X-Nexus-Key"

    internal var timeoutOverrideMs: Long? = null

    internal fun reset() { coolUntil = 0L; timeoutOverrideMs = null }
    internal fun cooling() = clock() < coolUntil

    private fun enabled() = AppConfig.bool("answers.search.space.enabled", true)
    private fun timeoutMs() = timeoutOverrideMs ?: (AppConfig.long("answers.search.space.timeoutSec", 12L).coerceIn(2L, 30L) * 1000L)
    private fun mode() = AppConfig.string("answers.search.space.mode", "answer").trim().lowercase()
        .takeIf { it == "answer" || it == "results" } ?: "answer"

    private fun configuredUrl(): String? = normalizeUrl(
        AppConfig.string("answers.search.space.url", ""),
        try { com.tekyerema.language.LanguageLayer.secrets()["SPEECH_SPACE_URL"] } catch (_: Throwable) { null }
    )

    /** Off, no Space address, offline, or cooling after a failure → skipped without a request. */
    override fun isConfigured(ctx: Context): Boolean =
        enabled() && configuredUrl() != null && !cooling() && online()

    override suspend fun search(query: String, ctx: Context): SearchResult? {
        val url = configuredUrl() ?: return null
        if (!enabled() || cooling() || !online()) return null
        return ask(query, url, mode())
    }

    /** One call to the Space. Null on any failure; never throws except cancellation. */
    internal suspend fun ask(query: String, url: String, mode: String = "answer"): SearchResult? = withContext(Dispatchers.IO) {
        val headers = appKey().trim().takeIf { it.isNotEmpty() }?.let { mapOf(KEY_HEADER to it) } ?: emptyMap()
        val reply = withTimeoutOrNull(timeoutMs()) { transport(url, headers, requestBody(query, mode)) }
        if (reply == null) {
            cool(DEFAULT_COOL_MS)
            WebSearch.warn("space: no reply (offline, asleep or slow) — trying the next provider")
            return@withContext null
        }
        when {
            reply.code in 200..299 -> {
                val parsed = SpaceSearchParser.parse(reply.body)
                if (parsed == null) { cool(DEFAULT_COOL_MS); WebSearch.warn("space: reply had no sourced answer") }
                else coolUntil = 0L
                parsed
            }
            reply.code == 429 -> {
                cool((reply.retryAfterMs ?: DEFAULT_429_COOL_MS).coerceIn(10_000L, 3_600_000L))
                WebSearch.warn("space: HTTP 429 (its limit) → next provider")
                null
            }
            else -> {
                cool(DEFAULT_COOL_MS)
                WebSearch.warn("space: HTTP ${reply.code}")
                null
            }
        }
    }

    private fun cool(ms: Long) { coolUntil = clock() + ms }

    /** The whole request body: the query and the mode. Nothing else may ever be added here. */
    internal fun requestBody(query: String, mode: String): JSONObject = JSONObject().put("q", query).put("mode", mode)

    /** The configured URL, else the Space host the app already uses; https only; always ends in /web/ask. Null when none. */
    internal fun normalizeUrl(configured: String?, spaceHost: String?): String? {
        val base = configured?.trim().orEmpty().ifBlank { spaceHost?.trim().orEmpty() }.trimEnd('/')
        if (!base.startsWith("https://", ignoreCase = true) || base.length <= "https://".length) return null
        return if (base.endsWith(PATH)) base else base + PATH
    }
}

/** `/web/ask` reply → answer + sources. Needs a non-blank answer, `searched` not false, and at least one http(s) source. */
object SpaceSearchParser {
    fun parse(body: String): SearchResult? {
        val json = J.obj(body) ?: return null
        if (json.has("searched") && !json.optBoolean("searched", true)) return null   // an unsourced model answer is not a web answer
        val answer = SearchText.finish(J.str(json, "answer"))
        if (answer.isBlank()) return null
        val sources = SourceList()
        J.objects(json.optJSONArray("sources")).forEach { sources.add(J.str(it, "title").ifBlank { J.str(it, "site") }, J.str(it, "url")) }
        if (sources.isEmpty) return null
        return SearchResult(answer, sources.toList(), "space")
    }
}
