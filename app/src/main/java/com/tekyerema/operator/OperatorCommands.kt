package com.tekyerema.operator

import android.content.Context
import com.tekyerema.agent.FastOutcome

/** A control command said or typed by the user (no model involved). */
sealed class OpCommand {
    data class Cloud(val on: Boolean) : OpCommand()
    /** [ambiguous] = a bare "normal mode", which could also mean the ringer — only ours when a mode is on. */
    data class Mode(val mode: ExecutionMode, val ambiguous: Boolean = false) : OpCommand()
    object ModeQuery : OpCommand()
    object Inventory : OpCommand()
    object Usage : OpCommand()
    object DeleteAll : OpCommand()
    data class Export(val includeText: Boolean) : OpCommand()
    data class Revoke(val capability: Capability) : OpCommand()
    data class Restore(val capability: Capability) : OpCommand()
    object Providers : OpCommand()
    /** D8: "never touch WhatsApp" / "only guide me in Chrome" ([rule]); "you can use WhatsApp again" (null = lift it). [app] as said. */
    data class AppRuleSet(val app: String, val rule: AppRule?) : OpCommand()
    /** D8: "which apps are off limits?" */
    object AppRulesList : OpCommand()
}

/**
 * WS-C — the operator's controls by voice or typing, on the local fast path (checked before any other command, no model):
 * "switch to offline mode" / "use the cloud again", "ask me before every step" / "normal mode" / "guide only",
 * "what do you keep about me", "how much have you used today", "delete everything" (asked first), "export my history",
 * "never send messages for me" / "you can send messages again", "where does my data go?", "what mode are you in?", and the
 * per-app rules ("never touch WhatsApp", "only guide me in Chrome", "you can use WhatsApp again" — [AppRuleVoice]).
 * Each change is recorded in the activity log (PrefsOperatorStore) and applies to the very next step.
 */
object OperatorCommands {
    // ------------------------------------------------------------------ classification (pure)
    private val NEG = "(?:never|don't|dont|do not|stop|no more)"

