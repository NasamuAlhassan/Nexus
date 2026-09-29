package com.tekyerema.money

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LocalBrain
import org.json.JSONObject

/**
 * The bank menu the user is being guided through ([BankUssdTool] writes it; [BankGuide] reads it). Kept here rather than
 * in the step history because a history line is cut at 160 characters and a bank menu is longer: the user must hear all
 * of it. In memory only; nothing is written anywhere.
 */
object BankSession {
    /** [speakBalance]: earphones / Bluetooth were connected, or the user switched balances on, when the screen was read. */
    data class View(val bank: BankUssd.Bank, val screen: String, val kind: BankUssd.ScreenKind, val openedAt: Long, val at: Long,
                    val speakBalance: Boolean = false)

    const val MAX_MS = 10 * 60_000L
    @Volatile private var view: View? = null

    fun opened(bank: BankUssd.Bank, screen: String, kind: BankUssd.ScreenKind, speakBalance: Boolean = false,
               now: Long = System.currentTimeMillis()) {
        view = View(bank, screen, kind, now, now, speakBalance)
    }

    fun screen(screen: String, kind: BankUssd.ScreenKind, speakBalance: Boolean = false, now: Long = System.currentTimeMillis()) {
        view = view?.copy(screen = screen, kind = kind, at = now, speakBalance = speakBalance)
    }

    fun end() { view = null }

    /** The session, while it's recent enough to still be on the phone. */
    fun current(now: Long = System.currentTimeMillis()): View? = view?.takeIf { now - it.openedAt in 0..MAX_MS }

    /** The bank menu opened by the user's confirmed request is still going: only then may a reply be typed into it. */
    fun active(now: Long = System.currentTimeMillis()): Boolean = current(now) != null
}

/**
 * The guided bank menu, step by step, decided on the phone (RoutingBrain hands it every step of a bank request before any
 * brain — no model ever picks a bank menu option):
 *  1. no bank named → ask which one; a bank without a verified code → say which ones are supported, and that the bank's
 *     own app (with a fingerprint) is the safer way; a verified bank → open its code (bank_ussd, confirmed first: TIER3);
 *  2. each menu screen is read aloud as the question — "Stanbic Bank says: “…”. What should I enter?" — and exactly what
 *     the user answers is typed (bank_ussd reply; an amount or account number is read back for a yes first);
 *  3. a payment confirmation or a PIN prompt ends the task: the screen is read out and the user confirms and enters the
 *     PIN themselves ([end], via RoutingBrain.moneyEnd — before the screen is looked at again, like momo_send).
 * Everything is guided, never automated. Pure given the session.
 */
object BankGuide {
    const val RATIONALE = "local:guided bank USSD"
    const val STOPPED = "Okay — I've stopped. Press Cancel on the bank's screen to close it; nothing is sent without your PIN."
    const val SAME_SCREEN = "I stopped: the bank's menu showed the same screen again. Press Cancel on it; nothing is sent without your PIN."
    const val NOT_UNDERSTOOD = "I didn't catch a bank I can open."
    private const val AGAIN = "Again — "

    /** The step for a bank request, or null when [goal] isn't one. */
    fun decide(goal: String, history: List<String>, session: BankSession.View? = BankSession.current()): AgentDecision? {
        val req = BankUssd.parse(goal) ?: return null
        if (history.isEmpty()) return first(req)
        val last = LocalBrain.lastStep(history)
        val bankSteps = history.any { it.startsWith("bank_ussd(") }
        return when {
            last.startsWith("ask_user(") && last.contains("→ not asked") -> AgentDecision.CannotDo(REASON, SAME_SCREEN)
            last.startsWith("ask_user(") -> {
                val answer = answerOf(last) ?: return AgentDecision.Done(STOPPED)
                if (!bankSteps) whichBank(answer) else menuAnswer(answer, last, session)
            }
            last.startsWith("bank_ussd(") -> afterStep(last, session)
            // The user answered the "open …?" read-back with something else, or the step was refused: stop plainly.
            else -> if (bankSteps) AgentDecision.Done(STOPPED) else AgentDecision.Done("Okay — I didn't open it.")
        }
    }

    private const val REASON = "bank"

    private fun first(req: BankUssd.Request): AgentDecision = when (req) {
        BankUssd.Request.Supported -> AgentDecision.Done(BankUssd.supportedLine())
        is BankUssd.Request.Unsupported -> AgentDecision.Done(BankUssd.unsupportedLine(req.name))
        BankUssd.Request.Which -> AgentDecision.CallTool("ask_user", JSONObject().put("question", BankUssd.whichQuestion()), RATIONALE)
        is BankUssd.Request.Open -> open(req.bank)
    }

    private fun open(bank: BankUssd.Bank): AgentDecision =
        AgentDecision.CallTool(BankUssdTool.NAME, JSONObject().put(BankUssdTool.ARG_ACTION, BankUssdTool.OPEN)
            .put(BankUssdTool.ARG_BANK, bank.key), RATIONALE)

