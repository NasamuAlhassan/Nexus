package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig
import org.json.JSONObject

/**
 * The on-device PLANNER (31 Oct 2026; was the C-04 one-tool stub): an open-weight model on the phone takes the cloud
 * planner's seat when the router hands it a task — offline, or always with `router.localModel.first`. Each step it gets
 * the agent benchmark's prompt ([com.tekyerema.agent.llm.PlannerPrompt]: the step's tools, the goal, the steps so far, the
 * screen) and answers with ONE tool call, decoding constrained to a tool of this step ([PlannerPrompt.replySchema]).
 *
 * Off unless `router.localModel.enabled`, a runtime has registered ([LocalModelRuntime], the app registers LiteRT-LM), the
 * model file exists and the phone has the RAM. Safety is unchanged: the model only proposes; every call goes through the
 * engine's policy gates, confirmations, the money flow and the verifier like a cloud decision, a call outside this step's
 * tools is Malformed (never run), and USSD is never the model's to dial. A reply that doesn't parse is never a guess.
 */
interface OnDeviceLlm {
    /** Model id for logs, e.g. "qwen3.5-0.8b-int8". */
    val id: String
    /** Complete [prompt] with at most [maxTokens] new tokens within [timeoutMs]; null when not ready or too slow.
     *  Must be cancellable (a "stop" aborts it) and must never run on the main thread. */
    suspend fun complete(prompt: String, maxTokens: Int, timeoutMs: Long): String?
    /** One chat turn: [system] + [user] → the reply, decoding constrained to [jsonSchema] when the runtime can. */
    suspend fun chat(system: String, user: String, jsonSchema: String?, maxTokens: Int, timeoutMs: Long): String? =
        complete("$system\n\n$user", maxTokens, timeoutMs)
    /** A conversation for one task: [system] is read once, each [LlmSession.send] adds a user turn and keeps what was read.
     *  Null when the runtime can't hold one (the brain then sends single turns). */
    fun openSession(system: String, maxTokens: Int): LlmSession? = null
}

/** One task's conversation with the on-device model (the CONVERSATION prompt format, 1 Nov 2026). */
interface LlmSession {
    suspend fun send(user: String, jsonSchema: String?, timeoutMs: Long): String?
    /** Tokens held so far (prompt + replies); the brain starts a fresh session past its budget. */
    val tokens: Int
    fun close()
}

/** Where a runtime registers its loader (the app: LiteRT-LM, agent/llm/LiteRtLlm). [defaultPath]: the model's usual home. */
object LocalModelRuntime {
    /** Given the model file's absolute path, load it (or return null). */
    @Volatile var loader: ((modelPath: String) -> OnDeviceLlm?)? = null
    /** files/ondevice/llm/model.litertlm, set at start-up; used when `router.localModel.path` is blank. */
    @Volatile var defaultPath: String = ""
    private var cached: Pair<String, OnDeviceLlm>? = null

    /** One loaded model per path for the whole process: a router is made per task, a model load takes seconds. */
    @Synchronized fun get(path: String, load: (String) -> OnDeviceLlm?): OnDeviceLlm? {
        cached?.let { if (it.first == path) return it.second }
        return load(path)?.also { cached = path to it }
    }
}

