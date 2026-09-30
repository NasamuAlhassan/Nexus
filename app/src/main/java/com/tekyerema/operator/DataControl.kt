package com.tekyerema.operator

import android.content.Context
import java.io.File

/**
 * WS-C — "what do you keep about me?", and "delete everything". Every store that holds something about the user is
 * listed here with how much it holds and how long it keeps it ([items]); [deleteEverything] empties all of them. Settings,
 * API keys, the emergency contact and today's usage counters stay (they are choices, not a record of the user) — the
 * spoken confirmation says so.
 */
object DataControl {
    /** One store: what it is, how many items, how long they're kept (null = until you delete them). */
    data class Item(val label: String, val count: Int, val keptFor: String?, val unit: String = "")

    fun items(ctx: Context): List<Item> {
        val app = ctx.applicationContext
        val days = com.tekyerema.core.Privacy.historyDays()
        fun safe(block: () -> Int): Int = try { block() } catch (_: Throwable) { 0 }
        com.tekyerema.agent.Routines.ensureLoaded(app)
        com.tekyerema.android.Relations.ensureLoaded(app)
        return listOf(
            Item("Our recent conversation", safe { com.tekyerema.agent.AgentEngine.conversationTurns() }, "30 minutes of quiet", "lines"),
            Item("History of tasks", safe { com.tekyerema.history.HistoryStore.entries.value.size }, "$days days", "tasks"),
            Item("Activity log", safe { com.tekyerema.audit.AuditLog.count() }, "$days days", "entries"),
            Item("Recent messages and notifications", safe { com.tekyerema.notifications.MessageIndex.size() }, "a week", "items"),
            Item("Who your family and friends are", safe { com.tekyerema.android.Relations.all().size }, null, "people"),
            Item("Your routines", safe { com.tekyerema.agent.Routines.names().size }, null, "routines"),
            Item("Your schedules", safe { com.tekyerema.agent.triggers.ScheduledTasks.list(app).count { !it.id.startsWith("cfg:") } }, null, "schedules"),
            Item("Message alerts", safe { com.tekyerema.agent.triggers.NotificationTriggers.userRules(app).size }, null, "alerts"),
            Item("Words the Twi keyboard learned", safe { com.tekyerema.ime.LearnedWordStore.count(app) }, null, "words"),
            // Money safety round 2: scrambled (salted hashes), never the numbers — only "have I paid this number before?".
            Item("Numbers you've sent Mobile Money to, kept scrambled", safe { com.tekyerema.money.KnownRecipients.count() }, null, "numbers"),
            Item("Saved tasks to improve Tɛkyerɛma", safe { com.tekyerema.core.telemetry.Trajectories.count(app) },
                "${com.tekyerema.core.Privacy.trajectoryDays()} days", "tasks"),
            Item("Step-by-step traces", safe { File(app.filesDir, "traces").listFiles()?.size ?: 0 },
                "${com.tekyerema.core.Privacy.traceDays()} days", "files"),
            Item("Usage statistics (no words)", safe { com.tekyerema.core.telemetry.Telemetry.records().size },
                "${com.tekyerema.core.Privacy.telemetryDays()} days", "records")
        )
    }

    /** One sentence per non-empty store, then what leaves the phone (pure). */
    fun spoken(items: List<Item>, cloudOn: Boolean, providers: List<String>): String {
        val kept = items.filter { it.count > 0 }.map { i ->
            val what = "${i.label.replaceFirstChar { it.lowercase() }} (${i.count}${if (i.unit.isNotEmpty()) " ${i.unit}" else ""}" +
                (i.keptFor?.let { ", kept for $it" } ?: "") + ")"
            what
        }
        val head = if (kept.isEmpty()) "I'm not keeping anything about you on this phone right now."
            else "On this phone, encrypted, I keep: " + joinAnd(kept.take(8)) + "."
        val out = when {
            !cloudOn -> "The cloud is off, so nothing you say leaves the phone."
            providers.isEmpty() -> "No online AI is switched on, so no AI service gets your requests."
            else -> "When I need the online AI, what you ask and what's on the screen go to " +
                joinAnd(providers.map { Disclosure.name(it) }) + " to work out each step."
        }
        return "$head $out Say “export my history” for a copy, or “delete everything” to erase it."
    }

