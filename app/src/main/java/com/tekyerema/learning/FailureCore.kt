package com.tekyerema.learning

import com.tekyerema.audit.AuditEntry
import com.tekyerema.audit.AuditLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Why a task is on the list of mistakes. [id] is the stored / exported form. */
enum class FailureReason(val id: String) {
    USER_REJECTED("user_rejected"),
    DECLINED_REPHRASED("declined_rephrased"),
    STEP_FAILED("step_failed"),
    UNVERIFIED("unverified"),
    LOOP_STOPPED("loop_stopped"),
    CANNOT_DO("cannot_do"),
    STOPPED_BY_USER("stopped_by_user"),
    RECOVERED("recovered");

    companion object { fun of(id: String?): FailureReason? = values().firstOrNull { it.id == id } }
}

/** One step: the tool, the label it targeted ("" = none kept) and how it ended: verified / done / unverified / failed / blocked /
 *  not_run / declined / revised / proposed. */
data class FailureStep(val tool: String, val target: String, val result: String)

/**
 * One task that went wrong, already redacted ([FailureRedact]): the request, the brain that answered, the steps, the app and
 * screen title where it ended, and the user's correction words (redacted) when they gave any. Never a PIN, code, amount,
 * number or message text.
 */
data class FailureCase(
    val id: String, val at: Long, val goal: String, val brain: String, val reason: FailureReason,
    val steps: List<FailureStep>, val app: String?, val title: String?, val correction: String?
)

/** A finished task as the learning layer holds it for a moment: [failing] = it already counts as a mistake; [declined] = the
 *  user said no to a step (a rephrase right after is then a correction). */
data class FailureDraft(val case: FailureCase, val failing: Boolean, val declined: Boolean)

/**
 * Learning from mistakes — pure logic (no Android): what counts as a failure, building a note from the audit trail, the cap,
 * the stored form, matching a new request to an old mistake, the hint for the on-phone planner, and the training-pool export.
 * The Android side is [FailureStore] (encrypted file), [FailureLearning] (the hooks) and [FailureCommands] (voice).
 * Tests: FailureCoreTest.
 */
object FailureCore {
    const val MAX_CASES = 200
    const val MAX_CHARS = 150_000
    const val MAX_STEPS = 12
    /** Word overlap (Jaccard) from which two requests are "the same request". */
    const val NEAR = 0.7
    const val HINT_DAYS = 30L
    const val DAY_MS = 86_400_000L

    private val BAD = setOf("failed", "unverified", "blocked", "not_run")
    private val REJECTED = setOf("declined", "revised")
    private val RAN = setOf("done", "verified", "failed", "unverified")
    /** The engine's own words for stopping a task that is going nowhere (AgentEngine's INFO lines). */
    private val GUARD = Regex("(?i)loop guard|futility|no-progress")
    private val MONEY_TOOL = Regex("(?i)momo|money|ussd|bank|telco|airtime|(?:^|_)pay|(?:^|_)pin(?:$|_)")
    private val QUOTE = Regex("“([^”]{1,40})”")

    // ---------------------------------------------------------------- what counts as a failure

    /**
     * Why a task that ended with [status] (an AgentStatus name) is a mistake, or null when it is not. A paused task is not
     * over. [guardLines] are the run's INFO lines (loop / futility / no-progress guards).
     */
    fun classify(status: String, paused: Boolean, steps: List<FailureStep>, guardLines: List<String>): FailureReason? {
        if (paused) return null
        val loop = guardLines.any { GUARD.containsMatchIn(it) }
        val struggled = steps.count { it.result == "failed" || it.result == "unverified" }
        return when {
            status == "FAILED" -> if (loop) FailureReason.LOOP_STOPPED else FailureReason.STEP_FAILED
            status == "CANNOT_DO" -> if (loop) FailureReason.LOOP_STOPPED else if (steps.isNotEmpty()) FailureReason.CANNOT_DO else null
            status == "COMPLETED_UNVERIFIED" -> FailureReason.UNVERIFIED
            steps.any { it.result == "revised" } -> FailureReason.DECLINED_REPHRASED
            status == "STOPPED" -> if (steps.any { it.result in RAN }) FailureReason.STOPPED_BY_USER else null
            status == "COMPLETED" && struggled >= 2 -> FailureReason.RECOVERED
            else -> null
        }
    }

