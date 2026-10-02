package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.MoMoOutcome
import com.tekyerema.money.MoMoOutcome.Kind
import com.tekyerema.money.MoMoOutcome.Msg
import com.tekyerema.phonecare.MoneyAlerts
import com.tekyerema.phonecare.MoneyAlerts.Event
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The network's Mobile Money messages as they REALLY read (finance audit 2026-10-01). The shapes — wording, spacing,
 * punctuation — are the ones in the test phone's inbox (audit/finance-2026-10-01/sms_shapes.txt, pulled with every
 * digit, name and reference masked); the names, amounts and ids here are made up. Before this, every MoMo SMS fixture
 * in the suite was written by hand, and the receipt parser had never met a merchant payment, an app payment, a failed
 * payment, a cash-out or an airtime purchase.
 */
class MoMoRealShapesTest {
    @Before fun defaults() { AppConfig.loadFrom(emptyList()) }
    @After fun reset() { AppConfig.loadFrom(emptyList()) }

    private fun p(s: String): Msg = MoMoOutcome.parse(s) ?: throw AssertionError("not parsed: $s")

    private val app = "Download the MoMo App for a Faster & Easier Experience. Click here: https://mtnmymomo.onelink.me/XJOt/MoMo"

    // ---------------------------------------------------------------- money out: person to person ("Payment made for")

    /** [106 on the phone] the name runs straight into "Current Balance", and a full stop sits after a space. */
    private val sentNoStop = "Payment made for GHS 5.00 to KOFI MENSAH Current Balance: GHS 120.00 . Available Balance: GHS 120.00. " +
        "Reference: lunch. Transaction ID: 76712833868. Fee charged: GHS0.00 Tax charged: 0. $app"

    /** [79] the tidy one. */
    private val sentTidy = "Payment made for GHS 5.00 to KOFI MENSAH. Current Balance: GHS 120.00. Available Balance: GHS 120.00. " +
        "Reference: lunch. Transaction ID: 76712833868. Fee charged: GHS 0.50 TAX charged: GHS 0.00."

    @Test fun payment_made_where_the_name_runs_into_current_balance() {
        assertEquals(Msg(Kind.SENT, "5.00", "KOFI MENSAH", null, "120.00", "0.00", "76712833868", "lunch"), p(sentNoStop))
        assertTrue(p(sentNoStop).outgoingDone)
    }

    @Test fun payment_made_with_a_full_stop_after_the_name() {
        assertEquals(Msg(Kind.SENT, "5.00", "KOFI MENSAH", null, "120.00", "0.50", "76712833868", "lunch"), p(sentTidy))
    }

    /** [11] "to NAME 0241234567." — the number is kept apart from the name. */
    @Test fun payment_made_with_the_number_after_the_name() {
        val m = p("Payment made for GHS 5.00 to KOFI MENSAH 0241234567. Current Balance: GHS 120.00. Available Balance: GHS 120.00. " +
            "Reference: lunch. Transaction ID: 76712833868. Fee charged: GHS 0.50 TAX charged: GHS 0.00.")
        assertEquals(Kind.SENT, m.kind)
        assertEquals("KOFI MENSAH", m.party)
        assertEquals("0241234567", m.partyNumber)
        assertEquals("5.00", m.amount)
    }

    /** [2] a name MTN holds in mixed case. */
    @Test fun payment_made_to_a_mixed_case_name() {
        val m = p("Payment made for GHS 5.00 to Kofi Mensah Current Balance: GHS 120.00 . Available Balance: GHS 120.00. " +
            "Reference: lunch. Transaction ID: 76712833868. Fee charged: GHS0.00 Tax charged: 0. $app")
        assertEquals(Kind.SENT, m.kind)
        assertEquals("Kofi Mensah", m.party)
        assertEquals("120.00", m.balance)
    }

    // ---------------------------------------------------------------- money out: a merchant, a bank, a debit ("Payment for")

    /** [134] "to NAME .Current Balance" and "GHS0.00,Tax Charged 0.Download" — exactly as MTN sends it. */
    @Test fun payment_for_a_merchant() {
        val sms = "Payment for GHS5.00 to KOFI MENSAH .Current Balance: GHS 115.00. Transaction Id: 76712833868. " +
            "Fee charged: GHS0.00,Tax Charged 0.$app"
        assertEquals(Msg(Kind.PAID, "5.00", "KOFI MENSAH", null, "115.00", "0.00", "76712833868", null), p(sms))
        assertTrue(p(sms).outgoingDone)
        assertNull("money out is never announced as money in", MoneyAlerts.classify(sms, trusted = true))
    }

