package com.tekyerema.voice

/**
 * Whether ANY of our voices is speaking right now — across providers and outside formal turns (repeatLast, announce).
 * The voice panel must never close itself mid-sentence (owner 2026-09-30: "when you are talking it kind of hides");
 * its close timers wait on [active]. Incremented around every speak, decremented in a finally, so a crash can't leave
 * it stuck — and belt-and-braces, a stale count older than [STALE_MS] reads as silent.
 */
object NowSpeaking {
    @Volatile private var count = 0
    @Volatile private var since = 0L
    private const val STALE_MS = 120_000L

    @Synchronized fun begin() { count++; since = System.currentTimeMillis() }
    @Synchronized fun end() { if (count > 0) count--; if (count == 0) since = 0L }

    val active: Boolean
        get() = count > 0 && System.currentTimeMillis() - since < STALE_MS
}
