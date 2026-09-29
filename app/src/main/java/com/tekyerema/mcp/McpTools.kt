package com.tekyerema.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * The MCP tool surface: TASK-LEVEL only (research note §1.3/§1.4). A computer can ask for a whole task, follow it, stop
 * it, and read the screen and the activity log. It can never tap, type, send or approve: those stay behind the
 * phone's own planner, policy gate, verification and — for anything consequential — the phone user's confirmation.
 *
 * Annotations are honest hints for the host (MCP: "clients MUST consider tool annotations to be untrusted"); nothing on
 * the phone depends on them. The list is static and in a fixed order (deterministic, cache-friendly).
 */
object McpTools {
    const val RUN_TASK = "run_task"
    const val GET_TASK = "get_task"
    const val STOP = "stop"
    const val DESCRIBE_SCREEN = "describe_screen"
    const val GET_ACTIVITY = "get_activity"

    const val GOAL_MAX = 500
    const val ACTIVITY_MAX = 100

    val names = listOf(RUN_TASK, GET_TASK, STOP, DESCRIBE_SCREEN, GET_ACTIVITY)

    private fun annotations(title: String, readOnly: Boolean, destructive: Boolean, idempotent: Boolean, openWorld: Boolean) =
        JSONObject().put("title", title).put("readOnlyHint", readOnly).put("destructiveHint", destructive)
            .put("idempotentHint", idempotent).put("openWorldHint", openWorld)

    /** An object schema. Inputs are closed (unknown arguments are an error); outputs are open (more fields may follow). */
    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList(), closed: Boolean = true) = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
        .apply { if (closed) put("additionalProperties", false) }

    private fun str(desc: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("type", "string").put("description", desc).apply(extra)
    private fun int(desc: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("type", "integer").put("description", desc).apply(extra)
    private fun any(desc: String) = JSONObject().put("description", desc)

    private val taskOutput = obj(
        "task_id" to any("The task's id (null when it was not started)."),
        "status" to str("running | waiting_for_phone_user | paused | done | cannot_do | failed | stopped | refused | finished_unknown"),
        "message" to str("What happened, in plain words."),
        required = listOf("status"), closed = false)

    /** The tools/list entries, in their fixed order. */
    fun definitions(): JSONArray = JSONArray()
        .put(JSONObject()
            .put("name", RUN_TASK)
            .put("title", "Run a task on the phone")
            .put("description", "Ask Tɛkyerɛma to do something on the user's Android phone, in plain words — exactly as if the " +
                "user had typed it. Returns a task_id straight away; follow it with get_task. The task is READ-ONLY unless " +
                "the phone's user has allowed remote actions, and every step that changes anything waits for the person " +
                "holding the phone to confirm it on the phone. Nothing here can approve it for them.")
            .put("inputSchema", obj("goal" to str("What to do, in plain words (1–$GOAL_MAX characters).") {
                put("minLength", 1); put("maxLength", GOAL_MAX) }, required = listOf("goal")))
            .put("outputSchema", taskOutput)
            .put("annotations", annotations("Run a task on the phone", readOnly = false, destructive = true, idempotent = false, openWorld = true)))
        .put(JSONObject()
            .put("name", GET_TASK)
            .put("title", "Task status")
            .put("description", "How a task started with run_task is going: status (running, waiting_for_phone_user, " +
                "paused, done, cannot_do, failed, stopped), what the phone user is being asked if it waits, the spoken " +
                "result, whether the outcome was verified on screen, and the task's steps.")
            .put("inputSchema", obj("task_id" to str("The id run_task returned."), required = listOf("task_id")))
            .put("outputSchema", taskOutput)
            .put("annotations", annotations("Task status", readOnly = true, destructive = false, idempotent = true, openWorld = false)))
        .put(JSONObject()
            .put("name", STOP)
            .put("title", "Stop the current task")
            .put("description", "Stop whatever task the phone is running (the same as the user's stop button). Always allowed; " +
                "it can only stop things.")
            .put("inputSchema", obj())
            .put("outputSchema", obj("stopped" to JSONObject().put("type", "boolean"), "message" to str("What happened."),
                required = listOf("stopped"), closed = false))
            .put("annotations", annotations("Stop the current task", readOnly = false, destructive = false, idempotent = true, openWorld = false)))
        .put(JSONObject()
            .put("name", DESCRIBE_SCREEN)
            .put("title", "Describe the phone's screen")
            .put("description", "The text and controls on the phone's screen right now, read from the accessibility tree " +
                "(no screenshot). Returns nothing, with the reason, while the user enters a PIN, password or code, on a " +
                "private screen, or for an app on the user's deny list.")
            .put("inputSchema", obj())
            .put("outputSchema", obj("shown" to JSONObject().put("type", "boolean"), "reason" to str("Why nothing is shown."),
                "screen" to any("The screen: app, title and elements."), required = listOf("shown"), closed = false))
            .put("annotations", annotations("Describe the phone's screen", readOnly = true, destructive = false, idempotent = true, openWorld = false)))
        .put(JSONObject()
            .put("name", GET_ACTIVITY)
            .put("title", "Recent activity")
            .put("description", "What Tɛkyerɛma has done recently (its audit log, in plain words, with who asked) and the " +
                "requests computers made over this connection.")
            .put("inputSchema", obj(
                "since" to int("Unix time in milliseconds; default: the last hour.") { put("minimum", 0) },
                "limit" to int("At most this many entries of each kind (1–$ACTIVITY_MAX, default 50).") {
                    put("minimum", 1); put("maximum", ACTIVITY_MAX) }))
            .put("outputSchema", obj("audit" to JSONObject().put("type", "array"), "remote_calls" to JSONObject().put("type", "array"),
                required = listOf("audit", "remote_calls"), closed = false))
            .put("annotations", annotations("Recent activity", readOnly = true, destructive = false, idempotent = true, openWorld = false)))
}
