package com.tekyerema.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * N-15 hedge — hedged attempts ("The Tail at Scale", Dean & Barroso, CACM 2013): start the first attempt; if it has not
 * produced a usable result after [afterMs] (or it failed), start the next one as well, and take whichever is usable
 * first. The losers are cancelled (a cancelled cloud call aborts its HTTP request).
 *
 * Why (A20e, 2026-09-28 night, build/device_reports/20260928-233636): on a campus Wi-Fi every Groq call hung for its
 * full 35 s timeout — seven in a row — while Gemini, tried only AFTER each timeout, answered in 4 s. A normal step takes
 * 1–2 s (p95 ≈ 5 s over the day's traces), so waiting 35 s before trying anything else turned every step into ~40 s and
 * the multi-app tasks into 80–150 s. Hedging after a few seconds bounds that tail without doubling normal traffic:
 * the second attempt only exists when the first is already far slower than usual.
 *
 * Pure coroutine logic (no Android, no I/O) — unit-tested with fake attempts.
 */
object Hedge {
    /** The outcome: the first usable result (or, when none was, every attempt's result in attempt order; null = never
     *  finished, i.e. cancelled as a loser or never started). [started] = how many attempts were launched. */
    data class Outcome<T>(val winner: T?, val winnerIndex: Int, val results: List<T?>, val started: Int)

    /** Run [block] with a hard coroutine-level deadline: null when it didn't finish in [ms]. OkHttp's own callTimeout
     *  cannot interrupt a thread stuck in a blocking DNS lookup (A20e: a "35 s" Groq call returned after 68 s); this
     *  resumes the caller on time and cancels the call behind it. */
    suspend fun <T> within(ms: Long, block: suspend () -> T): T? = kotlinx.coroutines.withTimeoutOrNull(ms) { block() }

    /**
     * @param attempts tried in order; attempt i+1 starts when attempt i is not usable after [afterMs], or fails.
     *   An attempt must report failure as a VALUE (a Fail decision, a Result.failure) — a thrown exception would cancel
     *   the whole race. Cancellation is the only exception that should escape one.
     * @param usable a completed result that ends the race (e.g. "not a Fail"); an unusable one lets the next attempt start at once.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun <T> race(attempts: List<suspend () -> T>, afterMs: Long, usable: (T) -> Boolean): Outcome<T> = coroutineScope {
        require(attempts.isNotEmpty())
        val running = ArrayList<Deferred<T>>()
        val results = arrayOfNulls<Any?>(attempts.size)
        val done = BooleanArray(attempts.size)
        fun start(i: Int) { running.add(async { attempts[i]() }) }
        start(0)
        var lastStart = System.currentTimeMillis()
        try {
            while (true) {
                val pending = running.withIndex().filter { !done[it.index] }
                if (pending.isEmpty()) {
                    // everything that started finished unusable: start the next, or report all of them
                    if (running.size < attempts.size) { start(running.size); lastStart = System.currentTimeMillis(); continue }
                    @Suppress("UNCHECKED_CAST")
                    return@coroutineScope Outcome(null, -1, results.toList() as List<T?>, running.size)
                }
                val canHedge = running.size < attempts.size
                val waitMs = (afterMs - (System.currentTimeMillis() - lastStart)).coerceAtLeast(0L)
                val finished: Pair<Int, T>? = select {
                    for ((i, d) in pending) d.onAwait { i to it }
                    if (canHedge) onTimeout(waitMs) { null }
                }
                if (finished == null) {   // the hedge timer fired: start the next attempt alongside
                    start(running.size); lastStart = System.currentTimeMillis(); continue
                }
                val (i, r) = finished
                done[i] = true; results[i] = r
                if (usable(r)) {
                    running.forEachIndexed { j, d -> if (j != i && !done[j]) d.cancel() }
                    @Suppress("UNCHECKED_CAST")
                    return@coroutineScope Outcome(r, i, results.toList() as List<T?>, running.size)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        } catch (ce: CancellationException) {
            running.forEach { it.cancel() }
            throw ce
        }
    }
}
