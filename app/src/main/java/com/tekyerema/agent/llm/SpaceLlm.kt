package com.tekyerema.agent.llm

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.LlmSession
import com.tekyerema.agent.LocalModelBrain
import com.tekyerema.agent.OnDeviceLlm
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Our OWN fine-tuned agent model, served free from our Hugging Face Space, in the on-device planner's seat (2 Nov 2026,
 * owner: no paid or third-party cloud brains). It is an [OnDeviceLlm] whose engine is one HTTPS call, so
 * [LocalModelBrain] builds exactly the prompt the model was trained on ([PlannerPrompt] — system once, then a first turn,
 * then next turns) and this class only carries the conversation to the Space and the reply back.
 *
 * Contract (docs/agent, the Space's `/agent/chat`):
 *   `POST <space>/agent/chat  {"messages":[{"role":"system"|"user"|"assistant","content":"…"}], "max_tokens": n}`
 *   → `{"content":"…","ms":n}`; failures `{"error":"…"}` with 400 (bad request), 429 (the Space's limiter) or 503 (loading/busy).
 * The Space is stateless, so a session keeps its own message list and resends it whole each turn.
 *
 * Privacy: the body is the planner's own messages and `max_tokens`, nothing else. Headers are the app key
 * (`X-Nexus-Key`, debug builds) and the rotating fair-use tag (`X-Nexus-Device`), the same two the other Space calls send.
 * https only. The user's saved memory and failure hints are NOT in those messages unless the user switched
 * "share memory with the cloud assistant" on ([plannerBrain]). Prompt text is never logged.
 *
 * Any failure (offline, timeout, 429, 503, an unreadable reply) returns null and the Space is skipped for a while
 * (a 429 for the time it says, else 2 minutes; anything else a minute) so a sleeping or busy Space doesn't add its wait to
 * every step; the brain then reports it could not answer and the router falls back as it does without any cloud.
 */
class SpaceLlm(
    private val url: String,
    private val headers: () -> Map<String, String>,
    /** The HTTP layer; tests swap it for a fake. */
    private val transport: suspend (url: String, headers: Map<String, String>, body: JSONObject) -> Reply? = { u, h, b -> httpPost(u, h, b) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : OnDeviceLlm {
    class Reply(val code: Int, val body: String, val retryAfterMs: Long? = null)

    override val id: String = "space-agent"

    /** Why the last call returned null, worded so [com.tekyerema.agent.FallbackBrain] classes it (timeout / 429 / 503). */
    @Volatile var failure: String? = null
        private set

    override suspend fun complete(prompt: String, maxTokens: Int, timeoutMs: Long): String? =
        exchange(listOf("user" to prompt), maxTokens, timeoutMs)

    override suspend fun chat(system: String, user: String, jsonSchema: String?, maxTokens: Int, timeoutMs: Long): String? =
        exchange(if (system.isBlank()) listOf("user" to user) else listOf("system" to system, "user" to user), maxTokens, timeoutMs)

    /** One task's conversation: [system] once, then each turn's user text and the model's reply, all resent every turn. */
    override fun openSession(system: String, maxTokens: Int): LlmSession = object : LlmSession {
        private val messages = ArrayList<Pair<String, String>>().apply { if (system.isNotBlank()) add("system" to system) }
        @Volatile private var held = estimate(messages)
        override val tokens: Int get() = held

        override suspend fun send(user: String, jsonSchema: String?, timeoutMs: Long): String? {
            // A turn that failed leaves nothing behind: the next send resends the same history without it.
            val reply = exchange(messages + ("user" to user), maxTokens, timeoutMs) ?: return null
            messages.add("user" to user)
            messages.add("assistant" to reply)
            held = estimate(messages)
            return reply
        }

        override fun close() { messages.clear(); held = 0 }
    }

    private suspend fun exchange(messages: List<Pair<String, String>>, maxTokens: Int, timeoutMs: Long): String? {
        if (!url.startsWith("https://", ignoreCase = true) || url.length <= "https://".length) { failure = "space planner needs an https address"; return null }
        val now = clock()
        if (now < coolUntil) { failure = coolReason; return null }
        val timeout = timeoutMs.coerceIn(2_000L, 120_000L)
        val reply = withContext(Dispatchers.IO) {
            try { withTimeoutOrNull(timeout) { transport(url, headers(), requestBody(messages, maxTokens)) } }
            catch (ce: CancellationException) { throw ce }
            catch (_: Throwable) { null }
        }
        if (reply == null) {
            fail("timeout: the Space planner did not answer in ${timeout / 1000} s (offline, asleep or slow)", DEFAULT_COOL_MS)
            return null
        }
        return when (reply.code) {
            in 200..299 -> parseContent(reply.body)?.also { coolUntil = 0L; failure = null }
                ?: run { fail("the Space planner's reply was unreadable", DEFAULT_COOL_MS); null }
            429 -> { fail("429 rate limit: the Space planner is at its limit", (reply.retryAfterMs ?: DEFAULT_429_COOL_MS).coerceIn(10_000L, 3_600_000L)); null }
            503 -> { fail("503 overloaded: the Space planner is loading or busy", DEFAULT_COOL_MS); null }
            400 -> { failure = "the Space planner refused the request (400)"; null }   // our request, not the Space: no cool-down
            else -> { fail("the Space planner failed (HTTP ${reply.code})", DEFAULT_COOL_MS); null }
        }
    }

    private fun fail(why: String, coolMs: Long) {
        failure = why
        coolReason = why
        coolUntil = clock() + coolMs
        try { android.util.Log.w("Tekyerema", "[LLM] space-agent: $why") } catch (_: Throwable) {}
    }

    companion object {
        const val PATH = "/agent/chat"
        private const val DEFAULT_COOL_MS = 60_000L
        private const val DEFAULT_429_COOL_MS = 2 * 60_000L

        // Shared by every instance: a brain is made per task, the Space's mood is not.
        @Volatile private var coolUntil = 0L
        @Volatile private var coolReason = ""
        internal fun resetCooling() { coolUntil = 0L; coolReason = "" }
        internal fun cooling(now: Long = System.currentTimeMillis()) = now < coolUntil

        /** The whole request body: the messages and the token cap. Nothing else may ever be added here. */
        internal fun requestBody(messages: List<Pair<String, String>>, maxTokens: Int): JSONObject = JSONObject()
            .put("messages", JSONArray().also { a -> messages.forEach { (role, content) -> a.put(JSONObject().put("role", role).put("content", content)) } })
            .put("max_tokens", maxTokens)

        /** `{"content": "…"}` → the text; null for anything else (an `error`, an empty or unreadable body). */
        internal fun parseContent(body: String): String? = try {
            JSONObject(body).optString("content", "").takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }

        /** Rough size of a conversation in tokens (4 characters each); the brain starts a fresh session past its budget. */
        internal fun estimate(messages: List<Pair<String, String>>): Int = messages.sumOf { (it.second.length + 16) / 4 }

        /** The `/agent/chat` address for a configured Space host, or null when there is none or it isn't https. */
        internal fun endpoint(raw: String?): String? =
            com.tekyerema.language.SpaceVoice.normalize(raw?.removeSuffix(PATH))?.plus(PATH)

        /** Config `models.planner.space.url`, else the Space host the app's other Space calls use (SPEECH_SPACE_URL). */
        private fun configuredUrl(): String? =
            endpoint(AppConfig.string("models.planner.space.url", "").ifBlank { null }) ?: endpoint(
                try { com.tekyerema.language.LanguageLayer.secrets()["SPEECH_SPACE_URL"] } catch (_: Throwable) { null })

        /** The Space planner can be used on this build: switched on (`models.planner.space.enabled`, default true) and an https address. */
        fun configured(): Boolean = AppConfig.bool("models.planner.space.enabled", true) && configuredUrl() != null

        /** The app's key (debug builds) and the rotating fair-use tag, the headers every Space call carries. */
        private fun phoneHeaders(): Map<String, String> {
            val h = LinkedHashMap<String, String>()
            (try { com.tekyerema.BuildConfig.SPACE_APP_KEY } catch (_: Throwable) { "" }).trim().takeIf { it.isNotEmpty() }
                ?.let { h[com.tekyerema.language.SpaceVoice.KEY_HEADER] = it }
            try {
                com.tekyerema.agent.AgentEngine.appContext()?.let {
                    h[com.tekyerema.language.SpaceVoice.DEVICE_HEADER] = com.tekyerema.ime.KeyboardPrefs(it).deviceTag(System.currentTimeMillis())
                }
            } catch (_: Throwable) {}
            return h
        }

        /** The planner brain for the Space: the trained prompt format, no memory or hints leaving the phone unless shared. */
        internal fun plannerBrain(llm: SpaceLlm): LocalModelBrain = LocalModelBrain(
            llm,
            // The on-phone planner reads the user's own notes and memory; the Space is not the phone. Both are empty unless the
            // user switched "share memory with the cloud assistant" on (Settings.shareMemoryWithCloud, default off).
            hints = { goal -> if (com.tekyerema.memory.PlannerMemory.shareWithCloud()) com.tekyerema.learning.FailureLearning.hintsFor(goal) else emptyList() },
            memory = { goal -> com.tekyerema.memory.PlannerMemory.forCloud(goal) },
            // A planner turn the Space could not answer is a Fail, so the cloud chain classes it (timeout / quota) like any provider.
            noAnswer = { AgentDecision.Fail(llm.failure ?: "the Space planner gave no answer") },
            timeoutMs = AppConfig.long("models.planner.space.timeoutSec", 25L).coerceIn(5L, 90L) * 1000L,
            maxTokens = AppConfig.int("models.planner.space.maxTokens", 200),
            maxContextTokens = AppConfig.int("models.planner.space.maxContextTokens", 6_000)
        )

        /** The cloud planner for this phone, or null when the Space isn't configured (no address, not https, switched off). */
        fun forPhone(): LocalModelBrain? {
            if (!AppConfig.bool("models.planner.space.enabled", true)) return null
            val url = configuredUrl() ?: return null
            return plannerBrain(SpaceLlm(url, { phoneHeaders() }))
        }

        private suspend fun httpPost(url: String, headers: Map<String, String>, body: JSONObject): Reply? {
            val request = try {
                okhttp3.Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            } catch (_: IllegalArgumentException) { return null }
            val callSec = AppConfig.long("models.planner.space.timeoutSec", 25L).coerceIn(5L, 90L) + 5L   // the coroutine timeout fires first
            val client = com.tekyerema.core.Http.client(8L, callSec)
            return try {
                val (code, text, h) = com.tekyerema.core.Http.execute(client.newCall(request))
                Reply(code, text, h["retry-after"]?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() })
            } catch (ce: CancellationException) { throw ce }
            catch (_: Throwable) { null }
        }
    }
}
