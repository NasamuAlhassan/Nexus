package com.tekyerema.mcp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** One remote request, for the audit ([RemoteAudit]) and get_activity. No arguments or payloads are kept. */
data class RemoteCall(
    val t: Long, val client: String, val method: String, val tool: String?, val outcome: String,
    val taskId: String? = null, val status: String? = null
)

/**
 * One MCP connection's JSON-RPC 2.0 handling — pure, no Android, unit-tested line by line (McpSessionTest). The
 * transport ([McpServer]) feeds it one UTF-8 line at a time and writes back the lines it returns.
 *
 * DUAL-ERA (MCP 2026-07-28 "Versioning"): "A request carrying modern per-request `_meta` is served statelessly
 * according to this revision. An `initialize` request selects legacy semantics, scoped to the stdio process" — here,
 * scoped to the connection.
 *  - Modern: every request carries `_meta` with `io.modelcontextprotocol/protocolVersion` (+ `clientCapabilities`, and
 *    optionally `clientInfo`). Missing envelope fields → -32602; an unsupported version → -32022 with
 *    `data.{supported, requested}`. Served: `server/discover`, `tools/list`, `tools/call`, `subscriptions/listen`
 *    (acknowledged, nothing is ever published). Every result has `resultType: "complete"` and the server info in
 *    `_meta`. `initialize`, `ping` and other legacy methods carrying a modern envelope → -32601.
 *  - Legacy (2025-11-25 and earlier): `initialize` negotiates a version (the requested one if supported, else the latest
 *    legacy one), then `tools/list`, `tools/call` and `ping` are served; `notifications/initialized` is accepted.
 *    A `server/discover` probe never commits the connection, so an `initialize` fallback still works.
 * Errors: -32700 parse (no id), -32600 invalid request (batches included), -32601 method not found, -32602 invalid
 * params (unknown tool, malformed `tools/call`, missing envelope), -32603 internal. A tool's own failure (a refused
 * task, a bad goal, a rate limit) is a result with `isError: true`, so the calling model can read it and adjust.
 *
 * Safety: the tools are task-level ([McpTools]); [RemoteBackend] has no way to approve anything; every call is reported
 * to [onCall] for the audit; tool calls and run_task are rate-limited.
 */
