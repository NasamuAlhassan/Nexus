package com.tekyerema.agent.habits

import android.content.Context
import android.util.Log
import com.tekyerema.agent.triggers.ScheduledTasks
import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import java.util.TimeZone

/**
 * The Android side of [RepeatDetector]: the bounded history and the offer memory in app storage (SharedPreferences
 * "tekyerema_habits" — kinds and times only, never the user's words), and a yes turned into a speak-only A-13 reminder.
 * Off when schedules are off (flag A-13, or the user's own switch in Settings › Control) or `habits.offers.enabled` is false.
 */
class Habits(
    context: Context,
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
    /** The A-13 scheduler (swapped in tests, which have no AlarmManager). */
    private val addSchedule: (com.tekyerema.agent.triggers.Schedule) -> com.tekyerema.agent.triggers.Schedule? =
        { ScheduledTasks.add(context.applicationContext, it) },
    private val findSchedule: (String) -> com.tekyerema.agent.triggers.Schedule? = { ScheduledTasks.get(context.applicationContext, it) }
) : HabitHost {
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun enabled(): Boolean =
        FeatureFlags.isEnabled(ScheduledTasks.FEATURE, true) && AppConfig.bool("habits.offers.enabled", true)

    @Synchronized
    override fun completed(goal: String, fastIntent: String?, now: Long, canAsk: Boolean): HabitOffer? {
        if (!enabled()) return null
        val kind = RepeatDetector.kindOf(goal, fastIntent) ?: return null   // never a consequential request
        val event = RepeatDetector.Event(kind.id, now)
        val history = RepeatDetector.record(RepeatDetector.decode(prefs.getString(KEY_HISTORY, null)), event)
        prefs.edit().putString(KEY_HISTORY, RepeatDetector.encode(history)).apply()
        if (!canAsk) return null
        val tz = zone()
        val pattern = RepeatDetector.detect(history, event, tz) ?: return null
        val today = RepeatDetector.epochDay(now, tz)
        val memory = memory()
        if (!RepeatDetector.mayOffer(pattern, memory, today)) return null
        // Already reminding them (a yes on another phone state, or saved by hand with the same id).
        if (findSchedule(RepeatDetector.schedule(pattern).id) != null) return null
        saveMemory(RepeatDetector.offered(pattern, memory, today))
        Log.i(TAG, "[HABIT] offering a ${pattern.cadence.name.lowercase()} reminder for '${kind.id}'")
        return HabitOffer(pattern, RepeatDetector.offerLine(pattern))
    }

    @Synchronized
    override fun answered(offer: HabitOffer, answer: RepeatDetector.Answer): String {
        val p = offer.pattern
        return when (answer) {
            RepeatDetector.Answer.YES -> {
                val stored = addSchedule(RepeatDetector.schedule(p)) ?: return "Sorry, I couldn't save that reminder."
                saveMemory(RepeatDetector.answered(p, memory(), answer))
                // J-12 / J-13: in the action audit, and "undo" takes it away again.
                try {
                    com.tekyerema.audit.AuditLog.record("user", com.tekyerema.audit.AuditLog.DONE,
                        "saved a reminder ${ScheduledTasks.whenPhrase(stored)}", com.tekyerema.audit.AuditLog.newTask())
                    com.tekyerema.audit.UndoLog.push(com.tekyerema.audit.UndoGroup(System.currentTimeMillis(), "HABIT_REMINDER",
                        listOf(com.tekyerema.audit.Inverse.RemoveSchedule(stored.id))))
                } catch (_: Throwable) {}
                Log.i(TAG, "[HABIT] yes — reminder ${stored.id} saved (speaks only)")
                RepeatDetector.yesLine(stored)
            }
            RepeatDetector.Answer.NO -> { saveMemory(RepeatDetector.answered(p, memory(), answer)); RepeatDetector.NO_LINE }
            RepeatDetector.Answer.NONE -> RepeatDetector.LATER_LINE
        }
    }

    private fun memory() = RepeatDetector.decodeMemory(prefs.getString(KEY_MEMORY, null))
    private fun saveMemory(m: RepeatDetector.Memory) { prefs.edit().putString(KEY_MEMORY, RepeatDetector.encodeMemory(m)).apply() }

    companion object {
        private const val TAG = "Tekyerema"
        const val PREFS = "tekyerema_habits"
        private const val KEY_HISTORY = "history"
        private const val KEY_MEMORY = "memory"

        /** H-12 "what do you remember": a line when requests are being noted, else null. */
        fun summary(ctx: Context): String? =
            if (RepeatDetector.decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, null)).isEmpty()) null
            else "when you usually ask for everyday things like the time, so I can offer a reminder"

        /** H-12 "forget everything": the noted requests go. The answers to offers stay — a "no" is kept for good. */
        fun forgetHistory(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
        }
    }
}
