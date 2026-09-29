package com.tekyerema.mcp

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Follows the tasks computers started, so get_task can report them — live while they run, and after they end (the
 * engine keeps one run's state at a time). Pure: it is fed the engine's states ([onState]) and asked for views ([view]);
 * unit-tested with plain [AgentUiState]s.
 *
 * A task is tied to the engine run by [AgentUiState.runSeq] (set when the goal is submitted), not by its words, so two
 * runs of the same goal are never confused. The engine's log for the run is copied when it ends.
 *
 * @param redact applied to every step's text before it leaves the phone — [com.tekyerema.core.TraceRedact] in release
 *   builds (the same redaction as trace files: no message bodies, numbers or typed words), identity in debug builds
 * @param maxTasks how many finished tasks are remembered (oldest dropped)
 */
class RemoteTaskTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val redact: (kind: String, text: String) -> String = { _, t -> t },
    private val maxTasks: Int = 20
) {
    private class Rec(val id: String, val origin: String, val goal: String, val runSeq: Long, val startedAt: Long) {
        var final: AgentUiState? = null
        var finishedAt: Long? = null
        var lost = false
        var stopRequested = false
    }

    private val recs = LinkedHashMap<String, Rec>()
    private var counter = 0L

    /** A task was just submitted as engine run [runSeq]; returns its id. */
    @Synchronized fun begin(origin: String, goal: String, runSeq: Long): String {
        val id = "t${clock()}-${++counter}"
        recs[id] = Rec(id, origin, goal, runSeq, clock())
        while (recs.size > maxTasks) recs.remove(recs.keys.first())
        return id
    }

    /** The computer asked to stop: a STOPPED ending is then its own doing, not the phone user's kill switch. */
    @Synchronized fun noteStopRequested(st: AgentUiState) { recs.values.firstOrNull { it.runSeq == st.runSeq && it.final == null }?.stopRequested = true }

    /**
     * Feed every engine state. Returns the finished remote task when [st] ends one (for the caller's side effects), else
     * null. A run that was overtaken by a newer one before its end was seen is marked [TaskStatus.UNKNOWN].
     */
    @Synchronized fun onState(st: AgentUiState): Finished? {
        var out: Finished? = null
        for (r in recs.values) {
            if (r.final != null || r.lost) continue
            if (st.runSeq == r.runSeq && TaskStatus.terminal(st.status)) {
                r.final = st; r.finishedAt = clock()
                out = Finished(r.id, r.origin, st.status, killedOnPhone = st.status == AgentStatus.STOPPED && !r.stopRequested &&
                    st.log.any { it.kind == LogEntry.Kind.INFO && it.text.startsWith("Stopped by user") })
            } else if (st.runSeq > r.runSeq) { r.lost = true; r.finishedAt = clock() }
        }
        return out
    }

    data class Finished(val taskId: String, val origin: String, val status: AgentStatus, val killedOnPhone: Boolean)

    /** get_task's answer for [id], from the final state if it ended, else from [live]; null for an unknown id. */
    @Synchronized fun view(id: String, live: AgentUiState): JSONObject? {
        val r = recs[id] ?: return null
        if (r.final == null && !r.lost) onState(live)   // catch an end the state collector hasn't delivered yet
        val st = r.final ?: live.takeIf { it.runSeq == r.runSeq && !r.lost }
        val out = JSONObject().put("task_id", r.id).put("goal", r.goal).put("origin", r.origin).put("started_at", r.startedAt)
        r.finishedAt?.let { out.put("finished_at", it) }
        if (st == null) {
            return out.put("status", TaskStatus.UNKNOWN).put("message", "The task ended, but its result was not recorded.")
                .put("steps", JSONArray())
        }
        val status = TaskStatus.of(st.status)
        out.put("status", status).put("agent_status", st.status.name)
        TaskStatus.waitingFor(st.status)?.let { w ->
            out.put("waiting_for", w)
            // What the phone's user is being asked. A secure step says nothing about the screen.
            when (w) {
                "confirmation" -> st.pendingConfirmation?.let { out.put("prompt", redact("CONFIRM", it)) }
                "question" -> st.pendingQuestion?.let { out.put("prompt", redact("CONFIRM", it)) }
            }
        }
        val result = st.log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text
        if (TaskStatus.terminal(st.status)) {
            out.put("verified", st.status == AgentStatus.COMPLETED)
            out.put("grade", when (st.status) {
                AgentStatus.COMPLETED -> "full"; AgentStatus.COMPLETED_UNVERIFIED -> "partial"
                AgentStatus.STOPPED -> "stopped"; else -> "failed"
            })
            result?.let { out.put("result", redact("RESULT", it)) }
        }
        out.put("message", when (status) {
            TaskStatus.WAITING -> "Waiting for the person holding the phone" + (TaskStatus.waitingFor(st.status)?.let { " (${it.replace('_', ' ')})" } ?: "") + "."
            TaskStatus.RUNNING -> "Working on it."
            TaskStatus.PAUSED -> "Paused on the phone."
            else -> result?.let { redact("RESULT", it) } ?: status
        })
        out.put("steps", JSONArray().also { a ->
            st.log.takeLast(MAX_STEPS).forEach { e ->
                a.put(JSONObject().put("t", e.timeMs).put("kind", e.kind.name).put("text", redact(e.kind.name, e.text).take(600)))
            }
        })
        return out
    }

    @Synchronized fun ids(): List<String> = recs.keys.toList()

    companion object { const val MAX_STEPS = 200 }
}
