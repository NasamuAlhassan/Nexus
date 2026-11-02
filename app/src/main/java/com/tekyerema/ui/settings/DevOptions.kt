package com.tekyerema.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Developer options (2 Nov 2026, owner: "what should we remove"): the AI provider, API keys, own server, online-AI limits and
 * the computer connection are tools for whoever builds or tests Nexus, not for a blind user in Accra — "Groq", "tokens" and
 * "API keys" were read out on the way to "Phone care". They stay reachable, the way Android does it: tap the version line
 * in Settings [TAPS] times. Everything they control still works by voice, as before.
 */
internal object DevOptions {
    const val TAPS = 7
    private const val PREFS = "nexus_dev"
    private const val KEY_ON = "on"

    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> get() = _on

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _on.value = prefs?.getBoolean(KEY_ON, false) ?: false
    }

    fun set(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ON, value)?.apply()
        _on.value = value
    }
}
