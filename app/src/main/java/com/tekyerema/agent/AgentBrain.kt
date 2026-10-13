package com.tekyerema.agent

import org.json.JSONArray
import org.json.JSONObject

/** What the planner sees each step. `screen` is UNTRUSTED data; `goal` is the TRUSTED user request. */
data class BrainContext(
    val goal: String,
    val screen: JSONObject,
    val history: List<String>,
    val geminiTools: JSONArray,   // Gemini functionDeclarations format
    val openAiTools: JSONArray,   // OpenAI/Groq tools format
    /** Prior CONVERSATION turns (older→newer), each "User: …" / "Tɛkyerɛma: …". Lets the brain resolve
     *  references ("him", "it"), corrections ("actually…"), and follow-ups ("what did Kwame send?") across
     *  utterances. Additive + optional: brains that ignore it (LocalBrain) behave exactly as before. */
    val conversation: List<String> = emptyList(),
    /** Compact structured PROGRESS (current app/screen, known entities, recent problems) from [TaskMemory],
     *  so the brain needn't reconstruct task state from the flat history. Additive + optional. */
    val progress: String = "",
    /** C-21: reasoning effort for THIS step ("low" | "medium" | "high"; null = the configured default). */
    val effort: String? = null,
    /** C-08: a step the fast model can take (a direct knowledge answer) — saves the deep model's quota. */
    val simple: Boolean = false
)

sealed class AgentDecision {
    /** One primitive (or accelerator) call, with the model's claim about the outcome it expects.
     *  [argError] is set when the call's arguments could not be parsed (D-06): the engine rejects the call with
     *  that reason instead of running the tool with empty arguments. */
    data class CallTool(val name: String, val args: JSONObject, val rationale: String?, val expects: String? = null,
                        val argError: String? = null,
                        /** B: the model's plan riding in this call (`plan` argument, stripped from [args]) — see agent/plan. */
                        val plan: String? = null) : AgentDecision()
    data class Done(val summary: String) : AgentDecision()
    /** Honest stop: the environment, not a missing tool, prevents the goal (plan §15). */
    data class CannotDo(val reasonClass: String, val detail: String) : AgentDecision()
    /** The model described an action as text instead of calling a tool — counted, never executed. */
    data class Malformed(val text: String) : AgentDecision()
    data class Fail(val reason: String) : AgentDecision()

    companion object {
        /** B: the optional argument that carries the model's plan (declared on the acting tools by ToolRegistry). */
        const val PLAN_ARG = "plan"

        /** D-06: every compiled tool name, from the registry itself, so a new tool can never be missing here. */
        val knownTools: Set<String> by lazy { com.tekyerema.tools.defaultTools().map { it.name }.toSet() }

        private val toolShape by lazy {
            Regex("\"(name|function|parameters|arguments)\"\\s*:|\\b(" + knownTools.joinToString("|") { Regex.escape(it) } + ")\\s*\\(")
        }

        /** Interpret a plain-text reply: a tool-shaped narration is Malformed, anything else is Done. */
        fun fromText(content: String): AgentDecision {
            val c = content.trim()
            if (c.isEmpty()) return Fail("LLM returned neither a tool call nor text.")
            // JSON of any shape is never an answer a blind user should hear ("{ "plan": [ …" was spoken as one, emulator
            // 13 October 2026); the engine's malformed path asks the model for a tool call or one short sentence instead.
            val bare = c.replace(Regex("```[a-zA-Z]*"), "").replace("```", "").trim()
            if ((bare.startsWith("{") && bare.endsWith("}")) || (bare.startsWith("[") && bare.endsWith("]"))) return Malformed(c)
            return if (toolShape.containsMatchIn(c)) Malformed(c) else Done(c)
        }

        /** Build a decision from a parsed tool call, lifting the `expects` claim out of the arguments
         *  and turning the `cannot_do` decision-tool into its own decision type. */
        fun fromToolCall(name: String, args: JSONObject, rationale: String?, argError: String? = null): AgentDecision {
            if (argError != null) return CallTool(name, args, rationale, null, argError)
            if (name == "cannot_do") {
                return CannotDo(
                    args.optString("reason_class").ifBlank { "unsafe_or_uncertain" },
                    args.optString("detail").ifBlank { "no detail given" }
                )
            }
            val expects = if (args.has("expects") && !args.isNull("expects")) args.optString("expects").trim().ifEmpty { null } else null
            args.remove("expects")
            // B: the plan is for the engine, never the tool — lifted out before argument validation and execution.
            val plan = com.tekyerema.agent.plan.PlanParser.flatten(args.opt(PLAN_ARG))
            args.remove(PLAN_ARG)
            return CallTool(name, args, rationale, expects, plan = plan)
        }
    }
}

/** The pluggable reasoning component. GeminiBrain / OpenAiCompatBrain are product paths; TestBrain is a deterministic, scenario-based test double — see TestBrain.kt. */
interface AgentBrain {
    suspend fun decide(ctx: BrainContext): AgentDecision
}
