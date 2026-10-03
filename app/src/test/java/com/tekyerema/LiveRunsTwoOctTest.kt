package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.BankUssd
import com.tekyerema.money.TelcoGuide
import com.tekyerema.money.TelcoSession
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.TelcoUssdTool
import com.tekyerema.money.ussd.Boundary
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.SlotKind
import com.tekyerema.money.ussd.TxContext
import com.tekyerema.money.ussd.TxIntent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The owner's live runs on the A20e, 2 October 2026 02:02–02:05 — every screen below is REAL, copied from the phone's log.
 *
 *  - "Send 10 pesewas to Prince Melanin": one read-back, a silent walk (Transfer Money → MoMo User → number → confirm →
 *    amount → reference), MTN's PIN screen 14.5 s after dialling, the PIN entered by the owner, MTN's message settling
 *    the hand-off 35 s later. No question was asked. The receipt was only announced as "news about your payment".
 *  - "Send 10 pesewas mashup to Prince melanin": one read-back, then FOUR questions — which offer tier, "GHC 1 or Enter
 *    amount", which mix (read out as one unintelligible run), and how to pay. The first two were answerable from the
 *    request; the third had to be readable; the fourth is the user's unless they said it.
 */
class LiveRunsTwoOctTest {
    @Before fun setUp() { AppConfig.loadFrom(emptyList()); TelcoGuide.resetForTest() }
    @After fun tearDown() { AppConfig.loadFrom(emptyList()); TelcoGuide.resetForTest() }

    private val OFFERS = "MashUp Offers 1. MashUp GHC 1 2. MashUp GHC 5 3. MashUp GHC 10 4. MashUp GHC 30 0. Back"
    private val TIER = "1. GHC 1 2. Enter amount GHC (0.07-4.99) 0. Back"
    private val AMOUNT = "Enter amount to buy your preferred bundle (GHC 0.07 - 4.99)"
    private val MIX = "Select your preferred option 1.2.54MB and 1.12Mins 2.3.05MB and 0.9Mins 3.3.56MB and 0.67Mins 4.5.09MB Only"
    private val PAY = "Choose payment mode 1. Airtime 2. Mobile Money"

    private fun flow(goal: String) = (TelcoUssd.parse(goal, "mtn") as TelcoUssd.Request.Open).flow.copy(number = "0552093690")
    private fun step(screen: String) = "telco_ussd({\"_action\":\"reply\",\"_text\":\"1\",\"_auto\":true}) → success [native]; verified=true " +
        "(the network's next screen was read); MTN: ${screen.take(120)}"
    private fun decide(goal: String, screen: String, userDrove: Boolean = false, history: List<String> = listOf(step(screen))) =
        TelcoGuide.decide(goal, history, TelcoSession.View(flow(goal), screen, BankUssd.ScreenKind.MENU, 1L, 1L, stepDone = 2, userDrove = userDrove), "mtn")
    private fun reply(d: AgentDecision?): org.json.JSONObject { d as AgentDecision.CallTool; assertEquals(d.toString(), TelcoUssdTool.NAME, d.name); return d.args }
    private fun question(d: AgentDecision?): String { d as AgentDecision.CallTool; assertEquals(d.toString(), "ask_user", d.name); return d.args.getString("question") }

    private val GOAL = "Send 10 pesewas mashup to Prince melanin"

    // ---------------------------------------------------------------- the Mashup walk

    @Test fun the_offer_tier_and_the_enter_amount_row_are_chosen_from_the_amount_that_was_said() {
        val tier = reply(decide(GOAL, OFFERS))
        assertEquals("1", tier.getString(TelcoUssdTool.ARG_TEXT))
        assertTrue(tier.optBoolean(TelcoUssdTool.ARG_AUTO))
        assertEquals("MashUp GHC 1", tier.getString(TelcoUssdTool.ARG_ROW))
        val enter = reply(decide(GOAL, TIER))
        assertEquals("2", enter.getString(TelcoUssdTool.ARG_TEXT))
        assertTrue(enter.optBoolean(TelcoUssdTool.ARG_AUTO))
        val typed = reply(decide(GOAL, AMOUNT))
        assertEquals("0.10", typed.getString(TelcoUssdTool.ARG_TEXT))
        assertTrue(typed.optBoolean(TelcoUssdTool.ARG_AUTO))
    }

    @Test fun a_tier_is_only_inferred_when_every_option_is_a_tier() {
        val rows = TelcoUssd.rows(OFFERS)
        assertEquals("1", TelcoGuide.tierRow(rows, "0.10")!!.first)
        assertEquals("1", TelcoGuide.tierRow(rows, "3")!!.first)
        assertEquals("2", TelcoGuide.tierRow(rows, "7")!!.first)
        assertEquals("4", TelcoGuide.tierRow(rows, "200")!!.first)
        assertEquals("an exact price is the priced-row rule's", "2", TelcoGuide.priceRow(rows, "5")!!.first)
        assertNull(TelcoGuide.tierRow(TelcoUssd.rows("1. MashUp for Self 2. MashUp for Others 3. Convert Minutes To Data"), "0.10"))
        assertNull(TelcoGuide.tierRow(TelcoUssd.rows("1. MashUp GHC 1 2. Check balance 0. Back"), "0.10"))
        // "Enter amount" is taken only when the amount fits the range the row states.
        assertEquals("2", TelcoGuide.amountRow(TelcoUssd.rows(TIER), "0.10")!!.first)
        assertNull(TelcoGuide.amountRow(TelcoUssd.rows(TIER), "10"))
        assertEquals("the 1 cedi offer itself", "1", TelcoGuide.priceRow(TelcoUssd.rows(TIER), "1")!!.first)
    }