class McpSession(
    private val backend: RemoteBackend,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onCall: (RemoteCall) -> Unit = {},
    private val serverVersion: String = "1",
    private val runTaskLimit: RateLimiter = RateLimiter(RUN_TASKS_PER_MIN, 60_000L, clock),
    private val toolCallLimit: RateLimiter = RateLimiter(TOOL_CALLS_PER_10S, 10_000L, clock)
) {
    /** The client's self-given name (sanitized); "computer" until it says. */
    var clientName: String = "computer"; private set
    /** The legacy protocol version `initialize` negotiated on this connection, or null (modern or not yet opened). */
    var legacyVersion: String? = null; private set
    var legacyInitialized: Boolean = false; private set

    fun origin(): String = RemoteGate.origin(clientName)

    /** Handle one line; returns the lines to write back (none for a notification). */
    fun handle(line: String): List<String> {
        val text = line.trim()
        if (text.isEmpty()) return emptyList()
        val parsed = try { JSONTokener(text).nextValue() } catch (e: JSONException) {
            return listOf(errorLine(null, PARSE_ERROR, "Parse error"))
        }
        if (parsed is JSONArray) return listOf(errorLine(null, INVALID_REQUEST, "Batches are not supported"))
        val msg = parsed as? JSONObject ?: return listOf(errorLine(null, INVALID_REQUEST, "Invalid Request"))
        val rawId = msg.opt("id")
        val id: Any? = rawId?.takeIf { it is String || it is Number }
        if (msg.optString("jsonrpc") != "2.0") return listOf(errorLine(id, INVALID_REQUEST, "jsonrpc must be \"2.0\""))
        val method = msg.opt("method") as? String
        if (method == null) {
            // A response to a request we never send: nothing to do. Anything else without a method is invalid.
            return if (!msg.has("method") && (msg.has("result") || msg.has("error"))) emptyList()
            else listOf(errorLine(id, INVALID_REQUEST, "Invalid Request: no method"))
        }
        val p = msg.opt("params")
        if (!msg.has("id")) { notification(method, p as? JSONObject); return emptyList() }
        if (id == null) return listOf(errorLine(null, INVALID_REQUEST, "id must be a string or a number"))
        if (p != null && p != JSONObject.NULL && p !is JSONObject) return listOf(errorLine(id, INVALID_PARAMS, "params must be an object"))
        val params = p as? JSONObject ?: JSONObject()
        return try {
            val meta = params.opt("_meta") as? JSONObject
            if (meta != null && meta.has(META_VERSION)) modern(id, method, params, meta) else legacy(id, method, params)
        } catch (t: Throwable) {
            record(method, null, "error:$INTERNAL_ERROR")
            listOf(errorLine(id, INTERNAL_ERROR, "Internal error"))
        }
    }

    // ---------------------------------------------------------------- notifications

    private fun notification(method: String, params: JSONObject?) {
        when (method) {
            "notifications/initialized" -> if (legacyVersion != null) legacyInitialized = true
            // Requests here finish at once (run_task returns immediately), so there is nothing in flight to cancel.
            "notifications/cancelled" -> {}
            else -> {}
        }
    }

    // ---------------------------------------------------------------- modern (2026-07-28)

    private fun modern(id: Any, method: String, params: JSONObject, meta: JSONObject): List<String> {
        val version = meta.opt(META_VERSION) as? String
            ?: return listOf(errorLine(id, INVALID_PARAMS, "_meta.$META_VERSION must be a string"))
        if (meta.opt(META_CAPS) !is JSONObject)
            return listOf(errorLine(id, INVALID_PARAMS, "_meta is missing $META_CAPS"))
        if (version !in MODERN_VERSIONS) {
            record(method, null, "error:$UNSUPPORTED_VERSION")
            return listOf(errorLine(id, UNSUPPORTED_VERSION, "Unsupported protocol version",
                JSONObject().put("supported", JSONArray(MODERN_VERSIONS)).put("requested", version)))
        }
        (meta.opt(META_CLIENT) as? JSONObject)?.optString("name")?.takeIf { it.isNotBlank() }?.let { noteClient(it) }
        return when (method) {
            "server/discover" -> { record(method, null, "ok"); listOf(resultLine(id, modernResult(discover()))) }
            "tools/list" -> toolsList(id, params, modern = true)
            "tools/call" -> toolsCall(id, params, modern = true)
            // Nothing is ever published (the tool list never changes): acknowledge and hold the request open.
            "subscriptions/listen" -> {
                record(method, null, "ok")
                listOf(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/subscriptions/acknowledged")
                    .put("params", JSONObject().put("_meta", JSONObject().put(META_SUBSCRIPTION, id)).put("notifications", JSONObject()))
                    .toString())
            }
            else -> { record(method, null, "error:$METHOD_NOT_FOUND"); listOf(errorLine(id, METHOD_NOT_FOUND, "Method not found: $method")) }
        }
    }

    private fun discover(): JSONObject = JSONObject()
        .put("supportedVersions", JSONArray(MODERN_VERSIONS))
        .put("capabilities", capabilities())
        .put("instructions", INSTRUCTIONS)
        .put("ttlMs", LIST_TTL_MS)
        .put("cacheScope", "private")

    private fun modernResult(r: JSONObject): JSONObject =
        r.put("resultType", "complete").put("_meta", JSONObject().put(META_SERVER_INFO, serverInfo()))

    // ---------------------------------------------------------------- legacy (initialize handshake)

    private fun legacy(id: Any, method: String, params: JSONObject): List<String> = when (method) {
        "initialize" -> {
            val requested = params.opt("protocolVersion") as? String
            val negotiated = if (requested != null && requested in LEGACY_VERSIONS) requested else LEGACY_VERSIONS.first()
            legacyVersion = negotiated
            (params.opt("clientInfo") as? JSONObject)?.optString("name")?.takeIf { it.isNotBlank() }?.let { noteClient(it) }
            record(method, null, "ok")
            listOf(resultLine(id, JSONObject()
                .put("protocolVersion", negotiated)
                .put("capabilities", capabilities())
                .put("serverInfo", serverInfo())
                .put("instructions", INSTRUCTIONS)))
        }
        "ping" -> listOf(resultLine(id, JSONObject()))
        "server/discover", "subscriptions/listen" -> {
            record(method, null, "error:$INVALID_PARAMS")
            listOf(errorLine(id, INVALID_PARAMS, "_meta with $META_VERSION and $META_CAPS is required"))
        }
        else -> if (legacyVersion == null) {
            record(method, null, "error:$INVALID_PARAMS")
            listOf(errorLine(id, INVALID_PARAMS, "No protocol version: send initialize first, or put $META_VERSION in _meta"))
        } else when (method) {
            "tools/list" -> toolsList(id, params, modern = false)
            "tools/call" -> toolsCall(id, params, modern = false)
            else -> { record(method, null, "error:$METHOD_NOT_FOUND"); listOf(errorLine(id, METHOD_NOT_FOUND, "Method not found: $method")) }
        }
    }

    // ---------------------------------------------------------------- tools

    private fun capabilities() = JSONObject().put("tools", JSONObject().put("listChanged", false))

    private fun serverInfo() = JSONObject().put("name", SERVER_NAME).put("title", SERVER_TITLE).put("version", serverVersion)

    private fun toolsList(id: Any, params: JSONObject, modern: Boolean): List<String> {
        if (params.has("cursor") && !params.isNull("cursor")) {
            record("tools/list", null, "error:$INVALID_PARAMS")
            return listOf(errorLine(id, INVALID_PARAMS, "Invalid cursor"))   // the list is never paginated
        }
        record("tools/list", null, "ok")
        val r = JSONObject().put("tools", McpTools.definitions())
        return listOf(resultLine(id, if (modern) modernResult(r.put("ttlMs", LIST_TTL_MS).put("cacheScope", "private")) else r))
    }

    private fun toolsCall(id: Any, params: JSONObject, modern: Boolean): List<String> {
        val name = params.opt("name") as? String
        if (name.isNullOrBlank()) return callError(id, null, "tools/call needs a tool name")
        val rawArgs = params.opt("arguments")
        val args = when (rawArgs) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> rawArgs
            else -> return callError(id, name, "arguments must be an object")
        }
        if (name !in McpTools.names) return callError(id, name, "Unknown tool: $name")
        val r: ToolReply = argumentProblem(name, args)?.let { ToolReply.error(it) }
            ?: if (!toolCallLimit.tryAcquire()) ToolReply.error("Too many requests from this computer — wait ${secs(toolCallLimit.retryAfterMs())} s.",
                    JSONObject().put("status", "rate_limited"), outcome = "rate_limited")
            else when (name) {
                McpTools.RUN_TASK -> runTask(args.getString("goal"))
                McpTools.GET_TASK -> getTask(args.getString("task_id"))
                McpTools.STOP -> backend.stop(origin()).let { s ->
                    ToolReply(s.message, JSONObject().put("stopped", s.stopped).put("message", s.message)) }
                McpTools.DESCRIBE_SCREEN -> backend.describeScreen().let { s ->
                    ToolReply(s.text, JSONObject().put("shown", s.blinded == null).put("screen", s.structured)
                        .apply { s.blinded?.let { put("reason", it) } }, outcome = if (s.blinded == null) "ok" else "blinded") }
                McpTools.GET_ACTIVITY -> {
                    val since = if (args.has("since")) args.getLong("since") else clock() - 3_600_000L
                    val limit = if (args.has("limit")) args.getInt("limit") else 50
                    val a = backend.activity(since, limit)
                    ToolReply("${a.optJSONArray("audit")?.length() ?: 0} audit entries and ${a.optJSONArray("remote_calls")?.length() ?: 0} " +
                        "remote requests since $since.", a)
                }
                else -> ToolReply.error("Unknown tool: $name")
            }
        record("tools/call", name, r.outcome, r.structured.opt("task_id") as? String, r.structured.opt("status") as? String)
        val result = JSONObject()
            .put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", r.text))
                .put(JSONObject().put("type", "text").put("text", r.structured.toString())))
            .put("structuredContent", r.structured)
            .put("isError", r.isError)
        return listOf(resultLine(id, if (modern) modernResult(result) else result))
    }

    private fun runTask(rawGoal: String): ToolReply {
        val goal = rawGoal.trim()
        if (goal.isEmpty()) return ToolReply.error("The goal is empty.", JSONObject().put("task_id", JSONObject.NULL).put("status", "refused"))
        if (goal.length > McpTools.GOAL_MAX) return ToolReply.error("The goal is longer than ${McpTools.GOAL_MAX} characters.",
            JSONObject().put("task_id", JSONObject.NULL).put("status", "refused"))
        if (!runTaskLimit.tryAcquire()) return ToolReply.error(
            "Too many tasks from this computer — wait ${secs(runTaskLimit.retryAfterMs())} s.",
            JSONObject().put("task_id", JSONObject.NULL).put("status", "rate_limited"), outcome = "rate_limited")
        return when (val o = backend.runTask(goal, origin())) {
            is RunTaskOutcome.Started -> {
                val readOnly = !backend.actionsAllowed()
                val msg = "Started. " + (if (readOnly) "This task can only read (remote actions are off on the phone). "
                    else "Every step that changes anything will wait for the phone's user to confirm it on the phone. ") +
                    "Follow it with get_task."
                ToolReply(msg, JSONObject().put("task_id", o.taskId).put("status", TaskStatus.RUNNING).put("read_only", readOnly).put("message", msg))
            }
            is RunTaskOutcome.Refused -> ToolReply.error(o.reason,
                JSONObject().put("task_id", JSONObject.NULL).put("status", "refused").put("message", o.reason), outcome = "refused")
        }
    }

    private fun getTask(taskId: String): ToolReply {
        val t = backend.getTask(taskId.trim())
            ?: return ToolReply.error("There is no task $taskId from this computer.",
                JSONObject().put("task_id", taskId).put("status", "unknown").put("message", "unknown task"))
        return ToolReply(t.optString("message").ifBlank { "Task ${t.optString("task_id")}: ${t.optString("status")}." }, t)
    }

    /** Arguments that don't fit the tool's input schema (unknown keys, missing or mistyped values), or null. */
    private fun argumentProblem(tool: String, args: JSONObject): String? {
        val allowed = when (tool) {
            McpTools.RUN_TASK -> setOf("goal"); McpTools.GET_TASK -> setOf("task_id")
            McpTools.GET_ACTIVITY -> setOf("since", "limit"); else -> emptySet()
        }
        args.keys().asSequence().firstOrNull { it !in allowed }?.let { return "unknown argument '$it' for $tool" }
        fun isInt(v: Any?) = v is Int || v is Long || (v is Number && v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite())
        return when (tool) {
            McpTools.RUN_TASK -> if (args.opt("goal") !is String) "run_task needs a string 'goal'" else null
            McpTools.GET_TASK -> if (args.opt("task_id") !is String) "get_task needs a string 'task_id'" else null
            McpTools.GET_ACTIVITY -> when {
                args.has("since") && (!isInt(args.opt("since")) || args.getLong("since") < 0) -> "'since' must be a non-negative integer (ms)"
                args.has("limit") && (!isInt(args.opt("limit")) || args.getInt("limit") !in 1..McpTools.ACTIVITY_MAX) ->
                    "'limit' must be an integer from 1 to ${McpTools.ACTIVITY_MAX}"
                else -> null
            }
            else -> null
        }
    }

    private fun callError(id: Any, tool: String?, message: String): List<String> {
        record("tools/call", tool, "error:$INVALID_PARAMS")
        return listOf(errorLine(id, INVALID_PARAMS, message))
    }

    private class ToolReply(val text: String, val structured: JSONObject, val isError: Boolean = false, val outcome: String = if (isError) "tool_error" else "ok") {
        companion object {
            fun error(text: String, structured: JSONObject = JSONObject().put("status", "error"), outcome: String = "tool_error") =
                ToolReply(text, structured.apply { if (!has("message")) put("message", text) }, isError = true, outcome = outcome)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun noteClient(name: String) { clientName = RemoteGate.clientName(name) }

    private fun record(method: String, tool: String?, outcome: String, taskId: String? = null, status: String? = null) {
        try { onCall(RemoteCall(clock(), clientName, method, tool, outcome, taskId, status)) } catch (_: Throwable) {}
    }

    private fun secs(ms: Long) = ((ms + 999) / 1000).coerceAtLeast(1)

    private fun resultLine(id: Any, result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val UNSUPPORTED_VERSION = -32022

        const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val META_CAPS = "io.modelcontextprotocol/clientCapabilities"
        const val META_CLIENT = "io.modelcontextprotocol/clientInfo"
        const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
        const val META_SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId"

        /** Served statelessly. Only modern revisions are advertised; legacy ones are negotiated by `initialize`. */
        val MODERN_VERSIONS = listOf("2026-07-28")
        /** `initialize` answers with the requested one of these, else the first (the latest legacy revision). */
        val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        const val SERVER_NAME = "tekyerema"
        const val SERVER_TITLE = "Nexus phone agent"
        const val LIST_TTL_MS = 3_600_000L
        const val RUN_TASKS_PER_MIN = 6
        const val TOOL_CALLS_PER_10S = 40

        val INSTRUCTIONS = "Nexus is a voice assistant that operates the user's Android phone for them. Give it whole " +
            "tasks in plain words with run_task and follow them with get_task. Remote tasks can only read unless the " +
            "phone's user has allowed remote actions; every step that changes anything waits for the person holding the " +
            "phone to confirm it on the phone (status waiting_for_phone_user) — nothing over this connection can approve it. " +
            "describe_screen shows nothing during PIN/password entry or for apps the user has denied. stop always works."

        fun errorLine(id: Any?, code: Int, message: String, data: JSONObject? = null): String = JSONObject()
            .put("jsonrpc", "2.0")
            .apply { if (id != null) put("id", id) }
            .put("error", JSONObject().put("code", code).put("message", message).apply { if (data != null) put("data", data) })
            .toString()
    }
}
