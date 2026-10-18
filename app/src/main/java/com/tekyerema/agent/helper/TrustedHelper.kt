package com.tekyerema.agent.helper

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LocalBrain
import com.tekyerema.audit.AuditEntry
import com.tekyerema.audit.AuditLog
import org.json.JSONObject

/*
 * A trusted helper, one step away (2026-09-29). Research (docs/agent/research/assistive_tech_blind.md): blind users want
 * a human one step away when the assistant can't do something — Aira / Be My Eyes are how they get one today — and in Ghana
 * PIN theft by "helpers" is documented, so a helper is NEVER brought in at a PIN.
 *
 * The helper is ONE contact the user names ("my helper is Kofi", "set my helper to Prince"); it is stored as the "helper"
 * relation (android/Relations — encrypted on the phone, "who is my helper", "remove my helper"), so "call my helper"
 * resolves through ContactResolver like "call my mother". What this file adds, all pure and unit-tested (TrustedHelperTest):
 *  - [HelperCommands]: "call my helper", "tell my helper what you did" (SMS, or WhatsApp when said);
 *  - [HelperGate]: refused while Secure Input is on, or when the last task stopped at a PIN / credential screen;
 *  - [LastTask] + [HelperSummary]: a short plain summary of the last task — no PINs, codes, money amounts or long numbers;
 *  - [HelperOffer]: after a second failed task in a row, the reply may add once "If you'd like, say “call my helper”";
 *  - [HelperPlan]: the two commands planned on the phone (RoutingBrain), confirmed by the engine like any call or send.
 */

/** The spoken commands that bring the helper in. */
object HelperCommands {
    sealed class Cmd {
        object Call : Cmd() { override fun toString() = "Call" }
        data class Summary(val whatsApp: Boolean) : Cmd()
    }

    private const val HELPER = "(?:my |the )?(?:trusted )?helper"
    private const val VIA = "(?: (?:on|by|via|through|using|in|over) (?:whatsapp|sms|text|a text|text message))?"
    private const val WHAT = "(?:about )?(?:what (?:you|we) (?:just )?did|what you(?:'ve| have) (?:just )?done|what (?:just )?happened|" +
        "what you did for me|what i asked you to do|how it went)"
    private val CALL = Regex("^(?:call|ring|phone|dial|reach) $HELPER$")
    private val SUMMARY = listOf(
        Regex("^(?:tell|text|message|whatsapp|sms|update|inform) $HELPER$VIA $WHAT$VIA$"),
        Regex("^let $HELPER know $WHAT$VIA$"),
        Regex("^(?:send|give|text|whatsapp|message) $HELPER (?:a |an |the )?(?:quick |short )?(?:summary|update|report|recap)" +
            "(?: of $WHAT| of it| of that)?$VIA$"),
        Regex("^(?:send|give) (?:a |an |the )?(?:quick |short )?(?:summary|update|report|recap)(?: of $WHAT| of it| of that)? to $HELPER$VIA$"),
        Regex("^(?:update|brief) $HELPER$VIA$")
    )
    private val LEAD = Regex("^(?:(?:hey |ok |okay )?t[ɛe]ky[ɛe]r[ɛe]ma[, ]+)?(?:(?:please|kindly|can you|could you|would you|will you|" +
        "i want you to|i'd like you to)\\s+)*")
    private val TAIL = Regex("(?:\\s+(?:please|now|right now|for me))+$")
    private val COMMS_VERB = Regex("\\b(?:call|ring|phone|dial|text|message|sms|whatsapp|tell|send|update|inform|let|brief|reach|email|write)\\b")
    private val MY_HELPER = Regex("\\bmy (?:trusted )?helper\\b")

    internal fun norm(text: String): String = text.trim().lowercase().replace('’', '\'').replace(Regex("\\s+"), " ")
        .trimEnd('.', '!', '?', ',').trim().replaceFirst(LEAD, "").replace(TAIL, "").trim()

    fun parse(text: String): Cmd? {
        val g = norm(text)
        if (g.isEmpty() || !g.contains("helper")) return null
        if (CALL.matches(g)) return Cmd.Call
        if (SUMMARY.any { it.matches(g) }) return Cmd.Summary(whatsApp = g.contains("whatsapp"))
        return null
    }

