package com.tekyerema.agent.routing

import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.core.config.AppConfig
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.operator.WeakModelPolicy

/**
 * Workstream B — quota-aware routing for the planner (docs/agent/research/agenticness_reliability_state.md §5 and the
 * routing table). The free tier is ~8k tokens/min per org and 200k/day per model; a step is ~6k tokens. Rules:
 *
 *  - The model is PINNED per task (the provider that answered the first complex step goes first on every later step).
 *  - A per-minute limit is WAITED out (≤ [RoutePolicy.complexMaxWaitMs], ~20 s; the voice layer says its progress line)
 *    instead of dropping to the weaker model mid-task — 20b scores ~13 points lower than 120b on τ-bench.
 *  - For a COMPLEX step (anything but a direct knowledge answer) the other capable provider (Gemini, if the operator
 *    allows it and the user consented) is tried BEFORE gpt-oss-20b; then a TPM reset ≤ 60 s is waited for.
 *  - gpt-oss-20b takes over a complex task only per [OperatorPolicy.weakModelPolicy]: NEVER → pause; ASK → ask the user
 *    once; ALLOW → carry on and say so in the result. Never for a recovery step (effort=high), and at most
 *    [RoutePolicy.maxWeakSteps] steps.
 *  - No capable model left → [AgentDecision.CannotDo] "no_capable_model" / "quota_exhausted" (Workstream A pauses the
 *    task on these); over the operator's budget → "budget_exhausted". Simple steps keep C-08 (20b first).
 */
data class RoutePolicy(
    val weakModelPolicy: WeakModelPolicy = OperatorPolicy.weakModelPolicy,
    val complexMaxWaitMs: Long = AppConfig.long("router.quota.complexMaxWaitMs", 20_000L),
    val tpmResetMaxWaitMs: Long = AppConfig.long("router.quota.tpmResetMaxWaitMs", 60_000L),
    val maxWeakSteps: Int = AppConfig.int("router.weak.maxSteps", 5)
)

/** Per-task routing state, shared by the brains of one task's chain. */
class TaskRoute(
    val policy: RoutePolicy = RoutePolicy(),
    /** the task started without the user (a schedule / notification rule): nobody can be asked */
    val unattended: Boolean = false
) {
    /** Which chain entry (FallbackBrain index) answered the first complex step — tried first from then on. */
    @Volatile var pinnedIndex: Int? = null
    /** Human-readable pinned model ("groq/openai/gpt-oss-120b", "gemini"), for the journal and traces. */
    @Volatile var pinnedName: String? = null
    @Volatile var weakApproved = false
    @Volatile var weakAsked = false
    @Volatile var weakSteps = 0
    /** Any step of this task ran on the weaker model. */
    @Volatile var degraded = false
    private var disclosed = false

    fun pin(index: Int, name: String) { if (pinnedIndex == null) { pinnedIndex = index; pinnedName = name } }
    /** The pinned provider is out for the day: the next capable answer re-pins the task. */
    fun unpin() { pinnedIndex = null; pinnedName = null }

    /** The honest tail for the spoken result when a weaker model did part of the task (once). */
    fun disclosure(): String {
        if (!degraded || disclosed) return ""
        disclosed = true
        return " (The main AI was busy, so a smaller backup AI did part of this — please check it.)"
    }
}

/** A brain that can report its capable model's quota and run one step on its weak model (OpenAiCompatBrain). */
interface QuotaAware {
    /** ms until the capable model can take a request (0 = now); null = not within a TPM window (daily cap / unknown). */
    fun capableReadyInMs(now: Long = System.currentTimeMillis()): Long?
    /** The weaker model's name, or null when there is none. */
    val weakModel: String?
    /** One step on the weaker model (its own per-model quota). */
    suspend fun decideWeak(ctx: BrainContext): AgentDecision
}

object ModelRouting {
    const val NO_CAPABLE_MODEL = "no_capable_model"
    const val QUOTA_EXHAUSTED = "quota_exhausted"
    const val BUDGET_EXHAUSTED = "budget_exhausted"
    /** Marks the router's own ask_user so its answer is recognised on the next step. */
    const val ASK_TAG = "router:weak-model"

    const val ASK_QUESTION = "The main AI is busy. Carry on with a smaller, less reliable one, or pause until it's back?"

    const val PAUSED_TEXT = "My main AI is busy or out of allowance for now, so I've paused this task here. Ask me to carry on later."
    const val PAUSED_BY_USER_TEXT = "Okay, I've paused this task until the main AI is back. Ask me to carry on later."
    /** A simple step (a direct answer) out of quota: nothing to resume, so no "paused" — the same words as before. */
    const val QUOTA_TEXT = "I'm getting too many requests right now. Please try again in a minute — " +
        "phone commands like the time, calls or opening an app still work now."
    const val BUDGET_TEXT = "You've reached the AI usage limit you set for today, so I've stopped using online AI. " +
        "Phone commands like the time, calls or opening an app still work."

    /**
     * Merge A+B: [PAUSED_TEXT] / [PAUSED_BY_USER_TEXT] promise a resume, which Workstream A's task journal keeps — but only
     * for a journaled task (attended, by the phone's user). The engine pauses those with A's own line; for a run with no
     * journal record (a schedule / notification trigger, a paired computer's task) nothing is kept, so it must not say
     * "paused" — this is the honest line instead. Any other text is returned unchanged.
     */
    fun notResumable(detail: String): String = when (detail) {
        PAUSED_TEXT -> "My main AI is busy or out of allowance for now, so I stopped this task here. Please ask me again later."
        PAUSED_BY_USER_TEXT -> "Okay, I've stopped this task until the main AI is back. Please ask me again later."
        else -> detail
    }

