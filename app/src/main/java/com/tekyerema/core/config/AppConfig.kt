package com.tekyerema.core.config

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * X-02/X-09/X-14 — every tunable (thresholds, timeouts, model names, phrases, aliases, rule tables) lives in
 * versioned JSON files under `assets/config/` instead of code. Files are merged into one tree by their top-level
 * keys (e.g. `agent.json` → `{"agent": {...}}`), then a local override document is applied on top (X-13 — set
 * over ADB in debug builds; a remote-config source can feed the same override later).
 *
 * Reads are by dotted key path with a caller-supplied default, so a missing file or key NEVER breaks behaviour:
 * the code's default is the fallback, the file is the source of truth when present.
 *
 *   AppConfig.int("agent.limits.maxSteps", 30)
 *   AppConfig.stringList("router.quickTiles.dnd.phrases", listOf("do not disturb"))
 *
 * Pure apart from [load] (asset + prefs I/O) → unit-tested via [loadFrom].
 */
object AppConfig {
    /** Bump when a file's shape changes; [migrate] upgrades older files on load (X-14). */
    const val SCHEMA_VERSION = 1
    private const val TAG = "Tekyerema"
    private const val PREFS = "tekyerema_config_overrides"
    private const val KEY_OVERRIDES = "overrides_json"

    @Volatile private var root: JSONObject = JSONObject()
    @Volatile var loadedFiles: List<String> = emptyList(); private set

    /** Load every JSON file under `assets/config` and the local override document. */
    fun load(ctx: Context) {
        val texts = ArrayList<Pair<String, String>>()
        fun readDir(dir: String) {
            val names = try { ctx.assets.list(dir)?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
            for (n in names.sorted()) if (n.endsWith(".json")) try {
                texts.add("$dir/$n" to ctx.assets.open("$dir/$n").bufferedReader().use { it.readText() })
            } catch (t: Throwable) { Log.w(TAG, "[CONFIG] can't read $dir/$n: ${t.message}") }
        }
        readDir("config")
        val overrides = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OVERRIDES, null)
        loadFrom(texts, overrides)
        Log.i(TAG, "[CONFIG] loaded ${loadedFiles.size} files ${loadedFiles}${if (overrides != null) " + overrides" else ""}")
    }

    /** Pure core: merge [files] (name to JSON text) in order, then deep-merge [overridesJson]. Bad files are skipped. */
    fun loadFrom(files: List<Pair<String, String>>, overridesJson: String? = null) {
        val merged = JSONObject()
        val ok = ArrayList<String>()
        for ((name, text) in files) {
            val doc = try { JSONObject(text) } catch (t: Throwable) { Log.w(TAG, "[CONFIG] $name is not valid JSON: ${t.message}"); continue }
            val migrated = migrate(doc, name)
            migrated.remove("schema_version")
            deepMerge(merged, migrated)
            ok.add(name)
        }
        if (!overridesJson.isNullOrBlank()) try { deepMerge(merged, JSONObject(overridesJson)) }
            catch (t: Throwable) { Log.w(TAG, "[CONFIG] overrides are not valid JSON: ${t.message}") }
        root = merged
        loadedFiles = ok
    }

    /** DEBUG/remote: persist an override document (deep-merged over the files) and reload. */
    fun setOverrides(ctx: Context, json: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OVERRIDES, json?.ifBlank { null }).apply()
        load(ctx)
    }

    /** X-14: upgrade steps, keyed by the version they upgrade FROM (n → n+1). v1 is the first shape, so none yet. */
    private val migrations: Map<Int, (JSONObject) -> JSONObject> = emptyMap()

    /** X-14: upgrade an older document to [SCHEMA_VERSION] step by step; a newer one is read best-effort. */
    private fun migrate(doc: JSONObject, name: String): JSONObject {
        var v = doc.optInt("schema_version", SCHEMA_VERSION)
        if (v > SCHEMA_VERSION) Log.w(TAG, "[CONFIG] $name has schema_version $v > supported $SCHEMA_VERSION — reading best-effort")
        var d = doc
        while (v < SCHEMA_VERSION) { d = migrations[v]?.invoke(d) ?: d; v++ }
        return d
    }

    private fun deepMerge(into: JSONObject, from: JSONObject) {
        val keys = from.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = from.get(k)
            val existing = into.opt(k)
            if (v is JSONObject && existing is JSONObject) deepMerge(existing, v) else into.put(k, v)
        }
    }

    /** The raw node at a dotted path, or null. */
    fun node(path: String): Any? {
        var cur: Any? = root
        for (part in path.split('.')) {
            cur = (cur as? JSONObject)?.opt(part) ?: return null
            if (cur == JSONObject.NULL) return null
        }
        return cur
    }

    fun has(path: String) = node(path) != null

    /** A copy of the whole merged tree (debug dumps). */
    fun snapshot(): JSONObject = JSONObject(root.toString())
    fun int(path: String, def: Int): Int = (node(path) as? Number)?.toInt() ?: def
    fun long(path: String, def: Long): Long = (node(path) as? Number)?.toLong() ?: def
    fun float(path: String, def: Float): Float = (node(path) as? Number)?.toFloat() ?: def
    fun double(path: String, def: Double): Double = (node(path) as? Number)?.toDouble() ?: def
    fun bool(path: String, def: Boolean): Boolean = node(path) as? Boolean ?: def
    fun string(path: String, def: String): String = (node(path) as? String) ?: def
    fun obj(path: String): JSONObject? = node(path) as? JSONObject

    fun stringList(path: String, def: List<String>): List<String> {
        val a = node(path) as? JSONArray ?: return def
        return (0 until a.length()).mapNotNull { a.opt(it) as? String }
    }

    /** A map of string lists, e.g. `{"dnd": ["do not disturb", "dnd"]}`. */
    fun stringListMap(path: String): Map<String, List<String>> {
        val o = obj(path) ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (k in o.keys()) out[k] = (o.opt(k) as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
        return out
    }

    /** A spoken template with `{name}` slots: `AppConfig.phrase("ux.phrases.appNotFound", "I couldn't find {app}.", "app" to "Zorblax")`. */
    fun phrase(path: String, def: String, vararg slots: Pair<String, String>): String {
        var s = string(path, def)
        for ((k, v) in slots) s = s.replace("{$k}", v)
        return s
    }
}
