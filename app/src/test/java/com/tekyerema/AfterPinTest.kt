package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.KnownRecipients
import com.tekyerema.money.MoMoAfterCare
import com.tekyerema.money.MoMoOutcome
import com.tekyerema.money.MoMoRetry
import com.tekyerema.money.MoneyLimits
import com.tekyerema.money.MoneyStatus
import com.tekyerema.money.TxLedger
import com.tekyerema.money.ussd.AfterPin
import com.tekyerema.operator.MemoryOperatorStore
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.voice.Verbalize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * money/ussd/AfterPin (13 October 2026): the network's last word after the PIN, read from its own screen — the receipt
 * of a send settles the ledger and is said short; anything else is read as written; the PIN prompt is never read.
 * The receipt text is the USSD simulator's, which is MTN's own shape (audit/finance-2026-10-01/sms_shapes.txt).
 */
class AfterPinTest {
    private val t0 = 1_790_000_000_000L
    private val min = 60_000L
    private lateinit var store: MemoryOperatorStore
    private val receipt = "Payment made for GHS 0.10 to PRINCE NASAMU ALHASSAN 0552093690. Current Balance: GHS 120.00. " +
        "Available Balance: GHS 120.00. Reference: test. Transaction ID: 76712833868. TRANSACTION FEE: 0.00"
    private val balance = "Current Balance: GHS 120.00 Available Balance: GHS 120.00"

    @Before fun setUp() {
        AppConfig.loadFrom(emptyList()); store = MemoryOperatorStore(); OperatorPolicy.resetForTest(store)
        MoMoRetry.clear(); MoMoAfterCare.resetForTest()
    }

    @After fun tearDown() {
        OperatorPolicy.resetForTest(); MoMoRetry.clear(); MoMoAfterCare.resetForTest(); AppConfig.loadFrom(emptyList())
    }

    private fun handOff() = MoneyStatus.handedOff(null, TxLedger.What.SEND, BigDecimal("0.1"), "0552093690", "Prince", "mtn", t0)

    @Test fun the_receipt_on_the_screen_settles_the_hand_off_and_is_said_short() {
        handOff()
        val s = AfterPin.lineFor(receipt, "mtn", withBalance = false, twiConversation = false, now = t0 + 20_000)
        // The figure stays "0.10 cedis" in the text; the voice says it in pesewas (F16, AuditPlannerFixesTest).
        assertEquals("MTN confirms 0.10 cedis sent to Prince Nasamu Alhassan.", s.english)
        assertTrue(Verbalize.apply(s.english), Verbalize.apply(s.english).contains("ten pesewas"))
        assertNull(s.twi)
        assertEquals(TxLedger.State.DONE, s.settled!!.tx.state)
        assertEquals("76712833868", s.settled!!.tx.txnId)
        assertTrue("now a number you have paid", KnownRecipients.isKnown("0552093690"))
        // MTN's text message with the same transaction ID, minutes later, is the same message to the ledger: silence.
        assertNull(TxLedger.settle(MoMoOutcome.parse(receipt)!!, t0 + 3 * min, false, t0 + 3 * min))
        assertTrue("nothing left waiting", TxLedger.waiting(t0 + 3 * min).isEmpty())
    }

    @Test fun the_balance_is_in_the_receipt_only_when_the_user_hears_balances() {
        handOff()
        val s = AfterPin.lineFor(receipt, "mtn", withBalance = true, twiConversation = false, now = t0 + 20_000)
        assertTrue(s.english, s.english.startsWith("MTN confirms 0.10 cedis sent to Prince Nasamu Alhassan. Your balance is 120"))
    }

    @Test fun a_screen_that_is_no_receipt_is_read_as_written_and_the_hand_off_stays_open() {
        handOff()
        val s = AfterPin.lineFor("Your request is being processed. You will receive an SMS shortly.", "mtn",
            withBalance = false, twiConversation = false, now = t0 + 20_000)
        assertNull(s.settled)
        assertTrue(s.english, s.english.startsWith("MTN says."))
        assertTrue(s.english, s.english.contains("being processed"))
        assertEquals("the text message or the follow-up still settles it", TxLedger.State.AT_PIN, TxLedger.latest(t0 + 30_000)!!.state)
    }

