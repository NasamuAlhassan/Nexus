package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig

/**
 * N-06 — provider-agnostic model layer: each ROLE is assigned a provider and model in config (`models.<role>`), and
 * providers are addressed by id with their endpoint in `providers.<id>.baseUrl`. Every provider speaks one of two
 * wire formats already in the app: OpenAI-compatible chat completions ([OpenAiCompatBrain] — Groq today, and any
 * OpenAI-compatible server such as a local Ollama by base URL) or Gemini ([GeminiBrain]).
 *
 *   planner  the step-by-step phone operator       models.planner.{model, fallbacks, chain}
 *   fast     simple steps / direct answers (C-08)   models.planner.fastModel
 *   search   web answers (I-03)                     answers.search.{providers, groq.models, gemini.model}
 *   guard    injection screening (J-19)             models.guard.model
 *   vision   camera descriptions (P-01)             models.vision.model (default: the Gemini model)
 *
 * [plannerChain] is the order cloud providers are tried for the planner role (`models.planner.chain`,
 * default groq → gemini); a provider without a key, or without the user's consent (J-18), is skipped.
 */
object ModelRoles {
    const val GROQ = "groq"
    const val GEMINI = "gemini"

    /** WS-C: the user's provider choice (Settings › Control, OperatorPolicy.providers) orders the configured chain and can
     *  only remove providers from it; a configured provider the user can't choose yet stays at the end. */
    fun plannerChain(): List<String> {
        val configured = AppConfig.stringList("models.planner.chain", listOf(GROQ, GEMINI)).map { it.trim().lowercase() }.distinct()
        val user = com.tekyerema.operator.OperatorPolicy.providers
        return user.filter { it in configured } + configured.filter { it !in com.tekyerema.operator.OperatorPolicy.KNOWN_PROVIDERS }
    }

    /** B / sovereignty: the chain the operator allows — none when the cloud is off, only the providers they permit. */
    fun plannerChainAllowed(policy: com.tekyerema.operator.OperatorPolicy = com.tekyerema.operator.OperatorPolicy): List<String> =
        com.tekyerema.agent.routing.ModelRouting.allowedChain(plannerChain(), policy.providers, policy.cloudAllowed)

    /** The model for [role], from `models.<role>.model`, else [default]. */
    fun model(role: String, default: String): String = AppConfig.string("models.$role.model", default).ifBlank { default }
}
