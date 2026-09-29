package com.tekyerema.ime

import android.content.Context
import com.tekyerema.core.SecureStore
import java.io.File

/**
 * G-05 — the keyboard's own options, in their own preferences file (the app's Settings stay the agent's). Read fresh
 * every time the keyboard opens, so a change on the Settings page applies to the next field without a restart.
 * App data is never backed up (allowBackup=false, data_extraction_rules exclude everything).
 */
class KeyboardPrefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var bigKeys: Boolean
        get() = p.getBoolean("big_keys", false)
        set(v) = p.edit().putBoolean("big_keys", v).apply()
    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.edit().putBoolean("vibrate", v).apply()
    var sound: Boolean
        get() = p.getBoolean("sound", false)
        set(v) = p.edit().putBoolean("sound", v).apply()
    var suggestions: Boolean
        get() = p.getBoolean("suggestions", true)
        set(v) = p.edit().putBoolean("suggestions", v).apply()
    /** "3" → ɛ and "c" → ɔ in Twi mode. On by default: the owner asked for it and a backspace undoes each one. */
    var textingHabit: Boolean
        get() = p.getBoolean("texting_habit", true)
        set(v) = p.edit().putBoolean("texting_habit", v).apply()
    /** Learn new words. OFF by default — a keyboard should keep nothing unless the user asks it to. */
    var learnWords: Boolean
        get() = p.getBoolean("learn_words", false)
        set(v) = p.edit().putBoolean("learn_words", v).apply()
    var language: KbLanguage
        get() = if (p.getString("language", "twi") == "en") KbLanguage.ENGLISH else KbLanguage.TWI
        set(v) = p.edit().putString("language", if (v == KbLanguage.ENGLISH) "en" else "twi").apply()

    companion object { const val FILE = "twi_keyboard" }
}

/**
 * The learned words, one copy per process (the keyboard service and the Settings page run in the app's process), in
 * noBackupFilesDir, encrypted with the app's Keystore key like relations and routines ([SecureStore]) — they hold
 * names and private words. Only ever written when learning is on; switching learning off deletes the file. A plain
 * file from the first build is read once, then replaced by the encrypted one.
 */
object LearnedWordStore {
    private const val NAME = "ime_learned_words.enc"
    private const val LEGACY = "ime_learned_words.tsv"
    @Volatile private var cached: LearnedWords? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.noBackupFilesDir, NAME)
    private fun legacy(ctx: Context) = File(ctx.applicationContext.noBackupFilesDir, LEGACY)

    @Synchronized
    fun get(ctx: Context): LearnedWords = cached ?: run {
        val f = file(ctx)
        val old = legacy(ctx)
        val words = try {
            when {
                f.exists() -> SecureStore.read(f)?.let { LearnedWords.parse(it) } ?: LearnedWords()
                old.exists() -> LearnedWords.parse(old.readText()).also { w ->
                    if (SecureStore.write(f, w.serialize())) old.delete()
                }
                else -> LearnedWords()
            }
        } catch (_: Throwable) { LearnedWords() }
        words.also { cached = it }
    }

    @Synchronized
    fun saveIfDirty(ctx: Context) {
        val w = cached ?: return
        if (!w.dirty) return
        try { SecureStore.write(file(ctx), w.serialize()) } catch (_: Throwable) {}
    }

    @Synchronized
    fun clear(ctx: Context) {
        cached?.clear()
        cached?.serialize()   // nothing left to write; marks it clean
        try { file(ctx).delete(); legacy(ctx).delete() } catch (_: Throwable) {}
    }

    fun count(ctx: Context): Int = get(ctx).size
}
