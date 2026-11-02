package com.tekyerema.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * One remembered name for an unlabelled control. [told] = the user said it; false = learned from a verified outcome.
 * [appName] is how the user calls the app ("WhatsApp"), kept so "forget the button names for WhatsApp" can find it.
 */
data class LabelEntry(
    val sig: LabelSig,
    val name: String,
    val appName: String?,
    val told: Boolean,
    val createdAt: Long,
    val lastUsed: Long
)

/**
 * Label memory — pure logic (no Android): which names may be kept, caps, expiry, forgetting, the stored form and the
 * spoken list. [LabelStore] keeps the list in one encrypted file. Only the NAME of a button, the app, and hashes ever
 * go in — never a screen's words, a message, a number, a code or an amount ([cleanName]).
 */
object LabelCore {
    const val DAY_MS = 86_400_000L
    const val MAX_TOTAL = 120
    const val MAX_PER_APP = 30
    const val EXPIRE_DAYS = 365L          // a name learned from use and never told, unused this long, is dropped
    const val MAX_CHARS = 40_000

    private val NAME_OK = Regex("""^\p{L}[\p{L}'\-]*(?: \p{L}[\p{L}'\-]*){0,2}$""")
    /** Remarks about a button, not names for it ("that button is broken"). */
    private val NOT_A_NAME = setOf(
        "broken", "stuck", "small", "big", "tiny", "huge", "slow", "wrong", "useless", "hidden", "missing", "confusing", "there",
        "here", "not working", "working", "bad", "good", "fine", "ok", "okay", "yes", "no", "it", "this", "that", "the", "a", "an",
        "button", "icon", "unnamed", "unlabelled", "unlabeled", "nameless", "nothing", "something", "again", "grey", "gray", "red",
        "blue", "green", "white", "black", "disabled", "off", "on", "first", "second", "third", "fourth", "fifth", "last", "right",
        "left", "correct", "only", "same", "other", "new", "old", "next", "previous"
    )

