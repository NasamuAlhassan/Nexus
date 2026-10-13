package com.tekyerema

import com.tekyerema.money.ussd.OnlineFallback
import org.junit.Assert.assertEquals
import org.junit.Test

/** B-14: the USSD menu helper's URL doubled `/v1` with the configured Groq base URL, so it could never answer. */
class OnlineFallbackUrlTest {
    @Test fun `the chat-completions URL has exactly one v1 whatever the base URL ends in`() {
        assertEquals("https://api.groq.com/openai/v1/chat/completions", OnlineFallback.chatCompletionsUrl("https://api.groq.com/openai/v1"))
        assertEquals("https://api.groq.com/openai/v1/chat/completions", OnlineFallback.chatCompletionsUrl("https://api.groq.com/openai/v1/"))
        assertEquals("https://api.groq.com/openai/v1/chat/completions", OnlineFallback.chatCompletionsUrl("https://api.groq.com/openai"))
        assertEquals("http://10.0.2.2:8080/v1/chat/completions", OnlineFallback.chatCompletionsUrl("http://10.0.2.2:8080/v1"))
    }

    @Test fun `the configured base URL in models json resolves to a single v1 path`() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        val base = org.json.JSONObject(java.io.File(dir, "models.json").readText()).getJSONObject("providers").getJSONObject("groq").getString("baseUrl")
        val url = OnlineFallback.chatCompletionsUrl(base)
        assertEquals(1, Regex("/v1/").findAll(url).count())
        assertEquals("https://api.groq.com/openai/v1/chat/completions", url)
    }
}
