package com.tekyerema

import com.tekyerema.android.ContactResolver
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.KnownRecipients
import com.tekyerema.money.MoMoAfterCare
import com.tekyerema.money.MoMoOutcome
import com.tekyerema.money.MoMoRetry
import com.tekyerema.money.MoneyLimits
import com.tekyerema.money.MoneyPlan
import com.tekyerema.money.MoneyStatus
import com.tekyerema.money.TxLedger
import com.tekyerema.money.ussd.Boundary
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.TxContext
import com.tekyerema.money.ussd.TxIntent
import com.tekyerema.money.ussd.UssdNavigatorTool
import com.tekyerema.operator.MemoryOperatorStore
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.tools.MoMoSendTool
import com.tekyerema.tools.ToolResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Finance audit (2026-10-01): what became of a payment after the PIN hand-off (TxLedger), the navigator's last screen
 * (Boundary, on the real MTN PIN screen of 29 September), a send to a contact (UssdNavigatorTool), and answers built
 * from the network's own messages (MoneyStatus.Lines).
 */
class FinanceLedgerTest {
    private val t0 = 1_790_000_000_000L
    private val min = 60_000L
    private lateinit var store: MemoryOperatorStore

    @Before fun setUp() {
        AppConfig.loadFrom(emptyList())
        store = MemoryOperatorStore()
        OperatorPolicy.resetForTest(store)
        MoMoRetry.clear(); MoMoAfterCare.resetForTest()
    }

    @After fun tearDown() {
        OperatorPolicy.resetForTest()
        MoMoRetry.clear(); MoMoAfterCare.resetForTest()
        AppConfig.loadFrom(emptyList())
    }

    private fun bd(s: String) = BigDecimal(s)
    private fun msg(kind: MoMoOutcome.Kind, amount: String?, party: String? = null, balance: String? = null, txn: String? = null,
                    number: String? = null) = MoMoOutcome.Msg(kind, amount, party, number, balance, null, txn, null)
    private fun handOff(amount: String, number: String? = "0553238011", name: String? = "Kofi", at: Long = t0,
                        what: TxLedger.What = TxLedger.What.SEND, registered: String? = null) =
        MoneyStatus.handedOff(null, what, bd(amount), number, name, "mtn", at, registered)

    // ---------------------------------------------------------------- the ledger

