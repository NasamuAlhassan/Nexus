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

    /**
     * J-01: a tier implied by the ARGUMENTS, or null. A USSD code that opens a Mobile-Money menu (any network's
     * root, e.g. *170#, or a one-shot *170*1*1*…# string) can move money, so it is TIER3 whichever tool dials it;
     * `policy.moneyUssdPrefixes` can add prefixes.
     */
    fun argTier(tool: Tool, args: org.json.JSONObject): RiskTier? {
        // E-17: pressing a notification's button (archive, delete, call back…) is confirmed; dismissing isn't.
        if (tool.name == "notification_action")
            return if (args.optString("action").trim().equals("dismiss", true)) null else RiskTier.TIER2
        // G-03: an app-defined action that sounds consequential ("Delete", "Archive", "Send") is confirmed.
        if (tool.name == "node_action") {
            val custom = args.optString("custom").trim()
            return if (custom.isNotEmpty() && TargetSemantics.consequential.containsMatchIn(custom)) RiskTier.TIER2 else null
        }
        if (tool.name != "run_ussd") return null
        val code = args.optString("code").replace(Regex("\\s"), "")
        if (code.isEmpty()) return null
        val prefixes = com.tekyerema.tools.GhanaMobileMoney.moneyPrefixes() +
            AppConfig.stringList("policy.moneyUssdPrefixes", emptyList()).map { it.trim().trimEnd('#') }.filter { it.isNotEmpty() }
        return if (prefixes.any { p -> code == "$p#" || code.startsWith("$p*") || code == p }) RiskTier.TIER3 else null
    }

    /** J-11: packages the agent must never operate. */
    fun denyApps(): Set<String> = AppConfig.stringList("policy.denyApps", emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** J-11: packages where the agent only guides (reads and explains) and never acts. */
    fun guideOnlyApps(): Set<String> = AppConfig.stringList("policy.guideOnlyApps", emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
