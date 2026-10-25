package com.tekyerema.core

import com.tekyerema.core.config.AppConfig

/**
 * N-10 / N-15 — is the internet ACTUALLY working, not just "connected"?
 *
 * Android's NET_CAPABILITY_VALIDATED is decided when a network connects (and re-checked only now and then), so it
 * stays true on a campus Wi-Fi that has since stopped passing traffic. On the A20e (2026-09-28) Android said
 * VALIDATED while TCP to Google and Groq timed out from the shell: every request waited out Groq's 35 s timeout,
 * then Gemini's 45 s one — 80 s of "still working" per request, then a failure — and the NEXT request did the same.
 *
 * This is a circuit breaker fed by what our own traffic observes (the passive approach of Chromium's Network Quality
 * Estimator) plus a tiny active probe ([LinkProbe]) when the evidence is thin:
 *  - a request that FAILED on the network after a long wait (a timeout — the stall signature), two network failures
 *    in a row, or a failed probe → the link is STALLED for [openMs]: the router keeps work on the phone and answers
 *    at once instead of making the user wait for another timeout;
 *  - any success (an HTTP answer of any status proves the path works) closes the circuit;
 *  - after [openMs] the circuit half-opens: the next request is tried, and one more failure re-opens it at once.
 * A failure whose request STARTED before the latest success is old news and doesn't count.
 *
 * 24 Oct 2026 (phone): one request failed after 12 s ("DNS lookup timed out") and the circuit stayed open for 60 s, so the
 * next English request went to the slow offline model. Now ONE slow request failure opens only a short window
 * ([shortOpenMs], 15 s); two failures in a row, or a failed probe, open the full [openMs].
 *
 * Pure (clock injected) → unit-tested; [LinkHealth] is the process-wide instance.
 */
class LinkHealthTracker(
    private val openMs: () -> Long = { AppConfig.long("router.network.stall.openMs", 60_000L) },
    private val slowFailMs: () -> Long = { AppConfig.long("router.network.stall.slowFailMs", 6_000L) },
    private val weakProbeMs: () -> Long = { AppConfig.long("router.network.weakProbeMs", 3_000L) },
    private val recentMs: () -> Long = { AppConfig.long("router.network.stall.recentOkMs", 120_000L) },
    private val warmSuspectMs: () -> Long = { AppConfig.long("router.network.stall.warmSuspectMs", 2_500L) },
    private val shortOpenMs: () -> Long = { AppConfig.long("router.network.stall.shortOpenMs", 15_000L) }
) {
    enum class Kind { REQUEST, PROBE }
    enum class Verdict { UNKNOWN, GOOD, SUSPECT, STALLED }
    /** Which offline window is open: SHORT after one slow request failure, LONG after two in a row or a failed probe. */
    enum class OpenKind { NONE, SHORT, LONG }

    private var lastOkAt = 0L
    private var lastFailAt = 0L
    private var consecutiveFails = 0
    private var openUntil = 0L
    private var openKind = OpenKind.NONE
    /** Total time of the last successful probe (DNS + TCP + TLS + one HTTP exchange) — a coarse link-speed sample. */
    private var lastProbeMs = 0L
    /** When the connection pre-warm (opened as the mic opens) started, while it hasn't answered yet; 0 = none pending. */
    private var warmSince = 0L

    /** The pre-warm started / ended. A pre-warm still silent after [warmSuspectMs] (a TLS handshake takes well under a
     *  second on a working link) makes the link SUSPECT, so even the first request on a stalled Wi-Fi gets a quick probe
     *  instead of a full model-call timeout. */
    @Synchronized fun warmStarted(now: Long) { warmSince = now }
    @Synchronized fun warmEnded() { warmSince = 0L }

    /** Records a success; returns true when this CLOSED an open circuit (the internet came back). */
    @Synchronized
    fun success(now: Long, elapsedMs: Long, kind: Kind = Kind.REQUEST): Boolean {
        val wasOpen = openUntil > now
        lastOkAt = now
        consecutiveFails = 0
        openUntil = 0L
        openKind = OpenKind.NONE
        warmSince = 0L   // the link answered: a pre-warm still pending is no longer evidence against it
        if (kind == Kind.PROBE) lastProbeMs = elapsedMs.coerceAtLeast(1L)
        return wasOpen
    }

    /** Records a network-class failure (not an HTTP error, not a user cancel); returns true when this OPENED the circuit. */
    @Synchronized
    fun failure(now: Long, elapsedMs: Long, kind: Kind = Kind.REQUEST): Boolean {
        val startedAt = now - elapsedMs.coerceAtLeast(0L)
        if (lastOkAt != 0L && startedAt < lastOkAt) return false   // a later request already got through
        val wasOpen = openUntil > now
        lastFailAt = now
        consecutiveFails++
        val long = kind == Kind.PROBE || consecutiveFails >= 2
        val short = !long && elapsedMs >= slowFailMs()
        if (long) { openUntil = now + openMs(); openKind = OpenKind.LONG }
        else if (short) { openUntil = now + shortOpenMs(); openKind = OpenKind.SHORT }
        return (long || short) && !wasOpen
    }

    /** Which window is open at [now] (NONE when the circuit is closed). */
    @Synchronized
    fun openKind(now: Long): OpenKind = if (openUntil > now) openKind else OpenKind.NONE

    /** A new network (or the old one lost): forget what the previous link did. */
    @Synchronized
    fun reset() { lastOkAt = 0L; lastFailAt = 0L; consecutiveFails = 0; openUntil = 0L; openKind = OpenKind.NONE; lastProbeMs = 0L; warmSince = 0L }

    @Synchronized fun stalled(now: Long): Boolean = openUntil > now

    /** The last failure is newer than the last success (or the pre-warm is hanging), but the circuit isn't open —
     *  worth a quick probe before waiting on a cloud call. */
    @Synchronized fun suspect(now: Long): Boolean =
        !stalled(now) && (lastFailAt > lastOkAt || (warmSince != 0L && now - warmSince >= warmSuspectMs()))

    @Synchronized
    fun verdict(now: Long): Verdict = when {
        stalled(now) -> Verdict.STALLED
        suspect(now) -> Verdict.SUSPECT
        lastOkAt != 0L && now - lastOkAt <= recentMs() -> Verdict.GOOD
        else -> Verdict.UNKNOWN
    }

    /** The last probe was slow enough to call the link weak (N-10) — e.g. EDGE, or a congested hotspot. */
    @Synchronized
    fun slowProbe(): Boolean = lastProbeMs >= weakProbeMs()

    /** Some exchange (any HTTP answer, even a 429) completed within the last [ms] — the link carries traffic. */
    @Synchronized fun okWithin(now: Long, ms: Long): Boolean = lastOkAt != 0L && now - lastOkAt <= ms

    /** Total time of the last successful probe on this link, 0 = none yet (a new process or a new network). */
    @Synchronized fun lastProbeMs(): Long = lastProbeMs

    /** When the open circuit half-opens (0 = not open). */
    @Synchronized fun openUntil(): Long = openUntil
}