    @Test fun reaching_the_pin_is_not_a_payment_until_the_network_says_so() {
        val tx = handOff("5")
        assertEquals(TxLedger.State.AT_PIN, tx.state)
        assertEquals("011", tx.tail)
        assertFalse("not a number you've paid yet", KnownRecipients.isKnown("0553238011"))
        assertEquals("counted toward today on the safe side", 0, bd("5").compareTo(MoneyLimits.spentToday(t0)))
        val saved = store.values[TxLedger.K_LEDGER]!!
        assertFalse("the number itself is never kept", saved.contains("553238011"))

        val s = TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH", balance = "120.00", txn = "76712833868"), t0 + min)!!
        assertEquals("MTN confirms 5 cedis sent to Kofi Mensah.", s.line)
        assertEquals(TxLedger.State.DONE, s.tx.state)
        assertEquals("76712833868", s.tx.txnId)
        assertTrue("now it is a number you've paid", KnownRecipients.isKnown("+233553238011"))
        assertNull("the same message again settles nothing", TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH"), t0 + 2 * min))
        assertEquals("Yes — MTN confirmed 5 cedis sent to Kofi Mensah, 3 minutes ago.", TxLedger.statusLine(t0 + 3 * min))
    }

    @Test fun the_balance_is_only_in_the_receipt_when_asked_for() {
        handOff("5")
        val s = TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH", balance = "120.00"), t0 + min, withBalance = true)!!
        assertEquals("MTN confirms 5 cedis sent to Kofi Mensah. Your balance is 120 cedis.", s.line)
    }

    @Test fun a_failed_message_gives_the_days_total_back_and_keeps_the_number_new() {
        handOff("20")
        assertEquals(0, bd("20").compareTo(MoneyLimits.spentToday(t0)))
        val s = TxLedger.settle(msg(MoMoOutcome.Kind.FAILED, "20.00", "KOFI MENSAH"), t0 + min, now = t0 + min)!!
        assertEquals("MTN says the 20 cedis for Kofi failed. Nothing left your wallet for it.", s.line)
        assertEquals(TxLedger.State.FAILED, s.tx.state)
        assertEquals(0, BigDecimal.ZERO.compareTo(MoneyLimits.spentToday(t0 + min)))
        assertFalse(KnownRecipients.isKnown("0553238011"))
        assertEquals("No — MTN said the 20 cedis for Kofi failed, 2 minutes ago. Nothing left your wallet for it.", TxLedger.statusLine(t0 + 2 * min))
        assertNull("a failed send is not a repeat to warn about", TxLedger.repeatOf("0553238011", bd("20"), t0 + 2 * min))
    }

    @Test fun silence_from_the_network_is_said_as_not_known_never_as_sent() {
        val tx = handOff("5")
        assertTrue(TxLedger.statusLine(t0 + 2 * min).startsWith("I took the 5 cedis for Kofi as far as your PIN 2 minutes ago, and I haven't seen MTN's message"))
        assertEquals("I haven't seen MTN's message for the 5 cedis for Kofi. If you finished the payment, it may still be on its way; " +
            "if you didn't, nothing was sent. Ask me “did my payment go through” to check again.", TxLedger.silentLine(tx))
        assertTrue(TxLedger.statusLine(t0 + 11 * min).startsWith("I'm not sure. I took the 5 cedis for Kofi as far as your PIN 11 minutes ago, but I never saw"))
        assertEquals(TxLedger.State.UNKNOWN, TxLedger.latest(t0 + 11 * min)!!.state)
        assertFalse(KnownRecipients.isKnown("0553238011"))
        assertNull("a message that came after the window is someone else's payment", TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00"), t0 + 12 * min))
        // …but one found later in the inbox with a time INSIDE the window still settles it.
        val late = TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH"), t0 + 2 * min)
        assertEquals(TxLedger.State.DONE, late!!.tx.state)
    }

    /** The alert path drops a line that reads like a code (core/Redact): a payment line must never be one, even with a
     *  four-digit amount in it. */
    @Test fun spoken_outcome_lines_are_never_mistaken_for_a_code() {
        val big = handOff("2500", number = "0244123456", name = "Ama")
        for (line in listOf(TxLedger.silentLine(big), TxLedger.outcomeLine(big.copy(state = TxLedger.State.DONE, party = "AMA SERWAA")),
                TxLedger.outcomeLine(big.copy(state = TxLedger.State.FAILED))))
            assertFalse(line, com.tekyerema.core.Redact.looksSecret(line))
    }

    @Test fun only_a_message_after_the_hand_off_and_about_money_going_out_settles_it() {
        handOff("5")
        assertNull("older than the hand-off", TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00"), t0 - 1))
        assertNull("money coming in", TxLedger.settle(msg(MoMoOutcome.Kind.RECEIVED, "5.00", "AMA SERWAA"), t0 + min))
        assertNull("a code", TxLedger.settle(msg(MoMoOutcome.Kind.OTP, null), t0 + min))
        assertNull("an airtime message doesn't settle a send", TxLedger.settle(msg(MoMoOutcome.Kind.AIRTIME, "5.00"), t0 + min))
        assertEquals(TxLedger.State.AT_PIN, TxLedger.latest(t0 + min)!!.state)
    }

    // ---- code review (2026-10-02): a wrong "confirmed" or "failed" is worse than "not known"

    @Test fun a_message_about_another_amount_or_with_none_settles_nothing() {
        handOff("5")
        assertNull("another amount is another payment", TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "50.00", "KOFI MENSAH"), t0 + min))
        assertNull("a failure that names no amount", TxLedger.settle(msg(MoMoOutcome.Kind.FAILED, null), t0 + min))
        assertEquals(TxLedger.State.AT_PIN, TxLedger.latest(t0 + min)!!.state)
    }

    @Test fun one_message_settles_one_hand_off_once_and_the_oldest_first() {
        handOff("5", name = "Kofi", at = t0)
        handOff("5", number = "0244123456", name = "Ama", at = t0 + min)
        val sms = msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH", txn = "76712833868")
        assertEquals("Kofi", TxLedger.settle(sms, t0 + 2 * min)!!.tx.name)
        assertNull("the app's push for the same payment", TxLedger.settle(sms, t0 + 2 * min + 5_000))
        assertNull("the inbox looked at again", TxLedger.settle(sms, t0 + 2 * min))
        assertNull("the same words with no transaction ID", TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH"), t0 + 3 * min))
        assertEquals(listOf("Ama"), TxLedger.waiting(t0 + 3 * min).map { it.name })
        assertFalse(KnownRecipients.isKnown("0244123456"))
    }

    @Test fun a_message_naming_someone_else_is_not_about_this_hand_off() {
        handOff("5", registered = "AMA MENSAH")
        assertNull("another person, same amount", TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH"), t0 + min))
        assertNull("another number, same amount", TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", number = "0244123456"), t0 + min))
        assertFalse(KnownRecipients.isKnown("0553238011"))
        assertEquals(TxLedger.State.DONE, TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "AMA MENSAH 0553238011", number = "0553238011"), t0 + min)!!.tx.state)
    }

    @Test fun the_networks_done_corrects_an_earlier_failed_never_the_reverse() {
        handOff("20")
        TxLedger.settle(msg(MoMoOutcome.Kind.FAILED, "20.00", txn = "1"), t0 + min, now = t0 + min)
        assertEquals(0, BigDecimal.ZERO.compareTo(MoneyLimits.spentToday(t0 + min)))
        val s = TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "20.00", "KOFI MENSAH", txn = "2"), t0 + 2 * min, now = t0 + 2 * min)!!
        assertEquals(TxLedger.State.DONE, s.tx.state)
        assertEquals("counted again", 0, bd("20").compareTo(MoneyLimits.spentToday(t0 + 2 * min)))
        assertTrue(KnownRecipients.isKnown("0553238011"))
        assertNull("a failure after the network said done", TxLedger.settle(msg(MoMoOutcome.Kind.FAILED, "20.00", txn = "3"), t0 + 3 * min))
        assertEquals(TxLedger.State.DONE, TxLedger.latest(t0 + 3 * min)!!.state)
    }

