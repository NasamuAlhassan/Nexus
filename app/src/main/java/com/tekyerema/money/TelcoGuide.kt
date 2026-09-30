package com.tekyerema.money

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LocalBrain
import org.json.JSONObject

/**
 * The guided NETWORK menu — data bundles, Mashup, offers, data balance — decided on the phone every step, before any
 * brain (MoneyPlan.decide), online or offline. The telco twin of [BankGuide], plus the hybrid the USSD research
 * recommends (ghana_ussd_flows.md, Recommendation): the researched steps are AUTO-taken, but only when a menu row's
 * own words on the LIVE screen match ("1. Buy Data Bundles" is pressed because the screen says so, never because the
 * research said "1") — any screen that doesn't match is read aloud and the user chooses. A payment confirmation or a
 * PIN ends the task with the user (Secure Input; nothing is paid without the user's own PIN).
 */
object TelcoGuide {
    const val RATIONALE = "local:guided telco USSD"
    const val STOPPED = "Okay — I've stopped. Press Cancel on the network's screen to close it; nothing is paid without your PIN."
    private const val AGAIN = "Again — "
    private const val REASON = "telco"

    /** The step for a network-menu request, or null when [goal] isn't one. */
    fun decide(goal: String, history: List<String>, session: TelcoSession.View? = TelcoSession.current(),
               network: String? = simNetwork()): AgentDecision? {
        if (history.isEmpty()) {
            return when (val req = TelcoUssd.parse(goal, network) ?: return null) {
                is TelcoUssd.Request.Unsupported -> AgentDecision.Done(req.line)
                is TelcoUssd.Request.Open -> open(req.flow)
            }
        }
        // Mid-task: only OUR task (a telco step or our ask in history) is continued here.
        val ours = history.any { it.startsWith("${TelcoUssdTool.NAME}(") }
        if (!ours && TelcoUssd.parse(goal, network) == null) return null
        val last = LocalBrain.lastStep(history)
        return when {
            last.startsWith("ask_user(") && last.contains("→ not asked") -> AgentDecision.CannotDo(REASON, STOPPED)
            last.startsWith("ask_user(") -> {
                val answer = answerOf(last) ?: return AgentDecision.Done(STOPPED)
                menuAnswer(answer, last, session)
            }
            last.startsWith("${TelcoUssdTool.NAME}(") -> afterStep(last, session)
            else -> if (ours) AgentDecision.Done(STOPPED) else AgentDecision.Done("Okay — I didn't open it.")
        }
    }

