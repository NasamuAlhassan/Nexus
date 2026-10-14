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
    data class Shown(val text: String, val input: Boolean, val masked: Boolean, val final: Boolean,
                     /** A last word after which the network PUSHES a PIN dialog: how long after, and what it says. */
                     val pushAfterMs: Long = 0L, val pushText: String? = null)

    /**
     * MTN's `*170#` (Mobile Money) and `*567#` (Pulse / Mashup) as recorded on the A20e (menus and prompts from the 1–13
     * October walks, wording as in the unit fixtures), as a pure state machine: [shown] is the current screen, [enter] is
     * what the user (or the navigator) sent. Pure, Android-free, unit-tested (UssdSimTest, UssdSimMashupTest). Only the
     * send-money, balance and Mashup branches are scripted; every other choice ends the session the way the network does
     * ("Incorrect choice"). A Mashup for someone else paid with Mobile Money ends with the network's PROMISE of a PIN prompt;
     * the dialog itself comes later ([Shown.pushText]) — a Script built with [pushedPin] is that later dialog. The simulator
     * APK carries its own copy (`ussdsim/…/MtnScript.kt`) — keep the two in step; this one is what the app's tests pin.
     */
    class Script(private val code: String, val registeredName: String = "PRINCE NASAMU ALHASSAN", val pin: String = "1234",
                 val balance: String = "12.50", pushedPin: String? = null) {
        private var state = when {
            pushedPin != null -> "pushpin"
            code.replace(" ", "") == "*170#" -> "root"
            code.replace(" ", "") == "*567#" -> "pulse"
            else -> "unknown"
        }
        private var number = ""
        private var amount = ""
        private var reference = ""
        private var finalText = ""
        private var pinTries = 0
        private var forOthers = false
        private var price = ""
        private var mix = ""
        private var pushText = ""
        private val pinScreen = pushedPin ?: ""
        // Airtime (*170# → 3 → 1): for this phone, for another MTN number, or for a number on another network.
        private var airtimeFor = ""
        private var otherNet = ""
        /** What a pushed PIN dialog pays for ("mashup" | "airtime"), read from its own words. */
        private var kind = "mashup"

        init {
            pushedPin?.let { t ->
                price = Regex("GH[CS]\\s*(\\d+(?:\\.\\d+)?)").find(t)?.groupValues?.get(1) ?: ""
                number = Regex("\\b0\\d{9}\\b").find(t)?.value ?: ""
                forOthers = number.isNotEmpty()
                kind = if (t.contains("airtime", ignoreCase = true)) "airtime" else "mashup"
            }
        }

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
            // Airtime & Bundles → Airtime, screen for screen as the A20e showed them (3 and 14 October 2026).
            "airtimehub" -> Shown(AIRTIME_HUB, input = true, masked = false, final = false)
            "airtime" -> Shown(AIRTIME, input = true, masked = false, final = false)
            "aamount" -> Shown("Enter Amount", input = true, masked = false, final = false)
            "apin" -> Shown("Buy GHS ${money(amount)} airtime for yourself. Fee is GHS 0.00. Enter MM PIN or 2 to cancel", input = true, masked = true, final = false)
            "anet" -> Shown(OTHER_NET, input = true, masked = false, final = false)
            "anumber" -> Shown("Enter recipient phone number", input = true, masked = false, final = false)
            "aconfirm" -> Shown("Re-enter recipient phone number", input = true, masked = false, final = false)
            "aamount2" -> Shown("Enter amount (GHS) minimum: 0.20, maximum: 500.00", input = true, masked = false, final = false)
            "aconfirmx" -> Shown("You are sending GHS ${money(amount)} airtime to this $otherNet number: $number. Reply 1 to confirm or 0 to start again", input = true, masked = false, final = false)
            "anotice" -> Shown(NOTICE_OTHER, input = true, masked = false, final = false)
            // Pulse / Mashup, screen for screen as the A20e showed them (3 and 13 October 2026).
            "pulse" -> Shown(PULSE, input = true, masked = false, final = false)
            "pulse2" -> Shown(PULSE2, input = true, masked = false, final = false)
            "mnumber" -> Shown("Enter Phone Number", input = true, masked = false, final = false)
            "mconfirm" -> Shown("Confirm Phone Number", input = true, masked = false, final = false)
            "tiers" -> Shown(TIERS, input = true, masked = false, final = false)
            "tier1" -> Shown(TIER1, input = true, masked = false, final = false)
            "mamount" -> Shown("Enter amount GHC (0.07-4.99)", input = true, masked = false, final = false)
            "bundle" -> Shown("Y'ello! the MashUp GHC $price Bundle gives you ${mbFor(price)} MB. This bundle does not expire.\n1. Buy", input = true, masked = false, final = false)
            "mixes" -> Shown(MIXES, input = true, masked = false, final = false)
            "paymode" -> Shown(PAYMODE, input = true, masked = false, final = false)
            "mpin" -> Shown("Y'ello! Confirm payment of GHS $price for MashUp GHC $price Bundle. Enter MM PIN or 2 to cancel", input = true, masked = true, final = false)
            "pushwait" -> Shown(finalText, input = false, masked = false, final = true, pushAfterMs = PUSH_MS, pushText = pushText)
            "pushpin" -> Shown(pinScreen, input = true, masked = true, final = false)
            "final" -> Shown(finalText, input = false, masked = false, final = true)
            else -> Shown("Connection problem or invalid MMI code.", input = false, masked = false, final = true)
        }

        fun enter(raw: String) {
            val t = raw.trim()
            when (state) {
                "root" -> state = when (t) { "1" -> "transfer"; "3" -> "airtimehub"; "6" -> "wallet"; else -> end(INCORRECT) }
                // ---- Airtime (A20e: self to the PIN at once; others and other networks end with the promise, the PIN pushed)
                "airtimehub" -> state = when (t) { "1" -> "airtime"; else -> end(INCORRECT) }
                "airtime" -> state = when (t) {
                    "1" -> { airtimeFor = "self"; "aamount" }
                    "2" -> { airtimeFor = "others"; "anumber" }
                    "4" -> { airtimeFor = "other-network"; "anet" }
                    "0" -> "airtimehub"
                    else -> end(INCORRECT)
                }
                "aamount" -> state = when (val v = t.toBigDecimalOrNull()) {
                    null -> end(NOT_VALID)
                    else -> if (v.signum() <= 0) end(NOT_VALID) else { amount = t; "apin" }
                }
                "anet" -> state = when (t) { "1" -> { otherNet = "AT"; "anumber" }; "2" -> { otherNet = "Telecel"; "anumber" }; "0" -> "airtime"; else -> end(INCORRECT) }
                "anumber" -> state = if (t.length == 10 && t.all { it.isDigit() }) { number = t; "aconfirm" } else end(NOT_VALID)
                "aconfirm" -> state = if (t == number) "aamount2" else end("The numbers do not match.")
                "aamount2" -> state = when (val v = t.toBigDecimalOrNull()) {
                    null -> end(NOT_VALID)
                    else -> if (v < java.math.BigDecimal("0.20") || v > java.math.BigDecimal("500")) end("Invalid amount.")
                        else { amount = t; if (airtimeFor == "other-network") "aconfirmx" else airtimePromise() }
                }
                "aconfirmx" -> state = when (t) { "1" -> "anotice"; "0" -> "root"; else -> end("Transaction cancelled.") }
                "anotice" -> state = when (t) { "1" -> airtimePromise(); else -> end("Transaction cancelled.") }
                "apin" -> state = if (t == "2") end("Transaction cancelled.") else if (t != pin) wrongPin()
                    else if (amount.toBigDecimal() > balance.toBigDecimal()) end("Not enough funds to perform this transaction.")
                    else end("Y'ello! Airtime purchase of GHS ${money(amount)} successful. Your MoMo balance is GHS ${leftAfter(amount)}.")
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
                // ---- Pulse / Mashup
                "pulse" -> state = when (t) { "1" -> "pulse2"; else -> end(INCORRECT) }
                "pulse2" -> state = when (t) { "1" -> { forOthers = false; "tiers" }; "2" -> { forOthers = true; "mnumber" }; else -> end(INCORRECT) }
                "mnumber" -> state = if (t.length == 10 && t.all { it.isDigit() }) { number = t; "mconfirm" } else end(NOT_VALID)
                "mconfirm" -> state = if (t == number) "tiers" else end("The numbers do not match.")
                "tiers" -> state = when (t) {
                    "1" -> "tier1"
                    "2" -> { price = "5"; "bundle" }; "3" -> { price = "10"; "bundle" }; "4" -> { price = "30"; "bundle" }
                    "0" -> "pulse2"
                    else -> end(INCORRECT)
                }
                "tier1" -> state = when (t) { "1" -> { price = "1"; "mixes" }; "2" -> "mamount"; "0" -> "tiers"; else -> end(INCORRECT) }
                "mamount" -> state = when (val v = t.toBigDecimalOrNull()) {
                    null -> end(NOT_VALID)
                    else -> if (v < java.math.BigDecimal("0.07") || v > java.math.BigDecimal("4.99")) end(NOT_VALID) else { price = t; "mixes" }
                }
                "bundle" -> state = when (t) { "1" -> "paymode"; else -> end(INCORRECT) }
                "mixes" -> state = when (t) { "1" -> end(APP_ONLY); "2", "3", "4", "5" -> { mix = t; "paymode" }; else -> end(INCORRECT) }
                "paymode" -> state = when (t) {
                    "1" -> end(receipt())                                     // airtime pays at once — no PIN
                    "2" -> if (forOthers) {
                        // The pushed PIN (A20e, 3 and 13 October 2026): the session ends with the promise, the PIN dialog comes later.
                        pushText = "Buy MashUp GHC $price for $number. Fee is GHS 0.00. Enter MM PIN or 2 to cancel"
                        finalText = "Your request to buy MashUp GHC $price for $number is being processed. You will soon receive payment prompt. If prompt delays, dial *170# to approve."
                        "pushwait"
                    } else "mpin"
                    else -> end(INCORRECT)
                }
                "mpin", "pushpin" -> state = if (t == "2") end("Transaction cancelled.") else if (t != pin) wrongPin()
                    else if ((price.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) > balance.toBigDecimal()) end("Not enough funds to perform this transaction.")
                    else end(receipt())
                else -> {}
            }
        }

        private fun receipt(): String =
            if (kind == "airtime") "Y'ello! You have sent GHS ${money(price)} airtime to $number. Your MoMo balance is GHS ${leftAfter(price)}."
            else "Y'ello! you have successfully purchased GHC$price MashUp Bundle" +
                (if (forOthers && number.isNotEmpty()) " for $number" else "") + ". Your MoMo balance is GHS ${leftAfter(price)}."

        /** Airtime for another number ends with MTN's promise; the PIN dialog is pushed afterwards (A20e, 3 October 2026). */
        private fun airtimePromise(): String {
            kind = "airtime"; price = amount; forOthers = true
            pushText = "Send GHS ${money(amount)} Airtime to $number. Fee is GHS 0.00. Enter MM PIN or 2 to cancel"
            finalText = "Your request to send airtime to $number is being processed. You will soon receive payment prompt. If prompt delays, dial *170# to approve."
            return "pushwait"
        }

        private fun wrongPin(): String {
            pinTries++
            return end(if (pinTries >= 3) "Your PIN has been blocked. Visit an MTN office." else "Wrong PIN. You have ${3 - pinTries} attempts left.")
        }

        private fun end(text: String): String { finalText = text; return "final" }

        private fun money(a: String): String = a.toBigDecimalOrNull()?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: a
        private fun left(): String = money((balance.toBigDecimal() - amount.toBigDecimal()).toPlainString())
        private fun leftAfter(spent: String): String = money((balance.toBigDecimal() - (spent.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)).toPlainString())
        /** MTN's figure for the GHC 30 bundle was 3133.94 MB (A20e, 13 October 2026); the other tiers scale with it. */
        private fun mbFor(p: String): String = p.toBigDecimalOrNull()?.multiply(java.math.BigDecimal("104.4647"))?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "0"
        // A fresh eleven-digit transaction ID per session, as the network gives one: a FIXED id made the ledger treat the
        // second walk's receipt as "the same message again" and fall back to reading it as written (13 Oct 2026, build 10).
        private val txnId = (System.currentTimeMillis() % 100_000_000_000L).toString().padStart(11, '0')

        companion object {
            // The menus carry MTN's banner line above the rows, as the real screens do ("Unlock more deals, try our new MoMo App").
            const val ROOT = "Unlock more deals, try our new MoMo App\n1) Transfer Money\n2) MoMoPay&Pay Bill\n3) Airtime & Bundles\n4) Allow Cash Out\n5) Financial Services\n6) Wallet\n7) My Approvals"
            const val TRANSFER = "More offers await on the new MoMo App\n1) MoMo User\n2) Non MoMo User\n3) Send with Care\n4) Favorite\n5) Other Networks\n6) Bank Account"
            const val WALLET = "1) Check Balance\n2) Allow Cash Out\n3) My Approvals\n4) Change PIN"
            const val INCORRECT = "Incorrect choice."
            const val NOT_VALID = "The Input is not valid."
            const val PULSE = "Welcome to MTN Pulse. Please select an option\n1. Proceed to buy bundle (No 4G bonus)\n99. More"
            const val PULSE2 = "Welcome to MTN Pulse.\n1. MashUp for Self\n2. MashUp for Others\n3. Convert Minutes To Data\n4. Pulse Loyalty\n5. Check Promo"
            const val TIERS = "MashUp Offers\n1. MashUp GHC 1\n2. MashUp GHC 5\n3. MashUp GHC 10\n4. MashUp GHC 30\n0. Back"
            const val TIER1 = "Select one of the following:\n1. GHC 1\n2. Enter amount GHC (GHC 0.07 - 4.99)\n0. Back"
            const val MIXES = "Select your preferred option:\n1. 15.27MB and 15.64Mins(available on mymtn app only)\n2. 25.45MB and 11.17Mins\n3. 30.53MB and 8.94Mins\n4. 35.62MB and 6.7Mins\n5. 50.89MB Only"
            const val PAYMODE = "Choose payment mode\n1. Airtime\n2. Mobile Money"
            // The airtime branch of *170# as the A20e showed it (3 October and 14 October 2026).
            const val AIRTIME_HUB = "Enjoy exclusive 100% bonus airtime only on the MoMo App\n1) Airtime\n2) Internet Bundle\n3) Fixed Broadband\n5) Just4U"
            const val AIRTIME = "Airtime\n1) Self\n2) Others\n3) Welcome Pack\n4) Other Networks\n0) Back"
            const val OTHER_NET = "Other Network\n1) AT\n2) Telecel\n3) E-zwich\n4) G-Money\n5) Zeepay\n6) Ghana Pay\n0) Back"
            const val NOTICE_OTHER = "Airtime purchased for other networks cannot be reversed\n1. Proceed\n2. Cancel"
            const val APP_ONLY = "Dear Valued Customer, this bundle option is only available on MyMTN App. Kindly check your SMS and click the link to complete your purchase."
            /** How long after its last word the network pushes the PIN dialog ("within seconds" in the field; R-A9). */
            const val PUSH_MS = 6_000L
        }
    }
}
