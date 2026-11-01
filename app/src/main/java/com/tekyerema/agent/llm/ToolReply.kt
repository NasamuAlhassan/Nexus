package com.tekyerema.agent.llm

import org.json.JSONObject

/**
 * A small model's reply → (tool, arguments), whatever format its chat template taught it — the benchmark's parse_reply:
 * plain JSON {"tool","args"} (or {"name","arguments"}), Qwen3.5's <function=…><parameter=…>, LFM2's [name(k="v")], and
 * FunctionGemma's call:name{k:<escape>v<escape>}. Null when no call is found: never a guess. Pure, unit-tested.
 */
object ToolReply {
    data class Call(val tool: String, val args: JSONObject)

    fun parse(raw: String): Call? {
        val text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        return functionGemma(text) ?: xmlParams(text) ?: pythonic(text) ?: json(text) ?: cutOff(text)
    }

    /** A JSON reply cut off after its tool and arguments (the model kept writing extra fields until its token cap): the
     *  "tool" string and a complete "args" object are enough. */
    private fun cutOff(text: String): Call? {
        val tool = Regex("\"tool\"\\s*:\\s*\"([A-Za-z_]\\w*)\"").find(text)?.groupValues?.get(1) ?: return null
        val at = Regex("\"args\"\\s*:\\s*\\{").find(text) ?: return Call(tool, JSONObject())
        val start = at.range.last
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { Call(tool, JSONObject(text.substring(start, i + 1))) }.getOrNull()
            }
        }
        return null   // the arguments themselves were cut off: never a guess
    }

    private fun value(v: String): Any {
        val s = v.trim()
        return when {
            s == "true" || s == "True" -> true
            s == "false" || s == "False" -> false
            s.toLongOrNull() != null -> s.toLong()
            s.toDoubleOrNull() != null -> s.toDouble()
            s.startsWith("{") -> runCatching { JSONObject(s) }.getOrElse { s }
            else -> s
        }
    }

    private fun xmlParams(text: String): Call? {
        val m = Regex("(?s)<function=([A-Za-z_]\\w*)>(.*?)(?:</function>|$)").find(text) ?: return null
        val args = JSONObject()
        for (p in Regex("(?s)<parameter=([A-Za-z_]\\w*)>\\s*(.*?)\\s*</parameter>").findAll(m.groupValues[2])) args.put(p.groupValues[1], value(p.groupValues[2]))
        return Call(m.groupValues[1], args)
    }

    private fun pythonic(text: String): Call? {
        val m = Regex("(?s)<\\|tool_call_start\\|>\\s*\\[\\s*([A-Za-z_]\\w*)\\((.*?)\\)\\s*\\]").find(text) ?: return null
        val args = JSONObject()
        val pair = Regex("([A-Za-z_]\\w*)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[^,)]+)")
        for (p in pair.findAll(m.groupValues[2])) {
            val k = p.groupValues[1]
            if (args.has(k)) continue
            val v = p.groupValues[2]
            args.put(k, if (v.startsWith("\"") || v.startsWith("'")) v.substring(1, v.length - 1).replace("\\" + v[0], v[0].toString()) else value(v))
        }
        return Call(m.groupValues[1], args)
    }

    private fun functionGemma(text: String): Call? {
        // Every literal brace and bracket escaped: Android's ICU regex rejects a bare "}" that desktop Java accepts (emulator,
        // 1 Nov 2026: "Syntax error in regexp pattern" crashed the planner's first on-device answer; JVM tests had passed).
        val m = Regex("(?s)call:([A-Za-z_]\\w*)\\{(.*?)\\}(?:<end_function_call>|$)").find(text) ?: return null
        val body = m.groupValues[2]
        val args = JSONObject()
        var i = 0
        val key = Regex("^\\s*,?\\s*([A-Za-z_]\\w*)\\s*:")
        while (i < body.length) {
            val k = key.find(body.substring(i)) ?: break
            i += k.range.last + 1
            if (body.startsWith("<escape>", i)) {
                var j = body.indexOf("<escape>", i + 8)
                if (j < 0) j = body.length
                args.put(k.groupValues[1], body.substring(i + 8, j))
                i = j + 8
            } else {
                val v = body.substring(i).substringBefore(',')
                args.put(k.groupValues[1], value(v))
                i += v.length + 1
            }
        }
        return Call(m.groupValues[1], args)
    }

    /** The first balanced {...} that names a tool. */
    private fun json(text: String): Call? {
        var start = text.indexOf('{')
        while (start >= 0) {
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) {
                        val o = runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
                        if (o != null) {
                            val name = o.optString("tool").ifBlank { o.optString("name").ifBlank { o.optString("function") } }
                            if (name.isNotBlank()) {
                                val a = o.opt("args") ?: o.opt("arguments") ?: o.opt("parameters")
                                val args = when (a) {
                                    is JSONObject -> a
                                    is String -> runCatching { JSONObject(a) }.getOrElse { JSONObject() }
                                    else -> JSONObject()
                                }
                                return Call(name, args)
                            }
                        }
                        break
                    }
                }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }
}