    /** Any request that brings the helper in — calling, texting or updating them ("text my helper saying …" too). */
    fun involvesHelper(text: String): Boolean {
        val g = norm(text)
        return parse(g) != null || (MY_HELPER.containsMatchIn(g) && COMMS_VERB.containsMatchIn(g))
    }
}

/**
 * The PIN rule: the helper is never offered or reachable while Secure Input is on, while a credential screen is in front,
 * or after the last task stopped at a PIN (Secure Input stopped before "done", or Mobile Money handed over at its PIN
 * screen). The PIN mark ends when the user says "done", when a later task finishes, or after `helper.pinHoldMs` (a USSD
 * PIN prompt is gone within minutes).
 */
object HelperGate {
    const val PIN_LINE = "Enter your PIN yourself first — I won't bring anyone in at the PIN."

    @Volatile private var pinAt = 0L
    @Volatile private var markedThisRun = false
    @Volatile private var dialogGoneAt = 0L

    /** The phone's PIN dialog closed (the PIN entered, or the menu cancelled) after the last PIN mark. */
    fun dialogGone(now: Long = System.currentTimeMillis()) { dialogGoneAt = now }
    fun dialogGoneSincePin(): Boolean = pinAt > 0L && dialogGoneAt >= pinAt

    fun runStarted() { markedThisRun = false }
    /** The task in flight reached a PIN / credential screen (Secure Input, or momo_send's hand-off). */
    fun atPin(now: Long = System.currentTimeMillis()) { pinAt = now; markedThisRun = true }
    /** The user said "done" after entering it. */
    fun pinDone() { pinAt = 0L }
    /** A task ended: unless it was the one that stopped at the PIN, the last task no longer did. */
    fun runEnded() { if (!markedThisRun) pinAt = 0L; markedThisRun = false }

    fun pinPending(now: Long = System.currentTimeMillis()): Boolean =
        pinAt > 0L && now - pinAt < com.tekyerema.core.config.AppConfig.long("helper.pinHoldMs", 10 * 60_000L)

    fun blocked(secureNow: Boolean, credentialOnScreen: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        secureNow || credentialOnScreen || pinPending(now)

    internal fun resetForTest() { pinAt = 0L; markedThisRun = false; dialogGoneAt = 0L }
}

/** The last finished task, in the few facts a helper summary needs. [status] = AgentStatus name. */
data class TaskRecap(
    val goal: String,
    val status: String,
    val result: String,
    /** Plain past-tense actions from the audit ("texted Ama", "opened WhatsApp"). */
    val done: List<String>,
    /** Phone numbers the user approved in that task (a call's or a text's recipient) — the only long numbers kept. */
    val confirmedNumbers: List<String>,
    val at: Long
)

object LastTask {
    @Volatile var recap: TaskRecap? = null
        private set

    fun note(r: TaskRecap) { recap = r }
    fun clear() { recap = null }

    /** The last task, if it ended within `helper.recapMaxAgeMs` (a day). */
    fun recent(now: Long = System.currentTimeMillis()): TaskRecap? =
        recap?.takeIf { now - it.at < com.tekyerema.core.config.AppConfig.long("helper.recapMaxAgeMs", 24 * 3_600_000L) }

    /** Screen primitives say little on their own ("tapped “Send”"); the accelerators' words say what happened. */
    private val SCREEN_WORK = setOf("tap", "long_press", "type", "scroll", "swipe", "key_event", "node_action", "set_range", "wait",
        "read_state", "read_screen", "read_all", "drag", "pinch", "tap_sequence")
    private val RECIPIENT_TOOLS = setOf("make_call", "send_sms", "reply_to_notification")
    private val PHONE = Regex("\\+?\\d(?:[\\s-]?\\d){8,12}")