    private val CLOUD_OFF = Regex("^(?:please )?(?:(?:switch|go|change|move) (?:to|into) offline mode|(?:turn|switch) on offline mode|" +
        "(?:use )?offline mode(?: on| please)?|(?:go|work|stay) offline|(?:turn|switch) off (?:the )?cloud|(?:turn|switch) (?:the )?cloud off|" +
        "cloud off|(?:don't|do not|stop) (?:use|using) the (?:cloud|internet)|keep everything on (?:my|the|this) phone)$")
    private val CLOUD_ON = Regex("^(?:please )?(?:(?:you can )?use the (?:cloud|internet)(?: again)?|(?:turn|switch) on the cloud|" +
        "(?:turn|switch) (?:the )?cloud (?:back )?on|cloud on|go back online|(?:switch (?:back )?to |go back to |use )?online mode|" +
        "(?:turn|switch) off offline mode|(?:leave|exit|stop) offline mode)$")
    private val ASK_EVERY = Regex("^(?:please )?(?:(?:always )?(?:ask|check with) me (?:first )?(?:before|at) (?:every|each) (?:step|action)|" +
        "ask (?:first )?before (?:every|each) (?:step|action)|ask me (?:before you do anything|every step|every time|first every time)|" +
        "(?:use |switch to |go to )?(?:step[- ]by[- ]step|ask every step|ask me every step) mode)$")
    private val STANDARD_CLEAR = Regex("^(?:stop asking (?:me )?(?:before )?(?:every|each) (?:step|time)|" +
        "you don'?t (?:need|have) to ask (?:me )?(?:before )?(?:every|each) step|(?:switch|go|change)(?: back)? to standard mode|standard mode|" +
        "stop guide(?:-| )?only(?: mode)?|stop guiding(?: me)?|you can (?:act|do things) (?:for me )?again)$")
    private val STANDARD_BARE = Regex("^(?:(?:switch|go|change)(?: back)? to normal mode|(?:use )?normal mode|back to normal(?: mode)?)$")
    private val GUIDE = Regex("^(?:(?:switch|go|change) to |use )?guide(?:-| )?(?:only )?mode$|^guide(?:-| )?only$|^(?:only|just) guide me$|" +
        "^(?:don't|do not) touch my phone$")
    private val MODE_QUERY = Regex("^(?:what|which) mode are you (?:in|on)$|^what(?:'s| is) your mode$")
    private val INVENTORY = Regex("^what (?:do|did) you (?:keep|store|hold|save|collect)(?: about me| on me| on this phone)?$|" +
        "^what (?:data|information|info|personal data) (?:do you (?:keep|have|store|hold)|are you keeping)(?: about me| on me)?$")
    private val USAGE = Regex("^how much (?:have|did) you (?:use|used|spend|spent)(?: today| so far(?: today)?)?$|" +
        "^how much (?:have|did) (?:i|we) (?:use|used)(?: today)?$|^what(?:'s| is) (?:my|today's|the) (?:usage|budget)(?: today)?$|" +
        "^how much (?:budget|allowance) (?:is )?left(?: today)?$|^how many (?:tokens|calls|requests) (?:have you|did you|have i|did i) (?:use|used|make|made)(?: today)?$")
    private val DELETE_ALL = Regex("^(?:please )?(?:delete|erase|wipe|remove|clear) (?:everything(?: you (?:keep|have|know))?|all (?:of )?my (?:data|information)|all (?:the )?data)(?: about me)?$")
    private val EXPORT = Regex("^(?:please )?(?:export|download|send me|share|give me) (?:a copy of )?(?:my|all my|the) " +
        "(?:history|data|activity(?: log)?|audit(?: log)?|activity history)(?: and (?:my |the )?(?:activity(?: log)?|history|audit(?: log)?))?" +
        "(?: (?:with|including) (?:the )?(?:message text|messages|text|words))?$")
    private val WITH_TEXT = Regex("(?:with|including) (?:the )?(?:message text|messages|text|words)$")
    private val PROVIDERS = Regex("^(?:which|what) (?:ai|model|brain|provider)s? (?:do|are) you us(?:e|ing)$|^where does my (?:data|information) go$|" +
        "^who (?:sees|can see) (?:my data|what i say|my requests)$")

    private val REVOKE = listOf(
        Capability.MESSAGES to Regex("^$NEG (?:ever )?(?:send(?:ing)? (?:any )?(?:messages|texts)|text(?:ing)? (?:anyone|people)|messag(?:e|ing) (?:anyone|people))(?: for me)?(?: again)?$"),
        Capability.CALLS to Regex("^$NEG (?:ever )?(?:mak(?:e|ing) (?:any )?(?:phone )?calls|call(?:ing)? (?:anyone|people))(?: for me)?(?: again)?$"),
        Capability.MONEY to Regex("^$NEG (?:ever )?(?:touch(?:ing)?|mov(?:e|ing)|send(?:ing)?|spend(?:ing)?|handl(?:e|ing)) (?:my )?money(?: for me)?(?: again)?$"),
        Capability.SETTINGS to Regex("^$NEG (?:ever )?chang(?:e|ing) (?:my |the |any )?(?:phone )?settings(?: for me)?(?: again)?$"),
        Capability.WEB to Regex("^$NEG (?:ever )?search(?:ing)? the (?:web|internet)(?: for me)?(?: again)?$|^no (?:more )?web search(?:es)?$")
    )
    private val RESTORE = Regex("^you can (send messages|text people|make calls|call people|handle money|touch my money|move money|" +
        "change (?:my )?settings|search the web|use the web) (?:for me )?again$")

    fun normalize(raw: String): String =
        raw.trim().lowercase().replace('’', '\'').replace(",", " ").replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?').trim()

