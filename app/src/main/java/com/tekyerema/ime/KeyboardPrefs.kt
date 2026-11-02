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
    /** Slide a finger from letter to letter to type a word (G-05 (2)). On by default; a tap still types one key. */
    var swipeTyping: Boolean
        get() = p.getBoolean("swipe_typing", true)
        set(v) = p.edit().putBoolean("swipe_typing", v).apply()
    /** Learn new words. OFF by default — a keyboard should keep nothing unless the user asks it to. */
    var learnWords: Boolean
        get() = p.getBoolean("learn_words", false)
        set(v) = p.edit().putBoolean("learn_words", v).apply()

    // ---- local languages (LocalVoice, docs/agent/KEYBOARD_LANGUAGES.md)

    /** The language the microphone listens in: a [LocalLang.code], or null for English (the default). */
    var voiceLanguage: LocalLang?
        get() = LocalLanguages.of(p.getString("voice_language", null))
        set(v) = p.edit().putString("voice_language", v?.code).apply()
    /** "Type my words in": my language / English / ask each time (the default). */
    var sendMode: SendMode
        get() = SendMode.of(p.getString("send_mode", null))
        set(v) = p.edit().putString("send_mode", v.key).apply()
    /** True the first time [lang]'s warning is due (then it is marked said). */
    fun firstWarning(lang: LocalLang): Boolean {
        if (lang.warning == null) return false
        val said = p.getStringSet("warned_languages", emptySet()).orEmpty()
        if (lang.code in said) return false
        p.edit().putStringSet("warned_languages", said + lang.code).apply()
        return true
    }

    /** This phone's code for the Space's fair-use budget ([DeviceTag]): a random id, renewed each month or on [resetDevice]. */
    fun deviceTag(nowMs: Long): String {
        val month = DeviceTag.month(nowMs)
        var id = p.getString("device_id", null)
        if (id == null || p.getInt("device_month", 0) != month) {
            id = java.util.UUID.randomUUID().toString()
            p.edit().putString("device_id", id).putInt("device_month", month).apply()
        }
        return DeviceTag.hash(id)
    }

    fun resetDevice() { p.edit().remove("device_id").remove("device_month").apply() }

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
