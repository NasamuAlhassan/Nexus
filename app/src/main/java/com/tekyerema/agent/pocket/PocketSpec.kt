package com.tekyerema.agent.pocket

import org.json.JSONArray
import org.json.JSONObject

/**
 * The pocket brain's model contract, read from the spec.json the training run wrote next to model.onnx
 * (campaign/kaggle/pocket-brain-v2/run_pocket.py, export_best). Pure: no Android, no ONNX -> PocketBrainTest.
 *
 * Everything the decoder needs is in here (heads' label lists, slot slices, temperatures, thresholds), so a new run
 * (v2_l6, a retrain) is dropped in by replacing the files; nothing is hard-coded in Kotlin except the question wording.
 */
class PocketSpec(
    val version: String,
    val tools: List<String>,            // last entry "_none"
    val statuses: List<String>,         // ready, missing_slot, ambiguous, unsafe, no_tool_fits
    val missingSlots: List<String>,     // amount, contact, message, time, place, app, query
    val riskTools: Set<String>,
    val slotKeys: List<SlotKey>,
    val spanArgNames: List<String>,     // order of the tag head's per-argument channels
    val tTool: Float,
    val tStatus: Float,
    val tauRisk: Float,
    val tauOther: Float,
    val policyVariant: String,          // "msp" or "knn" (knn needs the bank, which the app does not ship: msp is used)
    val maxLen: Int,
    val padId: Int,
    val clsId: Int,
    val sepId: Int,
    val doLowerCase: Boolean,
    val truncation: String,             // "only_second" | "longest_first"
    val requestCharCap: Int,
    val contextLines: Int,
    val contextCharCap: Int,
    val ignoredKeys: Set<String>,       // "tool.arg" built by the rules, not by the model
    val outputNames: List<String>
) {
    /** One slot head entry. Class 0 = ABSENT, 1 = SPAN (cut from the request by the tag head), 2.. = [classes] constants. */
    class SlotKey(val tool: String, val arg: String, val mode: String, val from: Int, val to: Int, val classes: List<Any?>)

    val noneTool = "_none"
    val realTools: List<String> get() = tools.filter { it != noneTool }
    fun keysOf(tool: String): List<SlotKey> = slotKeys.filter { it.tool == tool }
    fun isRisk(tool: String) = tool in riskTools

    companion object {
        /** Parses spec.json text. Throws IllegalArgumentException on a spec this code cannot honour. */
        fun parse(text: String): PocketSpec {
            val j = JSONObject(text)
            fun strs(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
            val tokenizer = j.optJSONObject("tokenizer") ?: JSONObject()
            val fmt = j.optString("input_format")
            val trunc = when {
                "only_second" in fmt -> "only_second"
                "longest_first" in fmt -> "longest_first"
                else -> tokenizer.optString("truncation", "longest_first")
            }
            val keys = j.getJSONArray("slot_keys").let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    val sl = o.getJSONArray("slice")
                    val cl = o.getJSONArray("classes")
                    SlotKey(o.getString("tool"), o.getString("arg"), o.optString("mode"), sl.getInt(0), sl.getInt(1),
                        (0 until cl.length()).map { k -> cl.get(k).takeIf { it != JSONObject.NULL } })
                }
            }
            val temps = j.optJSONObject("temperatures") ?: JSONObject()
            val pol = j.optJSONObject("policy") ?: JSONObject()
            val tools = strs(j.getJSONArray("tools"))
            require(tools.isNotEmpty() && tools.last() == "_none") { "spec: tools must end with _none" }
            val statuses = strs(j.getJSONArray("statuses"))
            require(statuses.size >= 5) { "spec: statuses" }
            return PocketSpec(
                version = j.optString("version"),
                tools = tools,
                statuses = statuses,
                missingSlots = strs(j.getJSONArray("missing_slots")),
                riskTools = strs(j.optJSONArray("risk_tools") ?: JSONArray()).toSet(),
                slotKeys = keys,
                spanArgNames = strs(j.optJSONArray("span_arg_names") ?: JSONArray()),
                tTool = temps.optDouble("tool", 1.0).toFloat(),
                tStatus = temps.optDouble("status", 1.0).toFloat(),
                tauRisk = pol.optDouble("tau_risk", 0.9).toFloat(),
                tauOther = pol.optDouble("tau_other", 0.0).toFloat(),
                policyVariant = pol.optString("variant", "msp"),
                maxLen = j.optInt("max_len", 128),
                padId = j.optInt("pad_token_id", 0),
                clsId = j.optInt("cls_token_id", 101),
                sepId = j.optInt("sep_token_id", 102),
                doLowerCase = tokenizer.optBoolean("do_lower_case", true),
                truncation = trunc,
                requestCharCap = tokenizer.optInt("request_char_cap", 600),
                contextLines = tokenizer.optInt("context_lines", 2),
                contextCharCap = tokenizer.optInt("context_char_cap", 300),
                ignoredKeys = strs(j.optJSONArray("ignored_keys") ?: JSONArray()).toSet(),
                outputNames = listOf("tool_logits", "status_logits", "missing_logits", "slot_logits", "tag_logits")
            )
        }
    }
}