    /** The steps of one task from its audit entries (only steps that change the phone or send something are audited). */
    fun stepsFrom(entries: List<AuditEntry>, personal: (String) -> Boolean = { false }): List<FailureStep> {
        val byStep = LinkedHashMap<Int, MutableList<AuditEntry>>()
        for (e in entries) if (e.step > 0 && e.tool.isNotBlank()) byStep.getOrPut(e.step) { ArrayList() }.add(e)
        return byStep.values.map { g ->
            val tool = g.first().tool
            val plan = g.firstOrNull { it.kind == AuditLog.PROPOSED || it.kind == AuditLog.BLOCKED }?.text.orEmpty()
            FailureStep(tool, FailureRedact.label(targetOf(tool, plan), personal), resultOf(g))
        }.takeLast(MAX_STEPS)
    }

    /** The control a step aimed at, from its plan words ("tap “Send”", "open WhatsApp"); "" when the words name none. */
    internal fun targetOf(tool: String, plan: String): String = when (tool) {
        "tap" -> QUOTE.find(plan)?.groupValues?.get(1).orEmpty()
        "long_press" -> plan.removePrefix("press and hold").trim().takeUnless { it == "an item" }.orEmpty()
        "open_app" -> plan.removePrefix("open").trim().takeUnless { it == "an app" }.orEmpty()
        "open_setting" -> plan.removePrefix("change").trim().takeUnless { it == "a setting" }.orEmpty()
        else -> ""
    }

    private fun resultOf(g: List<AuditEntry>): String {
        val kinds = g.map { it.kind }.toSet()
        return when {
            AuditLog.BLOCKED in kinds -> "blocked"
            AuditLog.DECLINED in kinds -> if (g.any { it.kind == AuditLog.DECLINED && it.outcome == "revised" }) "revised" else "declined"
            AuditLog.NOT_RUN in kinds -> "not_run"
            AuditLog.FAILED in kinds -> "failed"
            AuditLog.UNVERIFIED in kinds -> "unverified"
            AuditLog.VERIFIED in kinds -> "verified"
            AuditLog.DONE in kinds -> "done"
            else -> "proposed"
        }
    }

    /**
     * The note for a task that just ended, or null when nothing may be kept: the task paused, was not started by a brain, was
     * about money / a PIN / a password / a code, or used a money tool. [goal] is the user's words (redacted here, never stored
     * raw); [guardLines] the run's INFO lines; [app] / [title] where the phone was when it ended (labels only); [personal] says
     * whether a label is a person (their contacts).
     */
    fun build(
        goal: String, status: String, paused: Boolean, entries: List<AuditEntry>, guardLines: List<String>,
        app: String?, title: String?, now: Long, personal: (String) -> Boolean = { false }
    ): FailureDraft? {
        if (paused) return null
        val start = entries.firstOrNull { it.kind == AuditLog.TASK_START } ?: return null
        val said = goal.ifBlank { start.text }
        if (said.isBlank() || FailureRedact.sensitive(said)) return null
        if (entries.any { it.tool.isNotBlank() && MONEY_TOOL.containsMatchIn(it.tool) }) return null
        val redacted = FailureRedact.text(said)
        if (redacted.isBlank()) return null
        val steps = stepsFrom(entries, personal)
        val reason = classify(status, paused, steps, guardLines)
        val brain = (entries.lastOrNull { it.kind == AuditLog.PROPOSED && it.decidedBy.isNotBlank() }?.decidedBy ?: start.decidedBy)
            .replace(Regex("\\s+"), " ").trim().take(60)
        val case = FailureCase(
            idFor(redacted, now), now, redacted, brain, reason ?: FailureReason.USER_REJECTED, steps,
            FailureRedact.label(app, personal).ifEmpty { null },
            FailureRedact.label(title, personal, maxLen = 40, maxWords = 6).ifEmpty { null }, null
        )
        return FailureDraft(case, failing = reason != null, declined = steps.any { it.result in REJECTED })
    }

    internal fun idFor(redactedGoal: String, now: Long): String =
        "f" + now.toString(36) + redactedGoal.hashCode().toLong().and(0xffffffffL).toString(36)

    /** The user's correction words as they may be kept (redacted, short), or null when none may be. */
    fun correctionText(raw: String): String? {
        if (FailureRedact.sensitive(raw)) return null
        return FailureRedact.text(raw, 100).ifBlank { null }
    }

    // ---------------------------------------------------------------- the list: cap, replace, forget

    /** [cases] with [case] added (replacing the one with its id); the oldest go first past [MAX_CASES]. */
    fun add(cases: List<FailureCase>, case: FailureCase): List<FailureCase> {
        val list = cases.filter { it.id != case.id } + case
        return list.sortedBy { it.at }.takeLast(MAX_CASES)
    }

    // ---------------------------------------------------------------- the stored form

    fun encode(cases: List<FailureCase>): String {
        var list = cases.sortedBy { it.at }.takeLast(MAX_CASES)
        while (true) {
            val arr = JSONArray()
            list.forEach { arr.put(caseJson(it)) }
            val text = JSONObject().put("v", 1).put("c", arr).toString()
            if (text.length <= MAX_CHARS || list.isEmpty()) return text
            list = list.drop(1)   // over the size cap: the oldest goes
        }
    }