class LocalModelBrain(
    private val llm: OnDeviceLlm,
    /** Tools the model may never call, whatever the step offers. */
    private val denied: Set<String> = DENIED,
    /** One-line hints from the user's own opt-in notes of past mistakes (learning/): "Last time, tapping 'Search' in Bolt did not work."
     *  They ride in this on-phone planner's "What I know" block ONLY — no cloud brain is ever given them. Empty unless the user turned
     *  "learn from your mistakes" on and a near-identical request failed recently. */
    private val hints: (String) -> List<String> = { goal -> com.tekyerema.learning.FailureLearning.hintsFor(goal) },
    /** The user's own memory for this goal ("my wife" is the contact Pearl, usual app, told facts; memory/PlannerMemory): at most 10 short
     *  lines in the same "What I know" block as [hints]. On-phone planner only — a cloud brain never gets them unless the user shares. */
    private val memory: (String) -> List<String> = { goal -> com.tekyerema.memory.PlannerMemory.forGoal(goal) },
    /** What to answer when the model gave none. Default: "can't decide" (CannotDo DEFER → the router falls back). The Space-backed
     *  planner ([com.tekyerema.agent.llm.SpaceLlm]) answers Fail instead, so the cloud chain classes it as a timeout or a quota. */
    private val noAnswer: (() -> AgentDecision)? = null,
    /** Per-step limits for a planner that isn't the on-phone model (the Space); null = the `router.localModel.*` config. */
    private val timeoutMs: Long? = null,
    private val maxTokens: Int? = null,
    private val maxContextTokens: Int? = null
) : AgentBrain {

    // The task's conversation: its tool list is fixed when it opens (the KV the runtime reuses depends on an unchanged
    // prefix), and only history lines newer than the last step go into the next turn.
    private var session: LlmSession? = null
    private var sessionTools: List<JSONObject> = emptyList()
    private var sessionGoal: String? = null
    private var sentHistory = 0
    private var turns = 0   // user turns sent in the open conversation (its token count may include the system prompt)

    override suspend fun decide(ctx: BrainContext): AgentDecision {
        // One conversation per TASK: a new goal, or a first step while an older conversation is still open (a task that
        // ended without a done, a stop), closes it — the brain object outlives tasks, the conversation must not.
        if (session != null && (sessionGoal != ctx.goal || (ctx.history.isEmpty() && turns > 0))) closeSession()
        val stepTools = (0 until ctx.openAiTools.length()).mapNotNull { ctx.openAiTools.optJSONObject(it)?.optJSONObject("function") }
            .filter { it.optString("name") !in denied }
        val registry = stepTools.associateBy { it.optString("name") } + REGISTRY_ALWAYS
        // A step offering a tool the open conversation doesn't list reopens it with both lists (the prefix changes anyway).
        val known = sessionTools.map { it.optString("name") }.toSet()
        val fresh = stepTools.filter { it.optString("name") !in known }
        if (session != null && fresh.isNotEmpty()) closeSession()
        val offered = if (session != null) sessionTools
            else com.tekyerema.agent.llm.PlannerPrompt.offered(sessionTools.filter { it.optString("name") != "done" && it.optString("name") !in com.tekyerema.agent.llm.PlannerPrompt.ALWAYS } + stepTools, registry)
        val names = offered.map { it.optString("name") }.toSet()
        val system = com.tekyerema.agent.llm.PlannerPrompt.system(offered)
        // Grammar-constrained decoding is OFF by default (Kaggle, 1 Nov 2026: the constraint forced a compact JSON shape the
        // model never saw in training; it then named the nearest tool with EMPTY arguments or looped — 5/75 on the
        // benchmark against 85.7 % unconstrained). ToolReply + the `names` check below validate the reply instead.
        val schema = if (AppConfig.bool("router.localModel.constrainReply", false)) com.tekyerema.agent.llm.PlannerPrompt.replySchema(offered) else null
        val maxTokens = this.maxTokens ?: AppConfig.int("router.localModel.maxTokens", 200)
        val timeout = timeoutMs ?: AppConfig.long("router.localModel.timeoutMs", 60_000L)
        val t0 = System.currentTimeMillis()
        var user: String
        val out: String?
        val s = session ?: llm.openSession(system, maxTokens)?.also { session = it; sessionTools = offered; sessionGoal = ctx.goal; turns = 0 }
        // What the user's own notes of past mistakes say about a request like this one: read with the conversation's FIRST turn
        // (or every turn of a single-turn run), never later ones. Empty almost always.
        val learned: List<String> = if (s == null || turns == 0) {
            com.tekyerema.memory.PlannerMemory.merge(
                try { memory(ctx.goal) } catch (_: Throwable) { emptyList() },
                try { hints(ctx.goal) } catch (_: Throwable) { emptyList() })
        } else emptyList()
        if (s != null) {
            user = when {
                // A conversation that opens mid-task (after a rollover or a new tool) starts from the full list of steps.
                turns == 0 && ctx.history.isNotEmpty() ->
                    com.tekyerema.agent.llm.PlannerPrompt.userTurn(ctx.goal, ctx.history, ctx.screen, ctx.conversation, learned)
                turns == 0 -> com.tekyerema.agent.llm.PlannerPrompt.firstTurn(ctx.goal, ctx.screen, ctx.conversation, learned)
                else -> com.tekyerema.agent.llm.PlannerPrompt.nextTurn(
                    ctx.history.drop(sentHistory).joinToString("\n").ifBlank { "done" }, ctx.screen)
            }
            out = s.send(user, schema, timeout)
            turns++
            sentHistory = ctx.history.size
            if (out == null || s.tokens > (maxContextTokens ?: AppConfig.int("router.localModel.maxContextTokens", 3_000))) closeSession()
        } else {
            user = com.tekyerema.agent.llm.PlannerPrompt.userTurn(ctx.goal, ctx.history, ctx.screen, ctx.conversation, learned)
            out = llm.chat(system, user, schema, maxTokens, timeout)
        }
        val ms = System.currentTimeMillis() - t0
        if (out == null) {
            log("[LLM] ${llm.id} no answer after $ms ms")
            return noAnswer?.invoke() ?: AgentDecision.CannotDo(LocalBrain.DEFER, "on-device model gave no answer")
        }
        val call = com.tekyerema.agent.llm.ToolReply.parse(out)
        log("[LLM] ${llm.id} ${ms} ms, turn ${user.length} chars, held ${session?.tokens ?: 0} tokens → ${call?.tool ?: "unparsed"}")
        if (call == null || call.tool !in names) return AgentDecision.Malformed(out.take(300))
        return when (call.tool) {
            "done" -> { closeSession(); AgentDecision.Done(call.args.optString("summary").ifBlank { "Done." }) }
            else -> AgentDecision.fromToolCall(call.tool, call.args, "local-model:${llm.id}")
        }
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        sessionGoal = null
    }

    private fun log(s: String) = try { android.util.Log.i("Tekyerema", s) } catch (_: Throwable) {}

    companion object {
        /** Single-step tools whose arguments are easy to check. Deliberately excludes money, USSD, emergency, installs. */
        val DEFAULT_TOOLS = setOf("open_app", "make_call", "send_sms", "set_alarm", "set_timer", "read_notifications",
            "read_screen", "set_volume", "open_setting", "play_music", "search_contacts")

        /** Never the planner model's: raw USSD (money and network menus go through the on-phone money flow only). */
        val DENIED = setOf("run_ussd")

        /** ask_user and cannot_do as the registry describes them, offered every step even when the step's subset left them out. */
        private val REGISTRY_ALWAYS: Map<String, JSONObject> by lazy {
            try {
                val arr = com.tekyerema.tools.ToolRegistry(com.tekyerema.tools.defaultTools()).openAiTools(listOf("ask_user", "cannot_do"))
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONObject("function") }.associateBy { it.optString("name") }
            } catch (_: Throwable) { emptyMap() }
        }

        /** Argument names each tool needs (one of them, for the call tools). */
        private val REQUIRED = mapOf(
            "open_app" to listOf("app"), "make_call" to listOf("contact", "number"), "send_sms" to listOf("message"),
            "set_alarm" to listOf("hour"), "set_timer" to listOf("seconds"), "set_volume" to listOf("level"),
            "open_setting" to listOf("target"), "search_contacts" to listOf("query")
        )

        /** How each tool is shown to the model: name and argument names only (short prompts are what a 270M model can afford). */
        private val SIGNATURES = linkedMapOf(
            "open_app" to "open_app(app)", "make_call" to "make_call(contact|number)",
            "send_sms" to "send_sms(contact|number,message)", "set_alarm" to "set_alarm(hour,minute)",
            "set_timer" to "set_timer(seconds)", "read_notifications" to "read_notifications()", "read_screen" to "read_screen()",
            "set_volume" to "set_volume(level 0-100)", "open_setting" to "open_setting(target)",
            "play_music" to "play_music(query)", "search_contacts" to "search_contacts(query)"
        )

        /** The short prompt (~150 tokens): the words and the allowed tools only — no screen, no history. Pure. */
        fun prompt(utterance: String, tools: Set<String> = DEFAULT_TOOLS): String =
            "Map the request to ONE phone tool. Reply with JSON only: {\"tool\":\"<name>\",\"args\":{...}} or {\"tool\":\"none\"}.\n" +
                "Tools: " + SIGNATURES.filterKeys { it in tools }.values.joinToString(" ") + "\n" +
                "Request: ${utterance.trim().take(200)}\nJSON:"

        /** A model reply → a checked CallTool, or null. Pure → unit-tested. */
        fun parse(reply: String, allowed: Set<String> = DEFAULT_TOOLS): AgentDecision.CallTool? {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = try { JSONObject(reply.substring(start, end + 1)) } catch (_: Throwable) { return null }
            val tool = o.optString("tool").trim()
            if (tool.isEmpty() || tool == "none" || tool !in allowed) return null
            val args = o.optJSONObject("args") ?: JSONObject()
            val need = REQUIRED[tool].orEmpty()
            if (need.isNotEmpty()) {
                val present = need.filter { k -> args.has(k) && args.opt(k)?.toString()?.isNotBlank() == true }
                // call tools need a contact OR a number; send_sms also a recipient besides its message
                val ok = when (tool) {
                    "make_call" -> present.isNotEmpty()
                    "send_sms" -> "message" in present && (args.optString("contact").isNotBlank() || args.optString("number").isNotBlank())
                    else -> present.size == need.size
                }
                if (!ok) return null
            }
            return AgentDecision.CallTool(tool, args, "local-model:${tool}")
        }

        /**
         * The model file to load on THIS phone, or null (device tiers, MASTER_STATUS #30: [ModelTierPicker]). An explicit
         * `router.localModel.path` is the owner's choice and only meets the old `minRamMb` guard; otherwise the files in the
         * model folder (beside [LocalModelRuntime.defaultPath]) are matched to the phone's RAM. Logs one `[TIER]` line.
         */
        internal fun modelPath(): String? {
            val total = com.tekyerema.core.DeviceProfile.totalRamMb
            val explicit = AppConfig.string("router.localModel.path", "")
            if (explicit.isNotBlank()) {
                if (total in 1 until AppConfig.long("router.localModel.minRamMb", 2_500L)) return null
                return explicit.takeIf { java.io.File(it).isFile }
            }
            val default = LocalModelRuntime.defaultPath
            if (default.isBlank()) return null
            val dir = java.io.File(default).parentFile ?: return null
            val installed = try { dir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() } catch (_: Throwable) { null } ?: emptySet()
            if (installed.isEmpty()) return null   // no model file: nothing changes, nothing logged
            val config = ModelTierPicker.configFromApp()
            // The default file's own name is the legacy file, whatever it is called.
            val free = com.tekyerema.core.DeviceProfile.availableRamMb()
            val pick = ModelTierPicker.pick(total, free, installed, config.copy(legacyFile = java.io.File(default).name))
            try { android.util.Log.i("Tekyerema", ModelTierPicker.logLine(total, free, installed, pick)) } catch (_: Throwable) {}
            return pick.file?.let { java.io.File(dir, it).absolutePath }
        }

        /**
         * The model brain for THIS phone, or null (the default — nothing changes): off unless enabled in config, a
         * runtime registered, the model file present, and at least `router.localModel.minRamMb` of RAM.
         */
        fun ifEnabled(): AgentBrain? {
            if (!AppConfig.bool("router.localModel.enabled", false)) return null
            val loader = LocalModelRuntime.loader ?: return null
            val path = modelPath() ?: return null
            return try { LocalModelRuntime.get(path, loader)?.let { LocalModelBrain(it) } } catch (_: Throwable) { null }
        }
    }
}