    /** [164, 12, 1, 1, 2] where a name would be, MTN writes its own label — returned as written. */
    @Test fun payment_for_a_service_label() {
        fun to(written: String) = p("Payment for GHS1.50 to ${written}Current Balance: GHS 115.00. Transaction Id: 76712833868. " +
            "Fee charged: GHS0.00,Tax Charged 0.$app")
        assertEquals("Debit. Inv1", to("Debit. Inv1.").party)
        assertEquals("GCB Bank ova", to("GCB Bank ova.").party)
        assertEquals("PaySwitch Push", to("PaySwitch Push.").party)
        assertEquals("Bills.INV", to("Bills.INV ..").party)
        assertEquals("ABC MoMo POS", to("ABC MoMo POS .").party)
        val m = to("Debit. Inv1.")
        assertEquals(Kind.PAID, m.kind)
        assertEquals("1.50", m.amount)
        assertEquals("115.00", m.balance)
        assertNull(m.partyNumber)
    }

    // ---------------------------------------------------------------- money out through an app: completed / failed

    private val completed = "Your payment of GHS 20.00 to BOLT GHANA has been completed at 2026-09-12 10:11:12. Your new balance: GHS 95.00. " +
        "Fee was GHS 0.00 Tax was GHS -. Reference: ride. Financial Transaction Id: 76712833868. External Transaction Id: 99887766." +
        "Download the MoMo App for a Faster & Easier Experience Click here: https://mtnmymomo.onelink.me/XJOt/MoMo"

    /** [2] the only failure wording on the phone. (Its last words are masked in the capture: capitals and one digit.) */
    private val failed = "Your payment of GHS 20.00 to BOLT GHANA has failed at 2026-09-12 10:11:12. Reference: ride. " +
        "Financial Transaction Id: 76712833868. External Transaction Id: 99887766. REASON UNKNOWN 1"

    @Test fun your_payment_has_been_completed() {
        assertEquals(Msg(Kind.PAID, "20.00", "BOLT GHANA", null, "95.00", "0.00", "76712833868", "ride"), p(completed))
        assertNull(MoneyAlerts.classify(completed, trusted = true))
    }

    @Test fun your_payment_has_failed() {
        val m = p(failed)
        assertEquals(Msg(Kind.FAILED, "20.00", "BOLT GHANA", null, null, null, "76712833868", "ride"), m)
        assertFalse("a failed payment did not leave the wallet", m.outgoingDone)
        assertFalse(MoMoOutcome.isSecret(failed))
        assertNull(MoneyAlerts.classify(failed, trusted = true))
    }

    @Test fun failure_in_the_networks_general_words() {
        // MTN-Just4U, real: no amount in it.
        val pack = p("Y'ello! Purchase of the requested pack was unsuccessful. Kindly check your balance and try again.")
        assertEquals(Msg(Kind.FAILED, null, null, null, null, null, null, null), pack)
        // Not seen on the phone — the wording other networks and older MTN messages use.
        val funds = p("Your transaction of GHS 5.00 has failed due to insufficient funds.")
        assertEquals(Kind.FAILED, funds.kind)
        assertEquals("5.00", funds.amount)
    }

    // ---------------------------------------------------------------- money in

    private val received = "Payment received for GHS 50.00 from KOFI MENSAH Current Balance: GHS 123.45 . Available Balance: GHS 123.45. " +
        "Reference: rent. Transaction ID: 12345678901. TRANSACTION FEE: 0.00"

    private val cashIn = "Cash In received for GHS 100.00 from AGENT KWAME STORES. Current Balance GHS 300.00 Available Balance GHS 300.00. " +
        "Transaction ID: 76712833868. Fee charged: GHS 0. Cash in (Deposit) is a free transaction on MTN Mobile Money. " +
        "Please do not pay any fees for it."

    /** [138] */
    @Test fun payment_received() {
        assertEquals(Msg(Kind.RECEIVED, "50.00", "KOFI MENSAH", null, "123.45", "0.00", "12345678901", "rent"), p(received))
        assertFalse(p(received).outgoingDone)
        assertEquals(Event.Received("50.00", "Kofi Mensah", "123.45"), MoneyAlerts.classify(received, trusted = true))
    }

