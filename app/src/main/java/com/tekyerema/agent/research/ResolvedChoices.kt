package com.tekyerema.agent.research

import android.content.Context
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the user picked last time, so the same question isn't asked again (research brief §B.5 "Remember"): a spoken
 * referent ("university of ghana sakai") → the target it resolved to (https://sakai.ug.edu.gh/). PrefEval shows a
 * preference stated in the conversation is followed < 10 % of the time after ~10 turns, so this lives outside the
 * prompt, in code.
 *
 *  - Encrypted at rest (SecureStore, AES-GCM in the Keystore; no key ⇒ memory only, never written in the clear), like
 *    the conversation memory (H-10 / J-15). App-private: `files/memory/choices.enc`.
 *  - Expires after `memory.choices.ttlDays` (180) without use.
 *  - Forgettable by voice (H-12): "forget that choice", "forget Sakai", "forget everything".
 *  - Only `url` targets are recalled and acted on by the research step; nothing here ever picks a money recipient or a
 *    person to call (a consequential target is confirmed every time by the policy layer regardless).
 */
data class Choice(
    /** "url" (research) — "app" | "contact" | "setting" reserved. */
    val kind: String,
    val value: String,
    /** How it is spoken ("UG Sakai"). */
    val label: String,
    /** "asked" (the user picked it) | "research" (one clear official site, not corrected) | "accepted". */
    val source: String,
    val uses: Int,
    val lastUsed: Long
)

class ChoiceStore(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: () -> Long = { AppConfig.long("memory.choices.ttlDays", 180L) * 86_400_000L }
) {
    private val map = LinkedHashMap<String, Choice>()
    private var loaded = false
    /** The referent last recalled, remembered or opened, its target, and when — "forget that" / "not that one" said
     *  right after refer to it. */
    private var lastKey: String? = null
    private var lastValue: String? = null
    private var lastAt = 0L

    @Synchronized fun recall(referent: String): Choice? {
        load()
        val k = key(referent).ifEmpty { return null }
        val c = map[k] ?: return null
        if (clock() - c.lastUsed > ttlMs()) { map.remove(k); save(); return null }
        lastKey = k; lastValue = c.value; lastAt = clock()
        return c
    }

    @Synchronized fun remember(referent: String, kind: String, value: String, label: String, source: String): Choice? {
        load()
        val k = key(referent).ifEmpty { return null }
        val prev = map[k]
        val c = Choice(kind, value, label, source, uses = if (prev?.value == value) prev.uses + 1 else 1, lastUsed = clock())
        map.remove(k); map[k] = c   // most recent last
        while (map.size > MAX) map.remove(map.keys.first())
        lastKey = k; lastValue = value; lastAt = clock()
        save()
        return c
    }

    /** Something was opened for [referent] without being remembered (it didn't verify): "not that one" still means it. */
    @Synchronized fun noteOpened(referent: String, value: String) {
        key(referent).takeIf { it.isNotEmpty() }?.let { lastKey = it; lastValue = value; lastAt = clock() }
    }

    /** (referent key, target) of the last recall / remember / open within [withinMs], or null. */
    @Synchronized fun recentOpened(withinMs: Long = RECENT_MS): Pair<String, String?>? =
        lastKey?.takeIf { clock() - lastAt <= withinMs }?.let { it to lastValue }

    /** "forget Sakai": entries whose referent or label mentions [what] (only that referent when [exact]). True if anything
     *  was removed. */
    @Synchronized fun forget(what: String, exact: Boolean = false): Boolean {
        load()
        val w = key(what).ifEmpty { return false }
        val gone = map.entries.filter { (k, c) -> k == w || (!exact && (k.contains(w) || key(c.label).contains(w))) }.map { it.key }
        gone.forEach { map.remove(it) }
        if (gone.isNotEmpty()) { if (lastKey in gone) lastKey = null; save() }
        return gone.isNotEmpty()
    }

    /** "forget that (choice)" / "not that one": the choice used in the last [withinMs]; its label, or null. */
    @Synchronized fun forgetRecent(withinMs: Long = RECENT_MS): String? {
        load()
        val k = lastKey ?: return null
        if (clock() - lastAt > withinMs) return null
        val c = map.remove(k) ?: return null
        lastKey = null
        save()
        return c.label
    }

    @Synchronized fun clear() { load(); map.clear(); lastKey = null; lastValue = null; save() }

    @Synchronized fun size(): Int { load(); return map.size }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = file?.let { SecureStore.read(it) } ?: return
        try {
            val arr = JSONObject(text).optJSONArray("choices") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("referent")
                if (k.isEmpty()) continue
                map[k] = Choice(o.optString("kind"), o.optString("value"), o.optString("label"), o.optString("source"),
                    o.optInt("uses", 1), o.optLong("lastUsed"))
            }
        } catch (_: Throwable) { map.clear() }
    }

    private fun save() {
        val f = file ?: return
        if (map.isEmpty()) { try { f.delete() } catch (_: Throwable) {}; return }
        val arr = JSONArray()
        for ((k, c) in map) arr.put(JSONObject().put("referent", k).put("kind", c.kind).put("value", c.value)
            .put("label", c.label).put("source", c.source).put("uses", c.uses).put("lastUsed", c.lastUsed))
        SecureStore.write(f, JSONObject().put("v", 1).put("choices", arr).toString())   // false = no key: memory only
    }

    companion object {
        private const val MAX = 200
        private const val RECENT_MS = 10 * 60_000L
        private val FILLER = setOf("the", "my", "a", "an", "please", "website", "web", "site", "page", "homepage", "home",
            "official", "login", "log", "in", "signin", "sign", "online", "link", "for", "me", "to", "that", "choice", "one")

        /** A referent as a stable key: lower-case words, no punctuation, no filler ("the UG Sakai login page" → "ug sakai"). */
        fun key(referent: String): String = referent.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() && it !in FILLER }
            .joinToString(" ")
    }
}

/** The process-wide store, opened lazily on the app's files dir. */
object ResolvedChoices {
    @Volatile private var store: ChoiceStore? = null

    fun store(ctx: Context): ChoiceStore = store ?: synchronized(this) {
        store ?: ChoiceStore(File(ctx.applicationContext.filesDir, "memory/choices.enc")).also { store = it }
    }

    /** For tests: a store of their own. */
    internal fun use(s: ChoiceStore?) { store = s }
}
