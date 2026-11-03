package com.tekyerema.agent.journal

import com.tekyerema.core.config.AppConfig

/**
 * When a step ends because the CLOUD is unavailable — not because the task is impossible — the task is PAUSED with a
 * checkpoint instead of failing (research: agenticness_reliability_state rank 1; Step Functions' "Catch to a fallback
 * state"). Before this a dropped link ended the task CANNOT_DO and a spent free-tier quota ended it FAILED with "try
 * again in a minute" — which restarted it from zero (audit §3, D5/D6; RUN_REPORT: a 5-song download stopped at 1/5).
 *
 * Pure: the decision's reason class / text in, a [PauseReason] (or null = end as before) out.
 */
object PauseRules {
    /** CannotDo reason classes Workstream B emits when no capable model is left (quota/budget routing). */
    const val QUOTA_EXHAUSTED = "quota_exhausted"
    const val NO_CAPABLE_MODEL = "no_capable_model"
    const val BUDGET_EXHAUSTED = "budget_exhausted"

    /** FallbackBrain.NETWORK_LOST / LocalBrain.NETWORK (kept as literals: this file stays free of the brain classes). */
    private val NETWORK_CLASSES = setOf("network_lost", "network_unavailable")

    private val QUOTA_TEXT = Regex("(?i)\\b429\\b|quota|rate.?limit|too many requests|tokens per (?:day|minute)|requests per (?:day|minute)")
    private val BUSY_TEXT = Regex("(?i)\\b50[23]\\b|high demand|overloaded|service unavailable")

    fun forCannotDo(reasonClass: String): PauseReason? = when (reasonClass) {
        in NETWORK_CLASSES -> PauseReason.NETWORK
        QUOTA_EXHAUSTED -> PauseReason.QUOTA
        NO_CAPABLE_MODEL -> PauseReason.NO_CAPABLE_MODEL
        BUDGET_EXHAUSTED -> PauseReason.BUDGET
        else -> null
    }

    /** A brain Fail: a quota / rate limit pauses; an overloaded provider (503 "high demand") means no model can take it now. */
    fun forFail(reason: String): PauseReason? = when {
        QUOTA_TEXT.containsMatchIn(reason) -> PauseReason.QUOTA
        BUSY_TEXT.containsMatchIn(reason) -> PauseReason.NO_CAPABLE_MODEL
        else -> null
    }

    /** "Please try again in 7m30.5s" (Groq) / "retry in 45s" → when the quota window should refill, or null. */
    fun resumeAfter(reason: String, now: Long): Long? {
        val m = Regex("(?i)(?:try again|retry) in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?").find(reason) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val ms = h * 3_600_000L + min * 60_000L + (s * 1000).toLong()
        return if (ms <= 0L) null else now + ms
    }

    /** Why, in words a person understands (never an error code). */
    fun because(r: PauseReason?): String = when (r) {
        // A20e, 2 Nov 2026: said with no connection at all ("the internet dropped" was wrong when there never was one).
        PauseReason.NETWORK -> AppConfig.string("agent.resume.phrases.network", "it needs the internet, and the phone is offline")
        PauseReason.QUOTA -> AppConfig.string("agent.resume.phrases.quota", "I've used up my AI allowance for now")
        PauseReason.NO_CAPABLE_MODEL -> AppConfig.string("agent.resume.phrases.noModel", "no AI that can do this safely is free right now")
        // (merge A+B: B's budget_exhausted is the operator's DAILY cloud budget — OperatorPolicy.dailyCloudCallCap/dailyTokenCap.)
        PauseReason.BUDGET -> AppConfig.string("agent.resume.phrases.budget", "the AI usage limit set for today has been reached")
        PauseReason.USER -> "you asked me to"
        PauseReason.PROCESS_DEATH -> AppConfig.string("agent.resume.phrases.processDeath", "the app was closed")
        null -> "it was interrupted"
    }

    /** Spoken the moment a task pauses: "I've paused this because the internet dropped. Say “carry on” when you want me to continue." */
    fun pausedLine(r: PauseReason, resumeAfterMs: Long? = null, clock: (Long) -> String = { EffectLines.clock(it) }): String =
        "I've paused this because ${because(r)}." +
            (resumeAfterMs?.let { " It should be back around ${clock(it)}." } ?: "") +
            " Say “carry on” when you want me to continue."
}
