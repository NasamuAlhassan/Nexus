package com.tekyerema.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a computer did over the connection, in two records (pure, unit-tested):
 *
 *  1. [CallLog] — EVERY request (method, tool, outcome, task, status; never arguments or payloads), newest 500, returned
 *     by get_activity and kept for the session.
 *  2. The user's action audit ([com.tekyerema.audit.AuditLog]), in the plain past-tense words "what did you do?" reads
 *     aloud, with source "remote:<client>": every request that starts, stops or reveals something — a task started or
 *     turned down, the screen shown or withheld, the activity shown, a stop, each connection let in or refused — and a
 *     task's status told to the computer each time it CHANGES. Status polls that report the same thing again, tool
 *     lists and handshakes stay in the call log only: a client polling get_task every second would otherwise push the
 *     user's own history out of the audit's 300-entry cap within minutes.
 */
object RemoteAudit {
    const val DONE = "done"
    const val BLOCKED = "blocked"

    /** (kind, words) for the user's audit, or null when [call] belongs in the call log only.
     *  @param lastStatus the last status reported per task on this connection (updated here) */
    fun entry(call: RemoteCall, lastStatus: MutableMap<String, String>): Pair<String, String>? {
        if (call.method != "tools/call") return null
        return when (call.tool) {
            McpTools.RUN_TASK -> if (call.outcome == "ok") DONE to "started a task your computer asked for"
                else if (call.outcome == "refused" || call.outcome == "rate_limited") BLOCKED to "turned down a task your computer asked for"
                else null
            McpTools.DESCRIBE_SCREEN -> when (call.outcome) {
                "ok" -> DONE to "showed your computer what was on the screen"
                "blinded" -> BLOCKED to "kept a private screen from your computer"
                else -> null
            }
            McpTools.STOP -> if (call.outcome == "ok") DONE to "stopped a task because your computer asked" else null
            McpTools.GET_ACTIVITY -> if (call.outcome == "ok") DONE to "showed your computer my recent activity" else null
            McpTools.GET_TASK -> {
                val task = call.taskId ?: return null
                val status = call.status ?: return null
                if (call.outcome != "ok" || lastStatus[task] == status) null
                else { lastStatus[task] = status; DONE to "told your computer a task was ${status.replace('_', ' ')}" }
            }
            else -> null
        }
    }

    const val CONNECTED = "let your computer connect"
    const val REFUSED_APP = "refused a connection from an app on this phone"
    const val REFUSED_CODE = "refused a computer that gave the wrong code"
    const val DISCONNECTED_IDLE = "turned off the computer connection because it wasn't used"

    /** Every remote request of the session, newest last (bounded). */
    class CallLog(private val max: Int = 500) {
        private val calls = ArrayDeque<RemoteCall>()

        @Synchronized fun add(c: RemoteCall) { calls.addLast(c); while (calls.size > max) calls.removeFirst() }

        @Synchronized fun since(ms: Long, limit: Int): JSONArray = JSONArray().also { a ->
            calls.filter { it.t >= ms }.takeLast(limit).forEach { c ->
                a.put(JSONObject().put("t", c.t).put("client", c.client).put("method", c.method)
                    .apply { c.tool?.let { put("tool", it) }; c.taskId?.let { put("task_id", it) }; c.status?.let { put("status", it) } }
                    .put("outcome", c.outcome))
            }
        }

        @Synchronized fun size(): Int = calls.size
        @Synchronized fun clear() = calls.clear()
    }
}
