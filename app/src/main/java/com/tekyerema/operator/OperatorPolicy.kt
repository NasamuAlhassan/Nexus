package com.tekyerema.operator

import com.tekyerema.policy.CapabilityScope

/**
 * CONTRACT (docs/agent/AGENTIC_ARCHITECTURE.md §Sovereignty) — the user/operator's standing choices that every layer
 * honours: how much the agent may do on its own, which providers and models it may use, what it may spend, and whether
 * remote control is allowed. No setting can lower the safety floor (confirmations for consequential actions, Secure
 * Input, the deny list) — these only restrict further.
 *
 * Stub: fixed defaults (today's behaviour). Workstream C backs this with Settings + UI + voice commands; the router
 * (Workstream B), policy engine and MCP server (Workstream D) read it.
 */
enum class ExecutionMode {
    /** Explains what to do; never touches the phone. */
    GUIDE_ONLY,
    /** Asks before every step that changes anything. */
    ASK_EVERY_STEP,
    /** Acts on its own; asks before anything consequential (the default and the floor). */
    STANDARD
}

/** May a weaker fallback model take over a complex task? */
enum class WeakModelPolicy { NEVER, ASK, ALLOW }

object OperatorPolicy {
    @Volatile var executionMode: ExecutionMode = ExecutionMode.STANDARD
    /** false = nothing leaves the phone (on-device answers and actions only). */
    @Volatile var cloudAllowed: Boolean = true
    /** Providers the router may use, in order of preference. */
    @Volatile var providers: List<String> = listOf("groq", "gemini")
    @Volatile var weakModelPolicy: WeakModelPolicy = WeakModelPolicy.ASK
    @Volatile var maxStepsPerTask: Int = 30
    /** 0 = no cap. When reached, the agent falls back to on-device only (never a silent provider switch). */
    @Volatile var dailyCloudCallCap: Int = 0
    @Volatile var dailyTokenCap: Int = 0
    @Volatile var remoteControlEnabled: Boolean = false
    @Volatile var revokedScopes: Set<CapabilityScope> = emptySet()

    private var day = 0L
    private var calls = 0
    private var tokens = 0L

    @Synchronized fun recordUsage(provider: String, tokenCount: Int, now: Long = System.currentTimeMillis()) {
        val d = now / 86_400_000L
        if (d != day) { day = d; calls = 0; tokens = 0 }
        calls++; tokens += tokenCount
    }

    @Synchronized fun usageToday(): Pair<Int, Long> = calls to tokens

    @Synchronized fun budgetExhausted(): Boolean =
        (dailyCloudCallCap > 0 && calls >= dailyCloudCallCap) || (dailyTokenCap > 0 && tokens >= dailyTokenCap)
}
