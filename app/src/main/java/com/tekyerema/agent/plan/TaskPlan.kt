package com.tekyerema.agent.plan

import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.journal.Subgoal
import com.tekyerema.core.config.AppConfig
import com.tekyerema.verifier.Expectation
import com.tekyerema.verifier.Expects
import com.tekyerema.verifier.OutcomeVerifier
import com.tekyerema.verifier.VerificationResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workstream B — the explicit plan (docs/agent/research/agenticness_mobile_agents.md §3, §8).
 *
 * A multi-step goal gets 3–7 subgoals, each with a success check in the VERIFY grammar, returned by the model WITH its
 * first action (an optional `plan` argument the engine strips before execution — no extra LLM call, see
 * [com.tekyerema.agent.AgentDecision.fromToolCall]). The engine owns the statuses: a subgoal is ticked only from a
 * verified outcome (its check holds on a fresh observation, or the step that ran while it was active verified), never
 * from the model saying so. Replanning is event-driven and rides in the next normal call: after a subgoal completes
 * (Agent S2's proactive replanning) and after 2 consecutive failures on the same subgoal (Mobile-Agent-E, k=2).
 * `done` is accepted only when every subgoal is done or explicitly skipped with a reason ([gate]).
 *
 * Pure (no Android), so parsing, tracking and the gate are unit-tested off-device.
 */
enum class SubgoalStatus { PENDING, DONE, SKIPPED }

data class PlanItem(
    val id: Int,
    val text: String,
    /** The success check in the VERIFY grammar ("foreground_is: WhatsApp"), or null when the model gave none. */
    val check: String?,
    val status: SubgoalStatus = SubgoalStatus.PENDING,
    /** How it was settled: "check held", "step verified", or the model's skip reason. */
    val evidence: String? = null
) {
    val open: Boolean get() = status == SubgoalStatus.PENDING

    /** A check the engine can evaluate by rule (a vacuous or unparsed one can't be). */
    val checkable: Boolean get() = !Expects.isVacuous(Expects.parse(check))
}

object PlanParser {
    const val MIN_ITEMS = 2
    const val MAX_ITEMS = 7
    private const val TEXT_MAX = 90
    private const val CHECK_MAX = 90

    private val NUMBERING = Regex("^\\s*(?:\\d{1,2}\\s*[.):]|[-*•·]|\\[[ x>-]?\\])\\s*")
    private val ARROW = Regex("\\s*(?:=>|->|→)\\s*")
    private val SKIP_PREFIX = Regex("(?i)^(?:skip(?:ped)?|waive(?:d)?)\\s*[:\\-]\\s*")
    private val SKIP_CHECK = Regex("(?i)^(?:skip(?:ped)?|waive(?:d)?)\\b\\s*[:\\-]?\\s*")
    private val REASON_SPLIT = Regex("\\s+(?:—|–|-{1,2}|because)\\s+")
    private val INLINE_NUMBERS = Regex("(?:^|\\s)(\\d{1,2})[.)]\\s+")

    /**
     * The model's `plan` argument as one string, whether it sent a string, a JSON array of strings, or a JSON array of
     * objects ({"subgoal"|"step"|"text": …, "check": …}). Null when there is nothing.
     */
    fun flatten(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim().ifEmpty { null }
        is JSONArray -> (0 until value.length()).mapNotNull { i ->
            when (val v = value.opt(i)) {
                is String -> v.trim().ifEmpty { null }
                is JSONObject -> {
                    val t = listOf("subgoal", "step", "text", "goal", "task").firstNotNullOfOrNull { k -> v.optString(k).trim().ifEmpty { null } }
                    val c = listOf("check", "success", "expects", "done_when").firstNotNullOfOrNull { k -> v.optString(k).trim().ifEmpty { null } }
                    t?.let { if (c != null) "$it => $c" else it }
                }
                else -> v?.toString()?.trim()?.ifEmpty { null }
            }
        }.takeIf { it.isNotEmpty() }?.joinToString("; ")
        is JSONObject -> value.optJSONArray("subgoals")?.let { flatten(it) } ?: value.optJSONArray("steps")?.let { flatten(it) }
        else -> value.toString().trim().ifEmpty { null }
    }

    /** Parse a plan string into 2–7 items; null when it isn't a plan (one step, or nothing usable). */
    fun parse(raw: String?): List<PlanItem>? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val pieces = when {
            s.contains('\n') -> s.split('\n')
            s.contains(';') -> s.split(';')
            INLINE_NUMBERS.findAll(s).count() >= 2 -> INLINE_NUMBERS.split(s)
            else -> listOf(s)
        }
        val items = pieces.mapNotNull { item(it) }.take(MAX_ITEMS)
        if (items.size < MIN_ITEMS) return null
        return items.mapIndexed { i, p -> p.copy(id = i + 1) }
    }

    private fun item(piece: String): PlanItem? {
        var p = piece.trim().replace(NUMBERING, "").trim()
        if (p.isEmpty()) return null
        // "skip: send a copy to Kofi — he already has it"
        SKIP_PREFIX.find(p)?.takeIf { it.range.first == 0 }?.let { m ->
            val rest = p.substring(m.range.last + 1).trim()
            val parts = rest.split(REASON_SPLIT, limit = 2)
            val text = parts[0].split(ARROW, limit = 2)[0].trim()
            if (text.isEmpty()) return null
            val reason = parts.getOrNull(1)?.trim()?.ifEmpty { null } ?: "no reason given"
            return PlanItem(0, text.take(TEXT_MAX), null, SubgoalStatus.SKIPPED, "skipped: ${reason.take(CHECK_MAX)}")
        }
        val parts = p.split(ARROW, limit = 2)
        val text = parts[0].trim().trimEnd(',', '.').take(TEXT_MAX)
        if (text.isEmpty()) return null
        val check = parts.getOrNull(1)?.trim()?.trimEnd(';')?.ifEmpty { null }
        // "open the menu => skip: not needed"
        if (check != null && SKIP_CHECK.find(check)?.range?.first == 0) {
            val reason = check.replace(SKIP_CHECK, "").trim().ifEmpty { "no reason given" }
            return PlanItem(0, text, null, SubgoalStatus.SKIPPED, "skipped: ${reason.take(CHECK_MAX)}")
        }
        p = text
        return PlanItem(0, p, check?.take(CHECK_MAX))
    }
}