    @Test fun a_wrong_pin_settles_the_hand_off_as_failed_and_gives_today_its_amount_back() {
        handOff()
        assertEquals("reserved on the safe side at the hand-off", 0, BigDecimal("0.1").compareTo(MoneyLimits.spentToday(t0 + 1_000)))
        val s = AfterPin.lineFor("Wrong PIN. You have 2 attempts left.", "mtn", withBalance = false, twiConversation = false, now = t0 + 20_000)
        assertEquals(TxLedger.State.FAILED, s.settled!!.tx.state)
        assertTrue(s.english, s.english.startsWith("MTN says."))
        assertTrue(s.english, s.english.contains("Wrong PIN") && s.english.endsWith("Nothing left your wallet."))
        assertEquals("the amount is back in today's allowance", 0, BigDecimal.ZERO.compareTo(MoneyLimits.spentToday(t0 + 30_000)))
        assertTrue("nothing left for the follow-up to chase", TxLedger.waiting(t0 + 30_000).isEmpty())
        // A blocked PIN and MTN's own "Not enough funds" final (A20e, 3 October 2026) are the same ending.
        assertEquals(TxLedger.State.FAILED, handOff().let { AfterPin.lineFor("Your PIN has been blocked. Visit an MTN office.", "mtn", false, false, t0 + 40_000).settled!!.tx.state })
        val funds = handOff().let { AfterPin.lineFor("Not enough funds to perform this transaction.", "mtn", false, false, t0 + 50_000) }
        assertEquals(TxLedger.State.FAILED, funds.settled!!.tx.state)
        assertTrue(funds.english, funds.english.startsWith("MTN says.") &&
            funds.english.endsWith("Not enough funds to perform this transaction. Nothing left your wallet."))
    }

    @Test fun the_screen_settles_the_newest_hand_off_not_an_older_one_of_the_same_amount() {
        // Emulator 13 Oct 03:29: the first 0.10 send's receipt was still open; the second send's wrong PIN marked the FIRST failed.
        val first = handOff()
        val second = MoneyStatus.handedOff(null, TxLedger.What.SEND, BigDecimal("0.1"), "0552093690", "Prince", "mtn", t0 + 60_000)
        val s = AfterPin.lineFor("Wrong PIN. You have 2 attempts left.", "mtn", withBalance = false, twiConversation = false, now = t0 + 80_000)
        assertEquals(second.at, s.settled!!.tx.at)
        assertEquals(TxLedger.State.FAILED, TxLedger.all(t0 + 90_000).single { it.at == second.at }.state)
        assertEquals("the older one is still the network's to tell", TxLedger.State.AT_PIN, TxLedger.all(t0 + 90_000).single { it.at == first.at }.state)
        // …and a receipt on the screen likewise settles the newest, even when the older one has the same amount.
        val third = MoneyStatus.handedOff(null, TxLedger.What.SEND, BigDecimal("0.1"), "0552093690", "Prince", "mtn", t0 + 120_000)
        val r = AfterPin.lineFor(receipt, "mtn", withBalance = false, twiConversation = false, now = t0 + 140_000)
        assertEquals(third.at, r.settled!!.tx.at)
        assertEquals(TxLedger.State.AT_PIN, TxLedger.all(t0 + 150_000).single { it.at == first.at }.state)
    }

    @Test fun a_balance_check_has_nothing_in_the_ledger_and_is_read_in_full_or_in_twi() {
        val s = AfterPin.lineFor(balance, "mtn", withBalance = false, twiConversation = false, now = t0)
        assertNull(s.settled)
        assertTrue(s.english, s.english.startsWith("MTN says."))
        assertTrue(s.english, s.english.contains("120"))
        assertNull(s.twi)
        assertNotNull("a balance in a Twi conversation has a Twi line", AfterPin.lineFor(balance, "mtn", false, true, t0).twi)
    }
}
