package com.tekyerema.voice

/**
 * The wake word's resume rules — pure decisions (no audio, no Android), so the contract is unit-tested.
 *
 * Owner, 27 Oct 2026: after some requests Tɛkyerɛma stops listening and only the floating icon or the volume keys bring
 * voice control back. Causes found in the wake loop (WakeWordService): the mic stayed closed through the idle tail of a
 * finished turn (job still alive, nothing to interject into), a stuck "busy" turn or a leaked recogniser hand-over kept
 * it closed for good, and an exception in the loop ended it with nothing to start it again.
 */
object WakeResume {
    /** What the loop knows each iteration. [busyMs] = how long the app has been continuously busy (0 = idle). */
    data class Inputs(
        val handedOver: Boolean,
        val interjecting: Boolean,
        val busy: Boolean,
        val canInterject: Boolean,
        val canRespond: Boolean,
        val canPreempt: Boolean,
        val speaking: Boolean,
        val busyMs: Long
    )

    /** [listen] = the wake mic should be open; [reason] = why not (or why); [stuck] = it listens only because the
     *  watchdog decided the busy state is stuck, so a wake then cancels that turn first. */
    data class Decision(val listen: Boolean, val reason: String, val stuck: Boolean = false)

    /** A turn that has held the mic this long with nothing to interject into, answer or preempt is taken as stuck. */
    const val STUCK_BUSY_MS = 120_000L

    fun decide(i: Inputs): Decision = when {
        i.handedOver -> Decision(false, "the phone's recogniser has the mic")
        i.interjecting -> Decision(false, "an interjection is opening the answer mic")
        !i.busy -> Decision(true, "idle")
        i.canInterject -> Decision(true, "task working, mic free")
        i.canRespond -> Decision(true, "task waits for the user")
        i.canPreempt && !i.speaking -> Decision(true, "idle tail of a finished turn")
        i.busyMs >= STUCK_BUSY_MS && !i.speaking -> Decision(true, "watchdog: busy for ${i.busyMs / 1000} s", stuck = true)
        else -> Decision(false, "a turn owns the mic")
    }
}

/** Restart backoff for the wake loop: short first so voice control comes back at once, capped so a hard fault can't spin. */
object WakeBackoff {
    private val STEPS = longArrayOf(300, 600, 1_200, 2_500, 5_000, 10_000)
    /** A loop that ran this long was healthy: the next failure starts the backoff over. */
    const val HEALTHY_MS = 30_000L

    fun delayMs(attempt: Int): Long = STEPS[attempt.coerceIn(0, STEPS.size - 1)]

    /** The attempt number to use after a loop that ran [ranMs] before it ended. */
    fun nextAttempt(attempt: Int, ranMs: Long): Int = if (ranMs >= HEALTHY_MS) 0 else attempt + 1
}

/** Guards against the loop's other ways to sit deaf for good. */
object WakeGuards {
    /** A recogniser hand-over that was never reclaimed (a cancel between handOver and its finally) must not hold the mic
     *  shut: past this age it is dropped. The longest legitimate listen is well under it. */
    const val HOLDER_MAX_MS = 45_000L
    fun holderExpired(holders: Int, sinceMs: Long, nowMs: Long): Boolean = holders > 0 && sinceMs > 0 && nowMs - sinceMs >= HOLDER_MAX_MS

    /** Consecutive frames of exact digital silence after which the mic is reopened (another app or a call may have
     *  silenced it and the system never gave it back). 250 frames of 80 ms = 20 s. */
    const val SILENT_FRAMES_REOPEN = 250
    fun silentTooLong(zeroFrames: Int): Boolean = zeroFrames >= SILENT_FRAMES_REOPEN

    /** The loop has not come round for this long while the mic is open: the read is stalled. */
    const val STALL_MS = 6_000L
    fun stalled(micOpen: Boolean, sinceBeatMs: Long): Boolean = micOpen && sinceBeatMs >= STALL_MS

    /** After our own voice stops, a follow-up recogniser waits until this long has passed (26 Oct 19:03: the mic reopened
     *  37 ms after the TTS stopped and the user had to say it twice). */
    const val SETTLE_AFTER_SPEECH_MS = 250L
    fun settleRemainingMs(msSinceSpeechEnd: Long): Long = (SETTLE_AFTER_SPEECH_MS - msSinceSpeechEnd).coerceIn(0L, SETTLE_AFTER_SPEECH_MS)
}