    /** The recap of a task from its end state and its audit [entries] (this task's only). Pure. */
    fun from(goal: String, status: AgentStatus, result: String?, entries: List<AuditEntry>, now: Long): TaskRecap {
        val doneEntries = entries.filter { it.kind == AuditLog.DONE }
        val words = doneEntries.filter { it.tool !in SCREEN_WORK }.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct()
        val done = if (words.isEmpty() && doneEntries.isNotEmpty()) listOf("worked on the phone's screen") else words
        val numbers = entries.filter { it.kind == AuditLog.APPROVED && it.tool in RECIPIENT_TOOLS }
            .flatMap { e -> PHONE.findAll(e.text).map { it.value.filter(Char::isDigit) }.toList() }.distinct()
        return TaskRecap(goal.trim(), status.name, result?.trim().orEmpty(), done, numbers, now)
    }
}

/** The short, plain summary a helper receives. GSM-7 only (a Ghanaian SMS in UCS-2 costs up to three times as much). */
object HelperSummary {
    private const val MAX = 300

    fun compose(r: TaskRecap): String {
        val asked = clip(r.goal.trimEnd('.', '?', '!'), 90)
        val did = if (r.done.isEmpty()) "Nothing was changed on the phone." else "Done: " + joinAnd(r.done.take(4)) + "."
        val res = clip(firstSentence(r.result).trimEnd('.', '!'), 130)
        val outcome = when (r.status) {
            AgentStatus.COMPLETED.name -> if (res.isEmpty()) "It worked." else "Result: $res."
            AgentStatus.COMPLETED_UNVERIFIED.name -> "Result: ${res.ifEmpty { "done" }} (not confirmed)."
            AgentStatus.CANNOT_DO.name, AgentStatus.FAILED.name -> if (res.isEmpty()) "It couldn't be done." else "It couldn't be done: $res."
            AgentStatus.STOPPED.name -> "It was stopped before it finished."
            else -> ""
        }
        val text = redact("Tekyerema update: I was asked to \"$asked\". $did $outcome".trim(), r.confirmedNumbers)
        return gsm(text).let { if (it.length <= MAX) it else it.take(MAX - 3).trimEnd() + "..." }
    }

    private val CURRENCY = Regex("(?i)(?:gh(?:s|₵|¢|c)\\.?|₵|¢)\\s*\\d[\\d,]*(?:\\.\\d+)?|\\b\\d[\\d,]*(?:\\.\\d+)?\\s*(?:gh(?:s|₵|¢)|cedis?|pesewas?)\\b")
    private val BALANCE = Regex("(?i)(\\bbal(?:ance)?\\b[^.\\d]{0,25})\\d[\\d,]*(?:\\.\\d+)?")
    /** 6+ digits, spaced or dashed ("0 2 4 4 1 2 …", "024-412-3456", an account number). */
    private val LONG_NUMBER = Regex("(?<!\\d)\\+?\\d(?:[\\s-]?\\d){5,}(?!\\d)")

    /**
     * Never a PIN, a one-time code, a money amount or balance, or a long number (account, transaction id) — only the phone
     * number of someone the user approved calling or texting in that task ([keepNumbers], compared on the last 9 digits).
     */
    fun redact(text: String, keepNumbers: List<String> = emptyList()): String {
        val keep = keepNumbers.map { it.filter(Char::isDigit).takeLast(9) }.filter { it.length == 9 }.toSet()
        var t = com.tekyerema.core.Redact.secrets(text) ?: text
        t = CURRENCY.replace(t, "an amount")
        t = BALANCE.replace(t) { m -> m.groupValues[1] + "(hidden)" }
        t = LONG_NUMBER.replace(t) { m ->
            val d = m.value.filter(Char::isDigit)
            if (d.length in 9..13 && d.takeLast(9) in keep) m.value else "(number hidden)"
        }
        return t
    }

    /** Twi letters folded, curly quotes and dashes straightened, anything outside the GSM alphabet dropped. */
    internal fun gsm(s: String): String = s.replace('ɛ', 'e').replace('ɔ', 'o').replace('Ɛ', 'E').replace('Ɔ', 'O')
        .replace('ŋ', 'n').replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        .replace("—", "-").replace("–", "-").replace("…", "...")
        .filter { it.code in 0x20..0x7E || it == '\n' }
        .replace(Regex("\\s+"), " ").trim()

