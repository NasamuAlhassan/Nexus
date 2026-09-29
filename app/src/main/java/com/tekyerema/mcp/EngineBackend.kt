package com.tekyerema.mcp

import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RemoteBackend] on the real engine. A remote task enters through [AgentEngine.submitGoal] — the same entry a typed
 * request reaches after the voice layer — tagged `remote:<client>`, so it runs the whole loop: policy (tiers, scopes,
 * target semantics, provenance, deny/guide-only apps) → confirmation on the phone → execute → verify → audit, plus the
 * remote rules in [RemoteGate]. The typed-request fast path is deliberately NOT used: it runs deterministic device
 * changes (Bluetooth, lock screen, clear history…) directly, outside the confirmation floor a remote task must meet.
 *
 * It never calls anything that approves, answers or resumes (see [RemoteBackend]).
 */
class EngineBackend(
    private val tracker: RemoteTaskTracker,
    private val callLog: RemoteAudit.CallLog,
    private val announce: (String) -> Unit
) : RemoteBackend {

    override fun runTask(goal: String, origin: String): RunTaskOutcome {
        val id = synchronized(AgentEngine) {   // the engine's own lock: the busy check and the start are one step
            if (AgentEngine.isBusy) return RunTaskOutcome.Refused(
                "The phone is busy with another task. Try again when it's done (get_task), or call stop.")
            AgentEngine.submitGoal(goal, origin = origin)
            val st = AgentEngine.state.value
            if (!AgentEngine.isBusy || st.origin != origin) return RunTaskOutcome.Refused("The phone didn't start the task.")
            tracker.begin(origin, st.goal ?: goal, st.runSeq)
        }
        // The person holding the phone hears that their computer is working on it (and what), before anything happens.
        announce("Your computer asked me to: ${goal.take(160)}")
        return RunTaskOutcome.Started(id)
    }

    override fun getTask(taskId: String): JSONObject? = tracker.view(taskId, AgentEngine.state.value)

    override fun stop(origin: String): StopOutcome {
        val st = AgentEngine.state.value
        if (!AgentEngine.isBusy) return StopOutcome(false, "Nothing is running.")
        tracker.noteStopRequested(st)
        AgentEngine.stop()
        if (st.origin != origin) announce("Your computer stopped the task.")
        return StopOutcome(true, "Stopped.")
    }

    override fun describeScreen(): ScreenReport {
        val secure = AgentEngine.state.value.status == AgentStatus.SECURE_INPUT || com.tekyerema.core.Privacy.secureStep
        val snap = if (secure) null else try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3_000L) { AgentEngine.currentController()?.takeIf { it.ready }?.buildSnapshot() }
            }
        } catch (_: Throwable) { null }
        return ScreenView.report(snap, secure, com.tekyerema.policy.PolicyConfig.denyApps())
    }

    override fun activity(sinceMs: Long, limit: Int): JSONObject {
        val audit = JSONArray()
        com.tekyerema.audit.AuditLog.since(sinceMs).takeLast(limit).forEach { e ->
            audit.put(JSONObject().put("t", e.t).put("source", e.source).put("kind", e.kind).put("text", e.text))
        }
        return JSONObject().put("audit", audit).put("remote_calls", callLog.since(sinceMs, limit))
    }

    override fun actionsAllowed(): Boolean = RemoteControl.actionsAllowed
}
