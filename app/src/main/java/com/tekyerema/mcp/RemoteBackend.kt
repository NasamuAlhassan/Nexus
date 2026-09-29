package com.tekyerema.mcp

import com.tekyerema.agent.AgentStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the MCP front door can ask of the phone. Deliberately NOTHING that can approve: there is no confirm,
 * approve, answer, revise or "secure step done" here, so no MCP method can say yes to a confirmation, answer the
 * task's question or end Secure Input Mode — those belong to the person holding the phone (research note §1.4 item 4;
 * InteropArchitectureTest checks this interface by reflection). And nothing that taps, types or sends: the only way in
 * is a whole task, through the same engine and gate as a typed request.
 */
interface RemoteBackend {
    /** Start [goal] as a task tagged [origin] ("remote:<client>"). Never waits for it to finish. */
    fun runTask(goal: String, origin: String): RunTaskOutcome

    /** A task this connection's computer started, as get_task reports it; null for an unknown id. */
    fun getTask(taskId: String): JSONObject?

    /** Stop whatever task is running (the kill switch). */
    fun stop(origin: String): StopOutcome

    /** The screen as text — or why it is not shown (a secure step, a private screen, a denied app). */
    fun describeScreen(): ScreenReport

    /** Recent audit entries and remote calls since [sinceMs] (newest last), at most [limit] of each. */
    fun activity(sinceMs: Long, limit: Int): JSONObject

    /** May a remote task act at all, or only read? (For the result text; enforcement is in the engine.) */
    fun actionsAllowed(): Boolean
}

sealed class RunTaskOutcome {
    data class Started(val taskId: String) : RunTaskOutcome()
    data class Refused(val reason: String) : RunTaskOutcome()
}

data class StopOutcome(val stopped: Boolean, val message: String)

/** describe_screen's answer: [text] for people, [structured] for programs; [blinded] = why nothing is shown. */
data class ScreenReport(val text: String, val structured: JSONObject, val blinded: String? = null)

/** How get_task names the engine's states for a remote caller (pure). */
object TaskStatus {
    const val RUNNING = "running"
    /** A confirmation, a question or a secure step waits for the person holding the phone — not for the computer. */
    const val WAITING = "waiting_for_phone_user"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val CANNOT_DO = "cannot_do"
    const val FAILED = "failed"
    const val STOPPED = "stopped"
    const val UNKNOWN = "finished_unknown"

    fun of(s: AgentStatus): String = when (s) {
        AgentStatus.IDLE, AgentStatus.THINKING, AgentStatus.ACTING -> RUNNING
        AgentStatus.WAITING_CONFIRMATION, AgentStatus.ASKING, AgentStatus.SECURE_INPUT -> WAITING
        AgentStatus.PAUSED -> PAUSED
        AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED -> DONE
        AgentStatus.CANNOT_DO -> CANNOT_DO
        AgentStatus.FAILED -> FAILED
        AgentStatus.STOPPED -> STOPPED
    }

    /** What the phone's user is being asked, for a WAITING task: "confirmation" | "question" | "secure_step". */
    fun waitingFor(s: AgentStatus): String? = when (s) {
        AgentStatus.WAITING_CONFIRMATION -> "confirmation"
        AgentStatus.ASKING -> "question"
        AgentStatus.SECURE_INPUT -> "secure_step"
        else -> null
    }

    fun terminal(s: AgentStatus): Boolean = s == AgentStatus.COMPLETED || s == AgentStatus.COMPLETED_UNVERIFIED ||
        s == AgentStatus.CANNOT_DO || s == AgentStatus.FAILED || s == AgentStatus.STOPPED

    fun steps(entries: List<Pair<String, String>>): JSONArray = JSONArray().also { a ->
        entries.forEach { (k, t) -> a.put(JSONObject().put("kind", k).put("text", t)) }
    }
}
