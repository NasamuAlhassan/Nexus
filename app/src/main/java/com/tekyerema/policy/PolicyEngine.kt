package com.tekyerema.policy

import com.tekyerema.tools.Tool
import org.json.JSONObject

/**
 * Deterministic authorization — sits OUTSIDE the model. The LLM cannot override it.
 * Grants everyday scopes; FINANCIAL is intentionally NOT granted and no financial or
 * PIN-capable tool exists (the boundary is architectural, plan §13.1).
 */
class PolicyEngine(
    private val grantedScopes: Set<CapabilityScope> = setOf(
        CapabilityScope.PERCEIVE,
        CapabilityScope.DEVICE_CONTROL,
        CapabilityScope.COMMS_READ,
        CapabilityScope.COMMS_SEND,
        CapabilityScope.SETTINGS
    )
) {
    /** Scopes where a screen-derived payload crossing the boundary must be confirmed. */
    private val externalScopes = setOf(CapabilityScope.COMMS_SEND, CapabilityScope.SETTINGS, CapabilityScope.FINANCIAL, CapabilityScope.WEB)

    /**
     * @param hasUntrustedParams a payload argument (what is typed/dialled/sent) was copied from
     *   screen content rather than the user's goal — computed by Provenance, never hardcoded.
     * @param targetTier escalation from TargetSemantics (resolved target/label), overriding the
     *   tool's static tier when higher.
     * @param hardBlock a non-null reason from TargetSemantics means BLOCK regardless of tier.
     */
    fun evaluate(
        tool: Tool,
        args: JSONObject,
        hasUntrustedParams: Boolean = false,
        targetTier: RiskTier? = null,
        hardBlock: String? = null
    ): PolicyDecision {
        if (hardBlock != null) {
            return PolicyDecision(PolicyOutcome.BLOCK, "Blocked: $hardBlock")
        }
        if (tool.scope !in grantedScopes) {
            return PolicyDecision(
                PolicyOutcome.BLOCK,
                "Capability '${tool.scope}' is not granted for this task."
            )
        }
        var tier = maxTier(tool.riskTier, targetTier)
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
                PolicyDecision(PolicyOutcome.ALLOW, "Low-risk action.$note")
            RiskTier.TIER2 ->
                PolicyDecision(
                    PolicyOutcome.CONFIRM,
                    "Meaningful external action requires confirmation.$note",
                    tool.confirmationPrompt(args)
                )
            RiskTier.TIER3 ->
                PolicyDecision(
                    PolicyOutcome.CONFIRM,
                    "Financial/irreversible action requires confirmation (PIN is never automated).$note",
                    tool.confirmationPrompt(args)
                )
        }
    }

    private fun escalate(t: RiskTier) = when (t) {
        RiskTier.TIER0 -> RiskTier.TIER1
        RiskTier.TIER1 -> RiskTier.TIER2
        RiskTier.TIER2 -> RiskTier.TIER3
        RiskTier.TIER3 -> RiskTier.TIER3
    }

    private fun maxTier(a: RiskTier, b: RiskTier?): RiskTier =
        if (b == null) a else if (b.ordinal > a.ordinal) b else a
}
