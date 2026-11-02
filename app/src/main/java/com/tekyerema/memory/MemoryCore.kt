package com.tekyerema.memory

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/** The four kinds of thing the memory layer keeps. [spoken] is how a category is named aloud. */
enum class MemKind(val id: String, val spoken: String) {
    PREF("pref", "preferences"),
    CONTACT("contact", "contacts you use most"),
    TASK("task", "things you often ask"),
    FACT("fact", "things you told me");

    companion object { fun of(id: String?): MemKind? = values().firstOrNull { it.id == id } }
}

/**
 * One remembered thing. [told] = the user said it; false = learned from use. [count] = times seen (shown to the user),
 * [weight] = the same count with time decay applied at [lastAt] (what ranking uses). Contacts hold the NAME only (the key adds
 * the last 4 digits of the number, the way the app already reads a recipient aloud) — never a message, amount, balance or PIN.
 */
data class MemEntry(
    val kind: MemKind, val key: String, val value: String, val told: Boolean,
    val count: Int, val weight: Double, val firstAt: Long, val lastAt: Long
)

data class MemState(val entries: List<MemEntry> = emptyList(), val off: Set<MemKind> = emptySet())

/**
 * User memory — pure logic (no Android): what may be stored, ranking with decay, caps, forget/correct, and the encoded form.
 * The Android side ([UserMemory]) keeps one [MemState] and writes it through SecureStore (AES-GCM, Keystore key).
 */
object MemoryCore {
    const val DAY_MS = 86_400_000L
    const val HALF_LIFE_DAYS = 45.0          // a learned count halves every 45 days of not being used
    const val EXPIRE_DAYS = 180L             // a learned entry unused this long is dropped
    const val MIN_KEEP = 0.25                // ... and one whose decayed weight falls under this
    const val MAX_TOTAL = 200
    const val MAX_CHARS = 48_000             // hard size cap of the encoded file
    private val CAPS = mapOf(MemKind.PREF to 24, MemKind.CONTACT to 60, MemKind.TASK to 40, MemKind.FACT to 40)

    // ---------------------------------------------------------------- what is NEVER stored
    private val SECRET_WORD = Regex("""(?i)\b(pin|pins|password|passcode|passphrase|otp|cvv|cvc|secret|token|code|balance|account number|card number|amount|cedis?|ghs|pesewas?|dollars?|naira)\b|[₵$£€]""")
    private val TASK_SECRET = Regex("""(?i)\b(pin|pins|password|passcode|passphrase|otp|cvv|cvc|secret|token)\b|[₵$£€]""")
    private val LONG_DIGITS = Regex("""\d{4,}|(?:\d\D?){6,}""")
    /** Verbs that carry a person, a message body or money: such a request is never kept as a "common task". */
    private val TASK_VERB = Regex("""(?i)\b(send|sends|text|tell|say|reply|forward|share|post|pay|transfer|buy|call|dial|write|type|enter|delete|erase|remove|install|uninstall)\b""")

    fun safeFact(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.length <= 100 && !SECRET_WORD.containsMatchIn(t) && !LONG_DIGITS.containsMatchIn(t)
    }

    /** A line fit for the planner's "What I know" block: short, and no secret word, currency sign or long digit run (planner prompt guard). */
    fun safeForPrompt(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && t.length <= 140 && !SECRET_WORD.containsMatchIn(t) && !LONG_DIGITS.containsMatchIn(t)
    }

    /** A contact name: anything but a phone number saved as a name. */
    fun safeName(name: String): Boolean {
        val t = name.trim()
        return t.isNotEmpty() && t.length <= 60 && !Regex("""\d{5,}""").containsMatchIn(t.replace(Regex("""[\s\-]"""), ""))
    }

    /** A request wording worth keeping: short, no digits, no secret word, no verb that sends, pays, calls or deletes. */
    fun safeTask(phrase: String): Boolean {
        val t = phrase.trim()
        if (t.isEmpty() || t.length > 60 || t.split(Regex("""\s+""")).size > 7) return false
        return !t.any { it.isDigit() } && !TASK_SECRET.containsMatchIn(t) && !TASK_VERB.containsMatchIn(t)
    }

    fun check(kind: MemKind, key: String, value: String): Boolean = when (kind) {
        MemKind.CONTACT -> safeName(value)
        MemKind.TASK -> safeTask(value)
        MemKind.FACT, MemKind.PREF -> safeFact(key) && safeFact(value)
    }

    // ---------------------------------------------------------------- ranking, decay, caps
    /** The decayed weight of [e] as of [now]. */
    fun score(e: MemEntry, now: Long): Double =
        e.weight * 0.5.pow((now - e.lastAt).coerceAtLeast(0).toDouble() / (HALF_LIFE_DAYS * DAY_MS))

    fun contactKey(name: String, number: String): String {
        val tail = number.filter { it.isDigit() }.takeLast(4)
        return name.trim().lowercase() + if (tail.isNotEmpty()) "#$tail" else ""
    }