    private fun firstSentence(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        val i = Regex("(?<=[.!?])\\s").find(t)?.range?.first ?: return t
        return t.substring(0, i)
    }

    private fun clip(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val cut = t.take(max)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: cut.length).trimEnd(',', ';', ':') + "..."
    }

    private fun joinAnd(xs: List<String>): String = when (xs.size) {
        0 -> ""; 1 -> xs[0]; else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }
}

/** After a second failed task in a row (and only then), the reply may add the helper once — never at a PIN. */
object HelperOffer {
    const val LINE = "If you'd like, say “call my helper”."

    private var failures = 0
    private var offered = false

    /** @return the line to add to this task's reply, or null. [paused] = the task paused (the cloud went away), not failed. */
    @Synchronized
    fun afterTask(status: AgentStatus, paused: Boolean, helperSet: Boolean, blocked: Boolean): String? {
        when (status) {
            AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED -> { failures = 0; offered = false; return null }
            AgentStatus.CANNOT_DO, AgentStatus.FAILED -> if (paused) return null else failures++
            else -> return null   // STOPPED: the user's own choice — neither a failure nor a success
        }
        if (failures < 2 || offered || !helperSet || blocked) return null
        offered = true
        return LINE
    }

    @Synchronized internal fun resetForTest() { failures = 0; offered = false }
}

/**
 * "call my helper" / "tell my helper what you did", planned on the phone — the same online or offline, whatever brain is in
 * charge (RoutingBrain asks here first). Step one is make_call / send_sms to the helper (the engine's gate confirms it with
 * the resolved name and number, and the summary read back in full); step two reports that tool's own result. Anything
 * else in between (the user revised the proposal) goes back to the normal brains.
 */
object HelperPlan {
    const val REASON = "helper"
    const val NO_HELPER = "You haven't chosen a helper yet. Say, for example: my helper is Ama."
    const val NOTHING_YET = "I haven't done anything yet that I could tell your helper about."
    const val RATIONALE_CALL = "local:call your helper"
    const val RATIONALE_SUMMARY = "local:tell your helper what happened"

    fun currentHelper(): String? = try { com.tekyerema.android.Relations.contactFor("helper") } catch (_: Throwable) { null }

    fun decide(
        goal: String, history: List<String>,
        helper: String? = currentHelper(),
        recap: TaskRecap? = LastTask.recent(),
        blocked: Boolean = HelperGate.pinPending()
    ): AgentDecision? {
        val cmd = HelperCommands.parse(goal) ?: return null
        val tool = if (cmd is HelperCommands.Cmd.Call) "make_call" else "send_sms"
        val last = history.lastOrNull()
        if (last != null) {
            if (!last.startsWith("$tool(")) return null
            // The tool's own words when it ran or failed its preflight; a refusal line (policy, a changed screen) is planner
            // talk, so the user hears a plain sentence instead.
            val said = if (last.contains("→ success") || last.contains("→ fail")) LocalBrain.resultMessage(last) else ""
            if (!last.contains("→ success")) return AgentDecision.CannotDo(REASON, said.ifBlank { "I couldn't do that just now." })
            return AgentDecision.Done(if (cmd is HelperCommands.Cmd.Call) "Calling your helper${helper?.let { ", $it" } ?: ""}." else said)
        }
        if (blocked) return AgentDecision.CannotDo(REASON, HelperGate.PIN_LINE)
        val who = helper ?: return AgentDecision.CannotDo(REASON, NO_HELPER)
        return when (cmd) {
            HelperCommands.Cmd.Call -> AgentDecision.CallTool("make_call", JSONObject().put("contact", who), RATIONALE_CALL)
            is HelperCommands.Cmd.Summary -> {
                val r = recap ?: return AgentDecision.CannotDo(REASON, NOTHING_YET)
                val args = JSONObject().put("contact", who).put("message", HelperSummary.compose(r))
                if (cmd.whatsApp) args.put("via", "whatsapp")
                AgentDecision.CallTool("send_sms", args, RATIONALE_SUMMARY)
            }
        }
    }
}
