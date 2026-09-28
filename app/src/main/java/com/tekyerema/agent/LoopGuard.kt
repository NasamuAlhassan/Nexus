package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig

/**
 * G-21 / G-12 — per-task loop and recovery bookkeeping, pure so it is unit-tested off-device. The engine already stops
 * a STALL (the screen doesn't change) and FUTILITY (the same element never appears); this adds the cases those miss:
 *
 *  - a snapshot CYCLE: the task keeps making the same screen-to-screen move and coming back (A→B→A→B…). A list →
 *    detail → list pattern that opens a DIFFERENT item each time is not a cycle — only a repeated identical transition
 *    counts. Nudge at `executor.loopGuard.cycleNudge` repeats (default 2), stop at `executor.loopGuard.cycleStop` (3).
 *  - an IRREVERSIBLE retry: a consequential action (send, call, pay…) that failed or didn't verify may still have
 *    happened. It is not tried again until the task has checked (any read of the screen, messages or call log).
 *  - ESCALATION to the user after `executor.recovery.maxConsecutiveFailures` failed steps in a row (default 3), at most
 *    `executor.recovery.maxEscalations` times per task (default 1). The streak counts only real stuckness — a failed tool,
 *    an action that changed nothing, an unmet claim with nothing new on screen; a step that moved on to new content and a
 *    read hold it (see [Progress.nextFailStreak]). On the A20e, two scrolls toward "About phone" used to count as failures
 *    and the user was asked "keep trying or stop?" in the middle of a task that was going fine.
 */
class LoopGuard(
    private val cycleNudge: Int = AppConfig.int("executor.loopGuard.cycleNudge", 2).coerceAtLeast(2),
    private val cycleStop: Int = AppConfig.int("executor.loopGuard.cycleStop", 3).coerceAtLeast(3),
    private val maxFailures: Int = AppConfig.int("executor.recovery.maxConsecutiveFailures", 3).coerceAtLeast(2),
    private val maxEscalations: Int = AppConfig.int("executor.recovery.maxEscalations", 1).coerceAtLeast(0)
) {
    enum class Cycle { NONE, NUDGE, STOP }

    private val transitions = HashMap<String, Int>()
    private val uncertain = LinkedHashSet<String>()
    private var escalations = 0

    /**
     * Record one action's screen change (signatures before → after). Returns NUDGE the first time an identical
     * transition has been made [cycleNudge] times AND its reverse was also made (we went back and did it again), STOP
     * once it reaches [cycleStop]. A step that didn't change the screen is the stall guard's business, not a cycle.
     */
    fun onTransition(before: String, after: String?): Cycle {
        if (after == null || after == before) return Cycle.NONE
        val key = "$before→$after"
        val n = (transitions[key] ?: 0) + 1
        transitions[key] = n
        val cameBack = (transitions["$after→$before"] ?: 0) > 0
        val pair = listOf(before, after).sorted().joinToString("⇄")
        return when {
            !cameBack -> Cycle.NONE
            n >= cycleStop -> Cycle.STOP
            n >= cycleNudge && nudged.add(pair) -> Cycle.NUDGE   // one nudge per back-and-forth, not one per direction
            else -> Cycle.NONE
        }
    }

    private val nudged = HashSet<String>()

    /** A consequential tool ran: if it failed or didn't verify, it may or may not have happened. */
    fun noteConsequential(tool: String, success: Boolean, verified: Boolean) {
        if (success && verified) uncertain.remove(tool) else uncertain.add(tool)
    }

    /** The task looked (read the screen, the messages, the call log…) — the uncertain outcomes have been checked. */
    fun noteChecked() = uncertain.clear()

    /** True when [tool] must not run again until the task has checked whether its last attempt happened. */
    fun mustCheckFirst(tool: String): Boolean = tool in uncertain

    /** Ask the user how to go on? True at most [maxEscalations] times, once [failStreak] reaches [maxFailures]. */
    fun shouldEscalate(failStreak: Int): Boolean {
        if (failStreak < maxFailures || escalations >= maxEscalations) return false
        escalations++
        return true
    }

    companion object {
        /** "try another way", "do it differently" — said while a task runs (G-12). */
        val ANOTHER_WAY = Regex("(?i)\\b(?:try (?:another|a different|some other) (?:way|route|approach)|try something else|do it (?:another|a different) way|that'?s not working)\\b")

        /** The user's answer to "should I keep trying?" means stop. */
        private val STOP_ANSWER = Regex("(?i)^(?:no|nope|stop|cancel|leave it|forget it|never ?mind|don'?t bother|quit|daabi|gyae)\\b")
        fun wantsStop(answer: String): Boolean = STOP_ANSWER.containsMatchIn(answer.trim())
    }
}
