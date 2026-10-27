package com.tekyerema.money.ussd

import android.content.Context
import android.util.Log
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.agent.NumberSpeech
import com.tekyerema.money.MoMoOutcome
import com.tekyerema.money.PinPad
import com.tekyerema.money.TxLedger
import com.tekyerema.money.UssdDialog
import com.tekyerema.money.UssdSpeech
import com.tekyerema.voice.SecureStep
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The network's LAST WORD after a hand-off at the PIN, read out — whatever the walk was for.
 *
 * Until 13 October 2026 only a balance check's answer was read from the screen (UssdNavigatorTool); a send relied on
 * MTN's text message (phonecare/PhoneAlerts → TxLedger). But the receipt is on the screen the moment the PIN goes
 * through — "Payment made for GHS 5.00 to KOFI MENSAH …" — where a blind user cannot read it; the text can be minutes
 * behind, and on the emulator's simulator it never comes (the first full walk, audit/emu-2026-10-13/momo-sim/021238:
 * the volume-key PIN went in, the receipt sat on the screen, nothing was said). So after EVERY hand-off — the
 * navigator's and the guided tool's — the phone's dialog is watched:
 *  - while it still has an entry box (the PIN prompt, a retry) nothing about it is kept, logged or spoken; what is
 *    typed there never reaches a snapshot at all (accessibility/TelephonyWindows);
 *  - the first screen without one is the answer. A payment's outcome for a hand-off still open in the ledger SETTLES it
 *    (TxLedger.settle: today's total, the known-recipient mark, the repeat guard) and the ledger's own short receipt is
 *    spoken — "MTN confirms 5 cedis sent to Kofi Mensah." The balance is in it only when the user has balances switched
 *    on (and, with private alerts, not to the room). The same outcome arriving later by text is the same message to the
 *    ledger (its transaction ID, else kind + amount + party): it is not said twice;
 *  - anything else — a balance, "Wrong PIN. You have 2 attempts left.", a failure the parser doesn't know — is read as
 *    written, paced for the ear (UssdSpeech.forEar), in Twi when it is a balance and the conversation is in Twi.
 * Then the dialog's OK is pressed for the user and the secure step ends. It gives up quietly when the dialog has been
 * gone for a while (cancelled) or after the secure step's own limit.
 */
object AfterPin {
    private const val TAG = "Tekyerema"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    /** Polling after the PIN (was 900 ms; the answer is spoken that much sooner). The "closed with no answer" notice keeps its ~7 s, the give-up its ~22 s. */
    private const val POLL_MS = 500L
    private const val GONE_TOLD = 14
    private const val GONE_GIVE_UP = 44

    /** What is said for the network's answer; [settled] when it closed a hand-off in the ledger. */
    data class Spoken(val english: String, val twi: String?, val settled: TxLedger.Settled?)

    /**
     * The line for [text] — the network's screen with no entry box — after a hand-off at [network]'s PIN. Pure apart
     * from the ledger it settles. [withBalance]: the user hears balances (phonecare/PhoneAlerts.balanceAloud).
     */
    fun lineFor(text: String, network: String?, withBalance: Boolean, twiConversation: Boolean,
                now: Long = System.currentTimeMillis()): Spoken {
        val name = network?.let { NumberSpeech.networkName(it) }
        val intro = "${name ?: "The network"} says"
        // The screen after the PIN belongs to the hand-off just made — the newest one still waiting — and to no other.
        val mine = TxLedger.waiting(now).lastOrNull()
        val open = mine != null || TxLedger.unsettled(now).isNotEmpty()
        // (isSecret: a one-time code is never a receipt; it also fires on the word "PIN", so it guards the parse only.)
        val settled = if (open && !MoMoOutcome.isSecret(text))
            MoMoOutcome.parse(text)?.let { TxLedger.settle(it, now, withBalance, now, eachItsOwn = true, onlyAt = mine?.at) } else null
        if (settled != null) return Spoken(settled.line, null, settled)
        // A wrong or blocked PIN, "Not enough funds", a declined transfer: the session ended with nothing sent. The open
        // hand-off is settled as FAILED here (today's total gets the amount back, the 150-s follow-up has nothing to chase)
        // and the network's own words are read. (The SMS parser's failure rule needs an amount in the text; the screen
        // rarely has one — the ledger entry's amount is used.)
        if (mine != null && UNPAID_END.containsMatchIn(text)) {
            val failed = mine.amount?.let {
                TxLedger.settle(MoMoOutcome.Msg(MoMoOutcome.Kind.FAILED, it, null, null, null, null, null, null), now, false, now,
                    eachItsOwn = true, onlyAt = mine.at)
            }
            return Spoken(UssdSpeech.forEar(text, intro = intro) + " Nothing left your wallet.", null, failed)
        }
        val twi = if (twiConversation) UssdSpeech.twiBalance(text, name ?: "Network no") else null
        return Spoken(UssdSpeech.forEar(text, intro = intro), twi, null)
    }

    /** "Wrong PIN. You have 2 attempts left." / "Your PIN has been blocked" / "Not enough funds to perform this transaction."
     *  (MTN's own final, A20e 3 October 2026) / "Transaction failed": the session ended with nothing sent. */
    private val UNPAID_END = Regex("(?i)\\b(?:wrong|incorrect|invalid)\\s+(?:mm\\s+|momo\\s+)?pin\\b|" +
        "\\bpin\\s+(?:is\\s+|was\\s+)?(?:wrong|incorrect|invalid)\\b|\\bpin\\s+(?:has\\s+been\\s+|is\\s+)?blocked\\b|" +
        "\\bnot\\s+enough\\s+(?:funds|money|balance)\\b|\\binsufficient\\s+(?:funds|balance|money)\\b|" +
        "\\b(?:transaction|transfer|payment|request)\\s+(?:has\\s+)?(?:failed|declined|was\\s+declined|cancelled|canceled|unsuccessful)\\b|" +
        "\\bcould\\s+not\\s+be\\s+(?:completed|processed)\\b|\\blimit\\s+exceeded\\b")

    /** Watch the phone's dialog after a hand-off at [network]'s PIN (or payment confirmation) and say its last word. */
    fun watch(ctrl: AccessibilityController, app: Context?, network: String?) {
        job?.cancel()
        job = scope.launch {
            val deadline = android.os.SystemClock.uptimeMillis() + SecureStep.MAX_MS
            var gone = 0
            var told = false
            var confirmAsked = false
            val started = System.currentTimeMillis()
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                delay(POLL_MS)
                val s = try { UssdDialog.read(ctrl) } catch (_: Throwable) { null }
                if (s == null) {
                    gone++
                    // The PIN went in and the dialog closed with no answer on the screen: MTN replies by TEXT. It used to end in
                    // silence (the owner's Mashup, 17 Oct 2026: "I didn't complete it") — say so, once.
                    // ~7 s, not ~4.5 s: the click on the dialog's Cancel can reach the service a moment after the dialog has gone, and a
                    // cancel must not be followed by "the MTN screen closed…" (A20e Twi take 22, 20 Oct 2026).
                    if (gone == GONE_TOLD && !told) {
                        told = true
                        if (com.tekyerema.phone.PinCurtain.cancelledSince(started)) { PinPad.disarm(); SecureStep.end(); return@launch }
                        Log.i(TAG, "[MONEY] the dialog closed after the PIN with no answer on screen — told the user the result comes by text")
                        // Council review, 19 Oct 2026: after a CANCEL this said "PIN sent" — wrong, and it could make a user think money
                        // had moved. "PIN sent" only when the volume keys sent one; otherwise what is known: the screen closed.
                        val line = if (com.tekyerema.money.PinPad.lastSentAt >= started)
                            "PIN sent. MTN will text you the result, and I'll read it out when it comes."
                        else "The MTN screen closed. If you entered your PIN, MTN will text you the result. If you cancelled, nothing was sent."
                        try { VoiceEngine.announce(line) } catch (_: Throwable) {}
                    }
                    if (gone > GONE_GIVE_UP) { PinPad.disarm(); SecureStep.end(); return@launch }   // no dialog for ~22 s: closed or cancelled
                    continue
                }
                gone = 0
                // MTN's confirmation AFTER the PIN ("1. Confirm 2. Cancel"): read out, answered with a key (AfterPinConfirm) —
                // before 21 Oct 2026 any screen with a box was taken for the PIN again and the user was left in silence.
                if (s.hasInput && !s.masked && !confirmAsked && AfterPinConfirm.looksLikeConfirm(s.text)) {
                    confirmAsked = true
                    PinPad.disarm()                                  // the volume keys answer this screen now, not a PIN
                    AfterPinConfirm.arm(ctrl, s, app)
                    val name = network?.let { NumberSpeech.networkName(it) } ?: "The network"
                    try { VoiceEngine.announce(UssdSpeech.forEar(s.text, intro = "$name asks you to confirm") +
                        " Press volume up to confirm, or the power button to cancel.") } catch (_: Throwable) {}
                    continue
                }
                if (s.hasInput || s.masked) continue                              // still asking for something: not ours to read
                val withBalance = app?.let { a -> try { com.tekyerema.phonecare.PhoneAlerts.balanceAloud(a) } catch (_: Throwable) { false } } ?: false
                val twiConversation = try { VoiceEngine.speaksTwi() } catch (_: Throwable) { false }
                val spoken = lineFor(s.text, network, withBalance, twiConversation)
                // A payment that didn't go through is never texted as sent afterwards ("tell him I've sent it", money/LastSend).
                if (spoken.settled?.tx?.state == TxLedger.State.FAILED || UNPAID_END.containsMatchIn(s.text)) {
                    com.tekyerema.money.LastSend.markFailed(); Log.i(TAG, "[MONEY] the last send is marked as not gone through")
                }
                Log.i(TAG, if (spoken.settled != null)
                    "[MONEY] outcome ${spoken.settled.tx.state.name.lowercase()} from the network's screen after the PIN"
                    else "[NAV] the network's answer after the PIN is read out (${s.text.length} chars${if (spoken.twi != null) ", in Twi" else ""})")
                // A payment's outcome (sent, or not): offer the next step — tell the person (agent/NextStep). Not after a
                // balance or any other answer.
                val offer = if (spoken.settled != null && com.tekyerema.agent.NextStep.enabled())
                    com.tekyerema.money.LastSend.recent()?.let { sent ->
                        com.tekyerema.agent.NextStep.afterSend(sent.name, spoken.settled.tx.state == TxLedger.State.FAILED || sent.failed,
                            System.currentTimeMillis())
                    } else null
                offer?.let { com.tekyerema.agent.NextStep.offer(it) }
                VoiceEngine.announceAnswer(
                    offer?.let { spoken.english + com.tekyerema.voice.SpokenText.BREAK + it.english } ?: spoken.english,
                    offer?.twi?.let { t -> spoken.twi?.let { "$it $t" } } ?: spoken.twi,
                    listenAfter = offer != null)
                // The answer has been taken: the dialog is closed so the user isn't left to find its OK button, and the
                // secure step (no self-opened microphone, the volume keys as a PIN pad) is over.
                try { s.cancelId?.let { ctrl.clickElement(it) } } catch (_: Throwable) {}
                PinPad.disarm(); SecureStep.end()
                return@launch
            }
        }
    }

    /** The walk was cancelled or a new one started: nothing more is read from this dialog. */
    fun cancel() { job?.cancel(); job = null; AfterPinConfirm.disarm() }
}
