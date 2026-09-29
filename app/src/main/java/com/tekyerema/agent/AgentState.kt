package com.tekyerema.agent

enum class AgentStatus {
    IDLE, THINKING, ACTING, WAITING_CONFIRMATION,
    /** Secure Input Mode: the current screen is a credential-entry surface (MoMo PIN / password / OTP).
     *  Tɛkyerɛma is deliberately BLIND — screen observation, OCR, brain reasoning and screen logging are
     *  all suspended; the user enters the secret directly and says "done" to resume. The PIN never enters
     *  the observation pipeline. */
    SECURE_INPUT,
    /** C-13: the task asked the user a question (ask_user) and waits for the answer — the run continues after it. */
    ASKING,
    /** C-12: the user said "pause"; the run waits at its next step until "continue". */
    PAUSED,
    COMPLETED, COMPLETED_UNVERIFIED, CANNOT_DO, FAILED, STOPPED
}

data class LogEntry(
    val timeMs: Long,
    val kind: Kind,
    val text: String
) {
    enum class Kind { USER, PERCEIVE, PLAN, ACTION, OBSERVE, VERIFY, POLICY, CONFIRM, RESULT, ERROR, INFO, STATS }
}

/**
 * Per-run measurements (plan §23). Reported at the end of every task as a [LogEntry.Kind.STATS]
 * line so Track B evaluation can be read straight from the trace. Deterministic bookkeeping —
 * never a judgement about the model.
 */
data class RunStats(
    val steps: Int = 0,
    val actions: Int = 0,
    val verifiedTrue: Int = 0,
    val verifiedFalse: Int = 0,
    val unnecessaryActions: Int = 0,   // identical call + identical result as the previous step
    val malformedResponses: Int = 0,   // prose-described actions instead of tool calls
    val vacuousExpects: Int = 0,       // actions with no concrete expectation
    val blocked: Int = 0,
    val confirmations: Int = 0,
    val staleRefusals: Int = 0,
    val acceleratorMisses: Int = 0,    // an accelerator shortcut failed but was recoverable → General-Operator fallback offered
    val decideLatencyMs: List<Long> = emptyList(),
    val channels: Map<String, Int> = emptyMap(),
    val perceptionRoutes: Map<String, Int> = emptyMap()
) {
    fun summary(): String {
        val lat = decideLatencyMs
        val avg = if (lat.isEmpty()) 0 else lat.sum() / lat.size
        return "steps=$steps actions=$actions verified=$verifiedTrue/${verifiedTrue + verifiedFalse} " +
            "unnecessary=$unnecessaryActions malformed=$malformedResponses vacuousExpects=$vacuousExpects " +
            "blocked=$blocked confirmations=$confirmations staleRefusals=$staleRefusals acceleratorMisses=$acceleratorMisses " +
            "decideMs(avg/max)=$avg/${lat.maxOrNull() ?: 0} channels=$channels perception=$perceptionRoutes"
    }
}

data class AgentUiState(
    val status: AgentStatus = AgentStatus.IDLE,
    val goal: String? = null,
    val log: List<LogEntry> = emptyList(),
    val pendingConfirmation: String? = null,
    /** §A.1 — a Twi rendering of the SAME confirmation (template with verbatim name + digit-by-digit number),
     *  present only for actions we can render structurally (call/money/airtime/message). Null ⇒ speak English. */
    val pendingConfirmationTwi: String? = null,
    /** J-02: which confirmation [pendingConfirmation] is (a new number for each one). A yes must name it — see
     *  [com.tekyerema.policy.ConfirmGate] — so a yes given to an earlier question can't approve this one. */
    val confirmationSeq: Long = 0L,
    /** G-13: why the agent handed the screen to the user in SECURE_INPUT — "momo_pin" | "pin" | "password" | "otp" |
     *  "captcha" — so the spoken instruction matches the screen instead of always saying "MoMo PIN". */
    val handoffReason: String? = null,
    /** C-13: the question an ask_user step is waiting on (spoken + shown). */
    val pendingQuestion: String? = null,
    val serviceReady: Boolean = false,
    val hasApiKey: Boolean = false,
    val brain: String = "not configured",
    val lastStats: String? = null,
    /** C-15: the last run's typed result as JSON (status, summary, steps, actions, verified, goal). */
    val lastResult: String? = null,
    /** WS-A: the words that started this run when they differ from [goal] — "carry on" resuming a paused task, whose
     *  [goal] is the original request. A caller waiting on its own words (the voice turn) matches either. */
    val request: String? = null,
    /** Who asked for the current run: null = the phone's user (voice, typing, their triggers); "remote:<client>" = a
     *  paired computer over MCP ([com.tekyerema.mcp.RemoteGate]). */
    val origin: String? = null,
    /** Numbers each submitted goal (never reused in the process), so an observer can tell two runs of the same goal apart. */
    val runSeq: Long = 0L
)
