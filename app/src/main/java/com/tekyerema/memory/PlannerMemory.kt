package com.tekyerema.memory

import com.tekyerema.android.Relations
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * "What I know" for the planner (MASTER_STATUS #19): the few lines of the user's own memory that matter to THIS goal, in the
 * exact wording the on-phone model was trained and scored on (campaign/agent_bench/cases/memory.jsonl, rendered by
 * bench.py user_turn and by [com.tekyerema.agent.llm.PlannerPrompt] under the header "What I know (the user's own saved memory: ...)":
 * `"my wife" is the contact Pearl Asante`, `Pearl Asante is usually messaged on WhatsApp`, `prefers Bolt over Uber for rides`,
 * `told on 20 Oct: the Data Structures exam is on Monday 3 Nov at 9:00 AM`). Only lines come from here; the header is the prompt's.
 *
 * The block goes to the ON-PHONE planner only ([forGoal]). A cloud brain gets it only through [forCloud], which is empty unless the
 * user switched on [com.tekyerema.settings.Settings.shareMemoryWithCloud]. Never in a line: PINs, OTPs, passwords, amounts,
 * balances, message text — [MemoryCore.safeForPrompt] is the last gate on every line, over what [MemoryCore.upsert] already refuses.
 *
 * [build] is pure (unit-tested: PlannerMemoryTest); [forGoal] / [forCloud] are the Android glue around the stores.
 */
object PlannerMemory {
    const val MAX_LINES = 10
    private const val LINE_MAX = 120
    /** A told fact about a date ("exam on Monday 3 Nov") is stale this long after it was told. */
    private const val DATED_FACT_DAYS = 30L
    private const val CHANNEL_MIN_USES = 2

    /** The key a person's usual app is kept under: "channel:pearl asante:whatsapp". */
    fun channelKey(name: String, app: String): String = "channel:${name.trim().lowercase()}:${app.trim().lowercase()}"

    private class Line(val text: String, val score: Int)

    private val WORD = Regex("""[\p{L}\p{N}']+""")
    private val STOP = setOf("the", "and", "for", "with", "that", "this", "from", "have", "has", "was", "are", "you", "your", "me",
        "my", "our", "his", "her", "him", "she", "they", "them", "what", "when", "where", "who", "how", "can", "will", "please",
        "tell", "say", "get", "let", "know", "now", "again", "about", "into", "some", "any", "not", "but", "then", "just", "its")
    /** Words that point at the same thing for the planner: a preference for "rides" is relevant to "get me a taxi". */
    private val SYNONYMS = listOf(
        setOf("ride", "taxi", "cab", "car", "trip", "bolt", "uber", "yango"),
        setOf("music", "song", "play", "playlist", "listen"),
        setOf("food", "eat", "order", "delivery", "dinner", "lunch", "breakfast"),
        setOf("pay", "payment", "momo", "money", "transfer"),
        setOf("message", "text", "chat", "whatsapp", "telegram", "sms")
    )
    private val DATED = Regex("""(?i)\b(mon|tues?|wed(?:nes)?|thu(?:rs)?|fri|sat(?:ur)?|sun)(?:day)?\b|\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b|\b(today|tomorrow|tonight|this week|next week)\b""")

    private fun stem(w: String): String = w.lowercase().removeSuffix("'s").removeSuffix("’s").let { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }

    private fun terms(text: String): Set<String> =
        WORD.findAll(text).map { stem(it.value) }.filter { it.length >= 3 && it !in STOP }.toSet()

    /** [t] and every word that means about the same. */
    private fun expand(t: Set<String>): Set<String> = t + SYNONYMS.filter { g -> t.any { it in g } }.flatten()

    private fun overlaps(goalTerms: Set<String>, thing: String): Boolean {
        val g = expand(goalTerms)
        return expand(terms(thing)).any { it in g }
    }

    private fun clip(s: String): String = if (s.length <= LINE_MAX) s else s.take(LINE_MAX - 1).trimEnd() + "…"

    /**
     * The lines for [goal], most relevant first, at most [max]. [state] is the encrypted memory, [relations] "my wife" → Pearl,
     * [skills] the taught skills' names. Empty when nothing in memory touches the goal (the prompt then has no block at all).
     */
    fun build(
        goal: String, state: MemState, relations: Map<String, String>, skills: List<String>, now: Long,
        canonical: (String) -> String? = Relations::canonical,
        zone: TimeZone = TimeZone.getDefault(), max: Int = MAX_LINES
    ): List<String> {
        if (goal.isBlank() || max <= 0) return emptyList()
        val words = WORD.findAll(goal).map { it.value }.toList()
        val goalTerms = terms(goal)
        val out = ArrayList<Line>()
        val people = LinkedHashMap<String, Int>()   // person's name (as stored) → score of the line that named them

        // 1. People the goal names by relation ("my wife", "Mum").
        val seenRelations = HashSet<String>()
        var i = 0
        while (i < words.size) {
            if (words[i].equals("my", true)) { i++; continue }   // "my wife": the pair is taken from "wife", with the "my" shown
            val two = if (i + 1 < words.size) words[i] + " " + words[i + 1] else null
            val take2 = two != null && canonical(two)?.let { relations[it] } != null
            val said = if (take2) two!! else words[i]
            val rel = canonical(said)
            val name = rel?.let { relations[it] }
            if (rel != null && name != null && rel != "helper" && seenRelations.add(rel) && MemoryCore.safeName(name)) {
                val shown = if (i > 0 && words[i - 1].equals("my", true)) "my $said".lowercase() else said
                out.add(Line("\"$shown\" is the contact $name", 100))
                people.putIfAbsent(name, 100)
            }
            i += if (take2) 2 else 1
        }

        // 2. People the goal names by first name, from the contacts the user uses most ("Richmond" usually means Richmond Boateng).
        if (MemKind.CONTACT !in state.off) {
            val contacts = state.entries.filter { it.kind == MemKind.CONTACT }
            for (said in words.distinctBy { it.lowercase() }) {
                val w = said.lowercase()
                if (w.length < 3 || w in STOP) continue
                // One saved contact whose first name this is (two "Richmond"s are a question for the planner, not a memory).
                val hits = contacts.filter { c -> c.value.trim().contains(' ') && c.value.trim().lowercase().substringBefore(' ') == w }
                    .distinctBy { it.value.trim().lowercase() }
                if (hits.size != 1) continue
                val full = hits[0].value.trim()
                if (!MemoryCore.safeName(full)) continue
                val first = people.putIfAbsent(full, 80) == null
                if (first && !goal.contains(full, ignoreCase = true)) out.add(Line("\"$said\" usually means $full", 80))
            }
            // The full name said as it is saved: no "means" line, but their usual app is still wanted.
            for (c in contacts) if (c.value.trim().contains(' ') && goal.contains(c.value.trim(), ignoreCase = true)) people.putIfAbsent(c.value.trim(), 80)
        }

        // 3. The app each of those people is usually written to on (kept by PhoneTools via UserMemory.noteChannel).
        if (MemKind.PREF !in state.off) for ((name, score) in people) {
            val prefix = "channel:${name.lowercase()}:"
            val best = state.entries.filter { it.kind == MemKind.PREF && it.key.startsWith(prefix) && (it.told || it.count >= CHANNEL_MIN_USES) }
                .maxByOrNull { MemoryCore.score(it, now) } ?: continue
            val app = best.value.trim()
            out.add(Line(if (app.equals("sms", true)) "$name is usually texted by SMS" else "$name is usually messaged on $app", score - 1))
        }

        // 4. Told facts that touch the goal, unless they are dated and stale.
        if (MemKind.FACT !in state.off) for (e in state.entries.filter { it.kind == MemKind.FACT }.sortedByDescending { it.lastAt }) {
            val text = e.key + " " + e.value
            if (!overlaps(goalTerms, text)) continue
            if (DATED.containsMatchIn(text) && now - e.lastAt > DATED_FACT_DAYS * MemoryCore.DAY_MS) continue
            out.add(Line("told on ${day(e.lastAt, zone)}: ${factSentence(e.key, e.value)}", 60))
        }

        // 5. Preferences that touch the goal (never speech rate, language or verbosity: the app already applies those).
        if (MemKind.PREF !in state.off) for (e in state.entries.filter { it.kind == MemKind.PREF }) {
            val head = e.key.substringBefore(':')
            if (head in SETTING_PREFS || head == "channel") continue
            if (!overlaps(goalTerms, e.key.replace(':', ' ') + " " + e.value)) continue
            out.add(Line("prefers ${e.value} for ${e.key.replace(':', ' ')}", 50))
        }

        // 6. Skills the user taught that the goal looks like (the names only).
        skills.filter { overlaps(goalTerms, it) }.take(2).forEach { out.add(Line("taught skill: \"$it\"", 40)) }

        return out.withIndex().sortedWith(compareByDescending<IndexedValue<Line>> { it.value.score }.thenBy { it.index })
            .map { clip(it.value.text) }.filter { MemoryCore.safeForPrompt(it) }.distinct().take(max)
    }

    private val SETTING_PREFS = setOf("rate", "language", "verbosity")

    private fun day(t: Long, zone: TimeZone): String =
        SimpleDateFormat("d MMM", Locale.ENGLISH).also { it.timeZone = zone }.format(java.util.Date(t))

    /** A told fact as a sentence about the user: "my exam" + "is on Monday" → "the user's exam is on Monday". */
    internal fun factSentence(subject: String, value: String): String {
        val s = subject.trim().lowercase()
        val v = value.trim()
        return when {
            s.startsWith("note:") -> "the user said: $v"
            s.startsWith("my ") -> "the user's ${s.removePrefix("my ")} is $v"
            s.startsWith("i am") -> "the user is ${s.removePrefix("i am").trim()} $v".replace("  ", " ")
            s.startsWith("i ") -> "the user says: I ${s.removePrefix("i ")} $v"
            else -> "$s $v"
        }
    }

    /** The memory lines first, then [hints] (notes of past mistakes): together at most [max]; a hint is never crowded out. */
    fun merge(memory: List<String>, hints: List<String>, max: Int = MAX_LINES): List<String> {
        val h = hints.filter { it.isNotBlank() }.take(max)
        return (memory.take((max - h.size).coerceAtLeast(0)) + h).distinct()
    }

    // ---------------------------------------------------------------- Android glue

    /** Lines for the on-phone planner. Empty off the phone, in a secure step, or when `memory.planner.enabled` is false. Never throws. */
    fun forGoal(goal: String): List<String> {
        return try {
            if (com.tekyerema.core.Privacy.secureStep) return emptyList()
            if (!com.tekyerema.core.config.AppConfig.bool("memory.planner.enabled", true)) return emptyList()
            val ctx = com.tekyerema.agent.AgentEngine.appContext() ?: return emptyList()
            UserMemory.ensureLoaded(ctx)
            Relations.ensureLoaded(ctx)
            val store = com.tekyerema.teach.TeachStore.shared
            store.ensureLoaded(ctx)
            val lines = build(goal, UserMemory.snapshot(), Relations.all(), store.names(), System.currentTimeMillis())
            android.util.Log.i("Tekyerema", "[PLANNER_MEMORY] ${lines.size} lines")   // a count, never the lines
            lines
        } catch (_: Throwable) { emptyList() }
    }

    /** Seams for the tests: the cloud flag and the line source. */
    @Volatile internal var shareWithCloud: () -> Boolean = {
        try { com.tekyerema.agent.AgentEngine.appContext()?.let { com.tekyerema.settings.Settings(it).shareMemoryWithCloud } ?: false }
        catch (_: Throwable) { false }
    }
    @Volatile internal var source: (String) -> List<String> = { forGoal(it) }

    /** Lines for a CLOUD brain: empty unless the user turned "share memory with the cloud assistant" on. Off by default. */
    fun forCloud(goal: String): List<String> = try { if (shareWithCloud()) source(goal) else emptyList() } catch (_: Throwable) { emptyList() }

    /** The cloud prompt's section: "" when [forCloud] is empty. */
    fun cloudSection(goal: String): String {
        val lines = forCloud(goal)
        if (lines.isEmpty()) return ""
        return "WHAT I KNOW (the user's own saved memory, shared with you because they chose to; actions still get the usual confirmation):\n" +
            lines.joinToString("\n") { "- $it" } + "\n\n"
    }
}
