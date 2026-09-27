package com.tekyerema.agent

import android.util.Log

/**
 * Cloud-brain fallback chain (demo-readiness): try each configured cloud brain in order and move to the
 * next ONLY when one returns a hard [AgentDecision.Fail] (e.g. Groq rate-limited across all its keys, or an
 * HTTP/parse error). This lets Tɛkyerɛma use EVERY available credential — the Groq keys (rotated inside
 * [OpenAiCompatBrain]) first, then Gemini — so a drained free-tier quota no longer means "nothing happened."
 * A non-Fail decision (CallTool / Done / CannotDo) is returned immediately (never override a real answer).
 * Pure of Android beyond logging; the wrapped brains own their own I/O.
 */
class FallbackBrain(private val brains: List<AgentBrain>) : AgentBrain {
    override suspend fun decide(ctx: BrainContext): AgentDecision {
        var last: AgentDecision = AgentDecision.Fail("no cloud brain configured")
        for ((i, b) in brains.withIndex()) {
            val d = b.decide(ctx)
            if (d !is AgentDecision.Fail) return d
            last = d
            if (i < brains.lastIndex)
                Log.w("Tekyerema", "[BRAIN] cloud #${i + 1}/${brains.size} failed (${d.reason.take(80)}) → trying next")
        }
        return last
    }
}
