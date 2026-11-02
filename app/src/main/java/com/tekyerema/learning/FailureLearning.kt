package com.tekyerema.learning

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.LogEntry
import com.tekyerema.audit.AuditLog
import com.tekyerema.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Learning from mistakes, opt-in (MASTER_STATUS #25) — the Android glue. Everything here is a no-op unless the user turned
 * [Settings.learnFromMistakes] on ("learn from your mistakes"). Three doors, all one short call from elsewhere:
 *  - [taskEnded]: called by [com.tekyerema.audit.AuditHooks.taskEnded], which both the engine's finish and the stop path
 *    already call — so a finished, failed, refused and stopped task all arrive here without touching the task loop;
 *  - [noteUtterance]: called by [FailureCommands] for each thing the user says — "no, that's wrong" or a rephrase right after a
 *    declined step is added to the task that just ended;
 *  - [hintsFor]: called by the ON-PHONE planner before it plans ([com.tekyerema.agent.LocalModelBrain]).
 * What is kept, and what never is: [FailureRedact], [FailureCore]. Stored only on the phone ([FailureStore]).
 */
object FailureLearning {
    private const val TAG = "Tekyerema"
    /** A "no, that's wrong" or a rephrase counts for the task that ended this long ago. */
    private const val WINDOW_MS = 120_000L
    private const val SCREEN_WAIT_MS = 1_500L
    /** A rephrase after a declined step needs at least this much of the old request's words (redacted). */
    private const val REPHRASE_SIMILARITY = 0.35

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private class Pending(var draft: FailureDraft, val endedAt: Long)
    private var pending: Pending? = null   // guarded by lock

    fun enabled(ctx: Context?): Boolean = try { ctx != null && Settings(ctx).learnFromMistakes } catch (_: Throwable) { false }

    /** Is [label] a person in the user's contacts? Then it is not kept (a chat row "Ama" is a person, "Search" is a button). */
    private fun personalCheck(ctx: Context): (String) -> Boolean = { label ->
        try { com.tekyerema.android.ContactResolver.resolve(ctx, label).isNotEmpty() } catch (_: Throwable) { false }
    }

    // ---------------------------------------------------------------- 1. a task ended

    /**
     * A task of the phone's user ended with [status] (an AgentStatus name). Keeps a note of it when it went wrong — see
     * [FailureCore.classify]. The audit trail and the engine's log are read here; the work itself runs off the engine's thread.
     */
    fun taskEnded(source: String, task: Long, status: String) {
        try {
            if (source != "agent") return   // not a trigger, a paired computer or a test
            val ctx = AgentEngine.appContext() ?: return
            if (!enabled(ctx)) return
            val entries = AuditLog.all().filter { it.task == task }
            if (entries.isEmpty()) return
            val st = AgentEngine.state.value
            val guardLines = st.log.filter { it.kind == LogEntry.Kind.INFO }.map { it.text }
            // A task that PAUSED (kept, resumable) has not ended: its goal is still on file as resumable.
            val paused = status == "CANNOT_DO" && pausedOnFile(st.goal)
            val seen = st.appSwitch?.takeIf { it.runSeq == st.runSeq }
            val now = System.currentTimeMillis()
            scope.launch {
                try { keep(ctx, status, paused, entries, guardLines, seen?.name, seen?.title, now) }
                catch (_: Throwable) { Log.w(TAG, "[LEARN] could not note the task") }
            }
        } catch (_: Throwable) {}
    }

    private fun pausedOnFile(goal: String?): Boolean = try {
        goal != null && com.tekyerema.agent.journal.TaskJournal.resumable()?.goal == goal
    } catch (_: Throwable) { false }

    private suspend fun keep(
        ctx: Context, status: String, paused: Boolean, entries: List<com.tekyerema.audit.AuditEntry>, guardLines: List<String>,
        app: String?, title: String?, now: Long
    ) {
        val personal = personalCheck(ctx)
        val draft = FailureCore.build("", status, paused, entries, guardLines, app, title, now, personal)
        synchronized(lock) { pending = draft?.let { Pending(it, SystemClock.elapsedRealtime()) } }
        if (draft == null) return
        // Where the phone is NOW (the app and screen title only): the screen the task ended on. If it can't be read, what the task
        // last saw is kept.
        val here = if (draft.failing || draft.declined) withTimeoutOrNull(SCREEN_WAIT_MS) { AgentEngine.screenNoteForLearning() } else null
        val id = draft.case.id
        synchronized(lock) {
            val p = pending ?: return
            if (p.draft.case.id != id) return
            val c = p.draft.case.copy(
                app = FailureRedact.label(here?.first, personal).ifEmpty { null } ?: p.draft.case.app,
                title = FailureRedact.label(here?.second, personal, maxLen = 40, maxWords = 6).ifEmpty { null } ?: p.draft.case.title
            )
            p.draft = p.draft.copy(case = c)
            if (p.draft.failing) FailureStore.put(ctx, c)
        }
    }

    // ---------------------------------------------------------------- 2. the user says it was wrong

    /**
     * Something the user just said. "No, that's wrong" / "that's not what I asked" about the task that ended a moment ago — or,
     * after a step they declined, the same request said another way — is added to that task's note (the words redacted). Never
     * stops the words from reaching the agent: the caller carries on as before.
     */
    fun noteUtterance(ctx: Context?, raw: String) {
        try {
            if (ctx == null || !enabled(ctx)) return
            val p = synchronized(lock) { pending } ?: return
            if (SystemClock.elapsedRealtime() - p.endedAt !in 0..WINDOW_MS) return
            if (FailureParse.isCorrection(raw)) { attach(ctx, FailureCore.correctionText(raw), rephrase = false); return }
            if (p.draft.declined) {
                val said = FailureRedact.text(raw)
                if (said != p.draft.case.goal && FailureCore.similarity(said, p.draft.case.goal) >= REPHRASE_SIMILARITY)
                    attach(ctx, FailureCore.correctionText(raw), rephrase = true)
            }
        } catch (_: Throwable) {}
    }

    private fun attach(ctx: Context, words: String?, rephrase: Boolean) {
        synchronized(lock) {
            val p = pending ?: return
            val old = p.draft.case
            val reason = when {
                rephrase -> FailureReason.DECLINED_REPHRASED
                p.draft.failing -> old.reason   // already a more specific mistake: keep why, add the words
                else -> FailureReason.USER_REJECTED
            }
            val c = old.copy(reason = reason, correction = words ?: old.correction)
            p.draft = FailureDraft(c, failing = true, declined = p.draft.declined)
            FailureStore.put(ctx, c)
        }
        Log.i(TAG, "[LEARN] the user corrected the last task (${if (rephrase) "rephrased" else "said it was wrong"})")
    }

    // ---------------------------------------------------------------- 3. before the on-phone planner plans

    /** The hints for a new request: empty unless learning is on. For the on-phone planner only. */
    fun hintsFor(goal: String): List<String> = hintsFor(AgentEngine.appContext(), goal)

    fun hintsFor(ctx: Context?, goal: String): List<String> {
        if (!enabled(ctx)) return emptyList()
        return try {
            FailureStore.ensureLoaded(ctx)
            FailureCore.hintsFor(FailureStore.all(), goal, System.currentTimeMillis()).also {
                if (it.isNotEmpty()) Log.i(TAG, "[LEARN] ${it.size} hint(s) for the on-phone planner")
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ---------------------------------------------------------------- forgetting

    /** "Forget my mistakes" / "delete everything": the notes, the file, the export copies. Returns how many notes there were. */
    fun forgetAll(ctx: Context?): Int {
        synchronized(lock) { pending = null }
        val n = try { FailureStore.clear(ctx) } catch (_: Throwable) { 0 }
        if (ctx != null) FailureExport.deleteFiles(ctx)
        return n
    }
}
