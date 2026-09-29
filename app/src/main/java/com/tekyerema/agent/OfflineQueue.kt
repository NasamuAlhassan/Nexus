package com.tekyerema.agent

import android.content.Context
import android.util.Log
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * N-09 — "I can't reach the internet right now. Should I find out when the connection is back?"
 *
 * A question that needs the internet, asked while it is out (or stalled), is kept — only when the user says yes — and
 * answered as soon as the link works again: "Earlier you asked: what's the cedi rate today. <answer>". The Android
 * offline-first pattern (keep the request locally, finish it when a network constraint is met), scoped for a blind
 * user's phone:
 *  - only self-contained knowledge QUESTIONS are kept ([queueable]) — they run on the direct-answer path, which never
 *    looks at or touches the screen, so answering later can only SPEAK; and they run unattended (D-08), which refuses
 *    every tool that sends, calls or pays;
 *  - never over the user: it waits while a task, a voice turn or a phone call is live ([com.tekyerema.agent.triggers.TriggerGate]);
 *  - small and short-lived: at most `router.offlineQueue.maxItems` (3), dropped after `maxAgeMs` (6 h) — an old
 *    answer nobody is waiting for is noise; memory only, so a restart forgets it.
 */
class OfflineQueueStore(
    private val maxItems: () -> Int = { AppConfig.int("router.offlineQueue.maxItems", 3) },
    private val maxAgeMs: () -> Long = { AppConfig.long("router.offlineQueue.maxAgeMs", 6 * 3_600_000L) },
    private val maxAttempts: () -> Int = { AppConfig.int("router.offlineQueue.maxAttempts", 3) },
    private val clock: () -> Long = System::currentTimeMillis
) {
    class Item(val goal: String, val at: Long) { var attempts = 0 }

    private val items = ArrayList<Item>()

    /** Keep [goal] for later; false when the queue is full (the user is told). A repeat of a kept question is a yes. */
    @Synchronized
    fun add(goal: String): Boolean {
        prune()
        val g = goal.trim()
        if (g.isEmpty()) return false
        if (items.any { it.goal.equals(g, ignoreCase = true) }) return true
        if (items.size >= maxItems()) return false
        items.add(Item(g, clock()))
        return true
    }

    @Synchronized fun next(): Item? { prune(); return items.firstOrNull() }
    @Synchronized fun contains(goal: String): Boolean { prune(); return items.any { it.goal.equals(goal.trim(), ignoreCase = true) } }
    @Synchronized fun remove(item: Item) { items.remove(item) }
    @Synchronized fun size(): Int { prune(); return items.size }
    @Synchronized fun clear() = items.clear()

    /** Count a failed try; the item is dropped after `maxAttempts`. Returns true while it stays queued. */
    @Synchronized
    fun failed(item: Item): Boolean {
        item.attempts++
        if (item.attempts >= maxAttempts()) { items.remove(item); return false }
        return true
    }

    private fun prune() { val now = clock(); items.removeAll { now - it.at > maxAgeMs() } }

    companion object {
        /** Worth offering to finish later: a self-contained knowledge question (see the class note). Pure. */
        fun queueable(goal: String): Boolean =
            com.tekyerema.core.config.FeatureFlags.isEnabled("N-09", true) && AppConfig.bool("router.offlineQueue.enabled", true) &&
                DirectAnswer.isKnowledgeQuestion(goal) && AnswerCache.key(goal) != null

        /** The user's reply to the offer: true = yes, false = no, null = unclear (treated as no — nothing is kept). */
        fun yesNo(answer: String?): Boolean? = when (com.tekyerema.voice.Confirmation.interpret(answer.orEmpty())) {
            com.tekyerema.voice.Confirmation.Answer.YES -> true
            com.tekyerema.voice.Confirmation.Answer.NO -> false
            else -> null
        }
    }
}

