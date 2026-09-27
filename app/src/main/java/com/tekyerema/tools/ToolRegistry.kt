package com.tekyerema.tools

import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry(val all: List<Tool>) {
    private val byName = all.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

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
