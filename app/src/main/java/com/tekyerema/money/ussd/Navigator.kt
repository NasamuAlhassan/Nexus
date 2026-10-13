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
    private val online: OnlineFallback? = null,
    /** The loudspeaker PIN tip for this phone right now (tools/MoneyPrivacy), said after the hand-off line. */
    private val pinTip: String? = null,
    /** The network has confirmed a send to this recipient from this phone before (money/KnownRecipients). */
    private val paidBefore: Boolean = false
) {
    sealed class Outcome {
        /** The consolidated line was spoken-worthy and the PIN/confirmation is the user's: the walk SUCCEEDED up to
         *  the boundary. [handedOff] = it reached the authorization (limits/receipt bookkeeping applies). */
        data class Boundary(val line: String, val handedOff: Boolean, val registeredName: String? = null) : Outcome()
        data class Final(val line: String) : Outcome()                  // the network's last word (a balance, a receipt)
        /** Guided from here — the user chooses. [pending] = the brain's own question, its answers mapped to rows. */
        data class NeedUser(val ask: String, val pending: MenuBrain.Move.Ask? = null) : Outcome()
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
        current = first
        try {
            return walk(first, maxSteps).also { current = null }   // a boundary (the PIN screen) is the user's to keep
        } catch (c: kotlinx.coroutines.CancellationException) {
            // "Stop" in the middle of the walk: the network's dialog is closed before the task ends — nothing in it has
            // moved money yet (a PIN screen ends the walk as a boundary, never here), and a blind user must not be left
            // with MTN's menu on the screen (QA E04, A20e, 3 October 2026: "stop" after two menus left the dialog open).
            val s = current
            if (s != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    t("STOP", "stopped by the user — closing the menu")
                    // The press just made may still be answered: the network's NEXT screen arrives a moment later and would
                    // be left open if only the remembered one were closed (R2_E04, 3 October 2026). Close what is there.
                    kotlinx.coroutines.delay(1_800)
                    cancel(UssdDialog.read(ctrl) ?: s)
                    kotlinx.coroutines.delay(1_200)
                    UssdDialog.read(ctrl)?.let { cancel(it) }
                } catch (_: Throwable) {}
            }
            throw c
        }
    }

    /** The screen the walk is on right now (closed if the walk is cancelled). */
    @Volatile private var current: UssdDialog.Screen? = null

    private suspend fun walk(first: UssdDialog.Screen, maxSteps: Int): Outcome {
        var screen: UssdDialog.Screen = first
        var lastPressedMenu: String? = null
        var morePressed = 0
        var lastEntered: SlotKind? = null      // the slot just typed into — the same one asked again means it was refused
        repeat(maxSteps) {
            current = screen
            val m = MenuModel.read(screen.text, screen.hasInput, screen.masked)
            // S-03: the PIN / payment-confirmation screen (the registered name, the amount) is traced by size, never in words.
            t("READ", if (m.kind == MenuModel.Kind.PIN || m.kind == MenuModel.Kind.CONFIRM) "${m.kind} (${m.text.length} chars)" else "${m.kind} ${m.text.take(80)}")
            when (m.kind) {
                MenuModel.Kind.PIN, MenuModel.Kind.CONFIRM -> return boundary(m, screen)
                MenuModel.Kind.ERROR -> { val closed = cancel(screen); return Outcome.Failed(honestError(m.text, closed)) }
                MenuModel.Kind.FINAL -> {
                    // The network's last word. Its words have been taken, so its dialog is closed here — a blind user was
                    // left with MTN's "OK" box on the screen (A20e, 2 October 2026). For a balance it is the answer; for
                    // anything that moves money it came BEFORE the PIN, so it is a refusal ("Not enough funds to perform
                    // this transaction") and is said as one: the network's words, then what did not happen.
                    // (A message that the request is on its way — "you will receive a prompt to authorise" — is not a
                    // refusal: it is read as it is, with no claim about what did or did not happen.)
                    cancel(screen)
                    return if (tx.intent == TxIntent.CHECK_BALANCE || IN_PROGRESS.containsMatchIn(m.text)) Outcome.Final(UssdSpeech.forEar(m.text))
                        else Outcome.Failed(endedBeforePin(tx, m.text))
                }
                MenuModel.Kind.INPUT -> {
                    val kind = m.slot ?: SlotKind.OTHER
                    val value = tx.slot(kind)
                        ?: return Outcome.NeedUser("The network asks: “${m.text.take(120)}”. What should I enter?")
                    // Money safety: what the network refused is NEVER typed again, and an amount outside the range the
                    // screen states is never typed at all ([entryStop]). The menu is closed and the reason said.
                    entryStop(tx, kind, m.text, value, lastEntered)?.let { why ->
                        t("REFUSED", "${kind.name}: not entered")
                        val closed = cancel(screen)
                        return Outcome.Failed(why + (if (closed) " I've closed the menu. " else " Press Cancel on the menu. ") + nothing())
                    }
                    lastEntered = kind
                    t("ENTER", "${kind.name} (${value.length} chars)")
                    screen = press(m.text, value) ?: return timedOut()
                }
                MenuModel.Kind.MENU -> {
                    // The same menu back after a press = the network refused the option — never press it again blind.
                    if (m.text == lastPressedMenu)
                        return Outcome.NeedUser("The menu came back unchanged — it may not have accepted that. " +
                            "It says: “${UssdSpeech.forEar(m.text, intro = "")}”. What should I enter?")
                    // The menu brain first for what is about MONEY on this screen (a loan, an offer to pay another way, how
                    // to pay): those are the user's to decide, before any meaning match could press a row.
                    val brain = MenuBrain.decide(goal(), m.text)
                    if (brain is MenuBrain.Move.Ask && brain.urgent) { t("ASK", brain.kind.name); return Outcome.NeedUser(brain.question, brain) }
                    val v = SemanticMatch.best(tx.intent, m.rows, embed, crossNetwork(),
                        com.tekyerema.agent.NumberSpeech.networkKey(tx.recipient))
                    t("MATCH", "best=${v?.option} conf=${"%.2f".format(v?.confidence ?: 0.0)} margin=${"%.2f".format(v?.margin ?: 0.0)}")
                    val choice = when {
                        v != null && v.confidence >= SemanticMatch.actBar() && v.margin >= SemanticMatch.marginBar() -> v.option
                        brain is MenuBrain.Move.Press -> brain.option.also { t("BRAIN", brain.why) }
                        // Page on while the intent's row may simply be on a later page (bounded).
                        morePressed < 3 && com.tekyerema.money.TelcoUssd.moreOption(m.text) != null &&
                            (v == null || v.confidence < SemanticMatch.fallbackBar()) -> {
                            morePressed++
                            t("PAGE", "More (${morePressed})")
                            com.tekyerema.money.TelcoUssd.moreOption(m.text)
                        }
                        else -> online?.choose(tx.intent, m.rows)?.also { t("ONLINE", "chose $it") }
                    } ?: return (brain as? MenuBrain.Move.Ask)?.let { Outcome.NeedUser(it.question, it) } ?: Outcome.NeedUser(
                        "I'm not sure which option means ${tx.intent.spoken} here. The menu says: " +
                            "“${UssdSpeech.forEar(m.text, intro = "")}”. Which number should I enter?")
                    lastEntered = null
                    lastPressedMenu = if (choice == com.tekyerema.money.TelcoUssd.moreOption(m.text)) null else m.text
                    t("PRESS", "option $choice")
                    screen = press(m.text, choice) ?: return timedOut()
                }
            }
        }
        return Outcome.Failed("That menu went deeper than I expected, so I stopped. ${nothing()}")
    }

    /** A send: the recipient's number is on another network than the SIM (decides "MoMo User" vs "Other Networks");
     *  null when either network isn't known — then nothing is assumed. */
    private fun crossNetwork(): Boolean? {
        if (tx.intent != TxIntent.SEND_MONEY && tx.intent != TxIntent.SEND_AIRTIME) return null
        val sim = tx.provider?.lowercase() ?: return null
        val to = com.tekyerema.agent.NumberSpeech.networkKey(tx.recipient ?: return null) ?: return null
        return to != sim
    }

    /** What the menu brain chooses by on a Mobile Money walk: paid from the wallet (the PIN is the last step), for the
     *  recipient when there is one. The amount is the send's, never a product price — a "Buy" is always asked. */
    private fun goal() = MenuBrain.Goal(amount = tx.amount, pay = "momo",
        forOthers = tx.recipient != null && tx.intent != TxIntent.CHECK_BALANCE,
        net = tx.provider?.let { com.tekyerema.agent.NumberSpeech.networkName(it) } ?: "The network")

    /** What did NOT happen, in the intent's own word: a balance check never sends or buys anything. */
    private fun nothing(): String = when (tx.intent) {
        TxIntent.SEND_MONEY -> "Nothing was sent."
        TxIntent.CHECK_BALANCE -> "Nothing was changed."
        else -> "Nothing was bought."
    }

    /** §9/§10 — the ONE consolidated line at the authorization ([Boundary]: the network's own shown name, fee and total;
     *  the amount and any shown number cross-checked against what the user confirmed). A screen that doesn't match is
     *  cancelled HERE — a blind user is not told to go and find the Cancel button. */
    private suspend fun boundary(m: MenuModel.Screen, screen: UssdDialog.Screen): Outcome {
        // A payment confirmation with its own confirm key ("Reply 1 to confirm or 0 to start again"): the checked summary,
        // then ONE spoken question — at the user's yes Tɛkyerɛma presses the key, and the PIN that follows is theirs.
        val keys = if (m.kind == MenuModel.Kind.CONFIRM) MenuBrain.confirmKeys(m.text) else null
        val twi = try { com.tekyerema.voice.VoiceEngine.speaksTwi() } catch (_: Throwable) { false }
        return when (val v = Boundary.decide(tx, m, pinTip, paidBefore, confirmByVoice = keys != null, twi = twi)) {
            is Boundary.Verdict.Stop -> {
                t("MISMATCH", v.why)
                val pressed = cancel(screen)
                Outcome.Failed(com.tekyerema.tools.MtnConfirm.cancelledLine(v.why, pressed))
            }
            is Boundary.Verdict.HandOff -> if (keys != null) {
                t("CONFIRM", "asked by voice")
                Outcome.NeedUser(v.line, MenuBrain.Move.Ask(v.line, MenuBrain.Kind.CONFIRM, mapOf("yes" to keys.first, "no" to keys.second)))
            } else {
                t("BOUNDARY", "pin=${m.kind == MenuModel.Kind.PIN} name=${v.registeredName != null}")
                com.tekyerema.voice.SecureStep.begin()
                com.tekyerema.agent.helper.HelperGate.atPin()
                if (m.kind == MenuModel.Kind.PIN) com.tekyerema.money.PinPad.arm()
                Outcome.Boundary(v.line, handedOff = true, registeredName = v.registeredName)
            }
        }
    }

    /** Press the dialog's Cancel (Back when it has none) and check the dialog went. Cancelling a USSD session never
     *  moves money. True when it is gone. */
    private suspend fun cancel(screen: UssdDialog.Screen): Boolean {
        try { screen.cancelId?.let { ctrl.clickElement(it) } ?: ctrl.globalAction("back") } catch (_: Throwable) {}
        kotlinx.coroutines.delay(900)
        var now = UssdDialog.read(ctrl)
        if (now != null && now.text == screen.text) {
            try { ctrl.globalAction("back") } catch (_: Throwable) {}
            kotlinx.coroutines.delay(900)
            now = UssdDialog.read(ctrl)
        }
        return now == null || now.text != screen.text
    }

    private fun honestError(text: String, closed: Boolean = false) =
        "The network said: “${text.take(140)}”." + (if (closed) " I've closed the menu. " else " ") + nothing()

    private fun timedOut(): Outcome {
        t("TIMEOUT", "no next screen")
        return Outcome.Failed("The network's menu didn't answer in time, so it may have closed. " + when (tx.intent) {
            TxIntent.SEND_MONEY -> "Nothing is sent without your PIN."
            TxIntent.CHECK_BALANCE -> "Nothing was changed."
            else -> "Nothing is paid without your PIN."
        })
    }

    /** Type [value] into the CURRENT dialog and wait for the next one; null when nothing came. */
    private suspend fun press(prevText: String, value: String): UssdDialog.Screen? {
        val now = UssdDialog.awaitCurrent(ctrl) ?: return null
        if (!UssdDialog.answer(ctrl, now, value)) return null
        kotlinx.coroutines.delay(400)
        return UssdDialog.awaitNext(ctrl, prevText, 18_000L)
    }

    companion object {
        private const val TAG = "Tekyerema"

        /** A last message that says the request went on (a prompt to approve will follow, it is being processed). */
        internal val IN_PROGRESS = Regex("(?i)\\bprompt\\b|\\bapprov|\\bauthori[sz]|being processed|\\bprocessing\\b|will receive|\\bsubmitted\\b|\\bsuccess")

        /** A money walk that the network ended with a message before any PIN was asked for: its words, then — in the
         *  intent's own word — what did not happen (no PIN was entered, so nothing moved). Pure. */
        internal fun endedBeforePin(tx: TxContext, screen: String): String {
            val net = tx.provider?.let { com.tekyerema.agent.NumberSpeech.networkName(it) } ?: "The network"
            val said = UssdSpeech.forEar(screen, intro = "$net says").trimEnd().let { if (it.last() in ".!?") it else "$it." }
            return said + com.tekyerema.voice.SpokenText.PAUSE + (if (tx.intent == TxIntent.SEND_MONEY) "Nothing was sent." else "Nothing was bought.")
        }

        /**
         * Why the value for [kind] must NOT be typed into this entry screen, or null when it may be. Pure → unit-tested
         * on the real screens of 2 October 2026 ("Enter amount (GHS) minimum: 0.20, maximum: 500.00", then "The amount you
         * have entered is less than or greater than allowed amount. Enter a…").
         *  - the screen says the last entry was refused, or asks again for the slot that was just typed;
         *  - an amount outside the range the screen itself states.
         */
        internal fun entryStop(tx: TxContext, kind: SlotKind, screen: String, value: String, lastEntered: SlotKind?): String? {
            val net = tx.provider?.let { com.tekyerema.agent.NumberSpeech.networkName(it) } ?: "The network"
            if (lastEntered == kind || AmountRange.REFUSED.containsMatchIn(screen))
                return "$net didn't accept what I entered. It said: “${screen.replace(Regex("\\s+"), " ").trim().take(140)}”."
            if (kind == SlotKind.AMOUNT) {
                val range = AmountRange.of(screen) ?: return null
                val v = value.replace(",", "").toBigDecimalOrNull() ?: return null
                if (!range.allows(v)) return "$net only takes ${range.spoken} here, and you asked for ${AmountRange.cedis(v)}."
            }
            return null
        }
    }
}