    /** [32] a payer in mixed case; [34] MTN's own label where the payer would be. */
    @Test fun payment_received_from_a_mixed_case_name_and_from_a_label() {
        val mixed = received.replace("KOFI MENSAH", "Kofi Mensah")
        assertEquals("Kofi Mensah", p(mixed).party)
        assertEquals(Event.Received("50.00", "Kofi Mensah", "123.45"), MoneyAlerts.classify(mixed, trusted = true))
        val label = received.replace("KOFI MENSAH", "Credit.Inv")
        assertEquals("Credit.Inv", p(label).party)
        assertEquals(Kind.RECEIVED, p(label).kind)
        // The alert says the amount and no "name": "You've received 50 cedis."
        val e = MoneyAlerts.classify(label, trusted = true)
        assertEquals(Event.Received("50.00", null, "123.45"), e)
        assertEquals("You've received 50 cedis.", MoneyAlerts.receivedLine(e as Event.Received, withBalance = false))
    }

    /** [25] and [7] ("from NAME . Current Balance"). */
    @Test fun cash_in_received() {
        assertEquals(Msg(Kind.CASH_IN, "100.00", "AGENT KWAME STORES", null, "300.00", "0", "76712833868", null), p(cashIn))
        val spaced = cashIn.replace("STORES. Current", "STORES . Current")
        assertEquals("AGENT KWAME STORES", p(spaced).party)
        assertEquals("300.00", p(spaced).balance)
        assertEquals(Event.Received("100.00", "Agent Kwame Stores", "300.00"), MoneyAlerts.classify(cashIn, trusted = true))
        assertEquals(Event.Received("100.00", "Agent Kwame Stores", "300.00"), MoneyAlerts.classify(spaced, trusted = true))
    }

    @Test fun the_same_words_from_an_ordinary_number_are_the_fake_credit_trick() {
        assertEquals(Event.UntrustedCredit, MoneyAlerts.classify(received, trusted = false, fromNumber = true))
        assertEquals(Event.UntrustedCredit, MoneyAlerts.classify(cashIn, trusted = false, fromNumber = true))
        assertNull("a named sender that isn't Mobile Money is not ours to announce", MoneyAlerts.classify(received, trusted = false, fromNumber = false))
    }

    /** [12] */
    @Test fun cash_out_made() {
        val sms = "Cash Out made for GHS50.00 to AGENT KWAME STORES. Current Balance: GHS250.00 Financial Transaction Id: 76712833868. " +
            "Cash-out fee is charged automatically from your MTN MoMo wallet. Please do not pay any fees to the Agent. " +
            "Thank you for using MTN MobileMoney. Fee charged: GHS0.50."
        assertEquals(Msg(Kind.CASH_OUT, "50.00", "AGENT KWAME STORES", null, "250.00", "0.50", "76712833868", null), p(sms))
        assertTrue(p(sms).outgoingDone)
        assertNull(MoneyAlerts.classify(sms, trusted = true))
    }

    // ---------------------------------------------------------------- airtime and bundles

    @Test fun airtime_bought_recharged_and_received() {
        // [2] MobileMoney
        val bought = "You have successfully purchased GHS 5.00 Airtime from MTNONLINEAIRTIMEVENDOR on 2026-09-12.Ref: AB12CD."
        assertEquals(Msg(Kind.AIRTIME, "5.00", "MTNONLINEAIRTIMEVENDOR", null, null, null, null, "AB12CD"), p(bought))
        assertTrue(p(bought).outgoingDone)
        assertNull(MoneyAlerts.classify(bought, trusted = true))
        // [1] MTNGH — the advert after it is on its own lines.
        val recharged = "You have recharged GHC5.00. \n\nGrow your business with targeted mobile ads on MTN. High reach, strong engagement, " +
            "real results. Contact us today. https://bit.ly/MTN-Ads"
        assertEquals(Msg(Kind.AIRTIME, "5.00", null, null, null, null, null, null), p(recharged))
        // [1] MobileMoney — airtime someone paid for from a wallet. It is NOT money received.
        val topUp = "Airtime recharge received for GHS 5.00 from 0241234567. Thank you"
        assertEquals(Msg(Kind.AIRTIME, "5.00", null, "0241234567", null, null, null, null), p(topUp))
        assertNull("airtime is not cash, even from the MobileMoney sender", MoneyAlerts.classify(topUp, trusted = true))
    }

