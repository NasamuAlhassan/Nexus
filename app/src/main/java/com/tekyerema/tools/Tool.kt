package com.tekyerema.tools

import android.content.Context
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import org.json.JSONObject

data class ToolParam(
    val name: String,
    val type: String,               // "string" | "integer" | "boolean"
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null
)

data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: JSONObject? = null,
    val needsDisambiguation: Boolean = false,
    /** Which mechanism level actually executed (plan §7.2): "node" | "gesture" | "vision" | "intent" | "native" | "none". */
    val channel: String = "none",
    /** For a FAILED result only: can the planner recover by trying a DIFFERENT strategy — typically the
     *  General Operator UI — instead of treating the whole task as impossible? Hybrid-architecture §3/§4:
     *  an accelerator "miss" (shortcut didn't apply) is recoverable; a genuine environment dead-end
     *  (no network, a permission the user refused) is not. Ignored when [success] is true. */
    val recoverable: Boolean = true
)

class ToolContext(
    val appContext: Context,
    val controller: AccessibilityController?,
    val notifications: NotificationStore,
    /** The exact UiSnapshot the model reasoned over when it decided to call this tool
     *  (AgentEngine's `before`). Lets a tool detect whether the screen has materially
     *  changed between planning and execution. */
    val observedBefore: UiSnapshot? = null,
    /** Static target resolution done by the engine before the policy gate, if the tool takes a target. */
    val resolvedTarget: ResolvedTarget? = null,
    /** D8: set only by the emergency tool for its own steps (the help text) — the user's app rules don't stop those. */
    val emergency: Boolean = false
)

/**
 * A typed capability the planner can compose. Either a universal PRIMITIVE (tap, type, scroll…)
 * or a justified native accelerator (plan §14). NOT a user command and NOT a task recipe.
 * Determinism lives here (mechanics, validation); reasoning lives in the agent.
 */
interface Tool {
    val name: String
    val description: String
    val scope: CapabilityScope
    val riskTier: RiskTier
    val params: List<ToolParam>

    /** True if this tool grounds against a specific on-screen target (element/label/point) and
     *  therefore needs the stale-state check before execution. */
    val grounded: Boolean get() = false

    /** True if this capability is a native ACCELERATOR — a faster / more reliable / more deterministic
     *  shortcut for a sub-operation (open_app, search_contacts, make_call, set_alarm…) — rather than a
     *  universal General-Operator primitive (tap/long_press/swipe/scroll/type…). Hybrid architecture:
     *  accelerators are OPTIONAL shortcuts the planner may compose; when one fails, the engine tells the
     *  planner it can fall back to the General Operator UI, never that the task itself failed. Used for
     *  recovery framing and capability discovery — NOT for any app-specific branching. */
    val isAccelerator: Boolean get() = false

    /** True if the tool's own verify() checks an OUTCOME (foreground package, range value…) rather
     *  than a weak "screen changed" signal. Only outcome-typed defaults may yield verified=true
     *  when the model supplied no concrete expectation. */
    val verifiesOutcome: Boolean get() = false

    /** Called only by an on-phone guide (money safety round 2: the guided bank menu, money/BankUssdTool) — never offered
     *  to a model: no schema entry, no find_tool hit. Default: offered like any tool. */
    val hiddenFromModel: Boolean get() = false

    /** Side-effect-free check that the action CAN proceed, run BEFORE the policy confirmation. Non-null = it can't
     *  (e.g. make_call to a name that isn't in the contacts, or matches several people): the engine then skips the
     *  confirmation and hands this failure to the brain, so a blind user is never asked to approve an action that
     *  would only fail — or that would go to someone they didn't mean. Default: no preflight. */
    suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? = null

    suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult

    suspend fun verify(
        args: JSONObject,
        ctx: ToolContext,
        before: UiSnapshot?,
        after: UiSnapshot?,
        result: ToolResult
    ): VerificationResult = VerificationResult(result.success, result.message)

    fun confirmationPrompt(args: JSONObject): String = "Proceed with $name?"
}

/** Small helpers for reading JSON args defensively. */
fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
/** Null for a value that isn't a whole number — optInt turns "7am" into 0, and a 00:00 alarm was set (audit half 2 #13). */
fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optString(key).trim().toIntOrNull() else null
fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
fun JSONObject.boolOrDefault(key: String, default: Boolean): Boolean = if (has(key) && !isNull(key)) optBoolean(key, default) else default
