package com.tekyerema.policy

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.Injection
import com.tekyerema.core.Provenance
import com.tekyerema.core.Tagged
import com.tekyerema.mcp.RemoteGate
import com.tekyerema.tools.Tool
import org.json.JSONObject

/**
 * The injection-facing half of the per-step gate, as one pure function the engine calls and the injection suite
 * (InjectionSuiteTest) exercises directly — so the tests run the code that runs on the phone, not a copy of it.
 *
 * Order (AgentEngine, after target semantics):
 *  1. provenance of every payload, with CaMeL-style taint once the run has read third-party text, and a paired
 *     computer's goal counted as a third party's words ([Provenance.taint], [Provenance.tagArgs]);
 *  2. the policy engine's decision (tiers, scopes, target semantics, escalation for untrusted payloads) — passed in as
 *     [evaluate] so this file doesn't pin the engine's signature (Workstream C owns modes/scopes);
 *  3. a control a person can't perceive as the agent reads it (hidden text, a description that disagrees with the
 *     visible text — [com.tekyerema.accessibility.Perceivability]) is never the target of a consequential action;
 *  4. the remote floor: a paired computer's task never runs a step that changes anything without the phone's user
 *     ([RemoteGate.floor]);
 *  5. instruction-like text inside the payload itself (J-06).
 * The engine then asks the optional guard model and applies [injectionStop].
 */
object StepGate {
    data class Assessment(
        val tags: Map<String, Tagged<String>>,
        val untrusted: Boolean,
        val decision: PolicyDecision,
        /** The instruction-like phrase inside a payload the model is about to use, or null. */
        val payloadInjection: String?
    )

    fun assess(
        tool: Tool, args: JSONObject, goal: String, before: UiSnapshot?, untrustedRead: List<String>,
        target: UiElement?, origin: String?, evaluate: (untrusted: Boolean) -> PolicyDecision
    ): Assessment {
        val remote = RemoteGate.isRemote(origin)
        val taint = Provenance.taint(thirdPartyRead = untrustedRead.isNotEmpty(), screenProse = Provenance.screenProse(before),
            remoteGoal = remote)
        val tags = Provenance.tagArgs(args, goal, before, untrustedRead, taint, goalIsThirdParty = remote)
        val untrusted = Provenance.hasUntrusted(tags)
        var pd = unseenFloor(target, evaluate(untrusted))
        pd = RemoteGate.floor(origin, tool, args, pd)
        // (integration: a remote step the floor just raised to CONFIRM is consequential too — rule 3 again after rule 4.)
        pd = unseenFloor(target, pd)
        val payloadInjection = Provenance.payloadKeys.firstNotNullOfOrNull { k ->
            if (args.has(k)) Injection.detect(args.optString(k)) else null
        }
        return Assessment(tags, untrusted, pd, payloadInjection)
    }

    /**
     * Rule 3 on its own: a step that must be CONFIRMED is BLOCKED when its target is a control a person can't perceive as
     * the agent reads it ([UiElement.unseen]). Only ever raises. Also applied by the engine after Workstream A's effect
     * ledger turns an allowed step into a confirmation ("I already sent this…").
     */
    fun unseenFloor(target: UiElement?, pd: PolicyDecision): PolicyDecision {
        val unseen = target?.unseen ?: return pd
        if (pd.outcome != PolicyOutcome.CONFIRM) return pd
        return PolicyDecision(PolicyOutcome.BLOCK, "Blocked: that control's text isn't what the screen shows ($unseen) — " +
            "hidden or mismatched text is a known trick, so it is never the target of a consequential action.")
    }

    /**
     * J-06: stop the task (nothing is sent, the user hears why) instead of asking — when the payload itself carries
     * instructions, the guard model flagged the text behind it, or the run has seen content that tried to give orders
     * and this consequential step's payload is untrusted.
     */
    fun injectionStop(a: Assessment, sentinelBad: Boolean, injectionSource: String?): Boolean =
        a.payloadInjection != null || sentinelBad ||
            (injectionSource != null && a.untrusted && a.decision.outcome != PolicyOutcome.ALLOW)
}
