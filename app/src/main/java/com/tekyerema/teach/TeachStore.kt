package com.tekyerema.teach

import android.content.Context
import com.tekyerema.core.SecureStore
import org.json.JSONArray
import java.io.File

/**
 * Teach mode — the taught skills, on the phone. Same pattern as [com.tekyerema.agent.Routines]: in memory with an
 * encrypted file behind it (`memory/skills.enc`, [SecureStore]); a saved file that can't be read right now is never
 * overwritten (memory-only until it loads). A store with no file (the tests) is memory-only.
 */
class TeachStore(private var file: File? = null) {
    private val skills = LinkedHashMap<String, TeachSkill>()
    @Volatile private var loaded = file == null
    private var failedLoads = 0

    val isLoaded: Boolean get() = loaded

    fun ensureLoaded(ctx: Context?) {
        if (loaded || ctx == null) return
        synchronized(this) {
            if (loaded) return
            val f = File(ctx.applicationContext.filesDir, "memory/skills.enc")
            val text = try { SecureStore.read(f) } catch (_: Throwable) { null }
            val arr = text?.let { try { JSONArray(it) } catch (_: Throwable) { null } }
            if (f.exists() && arr == null && ++failedLoads < 3) {
                android.util.Log.w("Tekyerema", "[TEACH] couldn't read the saved skills (try $failedLoads) — not overwriting them")
                return
            }
            file = f; loaded = true
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(TeachSkill::fromJson)?.let { if (it.name !in skills) skills[it.name] = it }
        }
    }

    @Synchronized fun names(): List<String> = skills.keys.toList()
    @Synchronized fun get(name: String): TeachSkill? = skills[name]
    @Synchronized fun all(): List<TeachSkill> = skills.values.toList()
    @Synchronized fun put(s: TeachSkill) { skills[s.name] = s; save() }
    @Synchronized fun remove(name: String): Boolean { val had = skills.remove(name) != null; if (had) save(); return had }
    @Synchronized fun clearForTest() { skills.clear() }

    /** "forget everything" also forgets what was taught (it is the user's own way of using the phone). */
    @Synchronized fun clear() { skills.clear(); save() }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        skills.values.forEach { arr.put(it.toJson()) }
        try { SecureStore.write(f, arr.toString()) } catch (_: Throwable) {}
    }

    companion object {
        /** The phone's one store. */
        val shared = TeachStore().also { it.loaded = false }
    }
}
