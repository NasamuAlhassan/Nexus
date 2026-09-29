package com.tekyerema.agent.recovery

import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig

/**
 * Workstream B — the recovery ladder (docs/agent/research/agenticness_mobile_agents.md §6; AndroidArena's exploration
 * prompt, Agent S2's mixture of grounding). "Try a DIFFERENT approach" left the model to guess what different means and
 * it tended to repeat itself. Now the ENGINE picks the next strategy for the failure class and performs what it can
 * itself — and the model is told which rung it is on and what was already tried:
 *
 *   RE_RESOLVE  → the closest items on screen to what it asked for (engine computes them)
 *   SCROLL_FIND → a goal-directed scroll toward it (engine queues it)
 *   OCR         → the next look adds on-device text recognition (engine)
 *   LOCATE      → online vision finds the described control in a screenshot (engine; ≤2 a task, consent-gated)
 *   BACK        → back to the last verified screen (engine)
 *   ALT_ROUTE   → the app's own search, another menu, another app (the model chooses the route)
 *   ASK_USER    → ask the user how to go on (engine)
 *
 * A rung is used at most once per subgoal (reset when a step verifies or the plan moves on) and only where it applies.
 * Pure — the engine supplies what's possible ([Env]) and applies the chosen rung.
 */
enum class Rung(val words: String) {
    RE_RESOLVE("closest matches on this screen"),
    SCROLL_FIND("scroll to find it"),
    OCR("look again with on-device text recognition"),
    LOCATE("find it in a picture of the screen (online vision)"),
    BACK("go back to the last screen that worked"),
    ALT_ROUTE("take another route"),
    ASK_USER("ask the user")
}

class RecoveryLadder {
    /** What the engine can do right now. */
    data class Env(
        /** the label / description the failed step was after (tap label, the claim's element), if any */
        val target: String?,
        val screen: UiSnapshot?,
        val visionAlready: Boolean,
        val locateAvailable: Boolean,
        val canBack: Boolean,
        val canAsk: Boolean,
        /** the failed tool already scrolled the list looking for it */
        val alreadyScrolled: Boolean
    )

    private val tried = LinkedHashSet<Rung>()
    val triedRungs: List<Rung> get() = tried.toList()

    /** A step verified, or the plan moved to another subgoal: the ladder starts over. */
    fun reset() = tried.clear()

    /** Mark a rung as used without choosing it (e.g. the loop guard already asked the user). */
    fun markTried(r: Rung) { tried.add(r) }

    /** The next rung for [cls], or null when the class needs none (or every applicable rung is used up). */
    fun next(cls: OutcomeClass, env: Env): Rung? {
        val order = ORDER[cls] ?: return null
        val r = order.firstOrNull { it !in tried && applies(it, env) } ?: return null
        tried.add(r)
        return r
    }

    internal fun applies(r: Rung, env: Env): Boolean = when (r) {
        Rung.RE_RESOLVE -> env.target != null && closest(env.target, env.screen).isNotEmpty()
        Rung.SCROLL_FIND -> env.target != null && !env.alreadyScrolled && env.screen?.elements?.any { it.scrollable } == true
        Rung.OCR -> !env.visionAlready
        Rung.LOCATE -> env.target != null && env.locateAvailable
        Rung.BACK -> env.canBack
        Rung.ALT_ROUTE -> true
        Rung.ASK_USER -> env.canAsk
    }

    /** The line the model reads: which rung, why, and what was already tried. */
    fun describe(r: Rung, cls: OutcomeClass, target: String?, detail: String? = null): String {
        val order = ORDER[cls].orEmpty()
        val n = order.indexOf(r) + 1
        val before = tried.filter { it != r }.joinToString(", ") { it.words }
        return "RECOVERY (${cls.name.lowercase().replace('_', ' ')}${target?.let { " '${it.take(50)}'" } ?: ""}): " +
            "strategy $n of ${order.size} — ${r.words}" + (detail?.let { ": $it" } ?: "") +
            (if (before.isNotEmpty()) ". Already tried: $before." else ".")
    }

