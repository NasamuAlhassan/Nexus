package com.tekyerema

import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.SemanticMatch
import com.tekyerema.money.ussd.SlotKind
import com.tekyerema.money.ussd.TxContext
import com.tekyerema.money.ussd.TxIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The intent-driven USSD navigator's pure core, tested on the REAL screens the A20e produced (2026-09-30). */
class UssdNavigatorTest {

    // ---- menu understanding

    @Test fun `live screens classify as what they are`() {
        val menu = MenuModel.read("Unlock more deals, try our new MoMo App 1) Transfer Money 2) MoMoPay&Pay Bill 3) Airtime&Bundles 4) Allow Cash Out 5) Financial", hasInput = true, masked = false)
        assertEquals(MenuModel.Kind.MENU, menu.kind)
        assertEquals(5, menu.rows.size)

        val input = MenuModel.read("Enter Phone Number", hasInput = true, masked = false)
        assertEquals(MenuModel.Kind.INPUT, input.kind)
        assertEquals(SlotKind.PHONE_NUMBER, input.slot)

        assertEquals(SlotKind.CONFIRM_NUMBER, MenuModel.read("Confirm Phone Number", true, false).slot)
        assertEquals(SlotKind.AMOUNT, MenuModel.read("Enter Amount:", true, false).slot)
        assertEquals(SlotKind.REFERENCE, MenuModel.read("Enter Reference", true, false).slot)

        val pin = MenuModel.read("Transfer GHS 0.10 to AMA MENSAH. Fee 0.00. Enter MM PIN to confirm", true, masked = true)
        assertEquals(MenuModel.Kind.PIN, pin.kind)

        val err = MenuModel.read("Invalid option selected. Try again later", false, false)
        assertEquals(MenuModel.Kind.ERROR, err.kind)

        val final = MenuModel.read("Y'ello Your data bundle balance: GHc3 Bundle: 63MB", false, false)
        assertEquals(MenuModel.Kind.FINAL, final.kind)
    }

    @Test fun `the network's shown name is read, never invented`() {
        assertEquals("AMA MENSAH", MenuModel.nameOf("Transfer GHS 0.10 to AMA MENSAH. Fee 0.00. Enter MM PIN"))
        assertEquals("Prince Nasamu", MenuModel.nameOf("Name: Prince Nasamu"))
        assertNull(MenuModel.nameOf("Enter Phone Number"))
        assertNull(MenuModel.nameOf("Send to 0244123456"))          // digits are not a name
        assertEquals("0.10", MenuModel.amountOf("Transfer GHS 0.10 to AMA MENSAH"))
    }

    // ---- semantic matching (levels 1–2; the embedding level is exercised with a fake embedder)

    private val mtnMain = listOf("1" to "Transfer Money", "2" to "MoMoPay&Pay Bill", "3" to "Airtime&Bundles",
        "4" to "Allow Cash Out", "5" to "Financial")

    @Test fun `send money finds Transfer Money and never Cash Out`() {
        val v = SemanticMatch.best(TxIntent.SEND_MONEY, mtnMain)!!
        assertEquals("1", v.option)
        assertTrue(v.toString(), v.confidence >= SemanticMatch.actBar() && v.margin >= SemanticMatch.marginBar())
        // "Allow Cash Out" scores ZERO for a money-moving intent whatever any similarity says.
        assertEquals(0.0, SemanticMatch.score(TxIntent.SEND_MONEY, "allow cash out"), 1e-9)
    }

    @Test fun `different wording on another network still matches`() {
        val telecel = listOf("1" to "Send Money", "2" to "Buy Airtime or Data", "3" to "Make Payments")
        assertEquals("1", SemanticMatch.best(TxIntent.SEND_MONEY, telecel)!!.option)
        assertEquals("2", SemanticMatch.best(TxIntent.BUY_AIRTIME, telecel)!!.option)
        val generic = listOf("1" to "Transfer Funds", "2" to "Recharge", "3" to "Account")
        assertEquals("1", SemanticMatch.best(TxIntent.SEND_MONEY, generic)!!.option)
        assertEquals("2", SemanticMatch.best(TxIntent.BUY_AIRTIME, generic)!!.option)
    }

    @Test fun `a murky menu does not clear the act bar`() {
        val murky = listOf("1" to "Payments", "2" to "Transfers", "3" to "Financial Services")
        val v = SemanticMatch.best(TxIntent.SEND_MONEY, murky)
        // "Transfers" keyword-matches, but nothing reaches exact confidence with a clean margin over "Payments"…
        assertTrue(v == null || v.confidence < 0.95)
        // …and navigation rows never win at all.
        assertNull(SemanticMatch.best(TxIntent.SEND_MONEY, listOf("0" to "Back", "99" to "More")))
    }

    @Test fun `the embedding level rescues wording the vocab never saw`() {
        // A fake embedder: "wire funds abroad?" no — plain deterministic vectors where row 1 is near the intent anchors.
        fun v(vararg x: Float) = x.toList().toFloatArray()
        val embed: (String) -> FloatArray = { s ->
            when {
                s.contains("wire") || s.contains("send money") || s.contains("transfer") -> v(1f, 0f)
                else -> v(0f, 1f)
            }
        }
        val rows = listOf("1" to "Wire funds", "2" to "Games and quizzes")
        val best = SemanticMatch.best(TxIntent.SEND_MONEY, rows, embed)!!
        assertEquals("1", best.option)
        assertTrue(best.confidence > 0.5)
    }

    // ---- context answering

    @Test fun `slots answer from the confirmed request, once`() {
        val tx = TxContext(TxIntent.SEND_MONEY, "mtn", recipient = "0552093690", amount = "50")
        assertEquals("0552093690", tx.slot(SlotKind.PHONE_NUMBER))
        assertEquals("0552093690", tx.slot(SlotKind.CONFIRM_NUMBER))   // MTN's double-entry costs nothing extra
        assertEquals("50", tx.slot(SlotKind.AMOUNT))
        assertEquals(null, tx.slot(SlotKind.REFERENCE))   // never invented: asked at the reference screen (owner, 5 Oct 2026)
        assertNull(tx.slot(SlotKind.PRODUCT))                          // a bundle we weren't told → the user is asked
    }
}
