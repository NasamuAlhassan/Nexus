package com.tekyerema.core

import com.tekyerema.accessibility.UiSnapshot
import org.json.JSONObject

/**
 * Where a value came from.
 *
 * TRUSTED   = the user's goal, our own state, verified tool outputs.
 * UNTRUSTED = screen text, message bodies, notifications, web content.
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
    val payloadKeys = setOf("text", "message", "number", "contact", "code", "query")
    private const val MIN_LEN = 4

    fun tagArgs(args: JSONObject, goal: String, snapshot: UiSnapshot?): Map<String, Tagged<String>> {
        val out = LinkedHashMap<String, Tagged<String>>()
        val goalLc = goal.lowercase()
        val labels = snapshot?.elements?.mapNotNull { it.label?.lowercase() }.orEmpty()
        for (key in payloadKeys) {
            if (!args.has(key) || args.isNull(key)) continue
            val v = args.optString(key).trim()
            if (v.length < MIN_LEN) { out[key] = Tagged(v, Trust.TRUSTED, "short"); continue }
            val vLc = v.lowercase()
            out[key] = when {
                goalLc.contains(vLc) -> Tagged(v, Trust.TRUSTED, "in user goal")
                labels.any { it.contains(vLc) } -> Tagged(v, Trust.UNTRUSTED, "copied from screen")
                else -> Tagged(v, Trust.TRUSTED, "model-authored")
            }
        }
        return out
    }

    fun hasUntrusted(tags: Map<String, Tagged<String>>): Boolean = tags.values.any { !it.isTrusted }

    fun describe(tags: Map<String, Tagged<String>>): String =
        tags.entries.filter { !it.value.isTrusted }.joinToString(", ") { "${it.key}=untrusted(${it.value.why})" }
}
