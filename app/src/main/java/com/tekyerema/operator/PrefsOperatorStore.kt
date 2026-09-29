package com.tekyerema.operator

import android.content.Context
import java.util.concurrent.Executors

/**
 * [OperatorStore] on the app's own preferences file (the same one [com.tekyerema.settings.Settings] uses, keys "op.*").
 * Nothing secret lives here — API keys stay encrypted in Settings. A write also bumps Settings' change counter, so every
 * settings page re-reads at once, and is recorded in the activity log ("switched the cloud off") off the main thread —
 * a change to how Tɛkyerɛma may act is itself part of the audit trail.
 */
class PrefsOperatorStore(context: Context) : OperatorStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(com.tekyerema.settings.Settings.PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // J-18 carried over: someone who had switched the Gemini backup off keeps Gemini out of the provider list.
        if (!prefs.contains(OperatorPolicy.K_PROVIDERS) && prefs.contains(KEY_GEMINI_BACKUP) &&
            !prefs.getBoolean(KEY_GEMINI_BACKUP, true)) {
            prefs.edit().putString(OperatorPolicy.K_PROVIDERS, "groq").apply()
        }
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        val before = prefs.getString(key, null)
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        // The usage counter changes on every model call; the screens that show it also watch OperatorPolicy.changes.
        if (key == OperatorPolicy.K_USAGE) return
        com.tekyerema.settings.Settings.notifyChanged()
        if (before == value) return
        OperatorPolicy.describeChange(key, value)?.let { words ->
            auditWriter.execute {
                // Its own group (not AuditLog.newTask(), which would move the fast path's current task from this thread).
                com.tekyerema.audit.AuditLog.record("user", com.tekyerema.audit.AuditLog.SETTING, words, System.currentTimeMillis())
            }
        }
    }

    private companion object {
        const val KEY_GEMINI_BACKUP = "allow_gemini_backup"
        /** One thread, so changes reach the log in the order they were made. */
        val auditWriter = Executors.newSingleThreadExecutor()
    }
}
