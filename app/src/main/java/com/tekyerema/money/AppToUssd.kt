package com.tekyerema.money

/**
 * The network drops while a money task is being done in MTN's app ([MtnApp], online): say so at once and, when the request can be
 * done on the USSD menu, ask before switching to it (owner, 28 Oct 2026: "when it's going through the online menus and the network
 * drops, it automatically alerts the person and re-confirms to go through the offline path if the request can do so").
 *
 * Never a second payment: once the app task passed the read-back the user confirmed, or reached the PIN, the money may already have
 * gone — then the user is told to check their MoMo message or balance first and nothing is switched. Pure → AppToUssdTest.
 */
object AppToUssd {
    const val ASK = "The internet dropped, so I can't finish this in the MTN app. I can do it on the MoMo menu instead, which " +
        "works without internet. Shall I?"
    const val STOPPED = "Okay, I've stopped. Nothing more was done."
    const val CHECK_FIRST = "The internet dropped after you confirmed in the MTN app, so I can't tell whether it went through. Check " +
        "your MoMo message or your balance before trying again. I won't do it a second time."
    const val NO_MENU = "The internet dropped, so I can't finish this in the MTN app, and this request has no offline menu. Ask me " +
        "again when the internet is back."

    sealed class Outcome {
        /** Ask [ASK]. */
        object Ask : Outcome()
        /** Said and the task ends. */
        data class Stop(val line: String) : Outcome()
        /** The user agreed: continue on USSD with only the steps since the answer (the app steps are not the menu's). */
        data class Switch(val history: List<String>) : Outcome()
    }

    private val APP_STEP = Regex("(?i)^open_app\\(.*(?:mtn|momo)|com\\.mtn(?:gh)?\\.")
    private val PIN_STEP = Regex("(?i)\\bPIN\\b|secure[_ ](?:step|input|done)")
    private val MONEY_CONFIRM = Regex("(?i)\\b(?:send|pay|buy|transfer|confirm|withdraw)\\b")

    /**
     * What to do this step, or null when this is not an app money task cut off by the network. [online] is the network now;
     * [menuCanDoIt] — the request is one the USSD walk handles (a MoMo send, airtime, a balance).
     */
    fun decide(history: List<String>, online: Boolean, menuCanDoIt: Boolean, isYes: (String) -> Boolean): Outcome? {
        if (history.isEmpty()) return null
        val asked = history.indexOfLast { MoneyFlow.answerOf(it)?.first == ASK }
        if (asked >= 0) {
            val answer = MoneyFlow.answerOf(history[asked])!!.second
            return if (isYes(answer)) Outcome.Switch(history.drop(asked + 1)) else Outcome.Stop(STOPPED)
        }
        if (online) return null
        if (history.none { APP_STEP.containsMatchIn(it) }) return null
        if (pastConfirm(history)) return Outcome.Stop(CHECK_FIRST)
        return if (menuCanDoIt) Outcome.Ask else Outcome.Stop(NO_MENU)
    }

    /**
     * The decision for a [Outcome.Stop]. After the read-back or the PIN the phone first reads the network's own Mobile Money messages
     * ([MoneyStatusTool], works offline) and says what they show, instead of only telling the user to check — then the task ends.
     */
    fun stopDecision(line: String, history: List<String>): com.tekyerema.agent.AgentDecision {
        if (line != CHECK_FIRST) return com.tekyerema.agent.AgentDecision.Done(line)
        val last = com.tekyerema.agent.LocalBrain.lastStep(history)
        if (last.startsWith("${MoneyStatusTool.NAME}(")) {
            val said = MoneyPlan.spokenPart(com.tekyerema.agent.LocalBrain.resultMessage(last))
            return com.tekyerema.agent.AgentDecision.Done(
                "The internet dropped after you confirmed in the MTN app. " + said.ifBlank { "I couldn't read your MoMo messages." } +
                    " I won't do it a second time; if you're not sure, check your balance before trying again.")
        }
        return com.tekyerema.agent.AgentDecision.CallTool(MoneyStatusTool.NAME,
            org.json.JSONObject().put(MoneyStatusTool.ARG_MODE, MoneyStatusTool.STATUS), "network dropped after the confirmation: what do the MoMo messages say")
    }

    /** The app task reached the PIN, or the user said yes to a money read-back: the money may already have gone. */
    internal fun pastConfirm(history: List<String>): Boolean =
        history.any { PIN_STEP.containsMatchIn(it) } ||
            MoneyFlow.answers(history).any { (q, a) -> MONEY_CONFIRM.containsMatchIn(q) && a.trim().lowercase().let { s ->
                s.startsWith("yes") || s.startsWith("yeah") || s == "ok" || s == "okay" || s.startsWith("aane") || s.startsWith("send") ||
                    s.startsWith("confirm") || s.startsWith("go ahead") || s.startsWith("do it") } }
}
