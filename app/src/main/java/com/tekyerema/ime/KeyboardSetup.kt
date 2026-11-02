package com.tekyerema.ime

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * G-05 — getting the Nexus keyboard turned on. Android needs two user steps that no app can do for them:
 *  1. ENABLE it in the keyboard list (Settings.ACTION_INPUT_METHOD_SETTINGS). Android then warns that a keyboard
 *     "may be able to collect all the text you type"; the user must accept.
 *  2. SELECT it as the keyboard in use (InputMethodManager.showInputMethodPicker — only works while our own window
 *     has focus, so it is called from the Settings page, never from the background).
 * [KeyboardStatus] reads which step the user is on; [KeyboardSetupText] says it in words that work read aloud.
 */
enum class KeyboardStatus { NOT_ENABLED, ENABLED_NOT_SELECTED, IN_USE }

object KeyboardSetupLogic {
    /** Android writes IME ids as "pkg/.Cls" or "pkg/pkg.Cls"; compare them in one form. */
    fun normalize(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val slash = id.indexOf('/')
        if (slash <= 0) return id
        val pkg = id.substring(0, slash)
        val cls = id.substring(slash + 1)
        return pkg + "/" + (if (cls.startsWith(".")) pkg + cls else cls)
    }

    fun status(enabledIds: List<String>, selectedId: String?, ownId: String): KeyboardStatus {
        val own = normalize(ownId)
        return when {
            normalize(selectedId) == own -> KeyboardStatus.IN_USE
            enabledIds.any { normalize(it) == own } -> KeyboardStatus.ENABLED_NOT_SELECTED
            else -> KeyboardStatus.NOT_ENABLED
        }
    }
}

object KeyboardSetupText {
    /** As the phone's keyboard list shows it (res/values/ime.xml ime_name). */
    const val NAME = "Nexus keyboard"

    fun statusLabel(s: KeyboardStatus) = when (s) {
        KeyboardStatus.NOT_ENABLED -> "Not added"
        KeyboardStatus.ENABLED_NOT_SELECTED -> "Added, not in use"
        KeyboardStatus.IN_USE -> "In use"
    }

    /** The steps for where the user is, written to be read aloud (no symbols, one action per sentence). */
    fun steps(s: KeyboardStatus): String = when (s) {
        KeyboardStatus.NOT_ENABLED ->
            "To add the keyboard, tap Add the keyboard. Your phone's keyboard list opens. " +
                "Find $NAME and switch it on. Your phone will warn that a keyboard can collect what you type. " +
                "This keyboard keeps everything on your phone and sends nothing anywhere, so tap OK. Then come back here."
        KeyboardStatus.ENABLED_NOT_SELECTED ->
            "The keyboard is added. To start using it, tap Use this keyboard, then choose $NAME from the list."
        KeyboardStatus.IN_USE ->
            "The $NAME is in use. Big keys, swipe typing and voice typing are below. " +
                "To go back to your other keyboard, touch and hold the language key or the space bar."
    }
}

/** Android glue for the two steps. */
object KeyboardSetup {
    fun ownId(ctx: Context) = android.content.ComponentName(ctx, TwiKeyboardService::class.java).flattenToShortString()

    fun status(ctx: Context): KeyboardStatus {
        val imm = ctx.getSystemService(InputMethodManager::class.java) ?: return KeyboardStatus.NOT_ENABLED
        val enabled = try { imm.enabledInputMethodList.map { it.id } } catch (_: Throwable) { emptyList() }
        val selected = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) } catch (_: Throwable) { null }
        return KeyboardSetupLogic.status(enabled, selected, ownId(ctx))
    }

    fun openKeyboardList(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            // A few OEM builds hide that screen; the language & input page is the parent of it everywhere.
            ctx.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The system keyboard chooser. Must be called while our activity has window focus. */
    fun showPicker(ctx: Context) {
        ctx.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }
}