    fun classify(raw: String): OpCommand? {
        val g = normalize(raw)
        if (g.isEmpty()) return null
        return when {
            CLOUD_OFF.matches(g) -> OpCommand.Cloud(false)
            CLOUD_ON.matches(g) -> OpCommand.Cloud(true)
            ASK_EVERY.matches(g) -> OpCommand.Mode(ExecutionMode.ASK_EVERY_STEP)
            GUIDE.matches(g) -> OpCommand.Mode(ExecutionMode.GUIDE_ONLY)
            STANDARD_CLEAR.matches(g) -> OpCommand.Mode(ExecutionMode.STANDARD)
            STANDARD_BARE.matches(g) -> OpCommand.Mode(ExecutionMode.STANDARD, ambiguous = true)
            MODE_QUERY.matches(g) -> OpCommand.ModeQuery
            INVENTORY.matches(g) -> OpCommand.Inventory
            USAGE.matches(g) -> OpCommand.Usage
            DELETE_ALL.matches(g) -> OpCommand.DeleteAll
            EXPORT.matches(g) -> OpCommand.Export(includeText = WITH_TEXT.containsMatchIn(g))
            PROVIDERS.matches(g) -> OpCommand.Providers
            else -> REVOKE.firstOrNull { it.second.matches(g) }?.let { OpCommand.Revoke(it.first) }
                ?: RESTORE.matchEntire(g)?.let { m -> restoreOf(m.groupValues[1])?.let { OpCommand.Restore(it) } }
                // D8, last: "don't touch my phone" (Guide only) and "never touch my money" (Money) are matched above.
                ?: AppRuleVoice.classify(g)
        }
    }

    private fun restoreOf(what: String): Capability? = when {
        what.startsWith("send messages") || what.startsWith("text") -> Capability.MESSAGES
        what.contains("call") -> Capability.CALLS
        what.contains("money") -> Capability.MONEY
        what.contains("settings") -> Capability.SETTINGS
        what.contains("web") -> Capability.WEB
        else -> null
    }

    // ------------------------------------------------------------------ "delete everything" is asked first (pure state)
    /** Only an answer that says "delete" confirms an irreversible wipe — one misheard "ok" must never do it. */
    private val DELETE_YES = Regex("^(?:(?:yes|yeah|aane|ok|okay)(?: please)? )?(?:go ahead and )?delete (?:it|everything|it all|all of it|all|all my data)(?: please| now)?$")
    private val BARE_YES = Regex("^(?:yes|yeah|yep|yes please|ok|okay|sure|go ahead|do it|confirm|aane)$")
    private val NO = Regex("^(?:no|nope|cancel|stop|don't|do not|never mind|daabi|keep it|keep everything)\\b.*$")
    const val CONFIRM_WINDOW_MS = 60_000L
    @Volatile private var deleteAskedAt = 0L

    enum class DeleteAnswer { CONFIRM, CANCEL, UNCLEAR, OTHER }

    /** [now] is a monotonic clock (elapsedRealtime on the phone), so a clock change can't reopen the question. */
    fun deletePending(now: Long): Boolean = deleteAskedAt != 0L && now - deleteAskedAt in 0..CONFIRM_WINDOW_MS

    /** What an utterance means while a delete is pending. UNCLEAR = a bare "yes"/"ok": ask for the full phrase instead. */
    fun answerToDelete(raw: String): DeleteAnswer {
        val g = normalize(raw)
        return when {
            DELETE_YES.matches(g) -> DeleteAnswer.CONFIRM
            NO.matches(g) -> DeleteAnswer.CANCEL
            BARE_YES.matches(g) -> DeleteAnswer.UNCLEAR
            else -> DeleteAnswer.OTHER
        }
    }

    const val DELETE_SAY_IT = "To delete everything, say “yes, delete everything”. Or say “no” to keep it."

    internal fun askDelete(now: Long) { deleteAskedAt = now }
    internal fun clearDelete() { deleteAskedAt = 0L }

    // ------------------------------------------------------------------ spoken lines (pure)
    fun modeLine(m: ExecutionMode): String = when (m) {
        ExecutionMode.STANDARD -> "Normal mode: I'll act on my own and still ask before calls, messages, money and anything important."
        ExecutionMode.ASK_EVERY_STEP -> "Okay — I'll ask you before every step that changes anything. Say “normal mode” to go back."
        ExecutionMode.GUIDE_ONLY -> "Guide-only mode: I won't touch your phone. I'll tell you what to do so you can do it yourself. " +
            "Say “normal mode” to go back."
    }

