package com.tekyerema.audit

import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.PolicyDecision
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.tools.Tool
import org.json.JSONObject

/**
 * WS-C — the small API the agent loop calls to keep the structured audit (one line per event in AgentEngine). Every event
 * about one step shares its task id and step number, so a proposal, the user's answer (with the confirmation number),
 * the outcome and the verification can be read together — each is its own hash-chained entry, nothing is rewritten:
 *
 *   taskStarted → proposed → [approved | declined | blocked] → done/failed → verified/unverified … → taskEnded
 *
 * Recorded for every step that changes the phone or sends something out (web included); pure reads are not. A step's
 * words are the same plain words "what did you just do?" speaks (who, never the typed or sent text); the confirmation
 * read-back and the verification detail may quote it — they stay in the encrypted log and keep only their length in a
 * default (redacted) export.
 */
object AuditHooks {
    /** One step's audit context; its methods add the step's later events. Null-safe: `step?.approved(seq, prompt)`. */
    class Step internal constructor(
        val source: String, val task: Long, val step: Int, val tool: String, val tier: String, val scope: String,
        val policy: String, val reason: String, val provenance: String, val decidedBy: String,
        /** "tap “Send”" — the plan; [done] words ("tapped “Send”") are separate. */
        val plan: String, val doneWords: String
    ) {
        private fun rec(kind: String, text: String, policy: String = this.policy, reason: String = this.reason, seq: Long = 0,
                        approved: Boolean? = null, verified: Boolean? = null, outcome: String = "") =
            AuditLog.append(AuditEntry(0, task, source, kind, text, step = step, tool = tool, tier = tier, scope = scope,
                policy = policy, reason = reason, provenance = provenance, decidedBy = decidedBy, confirmSeq = seq,
                approved = approved, verified = verified, outcome = outcome))

        internal fun proposed() = rec(AuditLog.PROPOSED, plan)
        /** Refused before it ran: by policy, the deny/guide-only app lists, an injection stop… */
        fun blocked(why: String = reason) = rec(AuditLog.BLOCKED, plan, policy = "BLOCK", reason = why, outcome = "blocked")
        /** The user said yes to confirmation [seq] (null = covered by an earlier approval, e.g. the money flow's menu steps). */
        fun approved(seq: Long?, prompt: String) = rec(AuditLog.APPROVED, prompt, seq = seq ?: 0, approved = true,
            outcome = if (seq == null) "covered by an earlier approval" else "approved")
        /** The user said no ([revised] = answered with a correction instead). */
        fun declined(seq: Long?, prompt: String, revised: Boolean = false) = rec(AuditLog.DECLINED, prompt, seq = seq ?: 0,
            approved = false, outcome = if (revised) "revised" else "declined")
        /** Held back just before it would have run (stale screen, overlay, target moved, own UI) — it did not happen. */
        fun notRun(why: String) = rec(AuditLog.NOT_RUN, plan, reason = why, outcome = "not run")
        fun executed(success: Boolean) = rec(if (success) AuditLog.DONE else AuditLog.FAILED, doneWords,
            outcome = if (success) "done" else "failed")
        /** What a fresh look at the screen showed afterwards ([detail] is kept short; may quote the screen — personal). */
        fun verified(ok: Boolean, detail: String) = rec(if (ok) AuditLog.VERIFIED else AuditLog.UNVERIFIED, detail.take(120),
            verified = ok, outcome = if (ok) "verified" else "not verified")
    }

    /** Steps worth auditing: anything that changes the phone or sends something out; pure reads are not. */
    fun recordable(scope: CapabilityScope): Boolean = scope != CapabilityScope.PERCEIVE && scope != CapabilityScope.COMMS_READ

    /**
     * A step the model (or a rule) proposed, with the policy's verdict on it. Records PROPOSED and returns the step's
     * context, or null for a pure read.
     * @param rationale the decision's rationale ("local:…" for on-device rules, "chain:…" for a tap_sequence step)
     */
    fun proposed(
        source: String, task: Long, step: Int, tool: Tool, args: JSONObject, decision: PolicyDecision,
        provenance: String, rationale: String?, element: String?
    ): Step? {
        if (!recordable(tool.scope)) return null
        val tier = decision.tier ?: com.tekyerema.policy.PolicyConfig.effectiveTier(tool)
        val reason = decision.reason + (decision.operatorRule?.let { " [$it]" } ?: "")
        return Step(source, task, step, tool.name, tier.name, tool.scope.name, decision.outcome.name, reason, provenance,
            decidedBy(rationale, com.tekyerema.operator.OperatorPolicy.lastModel),
            ActionWords.proposal(tool.name, args, element), ActionWords.describe(tool.name, args, element)
        ).also { it.proposed() }
    }

    /**
     * A step a safety rule refused BEFORE the policy gate — a tool an unattended task may not use, a consequential action
     * that must be checked before any retry, an identical send that already went (no-repeat). Recorded as BLOCKED.
     */
    fun refused(source: String, task: Long, step: Int, tool: Tool, args: JSONObject, rationale: String?, why: String) {
        if (!recordable(tool.scope)) return
        AuditLog.append(AuditEntry(0, task, source, AuditLog.BLOCKED, ActionWords.proposal(tool.name, args), step = step,
            tool = tool.name, tier = com.tekyerema.policy.PolicyConfig.effectiveTier(tool).name, scope = tool.scope.name,
            policy = PolicyOutcome.BLOCK.name, reason = why,
            decidedBy = decidedBy(rationale, com.tekyerema.operator.OperatorPolicy.lastModel), outcome = "blocked"))
    }

    /** Who chose the action: the on-device rules, or the model that answered last (the one that made this decision). */
    fun decidedBy(rationale: String?, lastModel: String?): String = when {
        rationale?.startsWith("local:") == true -> "on-device rules"
        rationale?.startsWith("chain:") == true -> "planned sequence (${lastModel ?: "model"})"
        else -> lastModel ?: "unknown model"
    }

    /** The start of an agent task: the user's words (personal — redacted in exports) and the brains it may use. */
    fun taskStarted(source: String, task: Long, goal: String, brain: String) {
        AuditLog.append(AuditEntry(0, task, source, AuditLog.TASK_START, goal, decidedBy = brain.take(120), outcome = "started"))
    }

    /** How the task ended (COMPLETED, COMPLETED_UNVERIFIED, CANNOT_DO, FAILED, STOPPED). */
    fun taskEnded(source: String, task: Long, status: String) {
        AuditLog.append(AuditEntry(0, task, source, AuditLog.TASK_END, "", outcome = status))
    }

    /** Recorded words for a policy outcome, for the viewer: "allowed", "asked you first", "blocked". */
    fun outcomeWords(policy: String): String = when (policy) {
        PolicyOutcome.ALLOW.name -> "allowed"
        PolicyOutcome.CONFIRM.name -> "asked you first"
        PolicyOutcome.BLOCK.name -> "blocked"
        else -> ""
    }
}