    private fun whichBank(answer: String): AgentDecision {
        val found = BankUssd.findBank(answer)
        found?.first?.let { return open(it) }
        found?.second?.let { return AgentDecision.Done(BankUssd.unsupportedLine(it)) }
        return AgentDecision.Done("$NOT_UNDERSTOOD ${BankUssd.supportedLine()}")
    }

    private fun menuAnswer(answer: String, last: String, s: BankSession.View?): AgentDecision {
        val session = s ?: return AgentDecision.CannotDo(REASON, "The bank's menu has closed. Nothing is sent without your PIN.")
        if (BankUssd.isCancel(answer)) return AgentDecision.Done(STOPPED)
        if (BankUssd.isRepeat(answer)) {
            if (questionOf(last)?.startsWith(AGAIN) == true) return AgentDecision.CannotDo(REASON, STOPPED)
            return ask(AGAIN + question(session, first = false))
        }
        val entry = BankUssd.entry(answer) ?: return ask(AGAIN + question(session, first = false))
        return AgentDecision.CallTool(BankUssdTool.NAME, JSONObject().put(BankUssdTool.ARG_ACTION, BankUssdTool.REPLY)
            .put(BankUssdTool.ARG_BANK, session.bank.key).put(BankUssdTool.ARG_TEXT, entry)
            .put(BankUssdTool.ARG_SCREEN, BankUssd.spokenScreen(session.screen, session.speakBalance).take(160)), RATIONALE)
    }

    private fun afterStep(last: String, s: BankSession.View?): AgentDecision {
        if (!last.contains("→ success")) return AgentDecision.CannotDo(REASON, stepMessage(last).ifBlank { "The bank's menu didn't work just now. Nothing was sent." })
        val session = s ?: return AgentDecision.Done(STOPPED)
        return when (session.kind) {
            BankUssd.ScreenKind.MENU -> ask(question(session, first = !last.contains("\"${BankUssdTool.ARG_ACTION}\":\"${BankUssdTool.REPLY}\"")))
            else -> AgentDecision.Done(handOffLine(session))
        }
    }

    private fun ask(q: String) = AgentDecision.CallTool("ask_user", JSONObject().put("question", q), RATIONALE)

    /** "Stanbic Bank says: “1 Transfer; 2 Balance”. What should I enter?" — the screen first, one question. */
    fun question(s: BankSession.View, first: Boolean): String =
        "${s.bank.name} says: “${BankUssd.spokenScreen(s.screen, s.speakBalance)}”. What should I enter?" + if (first) " You can say cancel to stop." else ""

    /** The last line at a payment confirmation, a PIN prompt or a final message — read out, then the user takes over. */
    fun handOffLine(s: BankSession.View): String {
        val said = "${s.bank.name}'s screen says: “${BankUssd.spokenScreen(s.screen, s.speakBalance)}”."
        return when (s.kind) {
            BankUssd.ScreenKind.MONEY -> "$said If that's right, confirm it on the screen and enter your PIN yourself; if not, press Cancel. " +
                "I've stopped here — I never confirm a payment or type a PIN."
            BankUssd.ScreenKind.PIN -> "$said It's asking for your PIN — enter it yourself if everything is right, or press Cancel. I never see or type it."
            BankUssd.ScreenKind.GUARDED -> "$said It mentions a PIN or a code, so I'll leave this screen to you — choose on it yourself, or press " +
                "Cancel. I never see or type a PIN."
            BankUssd.ScreenKind.FINAL -> said
            BankUssd.ScreenKind.MENU -> said
        }
    }

    /**
     * RoutingBrain.moneyEnd for a bank step: a failed step, or a screen that isn't a menu (payment confirmation, PIN, last
     * message), ENDS the task at once — before the screen is observed again (Secure Input would otherwise take over before
     * the confirmation is said) and before any recovery step runs in a bank's menu. Null = the guide goes on.
     */
    fun end(history: List<String>, session: BankSession.View? = BankSession.current()): Pair<AgentStatus, String>? {
        val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return null
        if (!last.startsWith("bank_ussd(")) return null
        if (last.contains("[preflight]")) return AgentStatus.CANNOT_DO to stepMessage(last).ifBlank { "I couldn't open the bank's menu." }
        if (!last.contains("→ success")) return AgentStatus.CANNOT_DO to stepMessage(last).ifBlank { "The bank's menu didn't work just now. Nothing was sent." }
        val s = session ?: return AgentStatus.CANNOT_DO to "The bank's menu has closed. Nothing is sent without your PIN."
        if (s.kind == BankUssd.ScreenKind.MENU) return null
        return AgentStatus.COMPLETED to handOffLine(s)
    }

    private val STEP_LINE = Regex("^[a-z_]+\\(")
    private val RECOVERY_HINT = Regex(" — (?:this route|re-observe|only the user|the '[^']*' accelerator)")

    private fun stepMessage(last: String): String = RECOVERY_HINT.split(LocalBrain.resultMessage(last), 2)[0].trim()

    private fun answerOf(line: String): String? =
        Regex("the user answered: \"(.*)\"(?: \\(= .*\\))?\\s*$").find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private fun questionOf(line: String): String? = Regex("^ask_user\\(\"(.*?)\"\\) →").find(line)?.groupValues?.get(1)
}
