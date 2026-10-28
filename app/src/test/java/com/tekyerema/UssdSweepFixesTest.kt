package com.tekyerema

import com.tekyerema.agent.journal.EffectKeys
import com.tekyerema.money.BankUssd
import com.tekyerema.money.ussd.Boundary
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.Navigator
import com.tekyerema.money.ussd.SemanticMatch
import com.tekyerema.money.ussd.TxContext
import com.tekyerema.money.ussd.TxIntent
import com.tekyerema.money.ussd.UssdNavigatorTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the USSD sweep on the A20e found on 2 October 2026 (audit/ussd-2026-10-02), on the screens MTN really showed. */
class UssdSweepFixesTest {

    // ---- MTN's PIN prompt, cut off by the page

    private val withReference = "Transfer to AMA MENSAH  for GHS 0.1 with Reference: transport. Fee is GHS 0.00, Tax amount is GHS 0.00. " +
        "Total Amount is GHS 0.10. Enter\n# for next"
    private val usual = "Transfer to AMA MENSAH  for GHS 0.1 with Reference: Payment. Fee is GHS 0.00, Tax amount is GHS 0.00. " +
        "Total Amount is GHS 0.10. Enter MM\n# for next"

    @Test fun `the PIN prompt is the PIN prompt wherever the page cuts it`() {
        assertEquals(MenuModel.Kind.PIN, MenuModel.read(usual, hasInput = true, masked = false).kind)
        assertEquals(MenuModel.Kind.PIN, MenuModel.read(withReference, hasInput = true, masked = false).kind)
        // …and the user hears the one hand-off line, not the whole screen read back with "confirm it there".
        val tx = TxContext(TxIntent.SEND_MONEY, "mtn", recipient = "0552093690", amount = "0.1", reference = "transport")
        val v = Boundary.decide(tx, MenuModel.read(withReference, true, false)) as Boundary.Verdict.HandOff
        assertTrue(v.line, v.line.startsWith("MTN shows 0.10 cedis to Zelia Awini, no fee, total 0.10 cedis — if that's right, enter your PIN"))
        assertFalse(v.line, v.line.contains("the screen says"))
    }

    @Test fun `a cut-off Enter is only a PIN prompt on a payment summary with an entry box`() {
        assertFalse(BankUssd.cutOffPinAsk("1) Transfer Money 2) MoMoPay 3) Airtime 7) # for next"))
        assertFalse(BankUssd.cutOffPinAsk("Enter mobile number"))
        assertFalse(BankUssd.cutOffPinAsk("Your balance is GHS 5.00. Fee is GHS 0.00. Thank you"))
        // A summary that asks to press 1 to confirm is a confirmation, not a PIN prompt.
        val confirm = "Buy GHS 1.00 airtime. Fee is GHS 0.00. 1) Confirm 2) Cancel"
        assertFalse(BankUssd.cutOffPinAsk(confirm))
        // No entry box (a last message): nothing to enter, so not a PIN prompt.
        assertEquals(MenuModel.Kind.CONFIRM, MenuModel.read(withReference, hasInput = false, masked = false).kind)
    }

    // ---- a send to another network: which network?

    private val otherNetworks = listOf("1" to "AT", "2" to "Telecel", "3" to "E-zwich", "4" to "G-Money", "5" to "Zeepay",
        "6" to "Ghana Pay", "0" to "Back")

    @Test fun `the recipient's network picks its own row`() {
        val telecel = SemanticMatch.best(TxIntent.SEND_MONEY, otherNetworks, crossNetwork = true, recipientNetwork = "telecel")!!
        assertEquals("2", telecel.option)
        assertTrue(telecel.confidence >= SemanticMatch.actBar() && telecel.margin >= SemanticMatch.marginBar())
        assertEquals("1", SemanticMatch.best(TxIntent.SEND_MONEY, otherNetworks, crossNetwork = true, recipientNetwork = "airteltigo")!!.option)
        // Older wording on another SIM's menu.
        val old = listOf("1" to "MTN", "2" to "Vodafone Cash", "3" to "AirtelTigo Money")
        assertEquals("2", SemanticMatch.best(TxIntent.SEND_MONEY, old, crossNetwork = true, recipientNetwork = "telecel")!!.option)
    }

    @Test fun `no network row is guessed`() {
        // The network isn't known, or isn't on the menu: nothing is picked for the user.
        assertNull(SemanticMatch.networkRow(otherNetworks, null))
        assertNull(SemanticMatch.networkRow(listOf("1" to "AT", "2" to "E-zwich", "3" to "G-Money", "4" to "MTN"), "telecel"))
        // One network's name in an ordinary menu is not a "which network?" menu.
        assertNull(SemanticMatch.networkRow(listOf("1" to "Transfer Money", "2" to "Telecel", "3" to "Airtime"), "telecel"))
        // Same network: the row is never used, whatever the menu.
        val same = SemanticMatch.best(TxIntent.SEND_MONEY, otherNetworks, crossNetwork = false, recipientNetwork = "mtn")
        assertTrue(same.toString(), same == null || same.confidence < SemanticMatch.actBar())
    }

    // ---- the network ends the walk before the PIN

    @Test fun `a refusal before the PIN says what did not happen`() {
        val send = TxContext(TxIntent.SEND_MONEY, "mtn", recipient = "0552093690", amount = "1")
        // As the Infinix showed it, 28 Oct 2026: the dialog's title first, then MTN's words — said plainly, without the title.
        val line = Navigator.endedBeforePin(send, "MTN GH Message\nNot enough funds to perform this transaction.")
        assertTrue(line, line.startsWith("MTN says there isn't enough money in your Mobile Money wallet for this."))
        assertTrue(line, !line.contains("GH Message"))
        assertTrue(line, line.endsWith("Nothing was sent."))
        val other = Navigator.endedBeforePin(send, "MTN GH Message\nThe number is not registered for MoMo.")
        assertTrue(other, other.startsWith("MTN says") && "registered" in other && !other.contains("GH Message"))
        val airtime = TxContext(TxIntent.BUY_AIRTIME, "mtn", amount = "1")
        assertTrue(Navigator.endedBeforePin(airtime, "Not enough funds").endsWith("Nothing was bought."))
    }

    @Test fun `a message that the request went on is not called a refusal`() {
        assertTrue(Navigator.IN_PROGRESS.containsMatchIn("You will receive a prompt to authorise the payment"))
        assertTrue(Navigator.IN_PROGRESS.containsMatchIn("Your request is being processed"))
        assertFalse(Navigator.IN_PROGRESS.containsMatchIn("Not enough funds to perform this transaction."))
        assertFalse(Navigator.IN_PROGRESS.containsMatchIn("The number you entered is not registered"))
    }

    // ---- "I may already have done this"

    @Test fun `the money walk is the money ledger's to remember, not the generic repeat guard's`() {
        val args = JSONObject().put("number", "0508797323").put("amount", "0.1")
        assertNull(EffectKeys.describe(UssdNavigatorTool.NAME, args, null, null, false, null))
        // The fixed driver it can fall back to is still a payment for the guard.
        assertEquals("0508797323", EffectKeys.describe("momo_send", args, null, null, false, null)!!.target)
    }
}
