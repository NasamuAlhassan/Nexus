package com.tekyerema.agent

import android.content.Context

/**
 * Read-only requests that come through a door which skipped the fast path (a schedule firing, the offline queue replaying a
 * kept request) run their fast-path handler instead of an engine task. An engine task steps Tɛkyerɛma's own screen aside
 * ([StepAside], AgentEngine.ensureOffOwnUi) — right for operating another app, wrong for "read my messages" at 7 am while the
 * user is looking at our screen, and slower than the phone's own handler, which needs no model and no screen.
 *
 * Only intents that READ phone data, listed in [allows]: texts, the notification / message catch-up, the call log, today's
 * calendar. Nothing that acts, opens an app, changes a setting or touches the screen is ever run this way — those stay with the
 * engine and its policy gates. Classification is the fast path's own ([FastIntentClassifier]); execution is [LocalFastRouter]'s
 * `run`, on a fresh router instance so a live voice turn's state (language, hand-off) is never touched. Pure part unit-tested
 * (ReadOnlyDoorTest).
 */
object ReadOnlyDoor {
    /** The phone-data reads. Deliberately NOT here: Notifications (opens the shade), ChatMessages / ReadMessage (a chat app), anything else. */
    fun allows(intent: FastIntent?): Boolean = when (intent) {
        FastIntent.LastSms, is FastIntent.LastSmsN, is FastIntent.Digest, is FastIntent.CallHistory, FastIntent.CalendarToday -> true
        else -> false
    }

    /** The read-only fast intent [goal] is, or null (then the goal goes to the engine as before). */
    fun intentFor(goal: String): FastIntent? =
        try { FastIntentClassifier.classify(goal, routines = false)?.takeIf { allows(it) } } catch (_: Throwable) { null }

    /** Run [goal]'s handler and return what it found; null = no handler took it (the caller submits to the engine as before). */
    suspend fun run(app: Context, goal: String): FastOutcome? = try {
        LocalFastRouter(app) { AgentEngine.currentController() }.runReadOnly(goal)
    } catch (_: Throwable) { null }

    /** The text for the notification shade: the spoken line without its pause marks. */
    fun shadeText(o: FastOutcome): String = com.tekyerema.voice.SpokenText.plain(o.spoken)
}
