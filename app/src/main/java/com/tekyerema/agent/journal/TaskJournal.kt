package com.tekyerema.agent.journal

/**
 * CONTRACT (agenticness plan, docs/agent/AGENTIC_ARCHITECTURE.md §State) — the persistent record of a task, so a task
 * interrupted by a quota limit, a dead link or the process being killed can be resumed instead of lost.
 *
 * Stub: in memory only. Workstream A replaces the storage (encrypted, fsync'd, survives process death) and wires it
 * into the agent loop; the API below is what the other workstreams code against.
 */
enum class TaskState { RUNNING, PAUSED, INTERRUPTED, COMPLETED, FAILED, CANCELLED }

/** Why a task stopped without finishing. Only these are resumable. */
enum class PauseReason { QUOTA, NETWORK, NO_CAPABLE_MODEL, BUDGET, USER, PROCESS_DEATH }

/** One subgoal of an explicit plan (Workstream B fills plans in; A persists them). */
data class Subgoal(val id: Int, val text: String, val successCheck: String? = null, val done: Boolean = false)

data class TaskRecord(
    val id: String,
    val goal: String,
    val source: String,
    val startedAt: Long,
    val state: TaskState = TaskState.RUNNING,
    val pauseReason: PauseReason? = null,
    /** Earliest time a resume makes sense (e.g. when the quota window refills); null = any time. */
    val resumeAfterMs: Long? = null,
    /** After this, the task is stale and is not offered for resume. */
    val resumeDeadlineMs: Long = startedAt + 60 * 60_000L,
    val plan: List<Subgoal> = emptyList(),
    val stepsDone: Int = 0,
    /** A one-line, speakable summary of progress ("Found the document and opened WhatsApp"). */
    val progress: String = "",
    /** Model pinned for this task (Workstream B). */
    val model: String? = null,
    /** Opaque, encrypted checkpoint for the loop (history, TaskMemory fields, last verified screen signature…). */
    val checkpoint: String? = null,
    val updatedAt: Long = startedAt
)

object TaskJournal {
    @Volatile private var current: TaskRecord? = null

    fun begin(goal: String, source: String, now: Long = System.currentTimeMillis()): TaskRecord =
        TaskRecord(id = "t$now", goal = goal, source = source, startedAt = now).also { current = it }

    /** Persist the record after a verified step. */
    fun checkpoint(rec: TaskRecord) { current = rec.copy(updatedAt = System.currentTimeMillis()) }

    fun pause(id: String, reason: PauseReason, resumeAfterMs: Long? = null) {
        current?.takeIf { it.id == id }?.let { current = it.copy(state = TaskState.PAUSED, pauseReason = reason, resumeAfterMs = resumeAfterMs) }
    }

    fun finish(id: String, state: TaskState) {
        current?.takeIf { it.id == id }?.let { current = it.copy(state = state) }
    }

    /** The most recent PAUSED or INTERRUPTED task still inside its resume deadline, or null. */
    fun resumable(now: Long = System.currentTimeMillis()): TaskRecord? =
        current?.takeIf { (it.state == TaskState.PAUSED || it.state == TaskState.INTERRUPTED) && now < it.resumeDeadlineMs }

    fun get(id: String): TaskRecord? = current?.takeIf { it.id == id }
}