/**
 * The live plan of one task. Holds the subgoals and their statuses, turns step outcomes into ticks, schedules
 * replans and runs the completion gate. One per run, owned by [com.tekyerema.agent.TaskMemory].
 */
class PlanTracker(
    private val replanAfterFailures: Int = AppConfig.int("agent.plan.replanAfterFailures", 2).coerceAtLeast(1),
    private val maxGateRefusals: Int = AppConfig.int("agent.plan.maxGateRefusals", 2).coerceAtLeast(0)
) {
    var items: List<PlanItem> = emptyList(); private set
    /** 0 = no plan yet; bumped on every accepted (re)plan. */
    var version = 0; private set
    val hasPlan: Boolean get() = items.isNotEmpty()
    val active: PlanItem? get() = items.firstOrNull { it.open }

    private var failures = 0
    private var replanWhy: String? = null
    private var justDone: PlanItem? = null
    private var dropped: List<String> = emptyList()
    private var gateRefusals = 0
    /** Steps taken since this plan (version) was adopted, and whether its soft budget already asked for a replan. */
    private var stepsOnPlan = 0
    private var overBudgetAsked = false
    /** How many replans the engine asked for (for traces/tests). */
    var replansRequested = 0; private set
    /** True when the most recent [onStep] settled at least one subgoal (read by LocalContinuation, P3-B3). */
    var justSettled = false; private set
    fun clearSettled() { justSettled = false }

    /**
     * The model sent `plan` with its action. A first plan is adopted; a later one is a REPLAN: subgoals already done
     * stay done (matched by wording) and are kept even if the new plan leaves them out, open ones the new plan drops
     * are noted. Returns a one-line log of what changed, or null when [raw] isn't a usable plan.
     */
    fun offer(raw: String?): String? {
        val parsed = PlanParser.parse(raw) ?: return null
        val old = items
        val settled = old.filter { !it.open }
        val fresh = parsed.map { p ->
            val prev = settled.firstOrNull { same(it.text, p.text) }
            if (p.status == SubgoalStatus.PENDING && prev != null) p.copy(status = prev.status, evidence = prev.evidence) else p
        }
        val keptDone = settled.filter { s -> fresh.none { same(it.text, s.text) } }
        dropped = old.filter { o -> o.open && fresh.none { same(it.text, o.text) } }.map { it.text }
        items = (keptDone + fresh).mapIndexed { i, it -> it.copy(id = i + 1) }
        version++
        justSettled = false
        failures = 0
        replanWhy = null
        justDone = null
        stepsOnPlan = 0
        overBudgetAsked = false
        return (if (version == 1) "plan: " else "replan v$version: ") +
            items.joinToString(" | ") { "${it.id}. ${it.text}${it.check?.let { c -> " [$c]" } ?: ""}${if (!it.open) " (${it.status.name.lowercase()})" else ""}" }
    }

    /** What one executed step showed. [verified]: its claim (or its tool's own outcome check) held. [read]: a successful
     *  read (screen, messages, the web) — it changes nothing but can settle a "find out X" subgoal. [failed]: a real
     *  failure (tool failed, no effect, claim not met, wrong page…); exploration and reads are neither. */
    data class StepEvidence(val verified: Boolean, val read: Boolean, val failed: Boolean, val before: UiSnapshot?, val after: UiSnapshot?)

    /** Tick subgoals from a step's verified outcome; count failures toward a REPLAN. Returns the subgoals just done. */
    fun onStep(e: StepEvidence): List<PlanItem> {
        justSettled = false
        if (!hasPlan) return emptyList()
        val done = ArrayList<PlanItem>()
        if (e.verified || e.read) {
            val screen = e.after ?: e.before
            var first = true
            while (true) {
                val a = active ?: break
                val holds = if (a.checkable) checkHolds(a.check, e.before, screen)
                    // No checkable claim of its own: the step that ran while it was active verified — only for the first one.
                    else first
                if (!holds) break
                val evidence = if (a.checkable) "check held" else if (e.verified) "step verified" else "read succeeded"
                mark(a.id, SubgoalStatus.DONE, evidence)
                done.add(items.first { it.id == a.id })
                first = false
            }
        }
        if (done.isNotEmpty()) {
            justSettled = true
            failures = 0
            justDone = done.last()
            replanWhy = null
        } else if (e.failed) {
            failures++
            val a = active
            if (a != null && failures >= replanAfterFailures) {
                replanWhy = "subgoal ${a.id} (${a.text.take(60)}) failed $failures times in a row"
                replansRequested++
                failures = 0
            }
        } else if (e.verified) failures = 0
        // Soft budget (AndroidWorld's ~2× rule): a plan still open well past twice its size is re-thought once.
        stepsOnPlan++
        val open = items.count { it.open }
        if (open > 0 && !overBudgetAsked && replanWhy == null && stepsOnPlan >= softBudget()) {
            overBudgetAsked = true
            replansRequested++
            replanWhy = "this is taking longer than planned ($stepsOnPlan steps for ${items.size} subgoals, $open still open)"
        }
        return done
    }

    /** Steps a plan may take before the engine asks for a rethink: ~2 per subgoal, at least [MIN_SOFT_BUDGET]. */
    internal fun softBudget(): Int = maxOf(MIN_SOFT_BUDGET, 2 * items.size + AppConfig.int("agent.plan.softBudgetSlack", 2))

    /** True once the engine has asked for a replan that hasn't been answered with a new plan yet. */
    val replanPending: Boolean get() = replanWhy != null

    /**
     * The PLAN block for this step's prompt. One-shot notes (a subgoal just finished; REPLAN) are shown once — call
     * this once per planning call. [hintMultiStep]: no plan yet and the goal looks like several parts.
     */
    fun promptBlock(hintMultiStep: Boolean = false): String {
        // A20e 2026-09-29: gpt-oss-120b ignored the one-line hint on a 3-part goal; the hint now shows the exact form.
        if (!hasPlan) return if (hintMultiStep) "PLAN: none yet — this goal has several parts. Add the `plan` argument to THIS tool " +
            "call: 3–7 items as 'subgoal => how you'll know it's done; …' (e.g. 'open About phone => Model name shows; read the " +
            "model => said to the user')." else ""
        val sb = StringBuilder("PLAN (x=done, >=now, -=skipped):")
        val act = active
        for (it in items) {
            val mark = when {
                it.status == SubgoalStatus.DONE -> "x"
                it.status == SubgoalStatus.SKIPPED -> "-"
                it.id == act?.id -> ">"
                else -> " "
            }
            sb.append("\n[").append(mark).append("] ").append(it.id).append(". ").append(it.text)
            if (it.check != null && it.open) sb.append(" — check: ").append(it.check)
        }
        if (dropped.isNotEmpty()) { sb.append("\n(dropped at replan: ").append(dropped.joinToString("; ") { it.take(50) }).append(')'); dropped = emptyList() }
        val why = replanWhy
        val jd = justDone
        when {
            why != null -> { sb.append("\nREPLAN: ").append(why).append(". Send a revised `plan` with your next action (a different route for what's left)."); replanWhy = null }
            jd != null && act != null -> {
                // A20e 8 Nov 2026: after the Telegram leg the brain looked for "Prince" inside Telegram — the next part may need
                // another app or its own tool; say what is left and that the app on screen is not where it has to happen.
                sb.append("\nSubgoal ").append(jd.id).append(" done. Still to do: ")
                    .append(items.filter { it.open }.joinToString("; ") { "${it.id}. ${it.text}" })
                    .append(". The next part may need a different app or tool than the one on screen — leave this app (open_app, or the " +
                        "part's own tool: 'text X' with no app named is send_sms, a reminder is set_alarm) rather than looking for it here. " +
                        "If the plan no longer fits, resend `plan`.")
                justDone = null
            }
            jd != null && act == null -> { sb.append("\nEvery subgoal is done — check the goal is met, then reply."); justDone = null }
        }
        return sb.toString()
    }

    sealed class Gate {
        object Accept : Gate()
        /** Not every subgoal is done: [note] tells the model what's missing. */
        data class Refuse(val open: List<PlanItem>, val note: String) : Gate()
        /** Still open after [maxGateRefusals] refusals: finish, but not as verified. */
        data class Unverified(val open: List<PlanItem>) : Gate()
    }

    /**
     * Completion gate: may this `done` stand? Rule first — an open subgoal whose check holds on the final screen is
     * settled now. Then every subgoal must be done or skipped; otherwise the model is told exactly what's missing (at
     * most [maxGateRefusals] times; after that the task ends COMPLETED_UNVERIFIED rather than looping).
     */
    fun gate(finalScreen: UiSnapshot?): Gate {
        if (!hasPlan) return Gate.Accept
        for (it in items.filter { it.open && it.checkable })
            if (checkHolds(it.check, null, finalScreen)) mark(it.id, SubgoalStatus.DONE, "check held on the final screen")
        val open = items.filter { it.open }
        if (open.isEmpty()) return Gate.Accept
        if (gateRefusals >= maxGateRefusals) return Gate.Unverified(open)
        gateRefusals++
        val list = open.joinToString("; ") { "${it.id}. ${it.text.take(60)}" + (it.check?.let { c -> " (check: ${c.take(60)})" } ?: "") }
        return Gate.Refuse(open, "NOT DONE YET — plan subgoal(s) still open: $list. Finish them, or if one is truly not needed " +
            "resend `plan` with it as 'skip: <subgoal> — <why>'. Don't reply done until every subgoal is done.")
    }

    /** For the task journal (Workstream A persists it). */
    fun toSubgoals(): List<Subgoal> = items.map { Subgoal(it.id, it.text, it.check, done = !it.open) }

    /** Workstream A resume ("carry on"): the plan saved on the task's journal record comes back as version 1 — settled
     *  subgoals stay settled, open ones open. Ignored when a plan is already live or [saved] isn't a plan (2–7 items). */
    fun restore(saved: List<Subgoal>) {
        if (hasPlan || saved.size !in PlanParser.MIN_ITEMS..PlanParser.MAX_ITEMS) return
        items = saved.mapIndexed { i, s ->
            PlanItem(i + 1, s.text, s.successCheck, if (s.done) SubgoalStatus.DONE else SubgoalStatus.PENDING,
                if (s.done) "settled before the pause" else null)
        }
        version = 1
    }

    private fun mark(id: Int, status: SubgoalStatus, evidence: String) {
        items = items.map { if (it.id == id) it.copy(status = status, evidence = evidence) else it }
    }

    companion object {
        const val MIN_SOFT_BUDGET = 6

        /** Does [check] hold? NewElement is judged against [before] when there is one, else read as "present now". */
        fun checkHolds(check: String?, before: UiSnapshot?, after: UiSnapshot?): Boolean {
            if (after == null) return false
            val e = Expects.parse(check)
            if (Expects.isVacuous(e)) return false
            val claim = if (e is Expectation.NewElement && before == null) Expectation.ElementPresent(e.label) else e
            return OutcomeVerifier.verify(claim, before ?: after, after, true, VerificationResult(false, "n/a"), false).verified
        }

        internal fun same(a: String, b: String): Boolean {
            fun n(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
            val x = n(a); val y = n(b)
            if (x == y) return true
            val wx = x.split(' ').filter { it.length > 2 }.toSet(); val wy = y.split(' ').filter { it.length > 2 }.toSet()
            if (wx.isEmpty() || wy.isEmpty()) return false
            return wx.intersect(wy).size.toDouble() / (wx + wy).size >= 0.75
        }

        private val MULTI = Regex("(?i)\\b(?:and then|then|and|after that|afterwards|also|as well as|before|after)\\b|,")

        /** A goal that probably needs several parts (for the "include a plan" nudge — the model still decides). */
        fun looksMultiStep(goal: String): Boolean {
            val words = goal.trim().split(Regex("\\s+")).size
            return words >= 6 && MULTI.containsMatchIn(goal)
        }
    }
}