    fun modeQueryLine(m: ExecutionMode, cloudOn: Boolean): String {
        val mode = when (m) {
            ExecutionMode.STANDARD -> "normal mode: I act on my own and ask before anything important"
            ExecutionMode.ASK_EVERY_STEP -> "ask-every-step mode: I ask before every step that changes anything"
            ExecutionMode.GUIDE_ONLY -> "guide-only mode: I explain, and never touch the phone"
        }
        return "I'm in $mode. " + if (cloudOn) "The cloud is on." else "The cloud is off, so I'm working on this phone only."
    }

    fun cloudLine(on: Boolean, budgetSpent: Boolean): String = when {
        !on -> "Okay — offline mode. Nothing leaves your phone now: I'll answer and act with what's on the phone only. " +
            "Simple commands work; web search, describing pictures and bigger tasks need the cloud. Say “use the cloud again” to go back."
        budgetSpent -> "The cloud is allowed again, but today's limit is used up, so I'll stay on the phone until tomorrow. " +
            "You can raise the limit in Settings, Control."
        else -> "Okay — I'll use the online AI again when a task needs it."
    }

    /** "How much have you used today?" (pure). */
    fun usageLine(byProvider: Map<String, OperatorPolicy.ProviderUsage>, callCap: Int, tokenCap: Int): String {
        val calls = byProvider.values.sumOf { it.calls }
        val tokens = byProvider.values.sumOf { it.tokens }
        if (calls == 0) return "I haven't used the online AI today." + capsLine(callCap, tokenCap, 0, 0)
        val per = if (byProvider.size > 1) " — " + DataControl.joinAnd(byProvider.map { (p, u) -> "${u.calls} to ${Disclosure.name(p)}" }) else
            " to ${Disclosure.name(byProvider.keys.first())}"
        return "Today I've made $calls call${if (calls == 1) "" else "s"} to the online AI$per, using about ${spokenTokens(tokens)} tokens." +
            capsLine(callCap, tokenCap, calls, tokens)
    }

    private fun capsLine(callCap: Int, tokenCap: Int, calls: Int, tokens: Long): String {
        if (callCap <= 0 && tokenCap <= 0) return " There's no daily limit set."
        val parts = buildList {
            if (callCap > 0) add("${(callCap - calls).coerceAtLeast(0)} of your $callCap daily calls")
            if (tokenCap > 0) add("${spokenTokens((tokenCap - tokens).coerceAtLeast(0))} of your ${spokenTokens(tokenCap.toLong())} daily tokens")
        }
        return " You have " + DataControl.joinAnd(parts) + " left."
    }