    /**
     * Note one use of ([kind], [key]): a new entry, or its count and weight go up. Unchanged when the value fails the
     * never-store filter, or when [kind] is switched off and this was only LEARNED (the user's own words still go in).
     */
    fun upsert(s: MemState, kind: MemKind, key: String, value: String, told: Boolean, now: Long): MemState {
        val k = key.trim().lowercase()
        val v = value.trim()
        if (k.isEmpty() || !check(kind, k, v)) return s
        if (!told && kind in s.off) return s
        val list = s.entries.toMutableList()
        val i = list.indexOfFirst { it.kind == kind && it.key == k }
        if (i >= 0) {
            val e = list[i]
            list[i] = e.copy(value = v, told = e.told || told, count = e.count + 1, weight = score(e, now) + 1.0, lastAt = now)
        } else list.add(MemEntry(kind, k, v, told, 1, 1.0, now, now))
        return prune(s.copy(entries = list), now)
    }

    /** Drop expired learned entries, then trim each kind and the whole to its cap (learned and lowest-weight go first). */
    fun prune(s: MemState, now: Long): MemState {
        var list = s.entries.filter { it.told || (score(it, now) >= MIN_KEEP && now - it.lastAt <= EXPIRE_DAYS * DAY_MS) }
        val order = compareBy<MemEntry>({ it.told }, { score(it, now) }, { it.lastAt })
        for (k in MemKind.values()) {
            val of = list.filter { it.kind == k }
            val cap = CAPS.getValue(k)
            if (of.size > cap) { val drop = of.sortedWith(order).take(of.size - cap).toSet(); list = list.filter { it !in drop } }
        }
        if (list.size > MAX_TOTAL) { val drop = list.sortedWith(order).take(list.size - MAX_TOTAL).toSet(); list = list.filter { it !in drop } }
        return s.copy(entries = list)
    }

    /** Contacts by decayed weight, most used first (those seen at least [minCount] times). */
    fun topContacts(s: MemState, now: Long, n: Int, minCount: Int = 2): List<MemEntry> =
        s.entries.filter { it.kind == MemKind.CONTACT && it.count >= minCount }.sortedByDescending { score(it, now) }.take(n)

    fun topTasks(s: MemState, now: Long, n: Int, minCount: Int = 2): List<MemEntry> =
        s.entries.filter { it.kind == MemKind.TASK && it.count >= minCount }.sortedByDescending { score(it, now) }.take(n)

    private const val MIN_TOP = 2.5       // a contact must have been used about 3 times (recently) to win a tie
    private const val DOMINANCE = 3.0     // ... and at least 3x as much as the runner-up

    /**
     * Of [hits] — contacts that fit [said] equally — the one used far more than the rest, else [hits] unchanged. An exact name
     * is never overridden, and the caller still reads the recipient back (name + last digits) before anything is done.
     */
    fun <T> preferFrequent(s: MemState, now: Long, said: String, hits: List<T>, nameOf: (T) -> String, numberOf: (T) -> String): List<T> {
        if (hits.size < 2 || MemKind.CONTACT in s.off) return hits
        val q = said.trim().lowercase().removePrefix("my ").trim()
        if (hits.any { nameOf(it).trim().lowercase() == q }) return hits
        val scored = hits.map { h ->
            val key = contactKey(nameOf(h), numberOf(h))
            h to (s.entries.firstOrNull { it.kind == MemKind.CONTACT && it.key == key }?.let { score(it, now) } ?: 0.0)
        }.sortedByDescending { it.second }
        val top = scored[0].second
        val runnerUp = scored[1].second
        return if (top >= MIN_TOP && top >= DOMINANCE * maxOf(runnerUp, 0.8)) listOf(scored[0].first) else hits
    }

    // ---------------------------------------------------------------- forget and switches
    fun removeEntry(s: MemState, kind: MemKind, key: String): MemState =
        s.copy(entries = s.entries.filterNot { it.kind == kind && it.key == key.trim().lowercase() })

    fun removeKind(s: MemState, kind: MemKind): Pair<MemState, Int> {
        val kept = s.entries.filter { it.kind != kind }
        return s.copy(entries = kept) to (s.entries.size - kept.size)
    }

    fun removeAbout(s: MemState, name: String): Pair<MemState, Int> {
        val kept = s.entries.filterNot { mentions(it.key.substringBefore('#'), name) || mentions(it.value, name) }
        return s.copy(entries = kept) to (s.entries.size - kept.size)
    }

    fun entriesAbout(s: MemState, name: String): List<MemEntry> =
        s.entries.filter { mentions(it.key.substringBefore('#'), name) || mentions(it.value, name) }

    fun withSwitch(s: MemState, kind: MemKind?, on: Boolean): MemState {
        val kinds = if (kind == null) MemKind.values().toSet() else setOf(kind)
        return s.copy(off = if (on) s.off - kinds else s.off + kinds)
    }

    /** Every word of [name] is a word of [text] ("Ama" is in "Ama Mensah" but not in "Kwama"). */
    fun mentions(text: String, name: String): Boolean {
        val want = words(name)
        return want.isNotEmpty() && words(text).containsAll(want)
    }
    private fun words(t: String): Set<String> = Regex("""[\p{L}\p{N}']+""").findAll(t.lowercase()).map { it.value }.toSet()