    sealed class Weak {
        object Use : Weak()
        object Ask : Weak()
        data class Pause(val reasonClass: String, val text: String) : Weak()
    }

    /**
     * May the weaker model take this complex step? [effort] "high" = a recovery step (the task is already failing) —
     * never on the weak model. Pure.
     */
    fun weakDecision(route: TaskRoute, effort: String?, hasWeak: Boolean): Weak {
        if (!hasWeak) return Weak.Pause(QUOTA_EXHAUSTED, PAUSED_TEXT)
        if (effort == "high") return Weak.Pause(NO_CAPABLE_MODEL, PAUSED_TEXT)
        if (route.weakSteps >= route.policy.maxWeakSteps) return Weak.Pause(NO_CAPABLE_MODEL, PAUSED_TEXT)
        return when (route.policy.weakModelPolicy) {
            WeakModelPolicy.NEVER -> Weak.Pause(NO_CAPABLE_MODEL, PAUSED_TEXT)
            WeakModelPolicy.ALLOW -> Weak.Use
            WeakModelPolicy.ASK -> when {
                route.weakApproved -> Weak.Use
                route.unattended || route.weakAsked -> Weak.Pause(NO_CAPABLE_MODEL, PAUSED_TEXT)
                else -> Weak.Ask
            }
        }
    }

    private val YES = Regex("(?i)\\b(?:carry on|continue|go on|go ahead|keep going|yes|yeah|yep|ok|okay|sure|fine|use (?:it|the smaller|a smaller)|smaller|do it|aane)")
    private val NO = Regex("(?i)\\b(?:pause|wait|no|nope|stop|later|not now|hold|leave it|daabi|gyae)\\b")

    /** The user's reply to [ASK_QUESTION]: true = carry on with the smaller model, false = pause, null = unclear. */
    fun weakAnswer(reply: String?): Boolean? {
        val r = reply?.trim().orEmpty()
        if (r.isEmpty()) return null
        val no = NO.containsMatchIn(r); val yes = YES.containsMatchIn(r)
        return when {
            no && !yes -> false
            yes && !no -> true
            // "don't pause, carry on" / "no, carry on": the carry-on words win only if they come last.
            yes && no -> r.lowercase().let { l -> (YES.findAll(l).last().range.first > NO.findAll(l).last().range.first) }
            else -> null
        }
    }

    /** The answer to the router's own question, if the last step was it (the engine writes `ask_user(…) → the user answered: "…"`). */
    fun pendingAnswer(history: List<String>): String? {
        val last = history.lastOrNull() ?: return null
        if (!last.contains(ASK_QUESTION.take(24))) return null
        return Regex("the user answered: \"(.*)\"\\s*$").find(last)?.groupValues?.get(1)
    }

    /** The router's question as a decision (the engine asks, waits, and records the answer in the history). */
    /** Tools that only look or ask — they change nothing on the phone. */
    private val LOOK_ONLY = setOf("read_state", "read_screen", "read_all", "read_notifications", "read_sms", "web_search", "find_tool",
        "wait", "ask_user", "describe_screen", "search_contacts", "find_contact", "search_messages", "call_log", "read_shared")

    /**
     * Split by risk (the owner's choice, 2026-09-29): under the "ask me first" policy, a step the smaller model proposes
     * that only answers, asks or looks (a web search, reading the screen or messages) goes ahead — the reply says a
     * smaller AI helped — while anything that would change the phone still asks first. A judge's "look something up"
     * was interrupted by "the main AI is busy…" right after the app asked what to look up. Pure.
     */
    fun safeOnWeak(d: AgentDecision): Boolean = when (d) {
        is AgentDecision.Done, is AgentDecision.CannotDo -> true
        is AgentDecision.CallTool -> d.name in LOOK_ONLY
        else -> false
    }

    fun askDecision(): AgentDecision.CallTool =
        AgentDecision.CallTool("ask_user", org.json.JSONObject().put("question", ASK_QUESTION), ASK_TAG)

    /** A Fail that means "out of quota" (per-minute or daily), as opposed to a network or request error. */
    private val QUOTA = Regex("(?i)429|rate.?limit|quota|too many|cooling|busy right now|\\b50[23]\\b|overloaded|high demand")
    private val DAILY = Regex("(?i)daily|per day")
    fun isQuota(reason: String): Boolean = QUOTA.containsMatchIn(reason)
    fun isDaily(reason: String): Boolean = DAILY.containsMatchIn(reason)

    /** Is this a complex step (the planner doing phone work) rather than a direct knowledge answer? */
    fun complex(ctx: BrainContext): Boolean = !ctx.simple

    /** The planner chain the operator allows: [configured] order, filtered to [allowed] providers. Pure. */
    fun allowedChain(configured: List<String>, allowed: List<String>, cloudAllowed: Boolean): List<String> {
        if (!cloudAllowed) return emptyList()
        val ok = allowed.map { it.trim().lowercase() }.toSet()
        return configured.filter { it in ok }
    }
}

/** Convenience for tests / wiring: a brain is quota-aware if it implements [QuotaAware]. */
fun AgentBrain.quotaAware(): QuotaAware? = this as? QuotaAware