    // (A missing value is left out, not written as JSON null: Android's optString turns a JSON null into the text "null".)
    private fun caseJson(c: FailureCase): JSONObject = JSONObject()
        .put("id", c.id).put("at", c.at).put("goal", c.goal).put("brain", c.brain).put("why", c.reason.id)
        .put("steps", JSONArray().also { a -> c.steps.forEach { a.put(JSONObject().put("t", it.tool).put("l", it.target).put("r", it.result)) } })
        .also { o ->
            c.app?.let { o.put("app", it) }
            c.title?.let { o.put("title", it) }
            c.correction?.let { o.put("corr", it) }
        }

    /** The cases in [text], or null when it is not a mistakes file. A bad entry is skipped, never fatal. */
    fun decode(text: String?): List<FailureCase>? {
        if (text.isNullOrBlank()) return null
        return try {
            val arr = JSONObject(text).optJSONArray("c") ?: return emptyList()
            val out = ArrayList<FailureCase>()
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val reason = FailureReason.of(j.optString("why")) ?: continue
                val id = j.optString("id"); val goal = j.optString("goal")
                if (id.isBlank() || goal.isBlank()) continue
                val steps = ArrayList<FailureStep>()
                j.optJSONArray("steps")?.let { s ->
                    for (k in 0 until s.length()) s.optJSONObject(k)?.let { o -> steps.add(FailureStep(o.optString("t"), o.optString("l"), o.optString("r"))) }
                }
                out.add(FailureCase(id, j.optLong("at"), goal, j.optString("brain"), reason, steps,
                    j.optString("app").ifEmpty { null }, j.optString("title").ifEmpty { null }, j.optString("corr").ifEmpty { null }))
            }
            out
        } catch (_: Throwable) { null }
    }

    // ---------------------------------------------------------------- matching an old mistake to a new request

    private val STOP = setOf("the", "a", "an", "to", "my", "me", "please", "can", "could", "you", "i", "for", "on", "in", "of", "and", "it",
        "now", "just", "up", "with", "is", "that", "this", "do", "want", "would", "like", "pls", "kindly", "nexus", "hey", "okay", "ok", "person")

    private fun tokens(s: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
        .filter { it.length > 1 && it !in STOP }.map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }.toSet()

    /** 0..1: how much of the words two (redacted) requests share. */
    fun similarity(a: String, b: String): Double {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    // ---------------------------------------------------------------- the hint for the on-phone planner

    /**
     * Up to [max] one-line hints for a new [goal]: for each recent mistake with a near-identical request and a specific step,
     * "Last time, tapping 'Search' in Bolt did not work." Newest first, no repeats. Only the on-phone planner ever reads these
     * ([com.tekyerema.agent.LocalModelBrain]); a cloud brain is never given them.
     */
    fun hintsFor(cases: List<FailureCase>, goal: String, now: Long, max: Int = 2, maxAgeDays: Long = HINT_DAYS): List<String> {
        if (cases.isEmpty() || goal.isBlank()) return emptyList()
        val g = FailureRedact.text(goal)
        return cases.asSequence()
            .filter { now - it.at in 0..maxAgeDays * DAY_MS && similarity(it.goal, g) >= NEAR }
            .sortedByDescending { it.at }
            .mapNotNull { hintFor(it) }.distinct().take(max).toList()
    }

    internal fun hintFor(c: FailureCase): String? {
        if (c.reason == FailureReason.STOPPED_BY_USER) return null
        val step = c.steps.lastOrNull { it.result in BAD || it.result in REJECTED } ?: c.steps.lastOrNull() ?: return null
        val what = phrase(step) ?: return null
        val where = c.app?.let { " in $it" }.orEmpty()
        return when {
            step.result in REJECTED || c.reason == FailureReason.USER_REJECTED || c.reason == FailureReason.DECLINED_REPHRASED ->
                "Last time, $what$where was not what the user wanted."
            c.reason == FailureReason.LOOP_STOPPED || c.reason == FailureReason.CANNOT_DO ->
                "Last time, $what$where got stuck. Try a different way."
            else -> "Last time, $what$where did not work. Try a different way."
        }
    }

    private fun phrase(s: FailureStep): String? {
        val t = s.target
        return when (s.tool) {
            "tap" -> if (t.isNotEmpty()) "tapping '$t'" else "tapping there"
            "long_press" -> if (t.isNotEmpty()) "pressing and holding '$t'" else "pressing and holding"
            "open_app" -> if (t.isNotEmpty()) "opening $t" else "opening the app"
            "open_setting" -> if (t.isNotEmpty()) "changing the setting '$t'" else "changing that setting"
            "scroll", "swipe" -> "scrolling"
            "type" -> "typing in the box"
            "key_event" -> "pressing a key"
            "make_call" -> "calling"
            "send_sms" -> "sending the message"
            "" -> null
            else -> "using " + s.tool.replace('_', ' ')
        }
    }

    // ---------------------------------------------------------------- export for the training pool

    private fun categoryOf(c: FailureCase): String {
        val g = c.goal.lowercase()
        val tools = c.steps.map { it.tool }.toSet()
        fun has(vararg w: String) = w.any { Regex("\\b$it").containsMatchIn(g) }
        return when {
            tools.any { it == "send_sms" || it == "reply_to_notification" } || has("message", "text", "whatsapp", "sms", "tell", "reply") -> "messaging"
            "make_call" in tools || has("call", "ring", "dial") -> "calls"
            tools.any { it == "set_alarm" || it == "set_timer" || it == "calendar" } || has("alarm", "timer", "remind") -> "productivity"
            "play_music" in tools || has("play", "music", "song", "youtube", "spotify") -> "media"
            tools.any { it == "open_setting" || it.startsWith("set_volume") } || has("wifi", "wi-fi", "bluetooth", "volume", "brightness", "setting") -> "settings"
            "walk_directions" in tools || has("direction", "map", "nearest") -> "navigation_info"
            has("bolt", "uber", "ride", "taxi") -> "transport"
            else -> "recovery"
        }
    }

    private fun day(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /**
     * One case as a line of the training pool (campaign/agent_tasks/TASK_SPEC.md: persona / category / goals / conversation /
     * start / good_behaviour / must_not / tags …) plus a `failure` object with what the phone saw. `good_behaviour` is empty:
     * the teacher labels it from the state the student reached (DAgger), the phone does not guess. Only the DAY is kept, not the time.
     */
    fun toTask(c: FailureCase): JSONObject {
        val convo = JSONArray().put("User: ${c.goal}")
        c.correction?.let { convo.put("User (correcting): $it") }
        val tags = JSONArray().put("from_phone").put("mistake").put(c.reason.id)
        val failure = JSONObject()
            .put("reason", c.reason.id).put("brain", c.brain).put("day", day(c.at))
            .put("steps", JSONArray().also { a -> c.steps.forEach { a.put(JSONObject().put("tool", it.tool).put("target", it.target).put("result", it.result)) } })
            .put("screen", JSONObject().put("app", c.app ?: JSONObject.NULL).put("title", c.title ?: JSONObject.NULL))
            .put("correction", c.correction ?: JSONObject.NULL)
        val o = JSONObject()
            .put("id", "phone-" + c.id).put("persona", "general").put("category", categoryOf(c))
            .put("goals", JSONArray().put(c.goal)).put("conversation", convo).put("memory", JSONArray())
            .put("start", "home").put("good_behaviour", "").put("must_not", mustNot(c)).put("ambiguity", "none").put("difficulty", 2)
            .put("apps", JSONArray().also { a -> c.app?.let { a.put(it) } }).put("tags", tags).put("failure", failure)
        return o
    }

    private fun mustNot(c: FailureCase): String = when (c.reason) {
        FailureReason.USER_REJECTED -> "What the phone did was not what the user asked; they said so."
        FailureReason.DECLINED_REPHRASED -> "The user turned down the proposed step and said it another way."
        FailureReason.STEP_FAILED -> "A step failed and the task did not finish."
        FailureReason.UNVERIFIED -> "The task ended without the result being confirmed on screen."
        FailureReason.LOOP_STOPPED -> "The task went in circles until the guard stopped it."
        FailureReason.CANNOT_DO -> "The phone gave up with cannot_do part way."
        FailureReason.STOPPED_BY_USER -> "The user stopped the task part way."
        FailureReason.RECOVERED -> "Steps failed before another way worked."
    }

    /** The JSONL text: one task per line. */
    fun toJsonl(cases: List<FailureCase>): String = cases.sortedBy { it.at }.joinToString("") { toTask(it).toString() + "\n" }

    /** What the user hears before agreeing to share (pure). */
    fun describeExport(cases: List<FailureCase>): String {
        val n = cases.size
        val notes = if (n == 1) "1 note" else "$n notes"
        return "I have $notes about tasks that went wrong. Each one has your request with names, numbers, amounts and messages taken out, " +
            "the app and screen title where it ended, the steps I tried and how each ended, which model answered, " +
            "and any words you used to correct me. It never has PINs, codes, passwords, amounts, phone numbers or message text. " +
            "I send nothing myself: you choose where the file goes."
    }
}
