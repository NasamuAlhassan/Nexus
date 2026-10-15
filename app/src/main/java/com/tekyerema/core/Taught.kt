package com.tekyerema.core

/**
 * Teach once, then trust the user (research for the owner, 15 Oct 2026: blind users find assistants talk AT them; long
 * read-backs made each demo step slow): a how-to sentence — "I'll go through the menu quietly and stop at the PIN", the
 * volume-key PIN tip — is said in full the first [times] times, then in its short form. Counted on the phone only.
 */
object Taught {
    private const val PREFS = "tekyerema_taught"

    /** True while [key] should still be taught in full (and counts this telling). */
    fun teach(key: String, times: Int = 3): Boolean {
        if (android.os.Build.VERSION.SDK_INT == 0) return true   // JVM unit tests (stubbed Android): always the full words
        val ctx = try { com.tekyerema.agent.AgentEngine.appContext() } catch (_: Throwable) { null } ?: return true
        val p = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val n = p.getInt(key, 0)
        if (n >= times) return false
        p.edit().putInt(key, n + 1).apply()
        return true
    }
}
