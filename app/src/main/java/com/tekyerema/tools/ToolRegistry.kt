package com.tekyerema.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * The model-facing tool set. [installed] is every tool compiled into the app; [all] is the subset usable right now:
 * a tool whose feature flag is off or whose API gate the device doesn't meet (X-02/X-10, see [ToolManifest]) is
 * hidden from the model and refused by [get]. Computed per call so a config override takes effect immediately.
 */
class ToolRegistry(
    val installed: List<Tool>,
    private val available: (Tool) -> Boolean = { ToolManifest.isAvailable(it) }
) {
    companion object {
        /** The optional plan argument (see [com.tekyerema.agent.AgentDecision.PLAN_ARG]). */
        const val PLAN_PARAM = "plan"
    }

    private val byName = installed.associateBy { it.name }

    val all: List<Tool> get() = installed.filter(available)

    fun get(name: String): Tool? = byName[name]?.takeIf(available)

    /** Gemini-style functionDeclarations describing every tool to the model — or only [subset] (D-02). */
    fun functionDeclarations(subset: Collection<String>? = null): JSONArray {
        val arr = JSONArray()
        for (t in all) {
            if (subset != null && t.name !in subset) continue
            if (t.hiddenFromModel) continue   // an on-phone guide's own tool (money/BankUssdTool): no model ever calls it
            val decl = JSONObject()
                .put("name", t.name)
                .put("description", describe(t))
            if (t.params.isNotEmpty()) {
                val props = JSONObject()
                val required = JSONArray()
                for (p in t.params) {
                    val schema = JSONObject().put("type", p.type).put("description", p.description)
                    if (p.enumValues != null) schema.put("enum", JSONArray(p.enumValues))
                    props.put(p.name, schema)
                    if (p.required) required.put(p.name)
                }
                // B (agenticness plan): an acting tool — one that takes an `expects` claim — may also carry the model's
                // plan for a multi-step goal. No description: the system prompt's PLAN rule names `plan` and its format,
                // and ~25 characters per tool is what the per-step schema budget can spare. The engine lifts it out before
                // argument validation and execution (AgentDecision.fromToolCall), so no tool ever sees it.
                if (props.has("expects") && !props.has(PLAN_PARAM) && planEnabled())
                    props.put(PLAN_PARAM, JSONObject().put("type", "string"))
                val paramsObj = JSONObject().put("type", "object").put("properties", props)
                if (required.length() > 0) paramsObj.put("required", required)
                decl.put("parameters", paramsObj)
            }
            arr.put(decl)
        }
        return arr
    }

    private fun planEnabled(): Boolean = com.tekyerema.core.config.AppConfig.bool("agent.plan.enabled", true)

    /** D-07: the tool's description plus up to two worked examples from tools.json (`examples`), when it has any. */
    internal fun describe(t: Tool): String {
        val ex = ToolManifest.meta(t.name).examples.take(2)
        return if (ex.isEmpty()) t.description else t.description + " Example: " + ex.joinToString(" | ") { it.toString() }
    }

    /** OpenAI/Groq tools format: [{ "type":"function", "function": <declaration> }] — optionally a [subset] (D-02). */
    fun openAiTools(subset: Collection<String>? = null): JSONArray {
        val decls = functionDeclarations(subset)
        val arr = JSONArray()
        for (i in 0 until decls.length()) {
            val d = decls.getJSONObject(i)
            if (!d.has("parameters")) {
                d.put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
            }
            arr.put(JSONObject().put("type", "function").put("function", d))
        }
        return arr
    }
}
