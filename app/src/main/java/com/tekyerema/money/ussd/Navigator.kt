package com.tekyerema.money.ussd

import android.util.Log
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.money.UssdDialog
import com.tekyerema.money.UssdSpeech

/**
 * The generic USSD navigator (owner's brief, 2026-09-30): ONE walk, silent between decisions. Each live screen is
 * read (never trusted from a plan), understood (MenuModel), matched against the INTENT (SemanticMatch), and only
 * then pressed. The user hears nothing until the network needs them: a slot we don't hold, a screen nobody is sure
 * about, the ONE consolidated pre-authorization line, or the end. Safety order is fixed: SAFE STOP beats a wrong
 * financial press; unknown result is never retried blind; the PIN is the user's alone (SecureStep).
 *
 * The LLM is never the execution authority (§19): the online fallback may only NAME one of the rows that are
 * actually on the screen, never for a confirmation or PIN screen, and its answer still passes the same press path.
 */
class Navigator(
    private val ctrl: AccessibilityController,
    private val tx: TxContext,
    private val embed: ((String) -> FloatArray)? = null,
    private val online: OnlineFallback? = null
) {
    sealed class Outcome {
        /** The consolidated line was spoken-worthy and the PIN/confirmation is the user's: the walk SUCCEEDED up to
         *  the boundary. [handedOff] = it reached the authorization (limits/receipt bookkeeping applies). */
        data class Boundary(val line: String, val handedOff: Boolean) : Outcome()
        data class Final(val line: String) : Outcome()                  // the network's last word (a balance, a receipt)
        data class NeedUser(val ask: String) : Outcome()                // guided from here — the user chooses
        data class Failed(val line: String) : Outcome()                 // honest, nothing moved
    }

    val trace = ArrayList<TraceStep>()
    private fun t(state: String, detail: String) {
        trace.add(TraceStep(state, detail))
        Log.i(TAG, "[NAV] $state ${com.tekyerema.core.Privacy.content(detail.take(120).replace('\n', ' '))}")
    }

    /** Walk from [first] until a boundary, an end, or the user is needed. At most [maxSteps] presses.
     *  Internal: the dialog type is the money module's own, and no model ever calls the navigator directly. */
    internal suspend fun run(first: UssdDialog.Screen, maxSteps: Int = 12): Outcome {
        var screen: UssdDialog.Screen = first
        var lastPressedMenu: String? = null
        var morePressed = 0
        repeat(maxSteps) {
            val m = MenuModel.read(screen.text, screen.hasInput, screen.masked)
            t("READ", "${m.kind} ${m.text.take(80)}")
            when (m.kind) {
                MenuModel.Kind.PIN, MenuModel.Kind.CONFIRM -> return boundary(m)
                MenuModel.Kind.ERROR -> return Outcome.Failed(honestError(m.text))
                MenuModel.Kind.FINAL -> return Outcome.Final(UssdSpeech.forEar(m.text))
                MenuModel.Kind.INPUT -> {
                    val kind = m.slot ?: SlotKind.OTHER
                    val value = tx.slot(kind)
                        ?: return Outcome.NeedUser("The network asks: “${m.text.take(120)}”. What should I enter?")
                    t("ENTER", "${kind.name} (${value.length} chars)")
                    screen = press(m.text, value) ?: return timedOut()
                }
                MenuModel.Kind.MENU -> {
                    // The same menu back after a press = the network refused the option — never press it again blind.
                    if (m.text == lastPressedMenu)
                        return Outcome.NeedUser("The menu came back unchanged — it may not have accepted that. " +
                            "It says: “${UssdSpeech.forEar(m.text, intro = "")}”. What should I enter?")
                    val v = SemanticMatch.best(tx.intent, m.rows, embed)
                    t("MATCH", "best=${v?.option} conf=${"%.2f".format(v?.confidence ?: 0.0)} margin=${"%.2f".format(v?.margin ?: 0.0)}")
                    val choice = when {
                        v != null && v.confidence >= SemanticMatch.actBar() && v.margin >= SemanticMatch.marginBar() -> v.option
                        // Page on while the intent's row may simply be on a later page (bounded).
                        morePressed < 3 && com.tekyerema.money.TelcoUssd.moreOption(m.text) != null &&
                            (v == null || v.confidence < SemanticMatch.fallbackBar()) -> {
                            morePressed++
                            t("PAGE", "More (${morePressed})")
                            com.tekyerema.money.TelcoUssd.moreOption(m.text)
                        }
                        else -> online?.choose(tx.intent, m.rows)?.also { t("ONLINE", "chose $it") }
                    } ?: return Outcome.NeedUser(
                        "I'm not sure which option means ${tx.intent.spoken} here. The menu says: " +
                            "“${UssdSpeech.forEar(m.text, intro = "")}”. Which number should I enter?")
                    lastPressedMenu = if (choice == com.tekyerema.money.TelcoUssd.moreOption(m.text)) null else m.text
                    t("PRESS", "option $choice")
                    screen = press(m.text, choice) ?: return timedOut()
                }
            }
        }
        return Outcome.Failed("That menu went deeper than I expected, so I stopped. Nothing was sent.")
    }

    /** §9/§10 — the ONE consolidated line at the authorization, with everything the user needs to recognise it:
     *  the network's own shown NAME when there is one, the amount cross-checked against what the user asked. */
    private fun boundary(m: MenuModel.Screen): Outcome {
        // A shown amount that isn't the user's amount is a hard stop — cancel wording, nothing handed over.
        val askedAmount = tx.amount?.toBigDecimalOrNull()
        val shownAmount = m.shownAmount?.toBigDecimalOrNull()
        if (askedAmount != null && shownAmount != null && askedAmount.compareTo(shownAmount) != 0) {
            t("MISMATCH", "amount shown=$shownAmount asked=$askedAmount")
            return Outcome.Failed("The network's screen shows ${m.shownAmount} cedis, but you asked for ${tx.amount}. " +
                "I've stopped — press Cancel on the screen; nothing is sent without your PIN.")
        }
        val who = m.shownName ?: tx.recipientName
        val what = when (tx.intent) {
            TxIntent.SEND_MONEY -> "send ${tx.amount ?: "money"} cedis" +
                (who?.let { " to $it" } ?: tx.recipient?.let { " to ${com.tekyerema.agent.NumberSpeech.spoken(it)}" } ?: "")
            TxIntent.BUY_AIRTIME, TxIntent.SEND_AIRTIME -> "buy ${tx.amount?.plus(" cedis of ") ?: ""}airtime" +
                (who?.let { " for $it" } ?: "")
            TxIntent.BUY_DATA, TxIntent.SEND_DATA -> "buy ${tx.product ?: "the bundle"}" + (who?.let { " for $it" } ?: "")
            else -> tx.intent.spoken
        }
        t("BOUNDARY", "pin=${m.kind == MenuModel.Kind.PIN} name=${who != null}")
        com.tekyerema.voice.SecureStep.begin()
        com.tekyerema.agent.helper.HelperGate.atPin()
        val line = "You're about to $what." +
            (if (m.kind == MenuModel.Kind.PIN) " Enter your PIN to continue — I never see or type it."
             else " The screen says: “${UssdSpeech.forEar(m.text, intro = "")}”. Confirm it there and enter your PIN yourself.")
        return Outcome.Boundary(line, handedOff = true)
    }

    private fun honestError(text: String) =
        "The network said: “${text.take(140)}”. Nothing was ${if (tx.intent == TxIntent.SEND_MONEY) "sent" else "bought"}."

    private fun timedOut(): Outcome {
        t("TIMEOUT", "no next screen")
        return Outcome.Failed("The network's menu didn't answer in time, so it may have closed. " +
            "Nothing is ${if (tx.intent == TxIntent.SEND_MONEY) "sent" else "paid"} without your PIN.")
    }

    /** Type [value] into the CURRENT dialog and wait for the next one; null when nothing came. */
    private suspend fun press(prevText: String, value: String): UssdDialog.Screen? {
        val now = UssdDialog.awaitCurrent(ctrl) ?: return null
        if (!UssdDialog.answer(ctrl, now, value)) return null
        kotlinx.coroutines.delay(400)
        return UssdDialog.awaitNext(ctrl, prevText, 18_000L)
    }

    companion object { private const val TAG = "Tekyerema" }
}
