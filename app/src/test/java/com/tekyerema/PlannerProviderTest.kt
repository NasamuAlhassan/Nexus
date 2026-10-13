package com.tekyerema

import com.tekyerema.agent.OpenAiCompatBrain
import com.tekyerema.agent.PlannerProvider
import com.tekyerema.core.config.AppConfig
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-15 (R-A6, 13 October 2026): extra OpenAI-compatible planner rungs are data — config under models.planner.extra plus a
 * key from local.properties — and the request body takes the shape each provider needs (one tool call per reply, no
 * reasoning_effort where it is rejected, the right cap field). A rung without a key does not exist.
 */
class PlannerProviderTest {
    private val cfg = """{"models":{"planner":{"extra":{
        "order":["zai","nvidia"],
        "zai":{"baseUrl":"https://api.z.ai/api/paas/v4/","model":"glm-4.7-flash","fallbacks":[],"toolChoice":"auto","parallelToolCalls":"false","reasoningEffort":false,"maxTokensField":"max_tokens"},
        "nvidia":{"baseUrl":"https://integrate.api.nvidia.com/v1","model":"openai/gpt-oss-120b","fallbacks":["openai/gpt-oss-20b"],"parallelToolCalls":"false","maxTokensField":"max_tokens"}
    }}}}"""

    @After fun reset() { AppConfig.loadFrom(emptyList()) }

    @Test fun keys_from_the_build_field_parse_and_a_rung_exists_only_with_a_key() {
        AppConfig.loadFrom(listOf("models.json" to cfg))
        val keys = PlannerProvider.parseKeys("zai=abc123; nvidia = nv-key ;broken;=nokey;empty=")
        assertEquals(mapOf("zai" to "abc123", "nvidia" to "nv-key"), keys)
        assertEquals(listOf("zai", "nvidia"), PlannerProvider.extraIds())
        val zai = PlannerProvider.extra("zai") { keys[it] }!!
        assertEquals("https://api.z.ai/api/paas/v4/chat/completions", zai.chatUrl)
        assertEquals(listOf("abc123"), zai.keys)
        assertEquals("glm-4.7-flash", zai.model)
        assertEquals(false, zai.parallelToolCalls); assertFalse(zai.reasoningEffort); assertEquals("max_tokens", zai.maxTokensField)
        val nv = PlannerProvider.extra("nvidia") { keys[it] }!!
        assertEquals(listOf("openai/gpt-oss-20b"), nv.fallbacks); assertTrue(nv.reasoningEffort)
        assertNull("no key, no rung", PlannerProvider.extra("zai") { null })
        assertNull("not described in config", PlannerProvider.extra("mistral") { "k" })
        assertTrue(PlannerProvider.parseKeys(null).isEmpty()); assertTrue(PlannerProvider.parseKeys("").isEmpty())
    }

    @Test fun the_request_body_takes_the_shape_the_provider_needs() {
        val msgs = JSONArray(); val tools = JSONArray()
        // Groq / the shipped shape: unchanged.
        val groq = OpenAiCompatBrain.requestBody("openai/gpt-oss-120b", msgs, tools, 0.2, "high", 900)
        assertEquals("auto", groq.getString("tool_choice")); assertFalse(groq.has("parallel_tool_calls"))
        assertEquals("high", groq.getString("reasoning_effort")); assertEquals(900, groq.getInt("max_completion_tokens"))
        // Z.ai: one tool call per reply, no reasoning_effort, max_tokens.
        val zai = PlannerProvider("zai", "https://api.z.ai/api/paas/v4", listOf("k"), "glm-4.7-flash", emptyList(),
            parallelToolCalls = false, reasoningEffort = false, maxTokensField = "max_tokens")
        val z = OpenAiCompatBrain.requestBody("glm-4.7-flash", msgs, tools, 0.2, "high", 900, zai)
        assertEquals(false, z.getBoolean("parallel_tool_calls")); assertFalse(z.has("reasoning_effort"))
        assertEquals(900, z.getInt("max_tokens")); assertFalse(z.has("max_completion_tokens"))
        // A provider that rejects any cap field gets none.
        val bare = zai.copy(id = "bare", maxTokensField = null)
        val b = OpenAiCompatBrain.requestBody("m", msgs, tools, 0.2, null, 900, bare)
        assertFalse(b.has("max_tokens")); assertFalse(b.has("max_completion_tokens")); assertFalse(b.has("reasoning_effort"))
        // The log names the provider.
        assertTrue(OpenAiCompatBrain.usageLine("m", 0, 1, 200, 10, null, null, "nvidia").startsWith("nvidia/m key#1/1"))
    }
}
