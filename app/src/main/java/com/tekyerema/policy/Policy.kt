package com.tekyerema.policy

enum class RiskTier { TIER0, TIER1, TIER2, TIER3 }

enum class CapabilityScope { PERCEIVE, DEVICE_CONTROL, COMMS_READ, COMMS_SEND, SETTINGS, FINANCIAL, WEB }

enum class PolicyOutcome { ALLOW, CONFIRM, BLOCK }

data class PolicyDecision(
    val outcome: PolicyOutcome,
    val reason: String,
    val confirmationPrompt: String? = null,
    /** The effective risk tier the decision was made at (for the audit); null when a hard block came first. */
    val tier: RiskTier? = null,
    /** Which of the user's operator choices changed the outcome ("mode:GUIDE_ONLY", "off:MESSAGES", "cloud-off"), if any. */
    val operatorRule: String? = null
)
