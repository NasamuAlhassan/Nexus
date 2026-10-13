package com.tekyerema.money.ussd

import android.content.Context
import android.content.Intent
import com.tekyerema.BuildConfig
import com.tekyerema.core.config.AppConfig

/**
 * The USSD SIMULATOR (debug builds only; mission P2-01, 13 October 2026): the emulator has no network behind `*170#` (its
 * fake carrier answers "USSD code running…" and nothing else), so the money walk could only ever be tested on the owner's
 * phone, with the owner's SIM, up to the PIN. With the switch on, the dialler opens the simulator APK's dialog
 * (`:ussdsim`, [SIM_ACTIVITY]) instead of the phone's MMI dialog; that activity replays MTN's recorded `*170#` screens
 * (menus, the number / amount / reference prompts, the registered-name PIN screen, the receipt) as a real dialog with an
 * entry box and Send / Cancel —
 * so the navigator, the policy gate, the PIN hand-off and the volume-key PIN pad run exactly as they do against the real
 * thing, and nothing can move money.
 *
 * Production is untouched: everything here is inert unless the build is a DEBUG build AND `money.ussd.simulator` is true
 * (a runtime override, never a shipped default), and the simulator's window is recognised only by OUR package name plus the
 * exact window title [TITLE] — the production detector (accessibility/TelephonyWindows) is not widened.
 *
 *   adb shell am broadcast -p com.tekyerema -a com.tekyerema.RUN_GOAL --es set_config '{"money":{"ussd":{"simulator":true}}}'
 */
object UssdSim {
    const val TITLE = "USSD simulator"
    const val EXTRA_CODE = "code"
    /** The simulator is a SEPARATE tiny APK (the `:ussdsim` module): an accessibility service is shown no windows of its
     *  own package — with the dialog inside this app, every read came back empty (Pixel emulator, 13 October 2026). */
    const val SIM_PACKAGE = "com.tekyerema.ussdsim"
    const val SIM_ACTIVITY = "com.tekyerema.ussdsim.SimActivity"

    fun enabled(): Boolean = BuildConfig.DEBUG && (try { AppConfig.bool("money.ussd.simulator", false) } catch (_: Throwable) { false })

    /** The carrier the simulated SIM pretends to be ("MTN Ghana"), or null when the simulator is off. */
    fun carrier(): String? = if (enabled()) AppConfig.string("money.ussd.simCarrier", "MTN Ghana") else null

    /** Only the simulator APK's own dialog, in a debug build with the switch on, counts as a USSD window besides the phone's. */
    fun isSimWindow(pkg: String?, title: String?): Boolean = enabled() && pkg == SIM_PACKAGE && title == TITLE

