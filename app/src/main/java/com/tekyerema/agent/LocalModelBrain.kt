package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig
import org.json.JSONObject

/**
 * C-04 — the seam for an OPTIONAL on-device model that turns an utterance the rules didn't match into ONE tool call,
 * offline. Design + interface + stub only: no model and no runtime are bundled (the APK is already ~335 MB), and the
 * router only consults it when `router.localModel.enabled` is true, a runtime has registered itself in
 * [LocalModelRuntime], the model file has been downloaded, and the phone has the RAM.
 *
 * Why this narrow job and not the planner (docs/agent/research/overnight_hybrid.md): on the A20e (Exynos 7884 — two
 * Cortex-A73 cores, ARMv8.0 with no dot-product instructions, 3 GB RAM) a 270M model is estimated at 6–12 tokens/s decode
 * and 2–5 s to first token on a ~150-token prompt; 0.5–1B models need 15–30 s or more. A full planning prompt (~3–5k tokens
 * with the screen) is out of reach, but a short "which tool, which arguments" prompt over a fixed tool list is not —
 * FunctionGemma-270M, fine-tuned on Mobile Actions, went from 58% to 85% on exactly this task.
 *
 * Safety is unchanged: the model can only name a tool from [allowedTools] (no money, USSD, emergency or install
 * tools), its arguments are checked here, and every call still goes through the engine's policy gates and
 * confirmations like a cloud decision. A reply that doesn't parse is a CannotDo — never a guess.
 */
interface OnDeviceLlm {
    /** Model id for logs, e.g. "functiongemma-270m-it-q8". */
    val id: String
    /** Complete [prompt] with at most [maxTokens] new tokens within [timeoutMs]; null when not ready or too slow.
     *  Must be cancellable (a "stop" aborts it) and must never run on the main thread. */
    suspend fun complete(prompt: String, maxTokens: Int, timeoutMs: Long): String?
}

/** Where a runtime module (llama.cpp / LiteRT-LM, shipped separately) registers its loader. Empty in this build. */
object LocalModelRuntime {
    /** Given the downloaded model file's absolute path, load it (or return null). */
    @Volatile var loader: ((modelPath: String) -> OnDeviceLlm?)? = null
}

class LocalModelBrain(
    private val llm: OnDeviceLlm,
    private val allowedTools: Set<String> = DEFAULT_TOOLS
) : AgentBrain {
    private var step = 0

    override suspend fun decide(ctx: BrainContext): AgentDecision {
        step++
        if (step > 1) {
            // Speak the tool's own outcome (its verified message, or the honest failure).
            val last = LocalBrain.lastStep(ctx.history)
            val msg = LocalBrain.resultMessage(last).ifEmpty { "Done." }
            return if (last.contains("→ fail")) AgentDecision.CannotDo(LocalBrain.ACTION_FAILED, msg) else AgentDecision.Done(msg)
        }
        val out = llm.complete(prompt(ctx.goal, allowedTools),
            AppConfig.int("router.localModel.maxTokens", 48), AppConfig.long("router.localModel.timeoutMs", 6_000L))
            ?: return AgentDecision.CannotDo(LocalBrain.DEFER, "on-device model gave no answer")
        return parse(out, allowedTools) ?: AgentDecision.CannotDo(LocalBrain.DEFER, "on-device model reply not usable")
    }

    companion object {
        /** Single-step tools whose arguments are easy to check. Deliberately excludes money, USSD, emergency, installs. */
        val DEFAULT_TOOLS = setOf("open_app", "make_call", "send_sms", "set_alarm", "set_timer", "read_notifications",
            "read_screen", "set_volume", "open_setting", "play_music", "search_contacts")

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
         * The model brain for THIS phone, or null (the default — nothing changes): off unless enabled in config, a
         * runtime registered, the model file present, and at least `router.localModel.minRamMb` of RAM.
         */
        fun ifEnabled(): AgentBrain? {
            if (!AppConfig.bool("router.localModel.enabled", false)) return null
            val loader = LocalModelRuntime.loader ?: return null
            if (com.tekyerema.core.DeviceProfile.totalRamMb in 1 until AppConfig.long("router.localModel.minRamMb", 2_500L)) return null
            val path = AppConfig.string("router.localModel.path", "")
            if (path.isBlank() || !java.io.File(path).isFile) return null
            return try { loader(path)?.let { LocalModelBrain(it) } } catch (_: Throwable) { null }
        }
    }
}
