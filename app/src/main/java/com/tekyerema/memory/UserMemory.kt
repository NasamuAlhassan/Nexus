package com.tekyerema.memory

import android.content.Context
import android.util.Log
import com.tekyerema.core.SecureStore
import java.io.File

/**
 * The user's memory on the phone: preferences, the contacts they use most, the requests they repeat, and what they told us.
 * One encrypted file (files/memory/user_memory.enc, [SecureStore] — AES-GCM, Keystore key), loaded once, rewritten on change.
 * Offline, never uploaded, and the planner prompt gets nothing from it. Logs say how many entries there are, never what they are.
 * Never stored: PINs, passwords, OTPs, balances, amounts, message text, or anything said while a secret is being typed
 * ([MemoryCore.check] and [com.tekyerema.core.Privacy.secureStep]). Rules and tests: [MemoryCore].
 */
object UserMemory {
    private const val TAG = "Tekyerema"

    @Volatile private var state = MemState()
    @Volatile private var file: File? = null
    @Volatile private var loaded = false
    private var failedLoads = 0

    /**
     * Load once (a Keystore decrypt, so not on the main thread). A file that exists but can't be read leaves the store
     * unloaded and memory-only — the next save must not overwrite what the user taught with a near-empty state.
     */
    fun ensureLoaded(ctx: Context?) {
        if (loaded || ctx == null) return
        synchronized(this) {
            if (loaded) return
            val f = File(ctx.applicationContext.filesDir, "memory/user_memory.enc")
            val parsed = try { MemoryCore.decode(SecureStore.read(f)) } catch (_: Throwable) { null }
            if (f.exists() && parsed == null && ++failedLoads < 3) {
                Log.w(TAG, "[MEMORY] couldn't read the saved memory (try $failedLoads) — not overwriting it")
                return
            }
            file = f
            loaded = true
            parsed?.let { state = MemoryCore.prune(it, System.currentTimeMillis()) }
        }
    }

    @Synchronized fun snapshot(): MemState = state

    fun isOn(kind: MemKind): Boolean = kind !in state.off

    /** Apply [change] and save. The log line is counts only. */
    @Synchronized fun update(ctx: Context?, change: (MemState) -> MemState): MemState {
        ensureLoaded(ctx)
        state = change(state)
        save()
        return state
    }

    private fun save() {
        val f = file ?: return   // not loaded from a context (unit tests, or an unreadable file): memory only
        try {
            if (state.entries.isEmpty() && state.off.isEmpty()) f.delete()
            else SecureStore.write(f, MemoryCore.encode(state))
            Log.i(TAG, "[MEMORY] ${state.entries.size} entries, ${state.off.size} categories off")
        } catch (_: Throwable) {}
    }

    private fun secure(): Boolean = com.tekyerema.core.Privacy.secureStep

    // ---------------------------------------------------------------- learned from use (one call each)

    /** A call or text to a saved contact went through: [name] is the contact's name, [number] only gives its last 4 digits. */
    fun noteContact(ctx: Context?, name: String?, number: String) {
        if (name.isNullOrBlank() || secure() || !isOn(MemKind.CONTACT)) return
        try { update(ctx) { MemoryCore.upsert(it, MemKind.CONTACT, MemoryCore.contactKey(name, number), name, false, System.currentTimeMillis()) } }
        catch (_: Throwable) {}
    }

    /** A request ran well on the fast path: [phrase] is how the user said it, [intent] what it resolved to. */
    fun noteTask(ctx: Context?, phrase: String, intent: String) {
        if (secure() || !isOn(MemKind.TASK) || SKIP_INTENT.containsMatchIn(intent)) return
        try { update(ctx) { MemoryCore.upsert(it, MemKind.TASK, intent, phrase, false, System.currentTimeMillis()) } }
        catch (_: Throwable) {}
    }
    private val SKIP_INTENT = Regex("^(MEMORY|FORGET|SET_|DELETE|CLEAR|CREATE|UNDO|RELATION|WATCH|ALERT|CALL|SMS|SEND|MOMO|MONEY|USSD|TWI|CALCULATE|MEANING|NEXT_STEP|COMPANION)")

    /** Of contacts that fit [said] equally, the one used far more than the others — else all of them, unchanged. */
    fun <T> preferContact(ctx: Context?, said: String, hits: List<T>, nameOf: (T) -> String, numberOf: (T) -> String): List<T> {
        if (hits.size < 2) return hits
        return try {
            ensureLoaded(ctx)
            MemoryCore.preferFrequent(snapshot(), System.currentTimeMillis(), said, hits, nameOf, numberOf)
        } catch (_: Throwable) { hits }
    }

    // ---------------------------------------------------------------- told by the user / forgetting

    /** "forget everything": the entries go and the file with them; the on/off switches the user chose stay. */
    fun clearAll(ctx: Context?) {
        update(ctx) { MemState(off = it.off) }
    }
}