    // ---------------------------------------------------------------- speaking
    fun spokenEntry(e: MemEntry): String = when (e.kind) {
        MemKind.CONTACT -> "you often contact ${e.value}, ${e.count} times"
        MemKind.TASK -> "you often ask “${e.value}”"
        MemKind.FACT -> spokenFact(e.key, e.value)
        MemKind.PREF -> spokenPref(e.key, e.value)
    }

    fun spokenFact(subject: String, value: String): String = when {
        subject.startsWith("my ") -> "your ${subject.removePrefix("my ")} is $value"
        subject.startsWith("i am") -> "you are${subject.removePrefix("i am")} $value"
        subject.startsWith("i ") -> "you ${subject.removePrefix("i ")} $value"
        else -> "$subject $value"
    }

    fun spokenPref(key: String, value: String): String {
        val ctx = key.substringAfter(':', "")
        return when (key.substringBefore(':')) {
            "rate" -> when (value) { "slow" -> "you like me to speak slowly"; "fast" -> "you like me to speak fast"; else -> "you like me to speak at a normal pace" }
            "language" -> "you prefer ${if (value == "twi") "Twi" else "English"}" + if (ctx.isNotEmpty()) " for $ctx" else ""
            "verbosity" -> if (value == "brief") "you like short answers" else "you like more detail"
            // "channel:pearl asante:whatsapp" (PlannerMemory.channelKey): the app the user usually writes to that person on.
            "channel" -> "you usually message ${ctx.substringBeforeLast(':')} on $value"
            else -> "$key is $value"
        }
    }

    /** The lines read for "what do you remember about me": told things first, then what was learned. */
    fun summaryLines(s: MemState, relations: Map<String, String>, preferredName: String, now: Long): List<String> {
        val out = ArrayList<String>()
        if (preferredName.isNotBlank()) out.add("you like to be called $preferredName")
        relations.forEach { (r, n) -> out.add("your $r is $n") }
        s.entries.filter { it.kind == MemKind.PREF && (it.key.substringBefore(':') != "channel" || it.count >= 2) }.sortedBy { it.firstAt }.forEach { out.add(spokenEntry(it)) }
        s.entries.filter { it.kind == MemKind.FACT }.sortedBy { it.firstAt }.forEach { out.add(spokenEntry(it)) }
        topContacts(s, now, 5).forEach { out.add(spokenEntry(it)) }
        topTasks(s, now, 3).forEach { out.add(spokenEntry(it)) }
        MemKind.values().filter { it in s.off }.forEach { out.add("I'm not keeping track of ${it.spoken}") }
        return out
    }

    /** What is remembered about [name]: relations and entries that mention them. */
    fun aboutLines(s: MemState, relations: Map<String, String>, name: String): List<String> {
        val out = ArrayList<String>()
        relations.forEach { (r, n) -> if (mentions(n, name) || mentions(r, name)) out.add("your $r is $n") }
        entriesAbout(s, name).forEach { out.add(spokenEntry(it)) }
        return out
    }

    /** One spoken page: up to [cap] lines from [from], and the index of the rest (== lines.size when done). */
    fun page(lines: List<String>, from: Int, cap: Int = 4): Pair<String, Int> {
        val end = minOf(lines.size, from + cap)
        return lines.subList(from, end).joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } } + "." to end
    }

    // ---------------------------------------------------------------- the stored form
    fun encode(s: MemState): String {
        var entries = s.entries
        while (true) {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().put("k", e.kind.id).put("key", e.key).put("val", e.value).put("t", if (e.told) 1 else 0)
                    .put("n", e.count).put("w", e.weight).put("f", e.firstAt).put("l", e.lastAt))
            }
            val text = JSONObject().put("v", 1).put("off", JSONArray(s.off.map { it.id })).put("e", arr).toString()
            if (text.length <= MAX_CHARS || entries.isEmpty()) return text
            // Over the hard cap: the oldest learned entry (else the oldest of all) goes first.
            val victim = entries.filter { !it.told }.minByOrNull { it.lastAt } ?: entries.minByOrNull { it.lastAt }
            entries = entries - victim!!
        }
    }

    /** The state in [text], or null when it is not a memory file. Bad entries are skipped, never fatal. */
    fun decode(text: String?): MemState? {
        if (text.isNullOrBlank()) return null
        return try {
            val o = JSONObject(text)
            val off = HashSet<MemKind>()
            o.optJSONArray("off")?.let { a -> for (i in 0 until a.length()) MemKind.of(a.optString(i))?.let { off.add(it) } }
            val list = ArrayList<MemEntry>()
            o.optJSONArray("e")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.optJSONObject(i) ?: continue
                    val kind = MemKind.of(j.optString("k")) ?: continue
                    val key = j.optString("key"); val value = j.optString("val")
                    if (key.isBlank() || value.isBlank()) continue
                    list.add(MemEntry(kind, key, value, j.optInt("t") == 1, j.optInt("n", 1), j.optDouble("w", 1.0),
                        j.optLong("f"), j.optLong("l")))
                }
            }
            MemState(list, off)
        } catch (_: Throwable) { null }
    }
}