    /** Erase everything personal (see the class comment for what stays). Returns what it could not clear, if anything. */
    fun deleteEverything(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val failed = ArrayList<String>()
        fun step(name: String, block: () -> Unit) { try { block() } catch (_: Throwable) { failed.add(name) } }
        step("conversation") { com.tekyerema.agent.AgentEngine.resetConversation() }
        step("references") { com.tekyerema.agent.References.clear() }
        step("screen memory") { com.tekyerema.agent.ScreenMemory.clear(); com.tekyerema.agent.ScreenReadCursor.clear() }
        step("answers") { com.tekyerema.agent.AnswerCache.shared.clear(); com.tekyerema.agent.OfflineQueue.store.clear() }
        step("messages") {
            com.tekyerema.notifications.MessageIndex.clear()
            // The reading in flight too — "continue" / "read them" must find nothing after "delete everything".
            com.tekyerema.notifications.MessageCursor.clear()
            com.tekyerema.notifications.PrivateReading.clear()
        }
        step("learned wordings") { com.tekyerema.agent.IntentMatcher.get(app)?.forgetPersonal() }
        step("relations") { com.tekyerema.android.Relations.ensureLoaded(app); com.tekyerema.android.Relations.clear() }
        step("routines") {
            com.tekyerema.agent.Routines.ensureLoaded(app)
            com.tekyerema.agent.Routines.names().forEach { com.tekyerema.agent.Routines.remove(it) }
        }
        step("schedules") {
            com.tekyerema.agent.triggers.ScheduledTasks.list(app).filter { !it.id.startsWith("cfg:") }
                .forEach { com.tekyerema.agent.triggers.ScheduledTasks.remove(app, it.id) }
        }
        step("alerts") {
            com.tekyerema.agent.triggers.NotificationTriggers.userRules(app)
                .forEach { com.tekyerema.agent.triggers.NotificationTriggers.removeRule(app, it.id) }
        }
        step("keyboard") { com.tekyerema.ime.LearnedWordStore.clear(app) }
        step("paid numbers") { com.tekyerema.money.KnownRecipients.clear() }   // round 2: every number is "new" again (the safe way)
        step("history") { com.tekyerema.history.HistoryStore.clear() }
        step("undo") { com.tekyerema.audit.UndoLog.clear() }
        step("traces") { com.tekyerema.agent.AgentEngine.clearTraces() }
        step("telemetry") { com.tekyerema.core.telemetry.Telemetry.clear(); com.tekyerema.core.telemetry.FieldLog.clear(app) }
        step("saved tasks") { com.tekyerema.core.telemetry.Trajectories.clear(app) }
        step("exports") { File(app.cacheDir, com.tekyerema.audit.AuditExport.DIR).deleteRecursively() }
        // Last, so the new log starts with one "cleared" entry (no content) — a missing past is visible, not silent.
        step("activity log") { com.tekyerema.audit.AuditLog.clear() }
        return failed
    }

    const val DELETE_PROMPT = "This deletes everything I keep about you on this phone: our conversations, your history, the " +
        "activity log, the messages I was keeping, who your family are, your routines, schedules and message alerts, and " +
        "the words the keyboard learned. Your settings, keys and emergency contact stay. It can't be undone. " +
        "Say “yes, delete everything” to go ahead, or “no” to keep it."

    const val DELETED = "Done — I've deleted everything I was keeping about you. Your settings, keys and emergency contact are unchanged."

    internal fun joinAnd(items: List<String>): String = when (items.size) {
        0 -> ""; 1 -> items[0]; else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
