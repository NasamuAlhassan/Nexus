package com.tekyerema.agent.journal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * App start (AgentEngine.init, once per process): load the task journal and the effect ledger from app-private storage,
 * give History an honest row for a task the process died under ("Interrupted"), plug the Android world checks into the
 * ledger, and offer a network-paused task once when the internet comes back. Nothing here resumes anything.
 */
object JournalBoot {
    @Volatile private var started = false

    /** The world checks the engine's [EffectGate] uses (screen-only until [start] wires the Android providers). */
    @Volatile var world: WorldCheck = WorldCheck.SCREEN_ONLY

    fun dir(ctx: Context): File = File(ctx.filesDir, "tasks")

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext ?: ctx
        try {
            val exit = com.tekyerema.core.Health.lastExitReason(app)
            val unknown = EffectLedger.load(dir(app))
            val interrupted = TaskJournal.load(dir(app), exitReason = exit)
            world = AndroidWorldCheck(app)
            if (interrupted.isNotEmpty() || unknown > 0)
                Log.i("Tekyerema", "[JOURNAL] cold start: ${interrupted.size} interrupted task(s), $unknown effect(s) of unknown outcome (last exit: $exit)")
            // History: a task killed mid-way used to leave no row at all (audit §3), so "where did I leave off" named the one before.
            if (interrupted.isNotEmpty()) {
                com.tekyerema.history.HistoryStore.init(app)
                interrupted.filter { it.source != "test" }
                    .forEach { com.tekyerema.history.HistoryStore.record(it.goal, com.tekyerema.history.Outcome.INTERRUPTED, it.updatedAt) }
            }
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[JOURNAL] load failed: ${t.message}")
        }
        // The internet came back: a task paused for THAT is offered once — spoken if nobody is busy, and left in the shade.
        com.tekyerema.core.LinkHealth.onRestored {
            try {
                if (com.tekyerema.agent.triggers.TriggerGate.userBusy(app)) return@onRestored
                ResumeOffer.take(networkOnly = true)?.let { (_, line) -> com.tekyerema.agent.triggers.TriggerGate.speak(app, line) }
            } catch (_: Throwable) {}
        }
    }
}
