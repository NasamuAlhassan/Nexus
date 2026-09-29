package com.tekyerema.agent.journal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CONTRACT (agenticness plan, docs/agent/AGENTIC_ARCHITECTURE.md §State) — the persistent record of a task, so a task
 * interrupted by a quota limit, a dead link or the process being killed can be resumed instead of lost.
 *
 * Workstream A: the records live in ONE encrypted file (`filesDir/tasks/journal.enc`, [com.tekyerema.core.SecureStore],
 * fsync'd before the atomic rename), loaded once at app start ([load]). A task still RUNNING at a cold start was cut
 * off by process death: it becomes INTERRUPTED ([PauseReason.PROCESS_DEATH]). Before this, every piece of task state was
 * a local variable of `AgentEngine.runTask` and a killed task left no trace (audit §3, D5).
 *
 * Rules (research: agenticness_reliability_state §2.4):
 *  - Only attended GUI tasks are journaled (not scheduled/notification runs, not screen-free knowledge questions).
 *  - Resume happens ONLY on the user's word ("carry on"); the offer is made once per pause ([markOffered]); a task past
 *    its [TaskRecord.resumeDeadlineMs] is dropped, never offered.
 *  - A new journaled task supersedes an older unfinished one (the user moved on; the offer was already made).
 *  - If the Keystore can't be used, nothing is written in the clear — the journal lives in memory for this process.
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
    val updatedAt: Long = startedAt,
    // ---- Workstream A additions ----
    /** The "say carry on" offer for THIS pause was spoken or posted — it is made once, never repeated. */
    val offered: Boolean = false,
    /** Why the process last ended, when this task was found RUNNING at a cold start (ApplicationExitInfo). */
    val exitReason: String? = null,
    /** How many times this task has been resumed. */
    val resumes: Int = 0
)

object TaskJournal {
    private const val FILE_NAME = "journal.enc"
    private const val MAX_RECORDS = 8
    private val lock = Any()
    /** Newest last. */
    private val records = ArrayList<TaskRecord>()
    @Volatile private var file: File? = null
    @Volatile private var counter = 0

    /** How long a paused or interrupted task stays resumable after it stopped (or after its [TaskRecord.resumeAfterMs]). */
    fun resumeWindowMs(): Long =
        com.tekyerema.core.config.AppConfig.long("agent.resume.windowMs", 30 * 60_000L).coerceIn(60_000L, 24 * 3_600_000L)

    /**
     * Cold start: read the journal from [dir], turn any task still RUNNING into INTERRUPTED (the process died under it),
     * drop stale ones. Returns the tasks that were just marked INTERRUPTED (History records them). Call once per process.
     */
    fun load(dir: File, now: Long = System.currentTimeMillis(), exitReason: String? = null): List<TaskRecord> = synchronized(lock) {
        file = File(dir, FILE_NAME)
        records.clear()
        com.tekyerema.core.SecureStore.read(file!!)?.let { text -> records.addAll(parse(text)) }
        val interrupted = ArrayList<TaskRecord>()
        for (i in records.indices) {
            val r = records[i]
            if (r.state == TaskState.RUNNING) {
                val next = r.copy(state = TaskState.INTERRUPTED, pauseReason = PauseReason.PROCESS_DEATH,
                    exitReason = exitReason, offered = false, resumeDeadlineMs = now + resumeWindowMs(), updatedAt = now)
                records[i] = next
                interrupted.add(next)
            }
        }
        dropStaleLocked(now)
        persistLocked()
        interrupted
    }

    /** A new journaled task. Any older unfinished task is superseded (CANCELLED): the user moved on. */
    fun begin(goal: String, source: String, now: Long = System.currentTimeMillis()): TaskRecord = synchronized(lock) {
        for (i in records.indices) if (records[i].state.resumableState()) records[i] = records[i].copy(state = TaskState.CANCELLED, updatedAt = now)
        val rec = TaskRecord(id = "t$now-${counter++}", goal = goal, source = source, startedAt = now, updatedAt = now)
        records.add(rec)
        while (records.size > MAX_RECORDS) records.removeAt(0)
        persistLocked()
        rec
    }

