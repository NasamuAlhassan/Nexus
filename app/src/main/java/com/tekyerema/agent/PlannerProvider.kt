package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig

/**
 * One OpenAI-compatible planner endpoint (P1-15, research R-A6, 13 October 2026). Groq — the shipped brain — is built from
 * Settings as before; EXTRA rungs come from config `models.planner.extra.<id>` (base URL, model, fallbacks, request shape)
 * with their key from `local.properties` (`PLANNER_KEY_<ID>` → BuildConfig `DEFAULT_PLANNER_KEYS`, debug builds only). A rung
 * with no key does not exist at run time, so the shipped config can describe the no-card providers (Z.ai GLM-4.7-Flash,
 * NVIDIA's gpt-oss-120b) without changing anything until the owner adds a key.
 *
 * The request-shape fields are the differences that break an agent loop when ignored (R-A6): `parallel_tool_calls` defaults
 * ON elsewhere (two calls for one step), some providers accept only `tool_choice: "auto"`, some reject `reasoning_effort`,
 * and the completion cap is `max_tokens` on older gateways.
 */
data class PlannerProvider(
    val id: String,
    val baseUrl: String,
    val keys: List<String>,
    val model: String,
    val fallbacks: List<String>,
    val toolChoice: String = "auto",
    /** null = leave the provider's default (Groq: one call); false = ask for one tool call per reply. */
    val parallelToolCalls: Boolean? = null,
    /** Send `reasoning_effort` (gpt-oss on Groq/NVIDIA understands it; a provider that rejects unknown fields must not get it). */
    val reasoningEffort: Boolean = true,
    /** The field that caps the reply, or null for a provider that rejects both. */
    val maxTokensField: String? = "max_completion_tokens"
) {
    val chatUrl: String get() = baseUrl.trimEnd('/') + "/chat/completions"

    companion object {
        private const val EXTRA = "models.planner.extra"

        /** The shipped brain: Groq (or the user's own OpenAI-compatible server) exactly as Settings describes it. */
        fun groq(settings: com.tekyerema.settings.Settings): PlannerProvider = PlannerProvider(
            "groq", settings.groqBaseUrl, settings.groqApiKeys, settings.groqModel,
            AppConfig.stringList("models.planner.fallbacks", listOf("openai/gpt-oss-20b")))

        /** The ids described under `models.planner.extra`, in config order. */
        fun extraIds(): List<String> = AppConfig.stringList("$EXTRA.order", emptyList())

        /** The rung [id] when config describes it AND [keyFor] has a key for it; null otherwise (no rung). */
        fun extra(id: String, keyFor: (String) -> String?): PlannerProvider? {
            val p = id.trim().lowercase()
            val base = AppConfig.string("$EXTRA.$p.baseUrl", "")
            val model = AppConfig.string("$EXTRA.$p.model", "")
            val key = keyFor(p)?.trim().orEmpty()
            if (base.isBlank() || model.isBlank() || key.isBlank()) return null
            val parallel = AppConfig.string("$EXTRA.$p.parallelToolCalls", "").trim().lowercase()
            return PlannerProvider(
                id = p, baseUrl = base, keys = listOf(key), model = model,
                fallbacks = AppConfig.stringList("$EXTRA.$p.fallbacks", emptyList()),
                toolChoice = AppConfig.string("$EXTRA.$p.toolChoice", "auto"),
                parallelToolCalls = when (parallel) { "true" -> true; "false" -> false; else -> null },
                reasoningEffort = AppConfig.bool("$EXTRA.$p.reasoningEffort", true),
                maxTokensField = AppConfig.string("$EXTRA.$p.maxTokensField", "max_completion_tokens").ifBlank { null })
        }

        /** `"zai=…;nvidia=…"` (BuildConfig `DEFAULT_PLANNER_KEYS`) → id → key. Blank or malformed entries are dropped. */
        fun parseKeys(raw: String?): Map<String, String> = raw.orEmpty().split(';').mapNotNull { e ->
            val kv = e.split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0].trim().lowercase() to kv[1].trim() else null
        }.toMap()
    }
}
