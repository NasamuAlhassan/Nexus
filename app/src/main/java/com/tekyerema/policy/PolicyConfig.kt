package com.tekyerema.policy

import com.tekyerema.core.config.AppConfig
import com.tekyerema.tools.Tool

/**
 * X-04 — the risk policy as data (`assets/config/policy.json`). Config can make the agent MORE careful, never
 * less: a tier from the file only applies when it is higher than the tool's coded tier, and the hard boundaries
 * (Secure Input Mode at PIN/credential screens, password redaction, Sanitize) stay in code where no file can
 * reach them.
 *
 *   { "policy": { "toolTiers": { "open_app": "TIER1" }, "denyApps": ["com.example.bank"], "guideOnlyApps": [] } }
 */
object PolicyConfig {
    /** The tier the policy engine uses for [tool]: max(coded tier, configured tier). */
    fun effectiveTier(tool: Tool): RiskTier {
        val configured = AppConfig.string("policy.toolTiers.${tool.name}", "")
            .let { s -> RiskTier.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } }
        return if (configured != null && configured.ordinal > tool.riskTier.ordinal) configured else tool.riskTier
    }

    /** J-11: packages the agent must never operate. */
    fun denyApps(): Set<String> = AppConfig.stringList("policy.denyApps", emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** J-11: packages where the agent only guides (reads and explains) and never acts. */
    fun guideOnlyApps(): Set<String> = AppConfig.stringList("policy.guideOnlyApps", emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
