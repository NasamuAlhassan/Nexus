package com.tekyerema.ussdsim

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * MTN's `*170#` as recorded on the owner's A20e (1–9 October 2026), as a pure state machine. This is the simulator APK's copy
 * of `com.tekyerema.money.ussd.UssdSim.Script` in the app (which stays there so the app's unit tests, UssdSimTest, pin the
 * menu wording the navigator must understand); keep the two in step when a screen changes.
 */
class MtnScript(code: String, private val registeredName: String = "PRINCE NASAMU ALHASSAN", private val pin: String = "1234",
                private val balance: String = "12.50") {
    data class Shown(val text: String, val input: Boolean, val masked: Boolean, val final: Boolean)

    private var state = if (code.replace(" ", "") == "*170#") "root" else "unknown"
    private var number = ""
    private var amount = ""
    private var reference = ""
    private var finalText = ""
    private var pinTries = 0

    fun shown(): Shown = when (state) {
        "root" -> Shown(ROOT, input = true, masked = false, final = false)
        "transfer" -> Shown(TRANSFER, input = true, masked = false, final = false)
        // The prompts and the PIN sentence are MTN's own, as logged on the A20e (3 and 9 October 2026).
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
                else -> if (v.signum() <= 0) end("Invalid amount.") else if (v > BigDecimal(2000)) end("Transfer limit exceeded.")
                    else { amount = t; "reference" }
            }
            "reference" -> { reference = t.ifBlank { "-" }; state = "pin" }
            // The receipt in MTN's own shape; "Not enough funds to perform this transaction." is MTN's own final (A20e, 3 Oct 2026).
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

    private fun money(a: String): String = a.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: a
    private fun left(): String = money((balance.toBigDecimal() - amount.toBigDecimal()).toPlainString())
    // A fresh eleven-digit transaction ID per session, as the network gives one (a fixed id was deduplicated by the app's ledger).
    private val txnId = (System.currentTimeMillis() % 100_000_000_000L).toString().padStart(11, '0')

    companion object {
        // The menus carry MTN's banner line above the rows, as the real screens do.
        const val ROOT = "Unlock more deals, try our new MoMo App\n1) Transfer Money\n2) MoMoPay&Pay Bill\n3) Airtime & Bundles\n4) Allow Cash Out\n5) Financial Services\n6) Wallet\n7) My Approvals"
        const val TRANSFER = "More offers await on the new MoMo App\n1) MoMo User\n2) Non MoMo User\n3) Send with Care\n4) Favorite\n5) Other Networks\n6) Bank Account"
        const val WALLET = "1) Check Balance\n2) Allow Cash Out\n3) My Approvals\n4) Change PIN"
        const val INCORRECT = "Incorrect choice."
    }
}
