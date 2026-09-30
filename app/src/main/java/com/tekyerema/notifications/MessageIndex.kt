package com.tekyerema.notifications

import android.content.Context
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * H-09 — a persistent index of the messages and notifications the phone received, so "what did Ama send me
 * yesterday?" still works after the notification was dismissed or the app was killed (the live [NotificationStore]
 * is an in-memory ring of 50). Owner-approved persistent memory, with the same protections as capture:
 * authenticator apps are never captured and one-time codes are already hidden (J-10), text is sanitised (J-07).
 * Stored encrypted ([SecureStore]); entries expire after `memory.entityIndex.ttlDays` (7) and the index keeps at most
 * `memory.entityIndex.max` (500). "Forget Ama's messages" / "forget everything" remove them.
 */
data class IndexedMessage(val t: Long, val pkg: String, val app: String?, val sender: String?, val title: String?, val text: String?)

object MessageIndex {
    @Volatile private var ctx: Context? = null
    private val items = ArrayList<IndexedMessage>()
    @Volatile private var loaded = false
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tky-msgindex").apply { isDaemon = true } }
    @Volatile private var saveQueued = false

    fun init(c: Context) { ctx = c.applicationContext }

    private fun enabled() = com.tekyerema.core.config.FeatureFlags.isEnabled("H-09", true)
    private fun file(): File? = ctx?.let { File(it.filesDir, "memory/messages.enc") }
    private fun ttlMs() = AppConfig.long("memory.entityIndex.ttlDays", 7L) * 86_400_000L

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val f = file() ?: return   // no context yet: NOT loaded — a later save must not overwrite the file with nothing
        // A file that exists but can't be read (a Keystore hiccup) must not count as loaded either, or the next
        // save would overwrite the 7-day index with an empty one (audit 2026-09-30, half 2; Relations already guards this).
        val text = SecureStore.read(f)
        if (text == null && f.exists()) return
        loaded = true
        if (text == null) return
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                items.add(IndexedMessage(o.optLong("t"), o.optString("pkg"), o.str("app"), o.str("sender"), o.str("title"), o.str("text")))
            }
        } catch (_: Throwable) {}
        expire()
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null

    /** Index a live notification (not the reconnect backfill). Ongoing/empty ones are skipped. */
    fun add(r: NotificationRecord) {
        if (!enabled() || (r.text.isNullOrBlank() && r.title.isNullOrBlank())) return
        synchronized(this) {
            ensureLoaded()
            val m = IndexedMessage(r.postTime, r.packageName, r.appLabel, r.sender, r.title, r.text)
            // A chat notification is re-posted as messages arrive: keep one row per (app, conversation, text).
            items.removeAll { it.pkg == m.pkg && it.title == m.title && it.text == m.text }
            items.add(m)
            expire()
        }
        scheduleSave()
    }

    @Synchronized
    fun all(): List<IndexedMessage> { ensureLoaded(); expire(); return items.sortedByDescending { it.t } }

    /** Remove everything from [who] (sender, conversation title or app). Returns how many were removed. */
    fun forget(who: String): Int {
        val n = synchronized(this) {
            ensureLoaded()
            val before = items.size
            items.removeAll { matches(it, who) }
            before - items.size
        }
        if (n > 0) scheduleSave()
        return n
    }

    @Synchronized
    fun clear() { items.clear(); loaded = true; try { file()?.delete() } catch (_: Throwable) {} }

    @Synchronized fun size(): Int { ensureLoaded(); return items.size }

    /** Distinct people/conversations in the index. */
    @Synchronized fun people(): Int { ensureLoaded(); return items.mapNotNull { (it.sender ?: it.title)?.lowercase() }.distinct().size }

    fun matches(m: IndexedMessage, who: String): Boolean {
        val w = who.trim()
        if (w.isEmpty()) return false
        return m.sender?.contains(w, true) == true || m.title?.contains(w, true) == true || m.app?.equals(w, true) == true
    }

    private fun expire(now: Long = System.currentTimeMillis()) {
        items.removeAll { now - it.t > ttlMs() }
        val max = AppConfig.int("memory.entityIndex.max", 500)
        if (items.size > max) { items.sortBy { it.t }; while (items.size > max) items.removeAt(0) }
    }

    /** Coalesce writes: at most one encrypted save every couple of seconds, off the listener thread. */
    private fun scheduleSave() {
        if (saveQueued) return
        saveQueued = true
        try { io.schedule({ saveQueued = false; save() }, 2, TimeUnit.SECONDS) } catch (_: Throwable) { saveQueued = false }
    }

    private fun save() {
        val f = file() ?: return
        val arr = JSONArray()
        synchronized(this) {
            items.forEach { m ->
                arr.put(JSONObject().put("t", m.t).put("pkg", m.pkg).put("app", m.app ?: JSONObject.NULL)
                    .put("sender", m.sender ?: JSONObject.NULL).put("title", m.title ?: JSONObject.NULL).put("text", m.text ?: JSONObject.NULL))
            }
        }
        SecureStore.write(f, arr.toString())
    }
}
