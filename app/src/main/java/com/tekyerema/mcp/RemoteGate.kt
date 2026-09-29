package com.tekyerema.mcp

import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.policy.PolicyDecision
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.Tool
import org.json.JSONObject

/**
 * The rules for a task a paired COMPUTER asked for over MCP (pure, unit-tested). The computer is a different principal
 * from the person holding the phone (research note §1.4 item 3, Meta's "Rule of Two"), so its tasks run through the
 * same engine and the same policy gate as a typed request, with three extra rules on top — never instead:
 *
 *  1. Read-only by default: a remote task may only read (the screen, messages, the web) unless the user has allowed
 *     "let my computer ask for actions" ([RemoteControl.actionsAllowed]).
 *  2. Even then, every step that changes anything is confirmed ON THE PHONE by the phone's user (a floor of CONFIRM —
 *     it never lowers a BLOCK). No MCP method can answer that confirmation.
 *  3. The goal is a third party's words: a payload copied from it is not "in the user's goal" (see
 *     [com.tekyerema.core.Provenance.tagArgs]), and the read-back says the computer asked for it.
 */
object RemoteGate {
    const val PREFIX = "remote:"

    /** Scopes whose tools only look at things. A remote read-only task may use a tool here at tier 0. */
    private val READ_SCOPES = setOf(CapabilityScope.PERCEIVE, CapabilityScope.COMMS_READ, CapabilityScope.WEB)

    fun isRemote(origin: String?): Boolean = origin?.startsWith(PREFIX) == true

    /** "remote:<client>" — the source tag written to the audit log, the task journal and the engine state. */
    fun origin(client: String?): String = PREFIX + clientName(client)

    /**
     * A client's self-chosen name, made safe to log and to show: letters, digits, space, dot, dash and underscore, at
     * most 32 characters. It is a label only — never spoken as if it were a person ("Mum" would be a lure).
     */
    fun clientName(raw: String?): String =
        raw.orEmpty().filter { it.isLetterOrDigit() && it.code < 128 || it in " ._-" }.trim().take(32).ifBlank { "computer" }

    /** A tool that only reads: a read scope at tier 0 (config can raise a tier, which makes it not read-only). */
    fun readOnly(tool: Tool): Boolean = tool.scope in READ_SCOPES && PolicyConfig.effectiveTier(tool) == RiskTier.TIER0

    /** Why a remote task may not use [tool], or null when it may (rule 1). Local tasks (origin null) are never refused here. */
    fun refusal(origin: String?, tool: Tool, actionsAllowed: Boolean): String? = when {
        !isRemote(origin) || readOnly(tool) -> null
        !actionsAllowed -> "this task came from the user's computer, which may only ask me to read and tell, not to act " +
            "(the user hasn't allowed remote actions)"
        else -> null
    }

    /** Rule 2: a remote task's step that changes anything is at least CONFIRM. A BLOCK or a CONFIRM stays as it is. */
    fun floor(origin: String?, tool: Tool, args: JSONObject, pd: PolicyDecision): PolicyDecision = when {
        !isRemote(origin) || readOnly(tool) || pd.outcome != PolicyOutcome.ALLOW -> pd
        else -> PolicyDecision(PolicyOutcome.CONFIRM, pd.reason + " Remote request: confirmed on the phone.",
            pd.confirmationPrompt ?: tool.confirmationPrompt(args))
    }

    /** Rule 3: the read-back names where the request came from, before what it does. */
    fun prompt(origin: String?, prompt: String): String =
        if (!isRemote(origin)) prompt else "Your computer is asking me to do this. $prompt"
}