    fun start(app: Context, code: String) {
        app.startActivity(Intent().setClassName(SIM_PACKAGE, SIM_ACTIVITY)
            .putExtra(EXTRA_CODE, code).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** What the simulator shows: the dialog's words, whether it has an entry box, whether that box is masked (a PIN), and
     *  whether it is the session's last word (then only OK closes it). */
    data class Shown(val text: String, val input: Boolean, val masked: Boolean, val final: Boolean)

    /**
     * MTN's `*170#` as recorded on the A20e (menus and prompts from the 1–9 October walks, wording as in the unit fixtures),
     * as a pure state machine: [shown] is the current screen, [enter] is what the user (or the navigator) sent. Pure,
     * Android-free, unit-tested (UssdSimTest). Only the send-money and balance branches are scripted; every other choice ends
     * the session the way the network does ("Incorrect choice"). The simulator APK carries its own copy
     * (`ussdsim/…/MtnScript.kt`) — keep the two in step; this one is what the app's tests pin.
     */
    class Script(private val code: String, val registeredName: String = "PRINCE NASAMU ALHASSAN", val pin: String = "1234",
                 val balance: String = "12.50") {
        private var state = if (code.replace(" ", "") == "*170#") "root" else "unknown"
        private var number = ""
        private var amount = ""
        private var reference = ""
        private var finalText = ""
        private var pinTries = 0

        fun shown(): Shown = when (state) {
            "root" -> Shown(ROOT, input = true, masked = false, final = false)
            "transfer" -> Shown(TRANSFER, input = true, masked = false, final = false)
            // The prompts and the PIN sentence are MTN's own, as logged on the A20e (3 and 9 October 2026: "Enter mobile
            // number", "Confirm Number", "Transfer to AMA MENSAH for GHS 0.1 with Reference: Payment. Fee is GHS 0.00, Tax
            // amount is GHS 0.00.") — so the emulator exercises the same prompt classifier and the same name/amount check.
            "number" -> Shown("Enter mobile number", input = true, masked = false, final = false)
            "confirm" -> Shown("Confirm Number", input = true, masked = false, final = false)
            "amount" -> Shown("Enter Amount", input = true, masked = false, final = false)
            "reference" -> Shown("Enter Reference", input = true, masked = false, final = false)
            "pin" -> Shown("Transfer to $registeredName for GHS $amount with Reference: $reference. Fee is GHS 0.00, Tax amount is GHS 0.00. Enter MM PIN",
                input = true, masked = true, final = false)
            "wallet" -> Shown(WALLET, input = true, masked = false, final = false)
            "walletpin" -> Shown("Enter MM PIN", input = true, masked = true, final = false)
            "final" -> Shown(finalText, input = false, masked = false, final = true)
            else -> Shown("Connection problem or invalid MMI code.", input = false, masked = false, final = true)
        }

        fun enter(raw: String) {
            val t = raw.trim()
            when (state) {
                "root" -> state = when (t) { "1" -> "transfer"; "6" -> "wallet"; else -> end(INCORRECT) }
                "transfer" -> state = when (t) { "1" -> "number"; else -> end(INCORRECT) }
                "number" -> state = if (t.length in 9..13 && t.all { it.isDigit() }) { number = t; "confirm" } else end("Invalid number.")
                "confirm" -> state = if (t == number) "amount" else end("The numbers do not match.")
                "amount" -> state = when (val v = t.toBigDecimalOrNull()) {
                    null -> end("Invalid amount.")
                    else -> if (v.signum() <= 0) end("Invalid amount.") else if (v > java.math.BigDecimal(2000)) end("Transfer limit exceeded.")
                        else { amount = t; "reference" }
                }
                "reference" -> { reference = t.ifBlank { "-" }; state = "pin" }
                // The receipt in MTN's own shape (audit/finance-2026-10-01/sms_shapes.txt, shape [79]); "Not enough funds to
                // perform this transaction." is MTN's own final when the wallet can't cover it (A20e, 3 October 2026).
                "pin" -> state = if (t != pin) wrongPin()
                    else if (amount.toBigDecimal() > balance.toBigDecimal()) end("Not enough funds to perform this transaction.")
                    else end("Payment made for GHS ${money(amount)} to $registeredName $number. Current Balance: GHS ${left()}. " +
                        "Available Balance: GHS ${left()}. Reference: $reference. Transaction ID: $txnId. Fee charged: GHS 0.00 TAX charged: GHS 0.00.")
                "wallet" -> state = when (t) { "1" -> "walletpin"; else -> end(INCORRECT) }
                "walletpin" -> state = if (t == pin) end("Current Balance: GHS $balance Available Balance: GHS $balance") else wrongPin()
                else -> {}
            }
        }

        private fun wrongPin(): String {
            pinTries++
            return end(if (pinTries >= 3) "Your PIN has been blocked. Visit an MTN office." else "Wrong PIN. You have ${3 - pinTries} attempts left.")
        }

        private fun end(text: String): String { finalText = text; return "final" }

        private fun money(a: String): String = a.toBigDecimalOrNull()?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: a
        private fun left(): String = money((balance.toBigDecimal() - amount.toBigDecimal()).toPlainString())
        // A fresh eleven-digit transaction ID per session, as the network gives one: a FIXED id made the ledger treat the
        // second walk's receipt as "the same message again" and fall back to reading it as written (13 Oct 2026, build 10).
        private val txnId = (System.currentTimeMillis() % 100_000_000_000L).toString().padStart(11, '0')

        companion object {
            // The menus carry MTN's banner line above the rows, as the real screens do ("Unlock more deals, try our new MoMo App").
            const val ROOT = "Unlock more deals, try our new MoMo App\n1) Transfer Money\n2) MoMoPay&Pay Bill\n3) Airtime & Bundles\n4) Allow Cash Out\n5) Financial Services\n6) Wallet\n7) My Approvals"
            const val TRANSFER = "More offers await on the new MoMo App\n1) MoMo User\n2) Non MoMo User\n3) Send with Care\n4) Favorite\n5) Other Networks\n6) Bank Account"
            const val WALLET = "1) Check Balance\n2) Allow Cash Out\n3) My Approvals\n4) Change PIN"
            const val INCORRECT = "Incorrect choice."
        }
    }
}
