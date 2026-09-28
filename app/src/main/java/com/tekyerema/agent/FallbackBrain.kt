package com.tekyerema.agent

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Cloud-brain fallback chain (demo-readiness): try each configured cloud brain in order and move to the
 * next ONLY when one returns a hard [AgentDecision.Fail] (e.g. Groq rate-limited across all its keys, or an
 * HTTP/parse error). This lets Tɛkyerɛma use EVERY available credential — the Groq keys (rotated inside
 * [OpenAiCompatBrain]) first, then Gemini — so a drained free-tier quota no longer means "nothing happened."
 * A non-Fail decision (CallTool / Done / CannotDo) is returned immediately (never override a real answer).
 *
 * Network blips: when EVERY brain failed for a connectivity reason (DNS, timeout, connection reset — one
 * mobile-data hiccup takes out all providers at once), the chain waits [networkRetryDelayMs] and tries once
 * more before giving up; seen on-device, a single DNS failure otherwise ended a task that was one step from
 * done. If the retry also fails, the user hears the real reason ([NETWORK_LOST]) rather than "That didn't work."
 *
 * Only a QUICK failure is a blip worth retrying. A chain that failed slowly (every provider ran into its timeout)
 * means the connection is stalled, and a second round just doubles the silence: on the A20e (2026-09-28, a campus
 * Wi-Fi dropping connections) Groq timed out at 35 s, Gemini failed 16 s later, the retry did the same, and the
 * user heard "Still working on it" for 2 min 40 s before the error. Past [retryWithinMs] the user is told at once.
 * Pure of Android beyond logging; the wrapped brains own their own I/O.
 */
class FallbackBrain(
    private val brains: List<AgentBrain>,
    private val networkRetryDelayMs: Long = 1500L,
    private val retryWithinMs: Long = com.tekyerema.core.config.AppConfig.long("models.planner.networkRetryWithinMs", 10_000L),
    /** N-15 hedge: ask the next brain in parallel once the current one has been silent this long (0 = strictly one after another). */
    private val hedgeAfterMs: Long = com.tekyerema.core.config.AppConfig.long("models.planner.fallbackHedgeMs", 16_000L)
) : AgentBrain {
    override suspend fun decide(ctx: BrainContext): AgentDecision {
        val t0 = System.currentTimeMillis()
        val first = chain(ctx)
        if (first !is AgentDecision.Fail || !isNetworkError(first.reason)) return first
        val tookMs = System.currentTimeMillis() - t0
        if (tookMs > retryWithinMs) {
            Log.w("Tekyerema", "[BRAIN] every cloud brain failed on the network after ${tookMs / 1000}s — not retrying a stalled connection")
            return AgentDecision.CannotDo(NETWORK_LOST, "The internet connection is too slow right now, so I couldn't finish that. Please try again.")
        }
        Log.w("Tekyerema", "[BRAIN] every cloud brain hit a network error — retrying once in ${networkRetryDelayMs}ms")
        delay(networkRetryDelayMs)
        val second = chain(ctx)
        if (second is AgentDecision.Fail && isNetworkError(second.reason))
            return AgentDecision.CannotDo(NETWORK_LOST, "I lost the internet connection, so I couldn't finish that. Please try again.")
        return second
    }

    /**
     * N-15 hedge: the next provider no longer waits for the previous one's full timeout. Brain #1 still goes first; if it
     * hasn't decided after [hedgeAfterMs] (a normal step takes 1–2 s), brain #2 is asked IN PARALLEL and the first real
     * decision wins — the slower one is cancelled. A20e, 2026-09-28: Groq hung 35 s on every step while Gemini, asked
     * only afterwards, answered in 4 s; hedged, such a step costs ~20 s instead of ~40 s. A quick Fail still moves on at
     * once, exactly as before. The free Gemini tier (20 requests/day) is only touched when the first brain is this slow.
     */
    private suspend fun chain(ctx: BrainContext): AgentDecision {
        if (brains.isEmpty()) return AgentDecision.Fail("no cloud brain configured")
        val finished = BooleanArray(brains.size)
        val attempts = brains.mapIndexed { i, b ->
            suspend {
                if (i > 0 && !finished[i - 1])
                    Log.w("Tekyerema", "[BRAIN] cloud #$i/${brains.size} hasn't answered in ${hedgeAfterMs / 1000}s → asking cloud #${i + 1} in parallel")
                val d = try { b.decide(ctx) }
                    catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
                    catch (t: Throwable) { AgentDecision.Fail("brain error: ${t.message}") }
                finished[i] = true
                if (d is AgentDecision.Fail && i < brains.lastIndex)
                    Log.w("Tekyerema", "[BRAIN] cloud #${i + 1}/${brains.size} failed (${d.reason.take(80)}) → trying next")
                d
            }
        }
        val hedge = if (hedgeAfterMs > 0) hedgeAfterMs else Long.MAX_VALUE / 4
        val out = Hedge.race(attempts, hedge) { it !is AgentDecision.Fail }
        out.winner?.let {
            if (out.winnerIndex > 0 && out.results.take(out.winnerIndex).any { r -> r == null })
                Log.w("Tekyerema", "[BRAIN] cloud #${out.winnerIndex + 1} answered first — the slower brain was cancelled")
            return it
        }
        // Report the most informative failure: a later provider's quota 429 (the free Gemini tier is 20 requests/day)
        // must not mask a different, real problem upstream.
        val fails = out.results.filterIsInstance<AgentDecision.Fail>()
        return fails.firstOrNull { !RATE_LIMIT.containsMatchIn(it.reason) } ?: fails.lastOrNull()
            ?: AgentDecision.Fail("no cloud brain configured")
    }

    companion object {
        /** Not escalatable (RoutingBrain only escalates DEFER/NETWORK from the LOCAL brain). */
        const val NETWORK_LOST = "network_lost"

        private val NETWORK = Regex(
            "unable to resolve host|unknownhost|no address associated|timed? ?out|timeout|failed to connect|" +
                "connection (reset|refused|abort)|network is unreachable|software caused connection abort|" +
                "unexpected end of stream|eofexception|ssl.*(handshake|closed)",
            RegexOption.IGNORE_CASE
        )

        private val RATE_LIMIT = Regex("(?i)429|rate.?limit|quota|too many")

        /** True for connectivity failures (worth one retry), false for quota/HTTP/parse errors. */
        fun isNetworkError(reason: String): Boolean = NETWORK.containsMatchIn(reason)
    }
}
