package com.tekyerema.agent.journal

import com.tekyerema.accessibility.UiSnapshot
import org.json.JSONObject

/**
 * One journaled run of the engine loop — the few calls AgentEngine makes (begin / resume, a checkpoint after each
 * executed step, pause, finish) so the loop itself only gains small hooks. Holds the run's [TaskRecord] id, the plain
 * words for what has verifiably been done so far ("opened WhatsApp", "tapped “Ama”") and the last verified screen.
 */
class TaskRun private constructor(record: TaskRecord, val resumedFrom: Checkpoint?) {
    val id: String = record.id
    val goal: String = record.goal
    /** The pause/interruption this run resumes, for its note (null for a fresh task). */
    private val resumedBecause: PauseReason? = record.pauseReason.takeIf { resumedFrom != null || record.resumes > 0 }
    private val done = ArrayList<String>(resumedFrom?.done.orEmpty())
    private var lastApp: String? = resumedFrom?.lastVerifiedApp
    private var lastTitle: String? = resumedFrom?.lastVerifiedTitle
    private var lastSig: String? = resumedFrom?.lastVerifiedSig
    private var stepsDone: Int = resumedFrom?.step ?: 0
    @Volatile var paused: PauseReason? = null; private set

    companion object {
        fun begin(goal: String, source: String): TaskRun = TaskRun(TaskJournal.begin(goal, source), null)

        /** The user said "carry on": the SAME record runs again, from its checkpoint. */
        fun resume(rec: TaskRecord): TaskRun {
            val cp = Checkpoint.fromJson(rec.checkpoint)
            val reason = rec.pauseReason
            val running = TaskJournal.resumeStarted(rec.id) ?: rec
            return TaskRun(running.copy(pauseReason = reason), cp ?: Checkpoint(step = rec.stepsDone, at = rec.updatedAt, history = emptyList()))
        }
    }

    /** A state change verified on screen: remembered in plain words for the resume offer ("So far I …"). */
    fun noteVerified(words: String?, screen: UiSnapshot?) {
        words?.trim()?.takeIf { it.isNotEmpty() }?.let { w -> if (done.lastOrNull() != w) done.add(w.take(60)) }
        if (screen != null && screen.appPackage != null) {
            lastApp = screen.appName ?: screen.appPackage
            lastTitle = screen.screenTitle
            lastSig = Checkpoint.sigHash(screen.signature())
        }
    }

    /** "opened WhatsApp, tapped “Ama” and typed some text" — the last few verified changes. */
    fun progressLine(): String {
        val last = done.takeLast(3)
        return when (last.size) {
            0 -> ""
            1 -> last[0]
            else -> last.dropLast(1).joinToString(", ") + " and " + last.last()
        }
    }

    /** Persist a checkpoint after an executed step (see [Checkpoint] for what is — and isn't — kept). */
    fun save(
        step: Int, history: List<String>, memory: JSONObject?,
        mutatingCount: Int, mutatingVerified: Int, partialCount: Int, lastMutatingVerified: Boolean?,
        failStreak: Int, usedRead: Boolean, usedTools: Collection<String>, discoveredTools: Collection<String>,
        untrustedRead: List<String>, injectionSource: String?
    ) {
        stepsDone = step
        val cp = Checkpoint(step, System.currentTimeMillis(), history.takeLast(Checkpoint.MAX_HISTORY), memory,
            lastApp, lastTitle, lastSig, mutatingCount, mutatingVerified, partialCount, lastMutatingVerified, failStreak,
            usedRead, usedTools.toList(), discoveredTools.toList(), untrustedRead.takeLast(3), injectionSource, done.toList())
        val rec = TaskJournal.get(id) ?: return
        TaskJournal.checkpoint(rec.copy(checkpoint = cp.toJson(), stepsDone = step, progress = progressLine()))
    }

    /** Workstream B: the explicit plan and the model pinned for this task, on this run's record (one record per run).
     *  Written only when something changed; the loop checkpoint ([save]) keeps them (it copies the stored record). */
    fun notePlan(plan: List<Subgoal>, model: String?) {
        val rec = TaskJournal.get(id) ?: return
        val m = model ?: rec.model
        if (rec.plan == plan && rec.model == m) return
        TaskJournal.checkpoint(rec.copy(plan = plan, model = m))
    }

    /** The cloud went away mid-task: PAUSED with its checkpoint. Returns the line the user hears. */
    fun pause(reason: PauseReason, resumeAfterMs: Long? = null, now: Long = System.currentTimeMillis()): String {
        paused = reason
        TaskJournal.get(id)?.let { TaskJournal.checkpoint(it.copy(progress = progressLine(), stepsDone = stepsDone)) }
        TaskJournal.pause(id, reason, resumeAfterMs, now)
        return PauseRules.pausedLine(reason, resumeAfterMs)
    }

    /** A terminal outcome. A paused run keeps its PAUSED state (it ended the loop, not the task). */
    fun finish(state: TaskState) {
        if (paused != null) return
        TaskJournal.finish(id, state)
    }

    /**
     * The note the planner reads first after "carry on": why it stopped, what is already done (in words and as ledger
     * facts), and the two rules — look at the screen before acting, and confirm anything consequential again.
     */
    fun resumeNote(effects: List<Effect> = EffectLedger.forTask(id)): String {
        val why = PauseRules.because(resumedBecause)
        val soFar = progressLine().ifBlank { "nothing verified yet" }
        val facts = EffectLines.facts(effects)
        return "Resumed after a pause ($why). Done so far: $soFar." +
            (if (facts.isEmpty()) "" else " " + facts.joinToString(" ")) +
            " Check the current screen before acting; anything consequential needs a fresh confirmation."
    }

    /** On the first look after resuming: is the phone where it was left? (The saved screen is never acted on.) */
    fun resumeScreenNote(now: UiSnapshot): String? {
        val sig = lastSig ?: return null
        return if (Checkpoint.sigHash(now.signature()) == sig) "The screen is the same as when the task stopped."
            else "The screen has changed since the task stopped (it was ${lastApp ?: "another app"}" +
                (lastTitle?.let { ", $it" } ?: "") + ") — look at what is in front now before going on."
    }
}