    @Test fun bundles_bought() {
        // [4] MTN
        val data = "Y'ello! you have successfully purchased GHC3 Data Bundle and received 471.86 MB. You have just missed the 5G Bonus " +
            "on your bundle purchase. Buy your bundle on myMTN App to enjoy the 5G Bonus, click https://bit.ly/mymtn5g to download."
        assertEquals(Msg(Kind.BUNDLE, "3", null, null, null, null, null, "Data Bundle"), p(data))
        assertNull(MoneyAlerts.classify(data, trusted = true))
        // [1] MTN
        val midnight = p("Y'ello! you have successfully purchased GHC1.50 Midnight Bundle and received 1.20GB. This bundle does not expire. " +
            "Buy your bundle on myMTN app, click https://mymtn.onelink.me/cB9t/myMTN to install myMTN app and get Free 500MB for new users.")
        assertEquals(Kind.BUNDLE, midnight.kind)
        assertEquals("1.50", midnight.amount)
        assertEquals("Midnight Bundle", midnight.reference)
        // [2] MTN
        val mashup = "Congrats! you have purchased a Mashup of Ghc2.00 to be used for all networks. Dial *567# to check balance. " +
            "Discover MTN Pulse now on MyMTN App. Click here https://mymtn.onelink.me/cB9t/Pulse99 to download MyMTN and Pulse the new life style. T&Cs apply."
        assertEquals(Msg(Kind.BUNDLE, "2.00", null, null, null, null, null, "Mashup"), p(mashup))
        assertTrue(p(mashup).outgoingDone)
    }

    // ---------------------------------------------------------------- codes: never a transaction, never a credit, never spoken

    private val otp = "<#> MTN MOMO: This OTP gives access to your wallet. Anyone asking for this code is trying to fraudulently gain " +
        "access to your funds. MTN MOMO will NEVER ask for it. Enter Code:4821 to complete your login. N/RywfrtIZL 4XTs7SAuJcW"
    private val confidential = "Y'ello. Please note! This confidential code gives access to your MoMo account:VLUST2F+COIO5gBG71tQIauvSEF3pKes== " +
        "Do not share it with anyone. l+/DM+Y3kqw HTx54B8+12w"
    private val idCode = "Y'ello MTNner, do not share your code with any MTN employee or anyone. Your code is : \n482193. 4XTs7SAuJcW"

    @Test fun login_codes_are_secrets() {
        for (s in listOf(otp, confidential, idCode)) {
            assertTrue(s, MoMoOutcome.isSecret(s))
            assertEquals(s, Msg(Kind.OTP, null, null, null, null, null, null, null), MoMoOutcome.parse(s))
            assertFalse(p(s).outgoingDone)
            assertNull(s, MoneyAlerts.classify(s, trusted = true))
            assertNull(s, MoneyAlerts.classify(s, trusted = false, fromNumber = true))
        }
        assertTrue(MoMoOutcome.isSecret("Your MoMo verification code is [code hidden]. Do not share it."))
        // A receipt is not a secret; nor is advice that only NAMES the words.
        for (s in listOf(sentNoStop, sentTidy, completed, received, cashIn,
                "CalBank will never ask you to share your Password, OTP or PIN.",
                "GCB will NEVER ask for your PIN, OTP, password, card details or personal information.",
                "Dear Customer, protect your PIN at ATMs and POS terminals. Cover the keypad when entering it.",
                "Go cashless with MoMoPay. Pay any merchant using ID or QR Code and get 500MB FREE."))
            assertFalse(s, MoMoOutcome.isSecret(s))
        assertFalse(MoMoOutcome.isSecret(null))
    }

    // ---------------------------------------------------------------- adverts and notices from the same senders are nothing

