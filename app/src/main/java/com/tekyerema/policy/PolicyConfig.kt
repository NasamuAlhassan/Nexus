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
            return if (custom.isNotEmpty() && (TargetSemantics.consequential.containsMatchIn(custom) || TargetSemantics.isConsent(custom)))
                RiskTier.TIER2 else null
        }
        // Money safety round 2: the guided bank menu — opening it is TIER3, a typed amount/account number TIER2 (money/BankUssdTool).
        if (tool.name == com.tekyerema.money.BankUssdTool.NAME) return com.tekyerema.money.BankUssdTool.argTier(args)
        if (tool.name == com.tekyerema.money.TelcoUssdTool.NAME) return com.tekyerema.money.TelcoUssdTool.argTier(args)
        if (tool.name != "run_ussd") return null
        val code = args.optString("code").replace(Regex("\\s"), "")
        if (code.isEmpty()) return null
        val prefixes = com.tekyerema.tools.GhanaMobileMoney.moneyPrefixes() + com.tekyerema.money.BankUssd.moneyPrefixes() +   // + verified bank codes (round 2)
            AppConfig.stringList("policy.moneyUssdPrefixes", emptyList()).map { it.trim().trimEnd('#') }.filter { it.isNotEmpty() }
        return if (prefixes.any { p -> code == "$p#" || code.startsWith("$p*") || code == p }) RiskTier.TIER3 else null
    }

    /**
     * A balance check the user asked for: read-only, free, and — for Mobile Money — still stopped at the user's own PIN.
     * The policy engine allows it without a "shall I?" (owner, 2 October 2026: "it should only interrupt when genuinely
     * necessary"; "what's my airtime balance" used to be answered with "Check your airtime balance?"). Narrow on
     * purpose: the network's checked airtime-balance code, the navigator's balance walk, the guided data-balance menu —
     * nothing that buys, sends or types a value. The tools' coded tiers are untouched, so every other layer (provenance,
     * the operator's switches, a computer's request) treats them as before.
     */
    fun readOnlyCheck(tool: Tool, args: org.json.JSONObject): Boolean = when (tool.name) {
        "run_ussd" -> args.optString("code").replace(Regex("\\s"), "") in freeReadOnlyCodes()
        com.tekyerema.money.ussd.UssdNavigatorTool.NAME -> com.tekyerema.money.ussd.UssdNavigatorTool.isBalance(args)
        else -> false
    }

    /** Codes that only read something back and cost nothing: the airtime-balance code, plus `policy.freeUssdCodes`. */
    fun freeReadOnlyCodes(): Set<String> =
        (listOf(AppConfig.string("connectors.telco.airtimeBalanceCode", "*124#")) + AppConfig.stringList("policy.freeUssdCodes", emptyList()))
            .map { it.replace(Regex("\\s"), "") }.filter { it.isNotEmpty() }.toSet()

    /**
     * J-11: packages the agent must never operate — nor read (ScreenPrivacy). The app's own list PLUS the apps the user
     * said "never touch" ([com.tekyerema.operator.OperatorPolicy.appRules], D8): a user can add to it, never take a
     * config entry away. Read at every decision, so a change applies to the next task.
     */
    fun denyApps(): Set<String> = configDenyApps() + com.tekyerema.operator.OperatorPolicy.userApps(com.tekyerema.operator.AppRule.NEVER)

    /** J-11: packages where the agent only guides (reads and explains) and never acts — config ∪ the user's "guide only". */
    fun guideOnlyApps(): Set<String> = configGuideOnlyApps() + com.tekyerema.operator.OperatorPolicy.userApps(com.tekyerema.operator.AppRule.GUIDE)

    /** The app's own deny list only (`policy.denyApps`) — shown as "set by the app" and never removable. */
    fun configDenyApps(): Set<String> = AppConfig.stringList("policy.denyApps", emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** The app's own guide-only list only (`policy.guideOnlyApps`). */
    fun configGuideOnlyApps(): Set<String> = AppConfig.stringList("policy.guideOnlyApps", emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
