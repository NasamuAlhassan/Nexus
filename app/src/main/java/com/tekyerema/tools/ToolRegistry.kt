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
    private val byName = installed.associateBy { it.name }

    val all: List<Tool> get() = installed.filter(available)

    fun get(name: String): Tool? = byName[name]?.takeIf(available)

    /** Gemini-style functionDeclarations describing every tool to the model. */
    fun functionDeclarations(): JSONArray {
        val arr = JSONArray()
        for (t in all) {
            val decl = JSONObject()
                .put("name", t.name)
                .put("description", t.description)
            if (t.params.isNotEmpty()) {
                val props = JSONObject()
                val required = JSONArray()
                for (p in t.params) {
                    val schema = JSONObject().put("type", p.type).put("description", p.description)
                    if (p.enumValues != null) schema.put("enum", JSONArray(p.enumValues))
                    props.put(p.name, schema)
                    if (p.required) required.put(p.name)
                }
                val paramsObj = JSONObject().put("type", "object").put("properties", props)
                if (required.length() > 0) paramsObj.put("required", required)
                decl.put("parameters", paramsObj)
            }
            arr.put(decl)
        }
        return arr
    }

    /** OpenAI/Groq tools format: [{ "type":"function", "function": <declaration> }]. */
    fun openAiTools(): JSONArray {
        val decls = functionDeclarations()
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
