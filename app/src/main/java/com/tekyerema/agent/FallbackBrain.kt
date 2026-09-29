package com.tekyerema.agent

import android.util.Log
import com.tekyerema.agent.routing.ModelRouting
import com.tekyerema.agent.routing.QuotaAware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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
 *
 * N-10 / N-15 — three more guards against waiting on a dead link (same device logs: in a 4-step Chrome task Groq timed
 * out at 35 s on EVERY step and Gemini then answered in 4 s):
 *  - a watchdog: a call silent for [watchdogMs] triggers a quick [linkCheck]; if the internet isn't answering, the
 *    call is abandoned then (≈12 s) instead of at its 35 s timeout. A merely slow model passes the check and is waited for;
 *  - after one provider fails on the network, the link is checked before the next is tried — over a dead link the
 *    next provider would only wait out its own timeout (Gemini's 45 s);
 *  - a provider that just timed out goes to the back of the chain for a while ([ProviderCooling]), so the next step
 *    starts with the one that answers.
 * Pure of Android beyond logging; the wrapped brains own their own I/O, [linkCheck] is injected.
 */
class FallbackBrain(
    private val brains: List<AgentBrain>,
    private val networkRetryDelayMs: Long = 1500L,
    private val retryWithinMs: Long = com.tekyerema.core.config.AppConfig.long("models.planner.networkRetryWithinMs", 10_000L),
    /** Is the internet answering? null = can't tell here (JVM tests, probing off) → the chain behaves as it used to. */
    private val linkCheck: suspend () -> Boolean? = { com.tekyerema.core.LinkHealth.answering() },
    private val watchdogMs: Long = com.tekyerema.core.config.AppConfig.long("router.network.watchdogMs", 8_000L),
    private val health: ProviderCooling = ProviderCooling.shared,
    /** B: this task's quota-aware routing (pinned model, weak-model policy). null = the old chain: move on only on a Fail. */
    private val route: com.tekyerema.agent.routing.TaskRoute? = null,
    private val budgetExhausted: () -> Boolean = { com.tekyerema.operator.OperatorPolicy.budgetExhausted() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AgentBrain {
    /**
     * B — the routing policy (docs/agent/research/agenticness_reliability_state.md, routing table), on top of the chain:
     * budget → CannotDo budget_exhausted; a simple step as before (its quota failure is quota_exhausted); a complex step
     * on the capable providers with the pinned one first, then a TPM reset ≤ 60 s waited for, then the weak model only
     * per the operator's policy (NEVER → pause, ASK → ask the user once, ALLOW → carry on and say so).
     */
    override suspend fun decide(ctx: BrainContext): AgentDecision {
        val r = route ?: return decideChain(ctx)
        if (budgetExhausted()) return AgentDecision.CannotDo(ModelRouting.BUDGET_EXHAUSTED, ModelRouting.BUDGET_TEXT)
        if (!ModelRouting.complex(ctx)) {
            val d = decideChain(ctx)
            return if (d is AgentDecision.Fail && ModelRouting.isQuota(d.reason)) AgentDecision.CannotDo(ModelRouting.QUOTA_EXHAUSTED, ModelRouting.QUOTA_TEXT) else d
        }
        // The user answered the router's own "carry on with a smaller AI?" question.
        if (r.weakAsked && !r.weakApproved) ModelRouting.pendingAnswer(ctx.history)?.let { ans ->
            if (ModelRouting.weakAnswer(ans) == true) {
                r.weakApproved = true
                Log.i("Tekyerema", "[ROUTE] the user chose to carry on with the smaller model")
            } else return AgentDecision.CannotDo(ModelRouting.NO_CAPABLE_MODEL, ModelRouting.PAUSED_BY_USER_TEXT)
        }
        val first = decideChain(ctx)
        if (first !is AgentDecision.Fail || !quotaHit(first)) return first
        // Every capable provider is out of quota right now. A per-minute window refills within a minute: wait for it.
        val qa = brains.firstNotNullOfOrNull { it as? QuotaAware }
        val ready = qa?.capableReadyInMs(clock())
        if (ready != null && ready <= r.policy.tpmResetMaxWaitMs) {
            Log.i("Tekyerema", "[ROUTE] capable model back in ${ready} ms — waiting for it rather than downgrading")
            BrainActivity.pausingFor(ready + 250L)
            sleep(ready + 250L)
            val again = decideChain(ctx)
            if (again !is AgentDecision.Fail || !quotaHit(again)) return again
        }
        return when (val w = ModelRouting.weakDecision(r, ctx.effort, qa?.weakModel != null)) {
            is ModelRouting.Weak.Use -> {
                Log.w("Tekyerema", "[ROUTE] no capable model left → this step on the smaller model (${r.policy.weakModelPolicy})")
                val d = qa!!.decideWeak(ctx)
                when {
                    d !is AgentDecision.Fail -> { r.weakSteps++; r.degraded = true; d }
                    isNetworkError(d.reason) -> AgentDecision.CannotDo(NETWORK_LOST, STALLED_MESSAGE)
                    else -> AgentDecision.CannotDo(ModelRouting.QUOTA_EXHAUSTED, ModelRouting.PAUSED_TEXT)
                }
            }
            is ModelRouting.Weak.Ask -> {
                // Split by risk (owner, 2026-09-29): an answer, a question back or a look-up goes ahead on the smaller model
                // (the reply says so); a step that would change the phone is dropped and the user is asked first.
                val d = qa?.decideWeak(ctx)
                if (d != null && d !is AgentDecision.Fail && ModelRouting.safeOnWeak(d)) {
                    Log.w("Tekyerema", "[ROUTE] main model busy → a look-only step on the smaller model (split by risk)")
                    r.weakSteps++; r.degraded = true; d
                } else { r.weakAsked = true; ModelRouting.askDecision() }
            }
            is ModelRouting.Weak.Pause -> AgentDecision.CannotDo(w.reasonClass, w.text)
        }
    }

    /** Every provider failed, and at least one for capacity (a quota, a rate limit, an overloaded 503) — not the network. */
    private fun quotaHit(d: AgentDecision.Fail): Boolean =
        !isNetworkError(d.reason) && (ModelRouting.isQuota(d.reason) || lastFails.any { ModelRouting.isQuota(it.reason) })

    /** The failures of the last chain pass (for [quotaHit]). */
    @Volatile private var lastFails: List<AgentDecision.Fail> = emptyList()

    private suspend fun decideChain(ctx: BrainContext): AgentDecision {
        val t0 = System.currentTimeMillis()
        val first = chain(ctx)
        if (first !is AgentDecision.Fail || !isNetworkError(first.reason)) return first   // incl. a confirmed-dead link
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

    private fun key(i: Int) = "$i:${brains[i].javaClass.simpleName}"

    /** "groq" / "gemini" — for the journal's pinned model. */
    private fun label(i: Int) = brains[i].javaClass.simpleName.removeSuffix("Brain").lowercase().replace("openaicompat", "groq")

    private suspend fun chain(ctx: BrainContext): AgentDecision {
        val fails = ArrayList<AgentDecision.Fail>()
        val now = System.currentTimeMillis()
        // Stable: providers that timed out lately move to the back; otherwise the configured order stands.
        // B: a task's pinned provider goes first (unless it just timed out) — one model per task, not per step.
        val pinned = route?.pinnedIndex?.takeIf { ModelRouting.complex(ctx) && it in brains.indices }
        val order = brains.indices.sortedWith(compareBy<Int> { if (health.cooling(key(it), now)) 1 else 0 }.thenBy { if (it == pinned) 0 else 1 })
        if (order.isNotEmpty() && order.first() != 0)
            Log.i("Tekyerema", "[BRAIN] starting with cloud #${order.first() + 1} this step" + if (order.first() == pinned) " (pinned for this task)" else " (#1 timed out recently)")
        for ((n, i) in order.withIndex()) {
            val b = brains[i]
            val started = System.currentTimeMillis()
            // A brain that throws (a parse bug, a client crash) is a Fail — the next provider still gets its turn.
            val d = try { guarded(b, ctx) }
                catch (ce: CancellationException) { throw ce }
                catch (t: Throwable) { AgentDecision.Fail("brain error: ${t.javaClass.simpleName}: ${t.message}") }
            if (d !is AgentDecision.Fail) {
                health.ok(key(i))
                if (route != null && ModelRouting.complex(ctx)) route.pin(i, label(i))
                return d
            }
            // B: the pinned provider is out for the day — the next capable answer re-pins the task.
            if (route != null && route.pinnedIndex == i && ModelRouting.isDaily(d.reason)) route.unpin()
            if (d.reason == WATCHDOG_REASON) {
                // The link itself was checked and is down: no provider can answer, so don't make the user wait for each.
                health.networkFail(key(i), System.currentTimeMillis())
                return AgentDecision.CannotDo(NETWORK_LOST, STALLED_MESSAGE)
            }
            fails.add(d)
            if (isNetworkError(d.reason)) {
                val took = System.currentTimeMillis() - started
                // A slow network failure (a timeout) marks THIS provider; a quick blip is left to the retry above.
                if (took >= com.tekyerema.core.config.AppConfig.long("router.network.stall.slowFailMs", 6_000L))
                    health.networkFail(key(i), System.currentTimeMillis())
                if (n < order.lastIndex && linkCheck() == false) {
                    Log.w("Tekyerema", "[BRAIN] cloud #${i + 1} failed and the internet isn't answering — not trying the others")
                    return AgentDecision.CannotDo(NETWORK_LOST, STALLED_MESSAGE)
                }
            }
            if (n < order.lastIndex)
                Log.w("Tekyerema", "[BRAIN] cloud #${i + 1}/${brains.size} failed (${d.reason.take(80)}) → trying next")
        }
        lastFails = fails.toList()
        // Report the most informative failure: a later provider's quota 429 (the free Gemini tier is 20 requests/day)
        // must not mask a different, real problem upstream.
        return fails.firstOrNull { !RATE_LIMIT.containsMatchIn(it.reason) } ?: fails.lastOrNull()
            ?: AgentDecision.Fail("no cloud brain configured")
    }

    /** One provider call under the watchdog: silent past [watchdogMs] → is the internet still answering? */
    private suspend fun guarded(b: AgentBrain, ctx: BrainContext): AgentDecision {
        if (watchdogMs <= 0) return b.decide(ctx)
        return coroutineScope {
            val call = async { b.decide(ctx) }
            var linkDead = false
            val dog = launch {
                delay(watchdogMs)
                while (call.isActive) {
                    // The brain is pacing on purpose (every key at its per-minute cap): no request is in flight, so the
                    // silence says nothing about the link (A20e 2026-09-29: a pacing wait was abandoned as "internet
                    // isn't answering"). Look again once the wait is over.
                    val pausedFor = BrainActivity.pauseLeftMs()
                    if (pausedFor > 0) { delay(pausedFor + 500L); continue }
                    if (linkCheck() == false) {
                        Log.w("Tekyerema", "[BRAIN] no answer for ${watchdogMs / 1000}s and the internet isn't answering — abandoning the call")
                        linkDead = true
                        call.cancel()
                        return@launch
                    }
                    delay(watchdogMs)   // the link works: the model is just slow — look again later
                }
            }
            try {
                call.await()
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()   // the user stopped the task: that cancel wins
                if (linkDead) AgentDecision.Fail(WATCHDOG_REASON) else throw e
            } finally {
                dog.cancel()
            }
        }
    }

    companion object {
        /** Not escalatable (RoutingBrain only escalates DEFER/NETWORK from the LOCAL brain). */
        const val NETWORK_LOST = "network_lost"

        /** Internal: the watchdog abandoned a call because the link check failed. Matches [isNetworkError]. */
        internal const val WATCHDOG_REASON = "timeout: the internet stopped answering (watchdog)"

        private const val STALLED_MESSAGE = "The internet isn't answering right now, so I couldn't finish that. Please try again in a moment."

        private val NETWORK = Regex(
            "unable to resolve host|unknownhost|no address associated|timed? ?out|timeout|failed to connect|" +
                "connection (reset|refused|abort)|network is unreachable|software caused connection abort|" +
                // an HTTP/2 connection failed by a missing ping reply (OkHttp resets its streams with PROTOCOL_ERROR)
                "stream was reset|connection shutdown|" +
                "unexpected end of stream|eofexception|ssl.*(handshake|closed)",
            RegexOption.IGNORE_CASE
        )

        private val RATE_LIMIT = Regex("(?i)429|rate.?limit|quota|too many")

        /** True for connectivity failures (worth one retry), false for quota/HTTP/parse errors. */
        fun isNetworkError(reason: String): Boolean = NETWORK.containsMatchIn(reason)
    }
}

/** A brain that waits on purpose (rate-limit pacing) says so, so silence during the wait isn't read as a dead link. */
object BrainActivity {
    @Volatile private var pauseUntil = 0L
    fun pausingFor(ms: Long, now: Long = System.currentTimeMillis()) { pauseUntil = maxOf(pauseUntil, now + ms) }
    fun pauseLeftMs(now: Long = System.currentTimeMillis()): Long = (pauseUntil - now).coerceAtLeast(0L)
}

/**
 * N-15 — which cloud providers timed out lately. A provider that failed on the network after a long wait is moved to
 * the back of the chain for `router.network.providerCoolMs` (default 90 s); any answer from it clears the mark.
 * Keys are chain-position + class, so the per-task FallbackBrain instances share what earlier tasks learned.
 */
class ProviderCooling(private val coolMs: () -> Long = {
    com.tekyerema.core.config.AppConfig.long("router.network.providerCoolMs", 90_000L) }) {
    private val until = ConcurrentHashMap<String, Long>()
    fun networkFail(key: String, now: Long) { until[key] = now + coolMs() }
    fun ok(key: String) { until.remove(key) }
    fun cooling(key: String, now: Long): Boolean = (until[key] ?: 0L) > now

    companion object { val shared = ProviderCooling() }
}