    @Test fun promotions_and_notices_are_not_transactions() {
        for (s in listOf(
            "Go cashless with MoMoPay. Pay any merchant using ID or QR Code and get 500MB FREE. Dial *170#, select MoMoPay or use the MoMoApp https://mtnmymomo.onelink.me/XJOt/cVm9x",
            "Y'ello Valued Customer, get rewarded when you transfer or pay with the MoMo App. Send or pay GHS50 or more during MoMo Hour on " +
                "Tue 16 Sept, 7-8AM; Thu 18 Sept, 12-1PM; or Sat 20 Sept, 6-7PM and enjoy 1GB free data. Get ready: https://mtnmymomo.onelink.me/XJOt/MHour. T&Cs apply.",
            "Y'ello Valued Customer! You qualify for MoMo Boost. Use it for airtime, bill payments and transfers - no more failed transactions. " +
                "Dial *170#, 5, 1 then 6 to activate MoMo Boost today. Use the MoMo App for a Faster & Easier Experience. Click here: https://mtnmymomo.onelink.me/XJOt/MBoost",
            "Congratulations! You have just received 500MB valid for 3 days for using Financial services through your MoMo Account.",
            "Y'ello Valued Customer, save or invest GHS 50 or more on the MoMo App during MoMo Hour this week and enjoy instant cashback of up to GHS 20. " +
                "Tue, Thu & Sun, 12-1PM and enjoy rewards. Get ready: https://mtnmymomo.onelink.me/XJOt/MHour. T&Cs apply.",
            "Kwame Nkrumah Memorial Day is coming! Low on MoMo? Activate MoMo Boost now for up to GHS 1,000 credit to cover your holiday expenses. Dial *170#, 5, 1 then 6 to activate.",
            "Enjoy FREE 500MB for 3 days when you recharge or buy a bundle of GHS10 or more via MoMo! Dial *170# or visit MoMo app: https://mtnmymomo.onelink.me/XJOt/cVm9x",
            "Dear Valued customer, you have exhausted your subscribed Mashup Bundle. Dial *138# to purchase more. Thank you.",
            "Your Account has been credited with 2 GB, valid for 30 days.",
            "Y'ello! 2.00 GHC has been deducted from your Mashup bundle purchase to repay your owed Xtratime balance of -1.50 GHC. " +
                "The remaining balance has been deposited in your airtime account.",
            "Y'ello Your data bundle balance:GHc5 Bundle: 99MBY'ello Your bonus balance: 0",
            "Yello Valued Customer, thank you for reporting this issue. We will investigate and take the necessary action.",
            "Your balance is GHS 20.00"
        )) {
            assertNull(s, MoMoOutcome.parse(s))
            assertNull(s, MoneyAlerts.classify(s, trusted = true))
        }
        assertNull(MoMoOutcome.parse(null))
        assertNull(MoMoOutcome.parse("   "))
    }

    // ---------------------------------------------------------------- who is trusted

    @Test fun only_the_transaction_sender_is_trusted() {
        assertTrue(MoneyAlerts.isTrustedSender("MobileMoney"))
        // The other MTN senders on the phone send codes, adverts and usage notices — never a credit to believe.
        for (s in listOf("MoMoApp", "MTN ID", "MoMo Boost", "MTN", "MTNGhana", "MTNGH", "MTN-Just4U", "MTN_INFO", "MTNXtraTime"))
            assertFalse(s, MoneyAlerts.isTrustedSender(s))
    }

    // ---------------------------------------------------------------- the older, hand-written wordings still parse

    @Test fun older_wordings_still_parse() {
        val sent = p("You have sent GHS 5.00 to KOFI MENSAH 0244123456. Fee: GHS 0.00")
        assertEquals(Msg(Kind.SENT, "5.00", "KOFI MENSAH", "0244123456", null, "0.00", null, null), sent)
        assertEquals("Kofi Mensah", MoneyAlerts.cleanName(sent.party!!))
        assertEquals(Msg(Kind.SENT, "0.10", "AMA MENSAH", null, null, null, null, null), p("Your transfer of GHS 0.10 to AMA MENSAH was successful."))
        // Commas leave the amount; a number alone is no name.
        assertEquals(Msg(Kind.SENT, "1250.00", null, "0244123456", null, null, "1234", null), p("You've sent GH₵1,250.00 to 0244123456. Transaction ID: 1234"))
        assertEquals("KOFI MENSAH", p("GHS 5.00 sent to KOFI MENSAH").party)
        val got = p("You have received GHS 50.00 from KOFI MENSAH (0244123456). Your new balance is GHS 200.00.")
        assertEquals(Msg(Kind.RECEIVED, "50.00", "KOFI MENSAH", "0244123456", "200.00", null, null, null), got)
        // "+233…" folds to the 0-form.
        assertEquals("0244123456", p("You have sent GHS 5.00 to AMA SERWAA +233244123456. Fee: GHS 0.00").partyNumber)
        // Airtime "received" in the older wording is not cash here either.
        assertNull(MoMoOutcome.parse("You have received GHS 5.00 airtime from MTN."))
    }
}