    companion object {
        private val ORDER: Map<OutcomeClass, List<Rung>> = mapOf(
            OutcomeClass.NOT_FOUND to listOf(Rung.RE_RESOLVE, Rung.SCROLL_FIND, Rung.OCR, Rung.LOCATE, Rung.BACK, Rung.ALT_ROUTE, Rung.ASK_USER),
            OutcomeClass.CLAIM_NOT_MET to listOf(Rung.RE_RESOLVE, Rung.OCR, Rung.LOCATE, Rung.BACK, Rung.ALT_ROUTE, Rung.ASK_USER),
            OutcomeClass.NO_EFFECT to listOf(Rung.OCR, Rung.LOCATE, Rung.BACK, Rung.ALT_ROUTE, Rung.ASK_USER),
            OutcomeClass.WRONG_PAGE to listOf(Rung.BACK, Rung.ALT_ROUTE, Rung.ASK_USER),
            OutcomeClass.FAILED to listOf(Rung.ALT_ROUTE, Rung.BACK, Rung.ASK_USER),
            OutcomeClass.BLOCKED to listOf(Rung.ALT_ROUTE, Rung.ASK_USER)
        )

        fun order(cls: OutcomeClass): List<Rung> = ORDER[cls].orEmpty()

        private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        /**
         * Up to [n] on-screen items closest to [wanted] by shared words / characters — "Wi-Fi calling" when the model
         * asked for "WiFi calling". Tree elements only, labelled, tappable or text. Pure.
         */
        fun closest(wanted: String, screen: UiSnapshot?, n: Int = 3): List<Pair<Int, String>> {
            val w = norm(wanted)
            if (w.isEmpty() || screen == null) return emptyList()
            val wWords = w.split(' ').filter { it.length > 1 }.toSet()
            val wCompact = w.replace(" ", "")
            return screen.elements.asSequence()
                .filter { it.source == ElementSource.TREE && !it.label.isNullOrBlank() && it.label.length <= 80 }
                .mapNotNull { e ->
                    val l = norm(e.label!!)
                    if (l.isEmpty() || l == w) return@mapNotNull null
                    val lWords = l.split(' ').filter { it.length > 1 }.toSet()
                    val shared = if (wWords.isEmpty() || lWords.isEmpty()) 0.0 else wWords.intersect(lWords).size.toDouble() / wWords.size
                    val lc = l.replace(" ", "")
                    val compact = if ((lc.contains(wCompact) || wCompact.contains(lc)) && lc.length >= 3) 0.8 else 0.0
                    val score = maxOf(shared, compact)
                    if (score >= 0.5) Triple(e.id, e.label, score) else null
                }
                .sortedByDescending { it.third }
                .take(n)
                .map { it.first to it.second }
                .toList()
        }
    }
}

/**
 * AndroidArena's exploration hint, deterministic: how often this task has been on a screen and what it already did
 * there — "you've been on this screen 3 times and tried tap 'X' twice". Keyed on [UiSnapshot.signature].
 */
class VisitCounter(private val maxScreens: Int = AppConfig.int("agent.visits.maxScreens", 64)) {
    private val visits = LinkedHashMap<String, Int>()
    private val actions = HashMap<String, LinkedHashMap<String, Int>>()
    private val failedBranches = HashMap<String, LinkedHashSet<String>>()

    fun noteVisit(screen: UiSnapshot) {
        val k = screen.signature()
        visits[k] = (visits[k] ?: 0) + 1
        while (visits.size > maxScreens) visits.remove(visits.keys.first())
    }

    fun noteAction(screen: UiSnapshot, action: String) {
        val m = actions.getOrPut(screen.signature()) { LinkedHashMap() }
        m[action] = (m[action] ?: 0) + 1
    }

    /** [action] on [screen] led off track (wrong page / backtracked): named as a route to avoid. */
    fun noteFailedBranch(screen: UiSnapshot, action: String) { failedBranches.getOrPut(screen.signature()) { LinkedHashSet() }.add(action) }

    fun visitsOf(screen: UiSnapshot): Int = visits[screen.signature()] ?: 0

    /** The prompt line for [screen], or null when there is nothing worth saying (first visit, nothing repeated). */
    fun line(screen: UiSnapshot): String? {
        val k = screen.signature()
        val v = visits[k] ?: 0
        val acts = actions[k].orEmpty()
        val avoid = failedBranches[k].orEmpty()
        if (v < 2 && acts.values.none { it >= 2 } && avoid.isEmpty()) return null
        val sb = StringBuilder("VISITS: ")
        sb.append(if (v >= 2) "you've been on this screen $v times" else "on this screen")
        if (acts.isNotEmpty()) sb.append("; tried here: ").append(acts.entries.joinToString(", ") { (a, n) -> if (n > 1) "$a ×$n" else a })
        if (avoid.isNotEmpty()) sb.append("; led off track: ").append(avoid.joinToString(", "))
        sb.append(" — don't repeat what didn't work.")
        return sb.toString()
    }

    companion object {
        /** A short name for a step: "tap 'Wi-Fi'", "scroll down", "open_app WhatsApp". */
        fun actionKey(tool: String, args: org.json.JSONObject, resolvedLabel: String?): String {
            val what = args.optString("label").ifBlank { null } ?: resolvedLabel?.takeIf { it.isNotBlank() }
                ?: args.optString("app").ifBlank { null } ?: args.optString("direction").ifBlank { null }
                ?: args.optString("key").ifBlank { null } ?: args.optString("query").ifBlank { null }
                ?: args.optString("element_id").ifBlank { null }?.let { "#$it" }
            return if (what == null) tool else if (tool == "tap" || tool == "long_press") "$tool '${what.take(40)}'" else "$tool ${what.take(40)}"
        }
    }
}
