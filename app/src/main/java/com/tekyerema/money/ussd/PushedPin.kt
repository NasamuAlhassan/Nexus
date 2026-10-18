package com.tekyerema.money.ussd

import android.content.Context
import android.util.Log
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.BankUssd
import com.tekyerema.money.PinPad
import com.tekyerema.money.UssdDialog
import com.tekyerema.voice.SecureStep
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The PIN prompt that comes LATER. For a bundle bought for someone else and paid with Mobile Money — and for airtime
 * sent to another number — MTN ends the menu with "Your request … is being processed. You will soon receive payment
 * prompt. If prompt delays, dial *170# to approve." and pushes the PIN dialog a few seconds later, as a new window.
 * The app used to read that whole message aloud and end the task; the PIN dialog then arrived while the voice was still
 * talking, nothing was armed, and the volume keys did nothing (owner, A20e, 13 October 2026).
 *
 * Now: one short line ([waitingLine]), then this watcher polls the phone's dialog. The moment a PIN screen is in front
 * it silences the voice, enters the secure step (no self-opened microphone, the log blind), arms the volume-key pad,
 * says two words, and hands the network's last word to [AfterPin] (the receipt a blind user cannot see). A payment
 * confirmation that arrives first is announced once and the watch goes on to the PIN behind it. Nothing here ever
 * reads, keeps or types a PIN. The pure parts ([promptComing], the lines) are unit-tested.
 */
object PushedPin {
    private const val TAG = "Tekyerema"

    // MTN: "You will soon receive payment prompt. If prompt delays, dial *170# to approve." (A20e, 3 and 13 October 2026);
    // Telecel *700#: "For Telecel Cash, wait for a prompt to input your PIN" (research R-A9, asetenapa 2025).
    private val PROMPT_COMING = Regex("(?i)\\b(?:you(?: will|'ll| would| shall)|will) (?:soon |shortly )?(?:receive|get|be sent|be prompted)\\b.{0,60}?" +
        "\\b(?:prompt|request|message|pin)\\b|\\bpayment prompt\\b|\\bprompt (?:to|will) (?:enter|input|follow|come|approve|authori[sz]e|confirm)\\b|" +
        "\\bwait for (?:a |the )?prompt\\b|\\bif (?:the )?prompt delays\\b|\\bapprove (?:it|the (?:payment|request|transaction))\\b|" +
        "\\bbeing processed\\b.{0,80}\\bprompt\\b")

    /** True for a last screen that says the PIN (or payment) prompt will follow, false for a receipt or a plain refusal. */
    fun promptComing(text: String): Boolean = PROMPT_COMING.containsMatchIn(text.replace(Regex("\\s+"), " "))

    /** Said instead of the network's whole message: short, so it is over before the prompt lands. */
    fun waitingLine(network: String): String =
        "$network will ask for your PIN in a moment. I'll go quiet when it comes — then enter your PIN, or use the volume keys."

    /** Said the moment the prompt is in front (after the voice was silenced): two words, then nothing. */
    // Was "PIN now." (8 characters): the owner's Mashup purchase on the A20e, 17 Oct 2026, was never finished — two words said once,
    // over a muted voice, and he didn't take them for an ask. Said as a request, with the volume-key tip (taught three times).
    fun arrivedLine(): String = "MTN is asking for your PIN. Enter it now."

    /** A payment confirmation arrived before the PIN: the user confirms it themselves; the watch goes on. */
    fun confirmFirstLine(network: String): String =
        "$network is asking you to confirm the payment on the screen first. Reply there; the PIN comes after it."

    /** Nothing came within the wait: honest, with the way to finish it by hand (MTN: *170# → My Wallet → My Approvals; R-A9). */
    fun lateLine(network: String): String =
        "$network's PIN prompt hasn't come. If it comes, your volume keys will enter the PIN; if not, dial star 1 7 0 hash, choose My Wallet, then My Approvals."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null

    /**
     * Watch for the pushed prompt for up to `money.pushedPin.waitMs` (default two minutes). [onArrived] runs once with the
     * kind of screen that came (the caller's ledger bookkeeping); [network] names the network in the spoken lines.
     */
    fun watch(ctrl: AccessibilityController, app: Context?, network: String?,
              waitMs: Long = try { AppConfig.long("money.pushedPin.waitMs", 120_000L) } catch (_: Throwable) { 120_000L },
              onArrived: (BankUssd.ScreenKind) -> Unit = {}) {
        job?.cancel()
        job = scope.launch {
            val net = network ?: "the network"
            val started = android.os.SystemClock.uptimeMillis()
            var confirmSaid = false
            while (android.os.SystemClock.uptimeMillis() - started < waitMs) {
                delay(700)
                val s = try { UssdDialog.read(ctrl) } catch (_: Throwable) { null } ?: continue
                if (!s.hasInput && !s.masked) continue                       // a leftover last word, or an unrelated dialog
                val kind = BankUssd.kind(s.text, s.hasInput, s.masked)
                when (kind) {
                    BankUssd.ScreenKind.PIN -> {
                        try { VoiceEngine.hush() } catch (_: Throwable) {}
                        SecureStep.begin()
                        PinPad.arm()
                        try { com.tekyerema.agent.helper.HelperGate.atPin() } catch (_: Throwable) {}
                        Log.i(TAG, "[MONEY] the network's pushed PIN prompt arrived after ${(android.os.SystemClock.uptimeMillis() - started) / 1000} s — voice silenced, secure step, pad armed")
                        try { onArrived(kind) } catch (_: Throwable) {}
                        try { VoiceEngine.announce(arrivedLine() + com.tekyerema.money.PinPad.handOffTip()) } catch (_: Throwable) {}
                        AfterPin.watch(ctrl, app, network?.lowercase())
                        return@launch
                    }
                    BankUssd.ScreenKind.MONEY -> if (!confirmSaid) {
                        confirmSaid = true
                        try { VoiceEngine.hush() } catch (_: Throwable) {}
                        SecureStep.begin()
                        Log.i(TAG, "[MONEY] the network's pushed payment confirmation arrived — the user confirms it; watching for the PIN")
                        try { onArrived(kind) } catch (_: Throwable) {}
                        try { VoiceEngine.announce(confirmFirstLine(net)) } catch (_: Throwable) {}
                    }
                    else -> {}
                }
            }
            Log.i(TAG, "[MONEY] no pushed PIN prompt within ${waitMs / 1000} s")
            try { VoiceEngine.announce(lateLine(net)) } catch (_: Throwable) {}
        }
    }

    /** A new walk, a cancel, or a close: this watch is over. */
    fun cancel() { job?.cancel(); job = null }
}