/** The process-wide link tracker, plus listeners told when the internet comes back. */
object LinkHealth {
    val tracker = LinkHealthTracker()
    private val restored = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private fun now() = System.currentTimeMillis()

    fun success(elapsedMs: Long, kind: LinkHealthTracker.Kind = LinkHealthTracker.Kind.REQUEST) {
        if (tracker.success(now(), elapsedMs, kind)) {
            android.util.Log.i("Tekyerema", "[NET] the internet is working again")
            fireRestored()
        }
    }

    fun failure(elapsedMs: Long, why: String?, kind: LinkHealthTracker.Kind = LinkHealthTracker.Kind.REQUEST) {
        if (tracker.failure(now(), elapsedMs, kind)) {
            val window = if (tracker.openKind(now()) == LinkHealthTracker.OpenKind.SHORT)
                "${AppConfig.long("router.network.stall.shortOpenMs", 15_000L) / 1000}s (one slow request)"
            else "${AppConfig.long("router.network.stall.openMs", 60_000L) / 1000}s"
            android.util.Log.w("Tekyerema", "[NET] link stalled (${kind.name.lowercase()} failed after ${elapsedMs}ms: ${why?.take(80)}) " +
                "— keeping work on the phone for $window")
            for (l in stalledListeners) try { l() } catch (_: Throwable) {}
        }
    }

    private val stalledListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Register a callback for "the link just stalled" (the circuit opened). */
    fun onStalled(listener: () -> Unit) { stalledListeners.add(listener) }

    fun stalled(): Boolean = tracker.stalled(now())
    fun okWithin(ms: Long): Boolean = tracker.okWithin(now(), ms)

    /**
     * Is the internet answering? Traffic that just got through says yes without a probe: on the campus Wi-Fi (A20e,
     * 2026-09-29) four Groq 429 replies took ~10 s, the watchdog then probed, the probe timed out, and a working link
     * was called dead. Otherwise [LinkProbe.check] (null = can't tell).
     */
    suspend fun answering(recentMs: Long = AppConfig.long("router.network.watchdogMs", 8_000L)): Boolean? {
        if (okWithin(recentMs)) return true
        val probed = LinkProbe.check()
        // A reply that got through WHILE the probe ran proves the link too (A20e 2026-09-29: a Groq answer landed 0.5 s
        // into a probe that then timed out, and the step was abandoned on a working link).
        return if (probed == false && okWithin(recentMs)) true else probed
    }
    fun suspect(): Boolean = tracker.suspect(now())
    fun verdict(): LinkHealthTracker.Verdict = tracker.verdict(now())
    fun reset() = tracker.reset()

    /** Register a callback for "the internet is back" (circuit closed, or the OS network came back). */
    fun onRestored(listener: () -> Unit) { restored.add(listener) }

    internal fun fireRestored() {
        for (l in restored) try { l() } catch (t: Throwable) { android.util.Log.w("Tekyerema", "[NET] restored listener failed: ${t.message}") }
    }
}
