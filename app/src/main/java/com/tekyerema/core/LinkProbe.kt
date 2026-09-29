package com.tekyerema.core

import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * N-10 — a tiny active check that the internet really answers: one HTTPS GET of Google's `generate_204` (the same
 * endpoint Android's own validation uses; ~0 bytes of body) on a FRESH connection, bounded by a short timeout.
 *
 * Used only when the passive evidence ([LinkHealth]) is thin or bad — a cloud call has been silent for a while, the
 * last request failed, or the circuit is open and we want to know if the link is back. Never on every request: on a
 * healthy link the cloud calls themselves are the evidence.
 *
 * Only a 204 counts: a captive portal (campus Wi-Fi login page) answers 200 or a redirect, and an HTTPS probe through
 * one fails its TLS handshake — both mean "no internet" for us. Results feed [LinkHealth] as PROBE observations.
 */
object LinkProbe {
    private const val DEFAULT_URL = "https://connectivitycheck.gstatic.com/generate_204"

    /** Set by [NetworkMonitor.init]. Unset in JVM tests, so nothing there ever touches the network. */
    @Volatile internal var available = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var inflight: Deferred<Boolean>? = null
    @Volatile private var lastStartAt = 0L

    // A fresh connection per probe (no pooling, no retry, no redirects): a dead pooled socket or a portal's redirect
    // must not pass for a working link, and one attempt is the whole point of a bounded probe.
    private val client by lazy {
        Http.shared.newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun enabled() = available && FeatureFlags.isEnabled("N-10", true) && AppConfig.bool("router.network.probe.enabled", true)

    /**
     * Is the internet answering right now? Joins a probe already in flight rather than starting a second one.
     * @return true/false, or null when probing isn't available here (JVM tests, before init, or switched off) — callers
     *   then behave as they did before this existed.
     */
    suspend fun check(timeoutMs: Long = AppConfig.long("router.network.probe.timeoutMs", 4_000L)): Boolean? {
        if (!enabled()) return null
        // On a link already known to be slow (EDGE: DNS + TCP + TLS + one exchange ≈ 3–4 s) a working-but-slow link
        // must not be mistaken for a dead one: give it twice the time.
        val budget = if (try { NetworkMonitor.isWeak() } catch (_: Throwable) { false }) timeoutMs * 2 else timeoutMs
        val d = synchronized(this) {
            inflight?.takeIf { it.isActive } ?: scope.async { probe(budget) }.also {
                inflight = it; lastStartAt = System.currentTimeMillis()
            }
        }
        return d.await()
    }

    /** Fire-and-forget probe, at most once per `router.network.probe.minIntervalMs` — used to notice the link coming back. */
    fun kick() {
        if (!enabled()) return
        if (System.currentTimeMillis() - lastStartAt < AppConfig.long("router.network.probe.minIntervalMs", 10_000L)) return
        scope.launch { try { check() } catch (_: Throwable) {} }
    }

    private suspend fun probe(timeoutMs: Long): Boolean {
        val url = AppConfig.string("router.network.probe.url", DEFAULT_URL)
        val want = AppConfig.int("router.network.probe.expectCode", 204)
        val t0 = System.currentTimeMillis()
        return try {
            val call = client.newCall(Request.Builder().url(url).get().header("Cache-Control", "no-cache").build())
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            val (code, _, _) = Http.execute(call, observe = false)
            val took = System.currentTimeMillis() - t0
            if (code == want) {
                android.util.Log.i("Tekyerema", "[NET] probe ok in ${took}ms")
                LinkHealth.success(took, LinkHealthTracker.Kind.PROBE)
                true
            } else {
                // Something answered, but not the internet (a login page or a proxy).
                android.util.Log.w("Tekyerema", "[NET] probe got HTTP $code instead of $want (captive portal?)")
                LinkHealth.failure(took, "probe HTTP $code", LinkHealthTracker.Kind.PROBE)
                false
            }
        } catch (t: Throwable) {
            val took = System.currentTimeMillis() - t0
            android.util.Log.w("Tekyerema", "[NET] probe failed after ${took}ms: ${t.message?.take(80)}")
            LinkHealth.failure(took, t.message, LinkHealthTracker.Kind.PROBE)
            false
        }
    }
}