    /** Persist the record after a step (the engine calls this after every executed step, see [TaskRun]). */
    fun checkpoint(rec: TaskRecord) = synchronized(lock) {
        val i = records.indexOfFirst { it.id == rec.id }
        val next = rec.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) records[i] = next else records.add(next)
        persistLocked()
    }

    fun pause(id: String, reason: PauseReason, resumeAfterMs: Long? = null) = pause(id, reason, resumeAfterMs, System.currentTimeMillis())

    fun pause(id: String, reason: PauseReason, resumeAfterMs: Long?, now: Long) = synchronized(lock) {
        val i = records.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        val from = maxOf(now, resumeAfterMs ?: now)
        records[i] = records[i].copy(state = TaskState.PAUSED, pauseReason = reason, resumeAfterMs = resumeAfterMs,
            resumeDeadlineMs = from + resumeWindowMs(), offered = false, updatedAt = now)
        persistLocked()
    }

    fun finish(id: String, state: TaskState) = synchronized(lock) {
        val i = records.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        records[i] = records[i].copy(state = state, updatedAt = System.currentTimeMillis())
        persistLocked()
    }

    /** The most recent PAUSED or INTERRUPTED task still inside its resume deadline, or null. A stale one is dropped. */
    fun resumable(now: Long = System.currentTimeMillis()): TaskRecord? = synchronized(lock) {
        if (dropStaleLocked(now)) persistLocked()
        records.lastOrNull { it.state.resumableState() }
    }

    fun get(id: String): TaskRecord? = synchronized(lock) { records.firstOrNull { it.id == id } }

    /** The newest record of any state (LeftOff, diagnostics). */
    fun latest(): TaskRecord? = synchronized(lock) { records.lastOrNull() }

    /** The resume offer for this pause was made (spoken or posted): never again for the same pause. */
    fun markOffered(id: String) = synchronized(lock) {
        val i = records.indexOfFirst { it.id == id }
        if (i >= 0 && !records[i].offered) { records[i] = records[i].copy(offered = true); persistLocked() }
    }

    /** The user said "carry on": the same record runs again. */
    fun resumeStarted(id: String, now: Long = System.currentTimeMillis()): TaskRecord? = synchronized(lock) {
        val i = records.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized null
        records[i] = records[i].copy(state = TaskState.RUNNING, pauseReason = null, resumeAfterMs = null, offered = false,
            resumes = records[i].resumes + 1, updatedAt = now)
        persistLocked()
        records[i]
    }

    /** "Clear my history": the journal holds the user's own requests. */
    fun clear() = synchronized(lock) {
        records.clear()
        try { file?.delete() } catch (_: Throwable) {}
    }

    /** TEST ONLY: forget the in-memory state as a new process would (the file on disk stays). */
    internal fun resetForTest() = synchronized(lock) { records.clear(); file = null }

    private fun TaskState.resumableState() = this == TaskState.PAUSED || this == TaskState.INTERRUPTED

    /** Stale = past its resume deadline. Returns true when something was dropped. */
    private fun dropStaleLocked(now: Long): Boolean {
        var changed = false
        for (i in records.indices) {
            val r = records[i]
            if (r.state.resumableState() && now >= r.resumeDeadlineMs) {
                records[i] = r.copy(state = TaskState.CANCELLED, updatedAt = now)
                changed = true
            }
        }
        return changed
    }

    private fun persistLocked() {
        val f = file ?: return   // not loaded (no context): memory only
        if (records.isEmpty()) { try { f.delete() } catch (_: Throwable) {}; return }
        val arr = JSONArray()
        records.forEach { arr.put(toJson(it)) }
        com.tekyerema.core.SecureStore.write(f, arr.toString())   // false = no Keystore: keep it in memory, never in the clear
    }

    internal fun toJson(r: TaskRecord): JSONObject = JSONObject()
        .put("id", r.id).put("goal", r.goal).put("source", r.source).put("startedAt", r.startedAt)
        .put("state", r.state.name).put("pauseReason", r.pauseReason?.name ?: JSONObject.NULL)
        .put("resumeAfterMs", r.resumeAfterMs ?: JSONObject.NULL).put("resumeDeadlineMs", r.resumeDeadlineMs)
        .put("plan", JSONArray().apply {
            r.plan.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("check", it.successCheck ?: JSONObject.NULL).put("done", it.done)) }
        })
        .put("stepsDone", r.stepsDone).put("progress", r.progress).put("model", r.model ?: JSONObject.NULL)
        .put("checkpoint", r.checkpoint ?: JSONObject.NULL).put("updatedAt", r.updatedAt)
        .put("offered", r.offered).put("exitReason", r.exitReason ?: JSONObject.NULL).put("resumes", r.resumes)

    internal fun parse(text: String): List<TaskRecord> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
    } catch (_: Throwable) { emptyList() }

    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    internal fun fromJson(o: JSONObject): TaskRecord? = try {
        val started = o.getLong("startedAt")
        TaskRecord(
            id = o.getString("id"), goal = o.getString("goal"), source = o.optString("source", "agent"), startedAt = started,
            state = runCatching { TaskState.valueOf(o.optString("state")) }.getOrDefault(TaskState.CANCELLED),
            pauseReason = o.optStringOrNull("pauseReason")?.let { runCatching { PauseReason.valueOf(it) }.getOrNull() },
            resumeAfterMs = if (o.has("resumeAfterMs") && !o.isNull("resumeAfterMs")) o.optLong("resumeAfterMs") else null,
            resumeDeadlineMs = o.optLong("resumeDeadlineMs", started + 60 * 60_000L),
            plan = o.optJSONArray("plan")?.let { a ->
                (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let { s ->
                    Subgoal(s.optInt("id"), s.optString("text"), s.optStringOrNull("check"), s.optBoolean("done")) } }
            }.orEmpty(),
            stepsDone = o.optInt("stepsDone"), progress = o.optString("progress"), model = o.optStringOrNull("model"),
            checkpoint = o.optStringOrNull("checkpoint"), updatedAt = o.optLong("updatedAt", started),
            offered = o.optBoolean("offered"), exitReason = o.optStringOrNull("exitReason"), resumes = o.optInt("resumes")
        )
    } catch (_: Throwable) { null }
}