/** The process-wide queue and its drainer (runs a kept question when the internet is back). */
object OfflineQueue {
    val store = OfflineQueueStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var hooked = false
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)
    private val watching = java.util.concurrent.atomic.AtomicBoolean(false)

    private val TERMINAL = setOf(AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED, AgentStatus.CANNOT_DO,
        AgentStatus.FAILED, AgentStatus.STOPPED)
    private val NEEDS_USER = setOf(AgentStatus.WAITING_CONFIRMATION, AgentStatus.ASKING, AgentStatus.SECURE_INPUT)

    /** Keep [goal] for when the internet is back. Called by the router after the user said yes. */
    fun add(goal: String): Boolean {
        val ok = store.add(goal)
        if (ok) {
            Log.i("Tekyerema", "[OFFLINE_QUEUE] kept a question for later (${store.size()} waiting)")
            hook()
            watch()
        }
        return ok
    }

    /** Listen for "the internet is back" once; registered lazily so nothing runs for users who never queue. */
    private fun hook() {
        if (hooked) return
        synchronized(this) {
            if (hooked) return
            hooked = true
            com.tekyerema.core.LinkHealth.onRestored { scope.launch { drain() } }
        }
    }

    /** While something waits and the link is stalled, probe now and then so the answer comes without the user asking. */
    private fun watch() {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            try {
                val every = AppConfig.long("router.offlineQueue.probeEveryMs", 30_000L)
                while (store.size() > 0) {
                    delay(every)
                    if (com.tekyerema.core.NetworkMonitor.isOnline()) drain()
                    else com.tekyerema.core.LinkProbe.kick()   // a success closes the circuit → onRestored → drain
                }
            } finally { watching.set(false) }
        }
    }

    private suspend fun drain() {
        if (!draining.compareAndSet(false, true)) return
        try {
            val app = com.tekyerema.core.NetworkMonitor.context ?: return
            delay(AppConfig.long("router.offlineQueue.settleMs", 2_000L))   // let a returning link settle
            while (true) {
                val item = store.next() ?: return
                if (!com.tekyerema.core.NetworkMonitor.isOnline()) return
                if (com.tekyerema.core.LinkProbe.check() == false) return
                val accepted = withContext(Dispatchers.Main) {
                    if (com.tekyerema.agent.triggers.TriggerGate.userBusy(app)) false
                    else { AgentEngine.submitGoal(item.goal, unattended = true); AgentEngine.state.value.goal == item.goal }
                }
                if (!accepted) { delay(AppConfig.long("router.offlineQueue.busyRetryMs", 30_000L)); continue }
                Log.i("Tekyerema", "[OFFLINE_QUEUE] the internet is back — answering a kept question")
                val end = withTimeoutOrNull(AppConfig.long("router.offlineQueue.runTimeoutMs", 90_000L)) {
                    AgentEngine.state.first { it.goal == item.goal && (it.status in TERMINAL || it.status in NEEDS_USER) }
                }
                when {
                    end == null || end.status in NEEDS_USER -> {
                        // Unattended never approves anything; a question shouldn't need it — stop and keep quiet.
                        if (AgentEngine.state.value.goal == item.goal) withContext(Dispatchers.Main) { AgentEngine.stop() }
                        store.remove(item)
                    }
                    end.status == AgentStatus.COMPLETED || end.status == AgentStatus.COMPLETED_UNVERIFIED -> {
                        store.remove(item)
                        val result = end.log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text?.takeIf { it.isNotBlank() }
                        if (result != null) com.tekyerema.agent.triggers.TriggerGate.speak(app, AppConfig.phrase(
                            "router.offlineQueue.phrases.answer", "The internet is back. Earlier you asked: {goal}. {answer}",
                            "goal" to item.goal.trim().trimEnd('?', '.', '!'), "answer" to result))
                    }
                    end.status == AgentStatus.STOPPED -> {
                        // The user said "stop" to a kept question: don't start the next one straight after — drop them all.
                        Log.i("Tekyerema", "[OFFLINE_QUEUE] stopped by the user — dropping the ${store.size()} kept question(s)")
                        store.clear()
                        return
                    }
                    else -> if (!store.failed(item)) Log.i("Tekyerema", "[OFFLINE_QUEUE] gave up on a kept question after repeated failures")
                        else return   // CANNOT_DO/FAILED: likely the link again — wait for the next "back online"
                }
            }
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[OFFLINE_QUEUE] drain failed: ${t.message}")
        } finally {
            draining.set(false)
        }
    }
}
