package com.tekyerema.learning

import android.content.Context
import android.util.Log
import com.tekyerema.core.SecureStore
import java.io.File

/**
 * The notes of tasks that went wrong, on the phone only: one encrypted file (files/learning/failures.enc, [SecureStore] — AES-GCM,
 * Keystore key, like [com.tekyerema.memory.UserMemory]), loaded once, rewritten on change, at most [FailureCore.MAX_CASES] (the
 * oldest go first). Never uploaded: the only way out is the user's own "share my mistakes for training" ([FailureExport]).
 * Logs say how many notes there are, never what is in them.
 */
object FailureStore {
    private const val TAG = "Tekyerema"

    @Volatile private var cases: List<FailureCase> = emptyList()
    @Volatile private var file: File? = null
    @Volatile private var loaded = false
    private var failedLoads = 0

    private fun fileOf(ctx: Context) = File(ctx.applicationContext.filesDir, "learning/failures.enc")

    /** Load once (a Keystore decrypt, so not on the main thread). A file that exists but can't be read leaves the store unloaded and
     *  memory-only — the next save must not overwrite what was kept with a near-empty list. */
    fun ensureLoaded(ctx: Context?) {
        if (loaded || ctx == null) return
        synchronized(this) {
            if (loaded) return
            val f = fileOf(ctx)
            val parsed = try { FailureCore.decode(SecureStore.read(f)) } catch (_: Throwable) { null }
            if (f.exists() && parsed == null && ++failedLoads < 3) {
                Log.w(TAG, "[LEARN] couldn't read the saved notes (try $failedLoads) — not overwriting them")
                return
            }
            file = f
            loaded = true
            parsed?.let { cases = it }
        }
    }

    @Synchronized fun all(): List<FailureCase> = cases

    @Synchronized fun count(): Int = cases.size

    /** Add [case] (or replace the one with its id) and save. */
    @Synchronized fun put(ctx: Context?, case: FailureCase) {
        ensureLoaded(ctx)
        cases = FailureCore.add(cases, case)
        save()
    }

    /** "Forget my mistakes": every note and the file with it. Returns how many notes there were. */
    @Synchronized fun clear(ctx: Context?): Int {
        ensureLoaded(ctx)
        val n = cases.size
        cases = emptyList()
        try { (file ?: ctx?.let { fileOf(it) })?.delete() } catch (_: Throwable) {}
        Log.i(TAG, "[LEARN] notes cleared ($n)")
        return n
    }

    private fun save() {
        val f = file ?: return   // not loaded from a context (unit tests, or an unreadable file): memory only
        try {
            if (cases.isEmpty()) f.delete() else SecureStore.write(f, FailureCore.encode(cases))
            Log.i(TAG, "[LEARN] ${cases.size} notes")
        } catch (_: Throwable) {}
    }
}