    fun spokenTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f million", n / 1_000_000.0).replace(".0 ", " ")
        n >= 10_000 -> "${Math.round(n / 1000.0)} thousand"
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1f thousand", n / 1000.0).replace(".0 ", " ")
        else -> n.toString()
    }

    fun revokeLine(c: Capability): String =
        "Okay — I won't ${Capabilities.doing(c)} from now on. Say “${Capabilities.restorePhrase(c)}” to change that."

    fun restoreLine(c: Capability): String =
        "Okay — I can ${Capabilities.doing(c)} again. I'll still ask you before anything important."

    // ------------------------------------------------------------------ handling (Android)
    /**
     * Handle [raw] if it is an operator command (or the answer to "delete everything?"); null = not ours, carry on.
     * Called first on the local fast path (LocalFastRouter.tryHandle).
     */
    fun handle(ctx: Context, raw: String, now: Long = System.currentTimeMillis(),
               monotonic: Long = android.os.SystemClock.elapsedRealtime()): FastOutcome? {
        if (deletePending(monotonic)) {
            when (answerToDelete(raw)) {
                DeleteAnswer.CONFIRM -> {
                    clearDelete()
                    val failed = DataControl.deleteEverything(ctx)
                    return say("DELETE_ALL", if (failed.isEmpty()) DataControl.DELETED
                        else DataControl.DELETED + " I couldn't clear: " + DataControl.joinAnd(failed) + ".", ok = failed.isEmpty())
                }
                DeleteAnswer.CANCEL -> { clearDelete(); return say("DELETE_ALL", "Okay, I kept everything.") }
                DeleteAnswer.UNCLEAR -> return say("DELETE_ALL", DELETE_SAY_IT)   // still asked; nothing deleted
                DeleteAnswer.OTHER -> clearDelete()   // anything else: the question lapses; handle the words normally
            }
        }
        // D8: the yes/no to "should I be allowed to use WhatsApp again?" (anything else: the question lapses, nothing loosened).
        AppRuleVoice.answer(raw, monotonic)?.let { return say("APP_RULE", it, ok = true) }
        val cmd = classify(raw) ?: return null
        val op = OperatorPolicy
        val line = when (cmd) {
            OpCommand.Inventory -> DataControl.spoken(DataControl.items(ctx), op.cloudAllowed, op.providers)
            OpCommand.DeleteAll -> { AppRuleVoice.clearPending(); askDelete(monotonic); DataControl.DELETE_PROMPT }
            is OpCommand.AppRuleSet, OpCommand.AppRulesList -> {
                val said = AppRuleVoice.handle(ctx, cmd, monotonic) ?: return null
                // "I can't find …" / "I can't put Phone off limits" is a refusal: the error cue, not "done".
                return say(nameOf(cmd), said, ok = !said.startsWith("I can't"))
            }
            is OpCommand.Export -> if (com.tekyerema.audit.AuditExport.share(ctx, cmd.includeText))
                (if (cmd.includeText) "I've made a file of your history and activity log, with the message text. "
                    else "I've made a file of your history and activity log, with your own words left out. ") +
                    "The share menu is open — choose where to send it."
                else "Sorry, I couldn't make the file."
            else -> applyControl(cmd, now) ?: return null
        }
        return say(nameOf(cmd), line)
    }

    /**
     * The commands that only change or read the user's choices (no Android needed; unit-tested): applies [cmd] to
     * [OperatorPolicy] and returns what to say, or null when it isn't one of these (or a bare "normal mode" while already
     * in normal mode — that one is left to the ringer command).
     */
    fun applyControl(cmd: OpCommand, now: Long = System.currentTimeMillis()): String? {
        val op = OperatorPolicy
        return when (cmd) {
            is OpCommand.Cloud -> { op.cloudAllowed = cmd.on; cloudLine(cmd.on, op.budgetExhausted(now)) }
            is OpCommand.Mode -> {
                if (cmd.ambiguous && op.executionMode == ExecutionMode.STANDARD) return null   // "normal mode" = the ringer
                val was = op.executionMode
                op.executionMode = cmd.mode
                if (was == cmd.mode && cmd.mode != ExecutionMode.STANDARD) "I'm already in that mode. " + modeLine(cmd.mode) else modeLine(cmd.mode)
            }
            OpCommand.ModeQuery -> modeQueryLine(op.executionMode, op.cloudUsable(now))
            OpCommand.Usage -> usageLine(op.usageByProvider(now), op.dailyCloudCallCap, op.dailyTokenCap)
            is OpCommand.Revoke -> { op.revoke(cmd.capability); revokeLine(cmd.capability) }
            is OpCommand.Restore -> { op.restore(cmd.capability); restoreLine(cmd.capability) }
            OpCommand.Providers -> Disclosure.spoken(op.cloudAllowed, op.providers) +
                if (op.cloudAllowed && op.budgetExhausted(now)) " Today's limit is used up, so I'm on the phone only until tomorrow." else ""
            // These need Android (the data files, the share sheet, the app list) — handled in [handle].
            OpCommand.Inventory, OpCommand.DeleteAll, is OpCommand.Export, is OpCommand.AppRuleSet, OpCommand.AppRulesList -> null
        }
    }

    private fun nameOf(c: OpCommand): String = "OPERATOR_" + (c::class.simpleName ?: "COMMAND").uppercase()

    private fun say(name: String, line: String, ok: Boolean = !line.startsWith("Sorry")) = FastOutcome(name, line, systemVoice = true, ok = ok)
}