    private fun open(flow: TelcoUssd.Flow): AgentDecision {
        noteOpen(flow)
        return AgentDecision.CallTool(TelcoUssdTool.NAME, JSONObject()
            .put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN)
            .put(TelcoUssdTool.ARG_FLOW, flow.key).put(TelcoUssdTool.ARG_CODE, flow.code)
            .put(TelcoUssdTool.ARG_WHAT, flow.what).put(TelcoUssdTool.ARG_NET, flow.netName), RATIONALE)
    }

    /** [TelcoUssdTool.open]'s args back as the flow (the tool never dials a code the catalog didn't give). */
    internal fun flowOf(args: JSONObject): TelcoUssd.Flow? {
        val code = args.optString(TelcoUssdTool.ARG_CODE).takeIf { Regex("^\\*[0-9*]+#$").matches(it) } ?: return null
        val session = TelcoSession.current()
        // The auto-steps come from a fresh parse-independent source: the session (reply path) or the pending open.
        return session?.flow?.takeIf { it.code == code } ?: pendingOpen?.takeIf { it.code == code }
    }

    /** The flow whose open is being confirmed (set at [open]'s decision, read by the tool when it runs). */
    @Volatile private var pendingOpen: TelcoUssd.Flow? = null

    private fun menuAnswer(answer: String, last: String, s: TelcoSession.View?): AgentDecision {
        val session = s ?: return AgentDecision.CannotDo(REASON, "The network's menu has closed. Nothing is paid without your PIN.")
        if (BankUssd.isCancel(answer)) return AgentDecision.Done(STOPPED)
        if (BankUssd.isRepeat(answer)) {
            if (questionOf(last)?.startsWith(AGAIN) == true) return AgentDecision.CannotDo(REASON, STOPPED)
            return ask(AGAIN + question(session, first = false))
        }
        val entry = BankUssd.entry(answer) ?: return ask(AGAIN + question(session, first = false))
        return reply(session, entry, auto = false, more = false, row = null)
    }

    private fun afterStep(last: String, s: TelcoSession.View?): AgentDecision {
        if (!last.contains("→ success"))
            return AgentDecision.CannotDo(REASON, stepMessage(last).ifBlank { "The network's menu didn't work just now. Nothing was bought." })
        val session = s ?: return AgentDecision.Done(STOPPED)
        if (session.kind != BankUssd.ScreenKind.MENU) return AgentDecision.Done(handOffLine(session))
        // The hybrid: while researched steps remain, press the one whose row the LIVE screen shows; a long menu is paged
        // with "More" a bounded few times; anything else falls back to asking the user.
        val step = session.flow.auto.getOrNull(session.stepDone)
        if (step != null) {
            TelcoUssd.autoAnswer(session.screen, step)?.let { n ->
                val row = TelcoUssd.rows(session.screen).firstOrNull { it.first == n }?.second
                return reply(session, n, auto = true, more = false, row = row)
            }
            if (session.morePressed < 3) TelcoUssd.moreOption(session.screen)?.let { n ->
                return reply(session, n, auto = true, more = true, row = "More")
            }
        }
        // First ask of this walk: no reply and no ask has happened yet (like the bank's first-question check).
        return ask(question(session, first = !last.contains("\"${TelcoUssdTool.ARG_ACTION}\":\"${TelcoUssdTool.REPLY}\"")))
    }

    private fun reply(session: TelcoSession.View, value: String, auto: Boolean, more: Boolean, row: String?): AgentDecision {
        val args = JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.REPLY).put(TelcoUssdTool.ARG_TEXT, value)
        if (auto) args.put(TelcoUssdTool.ARG_AUTO, true)
        if (more) args.put(TelcoUssdTool.ARG_MORE, true)
        row?.let { args.put(TelcoUssdTool.ARG_ROW, it.take(60)) }
        if (!auto) args.put(TelcoUssdTool.ARG_SCREEN, BankUssd.spokenScreen(session.screen).take(160))
        return AgentDecision.CallTool(TelcoUssdTool.NAME, args, RATIONALE)
    }

    private fun ask(q: String): AgentDecision = AgentDecision.CallTool("ask_user", JSONObject().put("question", q), RATIONALE)

    /** "MTN says: “1 Buy Data; 2 …”. What should I enter?" — the screen first, one question. */
    fun question(s: TelcoSession.View, first: Boolean): String =
        "${s.flow.netName} says: “${BankUssd.spokenScreen(s.screen)}”. What should I enter?" +
            if (first) com.tekyerema.voice.Coaching.tail("tail_telco_cancel", " You can say cancel to stop.") else ""

    /** The last line at a payment confirmation, a PIN prompt or a final message — read out, then the user takes over. */
    fun handOffLine(s: TelcoSession.View): String {
        val said = "${s.flow.netName}'s screen says: “${BankUssd.spokenScreen(s.screen)}”."
        return when (s.kind) {
            BankUssd.ScreenKind.MONEY -> "$said If that's right, confirm it on the screen and enter your PIN yourself; if not, press Cancel. " +
                "I've stopped here — I never confirm a payment or type a PIN."
            BankUssd.ScreenKind.PIN -> "$said It's asking for your PIN — enter it yourself if everything is right, or press Cancel. I never see or type it."
            BankUssd.ScreenKind.GUARDED -> "$said It mentions a PIN or a code, so I'll leave this screen to you — choose on it yourself, or press Cancel."
            else -> said
        }
    }

    /**
     * RoutingBrain.moneyEnd for a telco step: a failed step, or a screen that isn't a menu (payment confirmation, PIN,
     * last message), ENDS the task at once — before the screen is observed again and before any recovery step runs in a
     * network's menu. Null = the guide goes on.
     */
    fun end(history: List<String>, session: TelcoSession.View? = TelcoSession.current()): Pair<AgentStatus, String>? {
        val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return null
        if (!last.startsWith("${TelcoUssdTool.NAME}(")) return null
        if (last.contains("[preflight]")) return AgentStatus.CANNOT_DO to stepMessage(last).ifBlank { "I couldn't open the network's menu." }
        if (!last.contains("→ success")) return AgentStatus.CANNOT_DO to stepMessage(last).ifBlank { "The network's menu didn't work just now. Nothing was bought." }
        val s = session ?: return AgentStatus.CANNOT_DO to "The network's menu has closed. Nothing is paid without your PIN."
        if (s.kind == BankUssd.ScreenKind.MENU) return null
        return AgentStatus.COMPLETED to handOffLine(s)
    }

    /** The SIM's network key ("mtn" | "telecel" | "airteltigo"), via the same table Mobile Money uses. */
    private fun simNetwork(): String? = try {
        val ctx = com.tekyerema.agent.AgentEngine.appContext() ?: return null
        val tm = ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        com.tekyerema.tools.GhanaMobileMoney.network(tm?.simOperatorName?.ifBlank { tm.simOperator } ?: tm?.simOperator)
    } catch (_: Throwable) { null }

    /** [open] decisions remember their flow for the tool (the args carry only strings). */
    fun noteOpen(flow: TelcoUssd.Flow) { pendingOpen = flow }

    fun resetForTest() { pendingOpen = null; TelcoSession.end() }

    private val STEP_LINE = Regex("^[a-z_]+\\(")
    private val RECOVERY_HINT = Regex(" — (?:this route|re-observe|only the user|the '[^']*' accelerator)")
    private fun stepMessage(last: String): String = RECOVERY_HINT.split(LocalBrain.resultMessage(last), 2)[0].trim()
    private fun answerOf(line: String): String? =
        Regex("the user answered: \"(.*)\"(?: \\(= .*\\))?\\s*$").find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    private fun questionOf(line: String): String? = Regex("^ask_user\\(\"(.*?)\"\\) →").find(line)?.groupValues?.get(1)
}
