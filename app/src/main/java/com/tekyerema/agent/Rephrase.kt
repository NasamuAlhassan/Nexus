package com.tekyerema.agent

import org.json.JSONObject

/**
 * A rephrase means the last try was wrong (owner, 3 Nov 2026; research: docs/agent/research/agent_invariants_deep_2026-11-03.md
 * I5, speech_repair_deep_2026-11-03.md — implicit feedback: a user who says it again, or says "no, I said…", is telling the
 * assistant it got it wrong).
 *
 * The owner's phone showed the same pattern again and again: the request went wrong, the owner said it again in other words,
 * and the assistant did the same wrong thing again. So:
 *  - each task's last actions (or its spoken answer) are remembered for a short while;
 *  - a new request within [WINDOW_MS] that corrects it ("no…", "I said…", "that's not what I asked") or says much the same
 *    thing in other words is marked as a REDO: the brain is told what was done last time and that it wasn't right;
 *  - the same action is not repeated as the first move of the redo without asking ([Invariants]).
 * A short command said again ("volume up", "next") is just a command again, never a redo. Pure but the clock → RephraseTest.
 */
object Rephrase {
    private class Last(val goal: String, val at: Long, val actions: List<String>, val reply: String?)

    @Volatile private var last: Last? = null
    @Volatile private var redo: Pair<String, Set<String>>? = null   // the redo goal → the action signatures not to repeat

    private const val WINDOW_MS = 90_000L
    private val CUE = Regex("(?i)^(?:no+\\b|nope\\b|not (?:that|this|what)\\b|that'?s not\\b|that is not\\b|i said\\b|i meant\\b|" +
        "i asked\\b|wrong\\b|you got it wrong\\b|that'?s wrong\\b|listen\\b|again\\b|i didn'?t (?:say|ask|mean)\\b)")
    private val STOP = setOf("the", "a", "an", "to", "my", "me", "i", "you", "it", "is", "of", "for", "on", "in", "and", "please",
        "can", "could", "would", "will", "do", "what", "that", "this", "just", "now", "so", "okay", "ok", "hey", "nexus", "with", "at")
    /** Reads that don't change anything: never "the wrong action". */
    private val OBSERVATION = setOf("read_state", "read_screen", "describe_screen", "find_tool", "wait", "ask_user", "done",
        "cannot_do", "scroll", "key_event")

    /** What this task has done so far ([history] lines "name(args) → …") and its answer, if any. Called every step. */
    @Synchronized
    fun observe(goal: String, history: List<String>, reply: String? = null, now: Long = System.currentTimeMillis()) {
        val acts = history.filter { l -> SIG.find(l)?.let { it.groupValues[1] !in OBSERVATION } == true }
            .map { it.substringBefore(" →").trim() }
        val prev = last
        last = Last(goal.trim(), now, acts.takeLast(4), reply ?: prev?.takeIf { it.goal == goal.trim() }?.reply)
    }

    /**
     * The line for the new task's history when [goal] redoes the last task, null otherwise. Also arms the
     * no-repeat check for this goal.
     */
    @Synchronized
    fun noteFor(goal: String, now: Long = System.currentTimeMillis()): String? {
        redo = null
        val l = redoOf(goal, now) ?: return null
        redo = goal.trim() to l.actions.toSet()
        val did = when {
            l.actions.isNotEmpty() -> "I did: " + l.actions.joinToString("; ") { it.take(120) }
            else -> "I answered: “" + l.reply!!.take(160) + "”"
        }
        return "THE USER IS SAYING IT AGAIN: just before, for “${l.goal.take(120)}”, $did. That was NOT what they wanted. " +
            "Read their words afresh (a misheard word? a different meaning?) and do something different — never the same " +
            "action again. If you can't tell what they mean, ask one short question."
    }

    /** [goal] says the last request again (corrected, or in other words) — the quick rules then leave it to a brain. */
    @Synchronized
    fun isRedo(goal: String, now: Long = System.currentTimeMillis()): Boolean = redoOf(goal, now) != null

    private fun redoOf(goal: String, now: Long): Last? {
        val l = last ?: return null
        val g = goal.trim()
        if (now - l.at > WINDOW_MS || g.isEmpty() || g.equals(l.goal, ignoreCase = true) && words(g).size <= 4) return null
        if (!CUE.containsMatchIn(g) && similarity(g, l.goal) < 0.5) return null
        if (l.actions.isEmpty() && l.reply.isNullOrBlank()) return null
        // "No, that's not a summary. Text Prince: Richmond is asking when we are meeting." — a request restated WITH its own new
        // words is a new explicit request, not a redo of the same thing (A20e 8 Nov 2026: it was blocked and re-planned elsewhere).
        val nb = dictatedBody(g)
        if (nb != null && !MessageFidelity.sameWords(nb, dictatedBody(l.goal).orEmpty())) return null
        return l
    }

    /** The message the request dictates, from the first sentence that is itself a messaging request (after any cue). */
    internal fun dictatedBody(goal: String): String? = goal.split(Regex("(?<=[.!?])\\s+"))
        .firstOrNull { com.tekyerema.voice.understand.MessageBody.isMessaging(it) }?.let { MessageFidelity.dictated(it)?.raw }

    /** [name]/[args] would repeat what the last try did, in a redo of it. */
    @Synchronized
    fun repeats(goal: String, name: String, args: JSONObject): Boolean {
        val r = redo ?: return false
        if (!r.first.equals(goal.trim(), ignoreCase = true) || name in OBSERVATION) return false
        // The same tool with different words is a different action ("text Prince: <new body>").
        val msg = args.optString("message", args.optString("text")).trim()
        if (msg.isNotEmpty() && r.second.none { it.contains(msg.take(40)) }) return false
        val sig = "$name(${args.toString().take(160)})"
        return r.second.any { it == sig || it.startsWith(sig) || sig.startsWith(it) }
    }

    fun clear() { last = null; redo = null }

    private val SIG = Regex("^([a-z_]+)\\(")

    internal fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in STOP }

    /** Share of content words in common (Jaccard). */
    internal fun similarity(a: String, b: String): Double {
        val x = words(a).toSet(); val y = words(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }
}
