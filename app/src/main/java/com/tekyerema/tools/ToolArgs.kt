package com.tekyerema.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * D-06 — check a model-proposed call against the tool's declared [ToolParam]s BEFORE target resolution, policy or
 * execution. Deliberately lenient where tools are lenient (e.g. set_volume's integer `level` also takes "mute"), and
 * strict where a wrong value would silently do the wrong thing:
 *  - a required parameter that is missing or blank → rejected;
 *  - an enum value outside the declared set (after trimming/lower-casing, spaces → underscores) → rejected;
 *  - an object/array where a scalar is declared → rejected;
 *  - numeric strings for integer params and "true"/"false" for boolean params are coerced in place.
 * Unknown extra arguments are left alone (tools read only what they declare).
 */
object ToolArgs {
    /** @return null when [args] are acceptable (possibly normalised in place), else a reason for the model. */
    fun validate(tool: Tool, args: JSONObject): String? {
        for (p in tool.params) {
            val present = args.has(p.name) && !args.isNull(p.name)
            if (!present) {
                if (p.required) return "missing required argument '${p.name}'"
                continue
            }
            val v = args.get(p.name)
            if (v is JSONObject || v is JSONArray) return "argument '${p.name}' must be a single ${p.type}, not a list or object"
            if (p.required && v is String && v.isBlank()) return "argument '${p.name}' is empty"
            when (p.type) {
                "integer" -> if (v is String) v.trim().toDoubleOrNull()?.let { args.put(p.name, it.toLong()) }
                "boolean" -> if (v is String) when (v.trim().lowercase()) {
                    "true", "yes" -> args.put(p.name, true)
                    "false", "no" -> args.put(p.name, false)
                }
                "string" -> if (v !is String) args.put(p.name, v.toString())
            }
            val allowed = p.enumValues
            if (allowed != null) {
                val norm = args.get(p.name).toString().trim().lowercase().replace(Regex("[\\s-]+"), "_")
                val match = allowed.firstOrNull { it.lowercase() == norm }
                    ?: return "argument '${p.name}' must be one of ${allowed.joinToString(", ")} (got '${args.get(p.name)}')"
                args.put(p.name, match)
            }
        }
        return null
    }
}