    /** The name as it will be kept and spoken ("search button" -> "Search"), or null when it is not a plain one to three word name. */
    fun cleanName(raw: String?): String? {
        var t = raw?.trim()?.trim('"', '“', '”', '\'', '’', '.', '!', '?', ',')?.replace(Regex("""\s+"""), " ") ?: return null
        t = t.replace(Regex("""(?i)\s+(?:button|icon)$"""), "").trim()
        if (t.length !in 2..24 || !NAME_OK.matches(t)) return null
        if (t.lowercase() in NOT_A_NAME) return null
        if (!MemoryCore.safeFact(t)) return null                       // codes, PINs, amounts, money words
        if (com.tekyerema.core.Injection.detect(t) != null) return null // wording aimed at an AI
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    fun sameControl(a: LabelSig, b: LabelSig): Boolean = a == b || LabelSignature.matches(a, b)

    /**
     * Add [entry], or replace the entry for the same control. A name learned from use never overwrites one the user told
     * (unless it is the same name); the user's own word always wins.
     */
    fun upsert(list: List<LabelEntry>, entry: LabelEntry, now: Long): List<LabelEntry> {
        val same = list.filter { sameControl(it.sig, entry.sig) }
        if (same.any { it.told } && !entry.told && same.none { it.name.equals(entry.name, true) }) return list
        val first = same.minByOrNull { it.createdAt }
        val merged = entry.copy(createdAt = first?.createdAt ?: entry.createdAt, told = entry.told || same.any { it.told && it.name.equals(entry.name, true) })
        return prune(list.filterNot { it in same } + merged, now)
    }

    /** Drop expired learned entries, then trim each app and the whole to the caps (learned and least recently used go first). */
    fun prune(list: List<LabelEntry>, now: Long): List<LabelEntry> {
        var out = list.filter { it.told || now - it.lastUsed <= EXPIRE_DAYS * DAY_MS }
        val order = compareBy<LabelEntry>({ it.told }, { it.lastUsed })
        for ((_, group) in out.groupBy { it.sig.pkg }) {
            if (group.size > MAX_PER_APP) { val drop = group.sortedWith(order).take(group.size - MAX_PER_APP).toSet(); out = out.filter { it !in drop } }
        }
        if (out.size > MAX_TOTAL) { val drop = out.sortedWith(order).take(out.size - MAX_TOTAL).toSet(); out = out.filter { it !in drop } }
        return out
    }

    fun remove(list: List<LabelEntry>, sig: LabelSig): List<LabelEntry> = list.filterNot { sameControl(it.sig, sig) }

    /** "forget the button names for WhatsApp": every entry of the app [said] names (by the user's name for it, or its package). */
    fun forgetApp(list: List<LabelEntry>, said: String): Pair<List<LabelEntry>, Int> {
        val flat = said.lowercase().filter { it.isLetterOrDigit() }
        val kept = list.filterNot { e ->
            (e.appName != null && MemoryCore.mentions(e.appName, said)) || (flat.length >= 3 && e.sig.pkg.lowercase().contains(flat))
        }
        return kept to (list.size - kept.size)
    }

    /** Lines for "what button names do you know": one per app, the names in it. [app] limits it to that app. */
    fun spokenLines(list: List<LabelEntry>, app: String? = null): List<String> {
        val flat = app?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()
        return list.filter { e -> app == null || (e.appName != null && MemoryCore.mentions(e.appName, app)) || (flat.length >= 3 && e.sig.pkg.lowercase().contains(flat)) }
            .groupBy { it.appName ?: "an app" }
            .map { (name, es) -> "in $name you named ${joinNames(es.map { it.name }.distinct())}" }
    }

    private fun joinNames(n: List<String>): String = when (n.size) {
        1 -> n[0]
        2 -> "${n[0]} and ${n[1]}"
        else -> n.dropLast(1).joinToString(", ") + " and " + n.last()
    }

    // ---------------------------------------------------------------- the stored form
    fun encode(list: List<LabelEntry>): String {
        var entries = list
        while (true) {
            val arr = JSONArray()
            entries.forEach { e ->
                val s = e.sig
                arr.put(JSONObject().put("n", e.name).put("app", e.appName ?: "").put("t", if (e.told) 1 else 0)
                    .put("c", e.createdAt).put("l", e.lastUsed)
                    .put("pkg", s.pkg).put("id", s.viewId ?: "").put("r", s.role).put("h", s.hBand).put("v", s.vBand)
                    .put("a", s.anchor ?: "").put("o", s.ordinal).put("g", s.groupSize).put("s", s.screen ?: ""))
            }
            val text = JSONObject().put("v", 1).put("e", arr).toString()
            if (text.length <= MAX_CHARS || entries.isEmpty()) return text
            val victim = entries.filter { !it.told }.minByOrNull { it.lastUsed } ?: entries.minByOrNull { it.lastUsed }
            entries = entries - victim!!
        }
    }

    /** The entries in [text], or null when it is not a label file. Bad entries are skipped, never fatal. */
    fun decode(text: String?): List<LabelEntry>? {
        if (text.isNullOrBlank()) return null
        return try {
            val a = JSONObject(text).optJSONArray("e") ?: return emptyList()
            val out = ArrayList<LabelEntry>()
            for (i in 0 until a.length()) {
                val j = a.optJSONObject(i) ?: continue
                val name = j.optString("n"); val pkg = j.optString("pkg"); val role = j.optString("r")
                if (name.isBlank() || pkg.isBlank() || role.isBlank()) continue
                val sig = LabelSig(pkg, j.optString("id").ifBlank { null }, role, j.optInt("h"), j.optInt("v"),
                    j.optString("a").ifBlank { null }, j.optInt("o"), j.optInt("g"), j.optString("s").ifBlank { null })
                out.add(LabelEntry(sig, name, j.optString("app").ifBlank { null }, j.optInt("t") == 1, j.optLong("c"), j.optLong("l")))
            }
            out
        } catch (_: Throwable) { null }
    }
}