    @Test fun the_daily_total_is_never_moved_by_airtime_or_by_yesterdays_message() {
        handOff("10")                                                                        // a send: today's total is 10
        MoneyStatus.handedOff(null, TxLedger.What.AIRTIME, bd("2"), null, null, "mtn", t0 + min)
        TxLedger.settle(msg(MoMoOutcome.Kind.FAILED, "2.00", txn = "a"), t0 + 2 * min, now = t0 + 2 * min)
        assertEquals("failed airtime was never in the total", 0, bd("10").compareTo(MoneyLimits.spentToday(t0 + 2 * min)))
        // The next day: a new send, then yesterday's failure message is found in the inbox.
        val day = 26 * 60 * min
        handOff("7", number = "0244123456", name = "Ama", at = t0 + day)
        val s = TxLedger.settle(msg(MoMoOutcome.Kind.FAILED, "10.00", txn = "b"), t0 + 5 * min, now = t0 + day + min)
        assertEquals(TxLedger.State.FAILED, s!!.tx.state)
        assertEquals("today's total is untouched", 0, bd("7").compareTo(MoneyLimits.spentToday(t0 + day + min)))
    }

    @Test fun a_payment_to_a_person_does_not_confirm_an_airtime_purchase() {
        MoneyStatus.handedOff(null, TxLedger.What.AIRTIME, bd("5"), null, null, "mtn", t0)
        assertNull(TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "AMA SERWAA"), t0 + min))
        val s = TxLedger.settle(msg(MoMoOutcome.Kind.PAID, "5.00", "MTNONLINEAIRTIMEVENDOR"), t0 + min)!!
        assertEquals("MTN confirms 5 cedis of airtime bought.", s.line)
    }

    @Test fun two_waiting_hand_offs_are_told_apart_by_amount() {
        handOff("5", name = "Kofi", at = t0)
        handOff("20", number = "0244123456", name = "Ama", at = t0 + min)
        assertNull("neither amount: not ours to guess", TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "7.00"), t0 + 2 * min))
        val s = TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "20.00", "AMA SERWAA"), t0 + 2 * min)!!
        assertEquals("MTN confirms 20 cedis sent to Ama Serwaa.", s.line)
        assertTrue(KnownRecipients.isKnown("0244123456"))
        assertFalse(KnownRecipients.isKnown("0553238011"))
        assertEquals(1, TxLedger.waiting(t0 + 2 * min).size)
    }

    @Test fun the_same_send_again_is_flagged_by_what_happened_to_the_first() {
        handOff("5")
        val first = TxLedger.repeatOf("+233553238011", bd("5.00"), t0 + 3 * min)
        assertNotNull(first)
        assertEquals("You took the same amount to this number as far as your PIN 3 minutes ago, and I haven't seen whether it went through. " +
            "If it did, this would pay twice.", TxLedger.repeatWarning(first!!, t0 + 3 * min))
        assertNull("another amount", TxLedger.repeatOf("0553238011", bd("6"), t0 + 3 * min))
        assertNull("another number", TxLedger.repeatOf("0244123456", bd("5"), t0 + 3 * min))
        TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH"), t0 + 4 * min)
        assertEquals("You sent the same amount to this number 5 minutes ago, and MTN confirmed it. This would be a second payment.",
            TxLedger.repeatWarning(TxLedger.repeatOf("0553238011", bd("5"), t0 + 5 * min)!!, t0 + 5 * min))
        assertNull("long enough ago to be a new payment", TxLedger.repeatOf("0553238011", bd("5"), t0 + 16 * min))
    }

    @Test fun airtime_is_settled_by_the_airtime_message() {
        MoneyStatus.handedOff(null, TxLedger.What.AIRTIME, bd("2"), null, null, "mtn", t0)
        assertEquals("airtime is not a Mobile Money send for the daily limit", 0, BigDecimal.ZERO.compareTo(MoneyLimits.spentToday(t0)))
        val s = TxLedger.settle(msg(MoMoOutcome.Kind.AIRTIME, "2.00"), t0 + min)!!
        assertEquals("MTN confirms 2 cedis of airtime bought.", s.line)
    }

    @Test fun the_ledger_survives_a_restart_and_delete_everything_empties_it() {
        handOff("5")
        TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH"), t0 + min)
        TxLedger.resetForTest(store)
        val tx = TxLedger.latest(t0 + 2 * min)!!
        assertEquals(TxLedger.State.DONE, tx.state)
        assertEquals("5", tx.amount)
        assertEquals("Kofi", tx.name)
        TxLedger.clear()
        assertNull(TxLedger.latest(t0 + 2 * min))
        assertNull(store.values[TxLedger.K_LEDGER])
        assertEquals(TxLedger.NOTHING, TxLedger.statusLine(t0 + 2 * min))
    }

    @Test fun todays_total_keeps_confirmed_and_unconfirmed_apart() {
        handOff("5", at = t0)
        handOff("20", number = "0244123456", name = "Ama", at = t0 + min)
        handOff("10", number = "0201234567", name = "Yaw", at = t0 + 2 * min)
        TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "5.00", "KOFI MENSAH"), t0 + 3 * min)
        TxLedger.settle(msg(MoMoOutcome.Kind.SENT, "20.00", "AMA SERWAA"), t0 + 3 * min)
        assertEquals("Today the network has confirmed 25 cedis through me, in 2 payments. Another 10 cedis in 1 payment reached your PIN, " +
            "but I haven't seen it confirmed. That's only what went through me — your Mobile Money statement has everything.",
            TxLedger.spentLine(dayStart = t0 - 60 * min, now = t0 + 4 * min))
        assertEquals("I haven't taken any Mobile Money payment to your PIN today.", TxLedger.spentLine(dayStart = t0 + 60 * min, now = t0 + 61 * min))
    }

    @Test fun how_long_ago_is_said_the_way_people_say_it() {
        assertEquals("a moment ago", TxLedger.ago(20_000))
        assertEquals("a minute ago", TxLedger.ago(min))
        assertEquals("45 minutes ago", TxLedger.ago(45 * min))
        assertEquals("an hour ago", TxLedger.ago(70 * min))
        assertEquals("5 hours ago", TxLedger.ago(5 * 60 * min))
        assertEquals("yesterday", TxLedger.ago(30 * 60 * min))
        assertEquals("3 days ago", TxLedger.ago(3 * 24 * 60 * min))
    }

    // ---------------------------------------------------------------- the navigator's last screen

    /** The final *170# screen of the owner's live 10-pesewa send (A20e, 2026-09-29) — the same fixture as MoneySafetyTest. */
    private val REAL = "Transfer to AMA MENSAH for GHS 0.1 with Reference: Payment. Fee is GHS 0.00, Tax amount is GHS 0.00. " +
        "Total Amount is GHS 0.10. Enter MM # for next\nCancel"
    private fun screen(text: String = REAL) = MenuModel.read(text, hasInput = true, masked = false)
    private fun send(amount: String = "0.1", number: String = "0553238011", name: String? = null) =
        TxContext(TxIntent.SEND_MONEY, "mtn", recipient = number, recipientName = name, amount = amount, confirmedUpFront = true)

    @Test fun the_real_mtn_pin_screen_is_handed_over_with_name_fee_and_total() {
        assertEquals(MenuModel.Kind.PIN, screen().kind)
        val v = Boundary.decide(send(), screen()) as Boundary.Verdict.HandOff
        assertEquals("MTN shows 0.10 cedis to Zelia Awini, no fee, total 0.10 cedis — if that's right, enter your PIN; if not, press Cancel. " +
            "I never see or type your PIN." + com.tekyerema.money.PinPad.handOffTip(), v.line)
        assertEquals("Zelia Awini", v.registeredName)
        val tip = Boundary.decide(send(), screen(), pinTip = "Use earphones.") as Boundary.Verdict.HandOff
        assertTrue(tip.line, tip.line.contains("I never see or type your PIN. Use earphones."))
    }

    @Test fun a_contact_whose_registered_name_differs_is_said_before_the_pin() {
        val v = Boundary.decide(send(name = "Prince Melanin"), screen()) as Boundary.Verdict.HandOff
        assertTrue(v.line, v.line.startsWith("MTN shows 0.10 cedis to Zelia Awini, no fee, total 0.10 cedis — that isn't the name you have saved, " +
            "Prince Melanin, so make sure it's the right person — if that's right, enter your PIN"))
        val same = Boundary.decide(send(name = "Zelia"), screen()) as Boundary.Verdict.HandOff
        assertFalse(same.line.contains("isn't the name you have saved"))
        val part = Boundary.decide(send(name = "Zelia Mensah"), screen()) as Boundary.Verdict.HandOff
        assertTrue(part.line, part.line.contains("total 0.10 cedis — only part of that matches the name you have saved, Zelia Mensah, so check it's the right person — if that's right"))
    }

    @Test fun a_screen_that_does_not_match_what_was_confirmed_is_stopped() {
        val amount = Boundary.decide(send(amount = "1"), screen()) as Boundary.Verdict.Stop
        assertEquals("MTN's screen shows 0.10 cedis, but you asked for 1 cedi", amount.why)
        val blind = Boundary.decide(send(), screen("Transfer to AMA MENSAH. Enter MM PIN to confirm")) as Boundary.Verdict.Stop
        assertEquals("MTN's screen doesn't show an amount I can check against the 0.10 cedis you asked for", blind.why)
        val other = REAL.replace("AMA MENSAH for", "AMA MENSAH (0244123456) for")
        val number = Boundary.decide(send(), screen(other)) as Boundary.Verdict.Stop
        assertTrue(number.why, number.why.startsWith("MTN's screen shows the number 0 2 4, 4 1 2, 3 4 5 6, not 0 5 5"))
    }

    @Test fun saved_names_and_registered_names_are_compared_by_their_name_words() {
        assertTrue(Boundary.nameDiffers("Prince Melanin", "Zelia Awini"))
        assertTrue(Boundary.nameDiffers("Richmond's Dad", "Kwame Apenensu"))
        assertFalse(Boundary.nameDiffers("Prince", "PRINCE NASAMU ALHASSAN"))
        assertFalse(Boundary.nameDiffers("Chris", "Christopher Osei"))
        assertFalse("nothing to compare is not a difference", Boundary.nameDiffers("Mum", "Akosua Mensah"))
        assertFalse(Boundary.nameDiffers(null, "Akosua Mensah"))
        assertFalse(Boundary.nameDiffers("Kofi", null))
        assertEquals("a shared first name is weak evidence", Boundary.NameMatch.PARTIAL, Boundary.nameMatch("Kofi Mensah", "KOFI BOATENG"))
        assertEquals(Boundary.NameMatch.SAME, Boundary.nameMatch("Kofi Mensah", "KOFI MENSAH ADDO"))
        assertEquals(Boundary.NameMatch.SAME, Boundary.nameMatch("Prince", "PRINCE NASAMU ALHASSAN"))
        assertEquals(Boundary.NameMatch.UNKNOWN, Boundary.nameMatch("Mum", "Akosua Mensah"))
    }

    /** INVENTED screens (no airtime or balance walk has been captured live yet): only the wording of our own line is checked. */
    @Test fun airtime_and_balance_hand_overs_say_what_the_pin_is_for() {
        val air = Boundary.decide(TxContext(TxIntent.BUY_AIRTIME, "mtn", amount = "5"), screen("Buy airtime of GHS 5.00. Enter MM PIN")) as Boundary.Verdict.HandOff
        assertEquals("You're about to buy 5 cedis of airtime for this phone — if that's right, enter your PIN; if not, press Cancel. " +
            "I never see or type your PIN." + com.tekyerema.money.PinPad.handOffTip(), air.line)
        val other = Boundary.decide(TxContext(TxIntent.SEND_AIRTIME, "mtn", recipient = "0552093690", recipientName = "Prince Melanin", amount = "5"),
            screen("Buy airtime of GHS 5.00. Enter MM PIN")) as Boundary.Verdict.HandOff
        assertTrue(other.line.startsWith("You're about to buy 5 cedis of airtime for Prince Melanin — "))
        val bal = Boundary.decide(TxContext(TxIntent.CHECK_BALANCE, "mtn"), screen("Enter MM PIN")) as Boundary.Verdict.HandOff
        assertEquals("Enter your PIN now, and I'll read your Mobile Money balance when the network answers. I never see or type your PIN." + com.tekyerema.money.PinPad.handOffTip(), bal.line)
    }

    // ---------------------------------------------------------------- a send to a contact

    private val several: (String, List<ContactResolver.Contact>) -> ToolResult =
        { said, m -> ToolResult(false, "More than one contact matches $said: ${m.size}", needsDisambiguation = true) }

    @Test fun a_named_person_is_resolved_to_the_one_saved_number() {
        val args = JSONObject().put(UssdNavigatorTool.ARG_CONTACT, "prince melanin").put("amount", "50")
        assertNull(UssdNavigatorTool.recipient(args, { listOf(ContactResolver.Contact("Prince Melanin", "+233552093690")) }, several))
        assertEquals("0552093690", args.getString("number"))
        assertEquals("Prince Melanin", args.getString(UssdNavigatorTool.ARG_CONTACT))
        args.put(MoMoSendTool.ARG_SIM, "mtn")
        assertEquals("Send 50 cedis to Prince Melanin, ending 3 6 9 0, an MTN number, with Mobile Money?\n" +
            "I'll go through the menu quietly and stop at the PIN for you to enter.", UssdNavigatorTool.confirmationPrompt(args))
        args.put(MoMoSendTool.ARG_NEW_RECIPIENT, true).put(UssdNavigatorTool.ARG_REPEAT, "REPEAT.")
        assertTrue(UssdNavigatorTool.confirmationPrompt(args).contains("with Mobile Money?\nREPEAT. You haven't sent money to this number before. I'll go through"))
    }

    @Test fun nobody_is_guessed_when_the_name_is_unknown_shared_or_a_pronoun() {
        val none = UssdNavigatorTool.recipient(JSONObject().put(UssdNavigatorTool.ARG_CONTACT, "Kofi"), { emptyList() }, several)!!
        assertTrue(none.message, none.message.startsWith("I couldn't find “Kofi” in your contacts, so nothing was sent."))
        val two = UssdNavigatorTool.recipient(JSONObject().put(UssdNavigatorTool.ARG_CONTACT, "Prince"),
            { listOf(ContactResolver.Contact("Prince Mensah", "0240000001"), ContactResolver.Contact("Prince Melanin", "0552093690")) }, several)!!
        assertTrue(two.needsDisambiguation)
        val him = UssdNavigatorTool.recipient(JSONObject().put(UssdNavigatorTool.ARG_CONTACT, "him"), { error("never looked up") }, several)!!
        assertTrue(him.message.startsWith("I don't know who “him” is"))
        val nobody = UssdNavigatorTool.recipient(JSONObject(), { emptyList() }, several)!!
        assertTrue(nobody.message.startsWith("I didn't get who it's for."))
        val short = UssdNavigatorTool.recipient(JSONObject().put(UssdNavigatorTool.ARG_CONTACT, "Office"), { listOf(ContactResolver.Contact("Office", "12345")) }, several)!!
        assertTrue(short.message, short.message.startsWith("Office's saved number, ") && short.message.contains("isn't a ten-digit Ghana mobile number"))
        // A number the user dictated (or picked from "which one?") is used as it is, folded to the 0-form.
        val picked = JSONObject().put("number", "233552093690").put(UssdNavigatorTool.ARG_CONTACT, "Prince Melanin")
        assertNull(UssdNavigatorTool.recipient(picked, { error("never looked up") }, several))
        assertEquals("0552093690", picked.getString("number"))
    }

    @Test fun zero_and_unclear_amounts_are_never_read_back_as_a_send() {
        assertNull(UssdNavigatorTool.amountProblem("5"))
        assertNull(UssdNavigatorTool.amountProblem("0.5"))
        assertTrue(UssdNavigatorTool.amountProblem("0")!!.startsWith("I heard zero cedis"))
        assertTrue(UssdNavigatorTool.amountProblem("lots")!!.startsWith("I couldn't make out the amount “lots”"))
        assertTrue(UssdNavigatorTool.amountProblem(null)!!.startsWith("I didn't get the amount."))
    }

    @Test fun airtime_is_read_back_with_whose_phone_it_is_for() {
        val self = JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.BUY_AIRTIME).put("amount", "5")
        assertEquals(TxIntent.BUY_AIRTIME, UssdNavigatorTool.intentOf(self))
        assertEquals("Buy 5 cedis of airtime for this phone, from your Mobile Money wallet?\n" +
            "I'll go through the menu quietly and stop at the PIN for you to enter.", UssdNavigatorTool.confirmationPrompt(self))
        val other = JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.SEND_AIRTIME).put("amount", "5")
            .put("number", "0552093690").put(UssdNavigatorTool.ARG_CONTACT, "Prince Melanin").put(MoMoSendTool.ARG_SIM, "mtn")
        assertEquals("Buy 5 cedis of airtime for Prince Melanin, ending 3 6 9 0, an MTN number, from your Mobile Money wallet?\n" +
            "I'll go through the menu quietly and stop at the PIN for you to enter.", UssdNavigatorTool.confirmationPrompt(other))
        assertEquals(TxIntent.SEND_MONEY, UssdNavigatorTool.intentOf(JSONObject().put("number", "0552093690")))
        assertEquals(UssdNavigatorTool.BALANCE_PROMPT,
            UssdNavigatorTool.confirmationPrompt(JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.CHECK_BALANCE)))
    }

    @Test fun which_one_asked_about_a_contact_does_not_end_the_task() {
        val asked = "ussd_navigate({\"contact\":\"Prince\",\"amount\":\"50\"}) → several contacts matched; the user was asked " +
            "\"Which Prince: Prince Mensah, or Prince Melanin?\" and answered \"Prince Melanin\" — the call is being proposed again for that contact."
        assertNull(MoneyPlan.navigatorEnd(listOf(asked)))
        val failed = "ussd_navigate({\"contact\":\"Kofi\",\"amount\":\"50\"}) → fail [preflight]; verified=false (not attempted); I couldn't find “Kofi” in your contacts, so nothing was sent."
        assertEquals("I couldn't find “Kofi” in your contacts, so nothing was sent.", MoneyPlan.navigatorEnd(listOf(failed))!!.second)
    }

    // ---------------------------------------------------------------- answers from the network's messages

    @Test fun answers_are_built_from_parsed_fields_never_a_messages_own_text() {
        val now = t0 + 60 * min
        val got = listOf(
            MoneyStatus.Entry(msg(MoMoOutcome.Kind.RECEIVED, "50.00", "KOFI MENSAH", balance = "170.00"), t0 + 30 * min),
            MoneyStatus.Entry(msg(MoMoOutcome.Kind.CASH_IN, "20.00", "AMA SERWAA"), t0 + 10 * min),
            MoneyStatus.Entry(msg(MoMoOutcome.Kind.SENT, "5.00", "YAW BOATENG"), t0 + 5 * min))
        assertEquals("Yes — 2 payments came in today: 50 cedis from Kofi Mensah, and 20 cedis from Ama Serwaa.", MoneyStatus.Lines.receivedToday(got))
        assertEquals("No — I don't see any Mobile Money coming in today.", MoneyStatus.Lines.receivedToday(got.drop(2)))
        assertEquals("Your last Mobile Money message, from 30 minutes ago, says you received 50 cedis from Kofi Mensah.",
            MoneyStatus.Lines.lastMessage(got, withBalance = false, now = now))
        assertEquals("Your last Mobile Money message, from 30 minutes ago, says you received 50 cedis from Kofi Mensah. Your balance after it was 170 cedis.",
            MoneyStatus.Lines.lastMessage(got, withBalance = true, now = now))
        assertEquals("The last payment in your Mobile Money messages, from 55 minutes ago, says you sent 5 cedis to Yaw Boateng.",
            MoneyStatus.Lines.lastOutgoing(got, now))
        assertEquals("a payment of 5 cedis to Yaw Boateng failed", MoneyStatus.Lines.says(msg(MoMoOutcome.Kind.FAILED, "5.00", "YAW BOATENG")))
        assertEquals("you bought 2 cedis of airtime", MoneyStatus.Lines.says(msg(MoMoOutcome.Kind.AIRTIME, "2.00")))
        assertEquals("I don't see a Mobile Money transaction message on this phone.", MoneyStatus.Lines.lastMessage(emptyList(), false, now))
    }
}