    @Test fun once_the_user_has_taken_over_no_tier_is_inferred_for_them() {
        assertTrue(question(decide(GOAL, OFFERS, userDrove = true)).startsWith("MTN says: “1 MashUp GHC 1; 2 MashUp GHC 5;"))
    }

    @Test fun the_mixes_are_options_even_with_no_space_after_the_number_and_can_be_heard() {
        assertEquals(listOf("1" to "2.54MB and 1.12Mins", "2" to "3.05MB and 0.9Mins", "3" to "3.56MB and 0.67Mins", "4" to "5.09MB Only"),
            TelcoUssd.rows(MIX))
        // Heard as ONE choice now (the menu brain, 2 October 2026) — not four rows of decimals.
        assertEquals("MTN has 4 mixes. The most calls is 1.1 minutes with 2.5 megabytes; the most data is 5.1 megabytes and no " +
            "call minutes. Do you want more calls or more data?", question(decide(GOAL, MIX)))
        // The ordinary shapes are untouched.
        assertEquals(listOf("1" to "Proceed to buy bundle (No 4G bonus)", "99" to "More"),
            TelcoUssd.rows("Welcome to MTN Pulse. Please select an option 1. Proceed to buy bundle (No 4G bonus) 99. More"))
        assertEquals(5, TelcoUssd.rows(OFFERS).size)
        assertTrue(TelcoUssd.rows(AMOUNT).isEmpty())
        // What the owner said at that screen, and at the next one.
        val q = question(decide(GOAL, MIX))
        val pick = reply(decide(GOAL, MIX, history = listOf(step(MIX), "ask_user(\"$q\") → the user answered: \"do the first option\"")))
        assertEquals("1", pick.getString(TelcoUssdTool.ARG_TEXT))
    }

    @Test fun how_to_pay_is_mobile_money_unless_the_request_said_airtime() {
        // Owner, 2 October 2026: "for all of these purchases, pay using MoMo rather than airtime" — the PIN stays the last step.
        assertEquals("momo", flow(GOAL).pay)
        assertEquals("2", reply(decide(GOAL, PAY)).getString(TelcoUssdTool.ARG_TEXT))
        val said = "Send 10 pesewas mashup to Prince melanin with mobile money"
        assertEquals("momo", flow(said).pay)
        assertEquals("Prince melanin", (TelcoUssd.parse(said, "mtn") as TelcoUssd.Request.Open).flow.name)
        val momo = reply(decide(said, PAY))
        assertEquals("2", momo.getString(TelcoUssdTool.ARG_TEXT))
        assertTrue(momo.optBoolean(TelcoUssdTool.ARG_AUTO))
        assertEquals("1", reply(decide("buy a 10 pesewas mashup from my airtime", PAY)).getString(TelcoUssdTool.ARG_TEXT))
        assertNull(TelcoGuide.payRow(TelcoUssd.rows("1. Airtime 2. Data"), "momo"))
        // It is in the one read-back.
        val args = org.json.JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN).put(TelcoUssdTool.ARG_WHAT, "an MTN Mashup bundle")
            .put(TelcoUssdTool.ARG_NET, "MTN").put(TelcoUssdTool.ARG_AMOUNT, "0.10").put(TelcoUssdTool.ARG_PAY, "momo")
        assertTrue(TelcoUssdTool.confirmationPrompt(args).startsWith("Buy an MTN Mashup bundle for 0.10 cedis, paying with Mobile Money?"))
    }

    // ---------------------------------------------------------------- the send

    @Test fun mtns_real_prompts_are_read_as_what_they_ask_for() {
        for ((screen, slot) in listOf("Enter mobile number" to SlotKind.PHONE_NUMBER, "Confirm Number" to SlotKind.CONFIRM_NUMBER,
                "Enter Amount" to SlotKind.AMOUNT, "Enter Reference" to SlotKind.REFERENCE, "Enter Phone Number" to SlotKind.PHONE_NUMBER,
                "Confirm Phone Number" to SlotKind.CONFIRM_NUMBER, AMOUNT to SlotKind.AMOUNT)) {
            val m = MenuModel.read(screen, hasInput = true, masked = false)
            assertEquals(screen, MenuModel.Kind.INPUT, m.kind)
            assertEquals(screen, slot, m.slot)
        }
    }

    @Test fun the_name_note_is_not_repeated_for_someone_already_paid() {
        val pin = MenuModel.read("Transfer to AMA MENSAH for GHS 0.1 with Reference: Payment. Fee is GHS 0.00, Tax amount is GHS 0.00. " +
            "Total Amount is GHS 0.10. Enter MM # for next\nCancel", hasInput = true, masked = false)
        val tx = TxContext(TxIntent.SEND_MONEY, "mtn", recipient = "0552093690", recipientName = "Prince Melanin", amount = "0.1")
        // What the owner heard on the first send (word for word from the log):
        assertEquals("MTN shows 0.10 cedis to Zelia Awini, no fee, total 0.10 cedis — that isn't the name you have saved, Prince Melanin, " +
            "so make sure it's the right person — if that's right, enter your PIN; if not, press Cancel. I never see or type your PIN." + com.tekyerema.money.PinPad.handOffTip(),
            (Boundary.decide(tx, pin) as Boundary.Verdict.HandOff).line)
        // After MTN has confirmed a send to that number: the registered name is still said, the note is not.
        val again = (Boundary.decide(tx, pin, paidBefore = true) as Boundary.Verdict.HandOff).line
        assertEquals("MTN shows 0.10 cedis to Zelia Awini, no fee, total 0.10 cedis — if that's right, enter your PIN; if not, press Cancel. " +
            "I never see or type your PIN." + com.tekyerema.money.PinPad.handOffTip(), again)
        assertFalse(again.contains("isn't the name"))
    }
}
