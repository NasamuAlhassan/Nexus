package com.tekyerema.companion

import com.tekyerema.core.config.AppConfig

/**
 * Is a companion chat on? A chat starts at "let's talk" / "I'm bored" / a lesson / a warm word, and stays on while the brain
 * is being asked for turns of it (each [rulesFor] call renews it) until it has been quiet for `companion.chatIdleMs`
 * (default 10 minutes) or the user ends it ("that's enough"). While it is on, the CLOUD brain's prompt carries
 * [CompanionGuard.CHAT_RULES] (BrainPrompts.goalRules — the one hook); the on-phone model needs nothing: it was trained on the
 * `companion` skill and its prompt must stay byte-for-byte what it learned.
 *
 * Process-scoped state, no personal data in it: only when the last turn was and what kind it was.
 */
object CompanionSession {
    @Volatile private var lastAt = 0L
    @Volatile var lastKind: CompanionIntents.Kind? = null
        private set
    @Volatile private var lessons = 0

    /** The phone's clock; tests replace it. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /** The user's own switch (Settings.companionEnabled), set at start-up (TekyeremaApp) and whenever it changes; null = the
     *  config default `companion.enabled`. Kept here so the pure classifier (FastIntent) needs no Context. */
    @Volatile var userEnabled: Boolean? = null

    fun enabled(): Boolean = userEnabled ?: AppConfig.bool("companion.enabled", true)

    fun idleMs(): Long = AppConfig.long("companion.chatIdleMs", 10 * 60_000L).coerceAtLeast(30_000L)

    fun active(now: Long = clock()): Boolean = enabled() && lastAt > 0L && now - lastAt in 0 until idleMs()

    fun start(kind: CompanionIntents.Kind) { lastAt = clock(); lastKind = kind }

    fun end() { lastAt = 0L; lastKind = null }

    /** How many lessons this chat has given (the next one is picked from it). */
    fun nextLesson(): Int = lessons++

    /**
     * The extra rules for a cloud brain's prompt, or null when no chat is on. Called for every brain step, so a chat that
     * is being answered stays on.
     */
    fun rulesFor(@Suppress("UNUSED_PARAMETER") goal: String): String? {
        if (!active()) return null
        lastAt = clock()
        return CompanionGuard.CHAT_RULES
    }

    internal fun resetForTest() { lastAt = 0L; lastKind = null; lessons = 0; clock = { System.currentTimeMillis() } }
}
