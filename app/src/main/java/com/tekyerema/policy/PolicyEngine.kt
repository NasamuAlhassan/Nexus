package com.tekyerema.policy

import com.tekyerema.operator.Capabilities
import com.tekyerema.operator.ExecutionMode
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.tools.Tool
import org.json.JSONObject

/**
 * Deterministic authorization — sits OUTSIDE the model. The LLM cannot override it.
 * Grants everyday scopes; FINANCIAL is intentionally NOT granted and no financial or
 * PIN-capable tool exists (the boundary is architectural, plan §13.1).
 *
 * Workstream C — the user's standing choices ([OperatorPolicy], read at every decision) are applied AFTER the safety
 * decision and can only make it stricter ([restrict]): Guide only blocks every step that changes the phone, Ask every
 * step turns every such step into a confirmation, a switched-off capability blocks its actions, and "cloud off" blocks
 * the tools that need our online services. Standard is today's behaviour. Nothing here can turn a BLOCK or a CONFIRM
 * into anything weaker — the floor (confirmations for consequential actions, hard blocks, Secure Input and the deny
 * list, which live in TargetSemantics / AgentEngine) is out of any setting's reach.
 */
class PolicyEngine(
    private val grantedScopes: Set<CapabilityScope> = setOf(
        CapabilityScope.PERCEIVE,
        CapabilityScope.DEVICE_CONTROL,
        CapabilityScope.COMMS_READ,
        CapabilityScope.COMMS_SEND,
        CapabilityScope.SETTINGS,
        // I-03: web search (owner-approved 2026-09-28). A query copied from screen text is still confirmed (externalScopes).
        CapabilityScope.WEB
    ),
    /** WS-C: the user's execution mode, switched-off capabilities and cloud switch, read fresh for every decision. */
    private val operator: () -> OperatorPolicy.View = { OperatorPolicy.view() }
) {
    /** Scopes where a screen-derived payload crossing the boundary must be confirmed. */
    private val externalScopes = setOf(CapabilityScope.COMMS_SEND, CapabilityScope.SETTINGS, CapabilityScope.FINANCIAL, CapabilityScope.WEB)

    /** What the action is aimed at, so a switched-off capability also covers the GUI route (a tap on "Send"). */
    data class ActionContext(val targetLabel: String? = null, val targetReason: String? = null, val appPackage: String? = null)

    /**
     * @param hasUntrustedParams a payload argument (what is typed/dialled/sent) was copied from
     *   screen content rather than the user's goal — computed by Provenance, never hardcoded.
     * @param targetTier escalation from TargetSemantics (resolved target/label), overriding the
     *   tool's static tier when higher.
     * @param hardBlock a non-null reason from TargetSemantics means BLOCK regardless of tier.
     * @param context the resolved target's label, TargetSemantics' reason and the app in front (WS-C scopes).
     */
    fun evaluate(
        tool: Tool,
        args: JSONObject,
        hasUntrustedParams: Boolean = false,
        targetTier: RiskTier? = null,
        hardBlock: String? = null,
        context: ActionContext? = null
    ): PolicyDecision {
        val base = safetyDecision(tool, args, hasUntrustedParams, targetTier, hardBlock)
        return restrict(base, tool, args, targetTier, context, operator())
    }

    /** The safety decision on its own (today's rules, unchanged): hard blocks, scope grants, tiers, provenance. */
    private fun safetyDecision(tool: Tool, args: JSONObject, hasUntrustedParams: Boolean, targetTier: RiskTier?, hardBlock: String?): PolicyDecision {
        if (hardBlock != null) {
            return PolicyDecision(PolicyOutcome.BLOCK, "Blocked: $hardBlock")
        }
        if (tool.scope !in grantedScopes) {
            return PolicyDecision(
                PolicyOutcome.BLOCK,
                "Capability '${tool.scope}' is not granted for this task."
            )
        }
        // X-04: config may only raise; J-01: arguments can raise too (a Mobile-Money USSD code is TIER3).
        var tier = maxTier(maxTier(PolicyConfig.effectiveTier(tool), PolicyConfig.argTier(tool, args)), targetTier)
        var note = ""
        // Provenance escalation: an untrusted payload only raises the tier when the action
        // actually leaves the device or targets a consequential control — reading a name off the
        // screen and typing it into a search box must not nag the user (plan §24 risk).
        if (hasUntrustedParams && (tool.scope in externalScopes || targetTier != null)) {
            tier = escalate(tier)
            note = " (escalated: payload copied from screen)"
        }

        return when (tier) {
            RiskTier.TIER0, RiskTier.TIER1 ->
                PolicyDecision(PolicyOutcome.ALLOW, "Low-risk action.$note", tier = tier)
            RiskTier.TIER2 ->
                PolicyDecision(
                    PolicyOutcome.CONFIRM,
                    "Meaningful external action requires confirmation.$note",
                    tool.confirmationPrompt(args), tier = tier
                )
            RiskTier.TIER3 ->
                PolicyDecision(
                    PolicyOutcome.CONFIRM,
                    "Financial/irreversible action requires confirmation (PIN is never automated).$note",
                    tool.confirmationPrompt(args), tier = tier
                )
        }
    }

    /**
     * WS-C: apply the user's choices to [base]. Only ever returns [base] or something STRICTER (ALLOW → CONFIRM → BLOCK);
     * a BLOCK is final. Reasons are written for the model's history — it relays them to the user (and, in Guide only,
     * explains how to do it instead).
     */
    internal fun restrict(
        base: PolicyDecision,
        tool: Tool,
        args: JSONObject,
        targetTier: RiskTier?,
        context: ActionContext?,
        op: OperatorPolicy.View
    ): PolicyDecision {
        if (base.outcome == PolicyOutcome.BLOCK) return base
        // Emergency help is never switched off or held back by a mode: the user asked for help out loud (still confirmed).
        if (tool.name in Capabilities.NEVER_SWITCHED_OFF) return base
        val changes = Capabilities.changesPhone(tool.scope, PolicyConfig.effectiveTier(tool))
        if (op.mode == ExecutionMode.GUIDE_ONLY && changes) {
            return PolicyDecision(PolicyOutcome.BLOCK, GUIDE_ONLY_REASON, tier = base.tier, operatorRule = "mode:GUIDE_ONLY")
        }
        val raised = maxTierOrNull(targetTier, PolicyConfig.argTier(tool, args))
        // A notification button ("Reply", "Call back") or an app-defined action ("Send") says what it does in its arguments.
        val actionText = args.optString("action").takeIf { tool.name == "notification_action" }
            ?: args.optString("custom").takeIf { tool.name == "node_action" }
        val cap = Capabilities.of(tool.name, tool.scope, changes, raised, context?.targetReason, context?.targetLabel,
            context?.appPackage, actionText)
        if (cap != null && cap in op.revoked) {
            return PolicyDecision(PolicyOutcome.BLOCK,
                "The user switched off “${Capabilities.label(cap)}” in Settings, Control. Don't do it any other way either. " +
                    "Tell them: ${Capabilities.revokedLine(cap)}",
                tier = base.tier, operatorRule = "off:${cap.name}")
        }
        if (!op.cloudUsable && Capabilities.needsCloud(tool.name, tool.scope)) {
            return PolicyDecision(PolicyOutcome.BLOCK,
                "The online services are off for now (the user's choice or today's limit). Tell them: ${Capabilities.cloudLine(op)}",
                tier = base.tier, operatorRule = if (op.cloudAllowed) "budget" else "cloud-off")
        }
        if (op.mode == ExecutionMode.ASK_EVERY_STEP && changes && base.outcome == PolicyOutcome.ALLOW) {
            return PolicyDecision(PolicyOutcome.CONFIRM,
                "Ask-every-step mode: the user confirms every step that changes something.",
                stepPrompt(tool, args), tier = base.tier, operatorRule = "mode:ASK_EVERY_STEP")
        }
        return base
    }

    /** The read-back for an ordinary step: the tool's own when it has one, else plain words ("Next I'll open WhatsApp."). */
    private fun stepPrompt(tool: Tool, args: JSONObject): String {
        val own = tool.confirmationPrompt(args)
        return if (own != "Proceed with ${tool.name}?") own
            else "Next I'll " + com.tekyerema.audit.ActionWords.proposal(tool.name, args) + ". Go ahead?"
    }

    private fun escalate(t: RiskTier) = when (t) {
        RiskTier.TIER0 -> RiskTier.TIER1
        RiskTier.TIER1 -> RiskTier.TIER2
        RiskTier.TIER2 -> RiskTier.TIER3
        RiskTier.TIER3 -> RiskTier.TIER3
    }

    private fun maxTier(a: RiskTier, b: RiskTier?): RiskTier =
        if (b == null) a else if (b.ordinal > a.ordinal) b else a

    private fun maxTierOrNull(a: RiskTier?, b: RiskTier?): RiskTier? = if (a == null) b else maxTier(a, b)

    companion object {
        /** Guide only: what the model is told (it relays the first sentence and explains how instead). */
        const val GUIDE_ONLY_REASON = "Guide-only mode: the user chose to be told how, so I never touch the phone. Don't act — " +
            "explain step by step what they can tap or say to do it themselves (in done's summary, or cannot_do's reason), " +
            "starting with: “I'm in guide-only mode, so I won't do it myself.”"
    }
}
