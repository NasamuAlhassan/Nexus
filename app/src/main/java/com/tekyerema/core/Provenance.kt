package com.tekyerema.core

import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiSnapshot
import org.json.JSONObject

/**
 * Where a value came from.
 *
 * TRUSTED   = the user's goal, our own state, verified tool outputs.
 * UNTRUSTED = screen text, message bodies, notifications, web content, shared text, a paired computer's request.
 *
 * Untrusted content is data, never an instruction. This file is the enforced half of that
 * boundary: the policy gate escalates any consequential action whose PAYLOAD (what is typed,
 * dialled, sent) was copied from the screen rather than supplied by the user (plan §13 b1).
 */
enum class Trust { TRUSTED, UNTRUSTED }

data class Tagged<T>(val value: T, val trust: Trust, val why: String = "") {
    val isTrusted: Boolean get() = trust == Trust.TRUSTED
}

object Provenance {
    /** Argument keys whose values are payloads that leave the device or change state. Targeting
     *  keys (element_id, label, x, y, app, direction…) are deliberately excluded: a target label is
     *  screen-derived by definition, and its risk is handled by TargetSemantics, not here. */
    // "question": ask_app types it into another company's AI app (ChatGPT, Gemini…) — a question built from screen or
    // message text must be confirmed like any other payload that leaves the phone; the user's own words go straight.
    val payloadKeys = setOf("text", "message", "number", "contact", "code", "query", "question",
        "amount", "reference", "_amount", "_number", "_recipient")

    /** The payload keys that name WHO an action reaches (the read-back rule, [com.tekyerema.policy.ActionSemantics]). */
    val recipientKeys = listOf("contact", "sender", "_recipient", "number", "_number")

    const val WHY_GOAL = "in user goal"
    const val WHY_SCREEN = "copied from screen"
    const val WHY_MESSAGE = "copied from a message or notification"
    const val WHY_REMOTE = "from the computer's request"
    const val WHY_AUTHORED = "model-authored"

    private const val MIN_LEN = 4
    /** A label this long that isn't a control is prose someone else wrote (a message bubble, a post, a web page). */
    private const val PROSE_MIN = 40

    /**
     * CaMeL-style conservative taint (research note §2.2 b): once a run has READ third-party free text, a payload the
     * model wrote itself may have been steered by that text — so it is not trusted merely because the model composed it
     * rather than copying it word for word. Returns the reason model-authored payloads are untrusted in this run, or null
     * when the run has read nothing of the kind (the old behaviour: model-authored = trusted).
     *
     * What escalates is still decided by the policy engine (external scopes and consequential targets only), so a TIER2
     * send stays one confirmation and a tier-0 web search is not nagged; what changes is that a payload written after
     * reading content that tried to give orders now counts as untrusted, and J-06 stops the task instead of asking.
     *
     * @param thirdPartyRead the run has read tool output marked untrusted (messages, notifications, web results,
     *   shared text, USSD replies, whole-screen readings)
     * @param screenProse the screen the decision was made on shows another person's free text
     * @param remoteGoal the goal itself came from a paired computer, not the phone's user
     */
    fun taint(thirdPartyRead: Boolean, screenProse: Boolean, remoteGoal: Boolean): String? = when {
        remoteGoal -> "$WHY_AUTHORED for a request from the computer"
        thirdPartyRead -> "$WHY_AUTHORED after reading third-party text"
        screenProse -> "$WHY_AUTHORED after reading another app's text"
        else -> null
    }

    /** True when [s] shows free text someone else wrote: a sentence-length tree label that is not a control. */
    fun screenProse(s: UiSnapshot?): Boolean = s?.elements?.any {
        it.source == ElementSource.TREE && !it.interactive && (it.label?.length ?: 0) >= PROSE_MIN
    } == true

    /**
     * @param untrustedTexts third-party text this run has already read through tools (notification and message
     *   bodies, USSD replies, web results, shared text — J-07). A payload copied from them is untrusted exactly like
     *   one copied from the screen.
     * @param taint from [taint]: when non-null, a payload the model wrote itself is untrusted for that reason.
     * @param goalIsThirdParty the goal is a paired computer's request (MCP): a payload copied from it is a third
     *   party's words, not the user's.
     */
    fun tagArgs(
        args: JSONObject, goal: String, snapshot: UiSnapshot?, untrustedTexts: List<String> = emptyList(),
        taint: String? = null, goalIsThirdParty: Boolean = false
    ): Map<String, Tagged<String>> {
        val out = LinkedHashMap<String, Tagged<String>>()
        val goalLc = goal.lowercase()
        val labels = snapshot?.elements?.mapNotNull { it.label?.lowercase() }.orEmpty()
        val read = untrustedTexts.map { it.lowercase() }
        for (key in payloadKeys) {
            if (!args.has(key) || args.isNull(key)) continue
            val v = args.optString(key).trim()
            if (v.length < MIN_LEN) { out[key] = Tagged(v, Trust.TRUSTED, "short"); continue }
            val vLc = v.lowercase()
            out[key] = when {
                goalLc.contains(vLc) ->
                    if (goalIsThirdParty) Tagged(v, Trust.UNTRUSTED, WHY_REMOTE) else Tagged(v, Trust.TRUSTED, WHY_GOAL)
                labels.any { it.contains(vLc) } -> Tagged(v, Trust.UNTRUSTED, WHY_SCREEN)
                read.any { it.contains(vLc) } -> Tagged(v, Trust.UNTRUSTED, WHY_MESSAGE)
                taint != null -> Tagged(v, Trust.UNTRUSTED, taint)
                else -> Tagged(v, Trust.TRUSTED, WHY_AUTHORED)
            }
        }
        return out
    }

    fun hasUntrusted(tags: Map<String, Tagged<String>>): Boolean = tags.values.any { !it.isTrusted }

    fun describe(tags: Map<String, Tagged<String>>): String =
        tags.entries.filter { !it.value.isTrusted }.joinToString(", ") { "${it.key}=untrusted(${it.value.why})" }
}
