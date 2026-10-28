package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.money.AppPin
import com.tekyerema.money.PinPad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppPinTest {
    private fun key(id: Int, label: String, x: Int, y: Int) = UiElement(id, "Button", label, Box(x, y, x + 100, y + 100), clickable = true)

    private fun keypad(withDelete: Boolean = true, withOk: Boolean = true): UiSnapshot {
        val els = ArrayList<UiElement>()
        "123456789".forEachIndexed { i, c -> els.add(key(i + 1, c.toString(), 100 + (i % 3) * 120, 1200 + (i / 3) * 120)) }
        els.add(key(10, "0", 220, 1560))
        if (withDelete) els.add(key(11, "Delete", 340, 1560))
        if (withOk) els.add(key(12, "Confirm", 100, 1680))
        els.add(UiElement(13, "TextView", "Enter your MoMo PIN", Box(100, 900, 900, 960)))
        return UiSnapshot("com.mtngh.mymtn", "PIN", els, 0L)
    }

    @Test fun theAppsOwnKeypadIsFound() {
        val k = AppPin.find(keypad())
        assertNotNull(k)
        assertEquals(10, k!!.digits.size)
        assertEquals(1, k.digits['1']); assertEquals(10, k.digits['0'])
        assertEquals(11, k.delete); assertEquals(12, k.confirm)
        assertEquals(1200, k.area.top)                                 // the touch pad covers the keypad, not the screen
    }

    @Test fun noKeypadNoPad() {
        val partial = UiSnapshot("com.mtngh.mymtn", "x", (1..5).map { key(it, it.toString(), 0, it * 120) }, 0L)
        assertNull(AppPin.find(partial))
        assertNull(AppPin.find(null))
    }

    @Test fun aDrawnDigitGoesStraightIn() {
        val c = PinPad.Counter()
        c.down(); c.down()                                             // counting a 2 …
        assertEquals(PinPad.Counter.Event.Digit(1), c.put('7'))      // … a drawn 7 replaces it
        assertEquals(0, c.count)
        assertEquals(1, c.size)
    }

    @Test fun aPinBoxWithThePhonesKeyboardIsFoundToo() {
        val els = listOf(UiElement(1, "TextView", "Enter your PIN to confirm", Box(100, 600, 900, 660)),
            UiElement(2, "EditText", null, Box(100, 700, 900, 800), editable = true, password = true),
            key(3, "Confirm", 100, 900))
        val k = AppPin.find(UiSnapshot("com.mtngh.mymtn", "PIN", els, 0L))
        assertNotNull(k)
        assertEquals(2, k!!.field); assertEquals(3, k.confirm)
        assertEquals(true, k.digits.isEmpty())
    }

    @Test fun theBoxTheKeyboardIsOnTakesThePin() {
        val els = listOf(UiElement(1, "EditText", "0552093690", Box(0, 200, 1000, 300), editable = true, hint = "Phone number"),
            UiElement(2, "EditText", "5", Box(0, 400, 1000, 500), editable = true, hint = "Amount"),
            UiElement(3, "EditText", null, Box(0, 1200, 1000, 1300), editable = true, focused = true),
            UiElement(4, "TextView", "Enter your MoMo PIN", Box(0, 1100, 1000, 1150)))
        assertEquals(3, AppPin.find(UiSnapshot("com.mtngh.mymtn", "PIN", els, 0L))!!.field)
    }

    @Test fun aFocusedFormBoxIsNeverThePinBox() {
        val els = listOf(UiElement(1, "EditText", "laptop", Box(0, 200, 1000, 300), editable = true, hint = "Reference", focused = true),
            UiElement(2, "EditText", "5", Box(0, 400, 1000, 500), editable = true, hint = "Amount"))
        assertNull(AppPin.find(UiSnapshot("com.mtngh.mymtn", "PIN", els, 0L)))
    }

    @Test fun oneDigitPerBoxGoesLeftToRight() {
        val els = (0 until 4).map { i -> UiElement(10 + (3 - i), "EditText", null, Box(100 + i * 200, 900, 250 + i * 200, 1050), editable = true) } +
            UiElement(1, "EditText", "5", Box(0, 400, 1000, 500), editable = true, hint = "Amount")
        assertEquals(listOf(13, 12, 11, 10), AppPin.find(UiSnapshot("com.mtngh.mymtn", "PIN", els, 0L))!!.cells)
    }

    /** MyMTN's PIN sheet as logged on the Infinix, 28 Oct 2026 (structure only). */
    private fun mymtnPinSheet() = UiSnapshot("com.mtngh.mymtn", "MoMo", listOf(
        UiElement(0, "Button", "Bottom sheet backdrop", Box(0, 0, 1080, 2358), clickable = true),
        UiElement(4, "View", "Enter your MoMo pin", Box(63, 905, 1017, 995)),
        UiElement(5, "TextView", "Enter Pin", Box(63, 1100, 1017, 1148)),
        UiElement(6, "ViewGroup", null, Box(63, 1169, 194, 1290), clickable = true, viewId = "otp-input"),
        UiElement(7, "ViewGroup", null, Box(126, 1190, 131, 1269), clickable = true, viewId = "otp-input-stick"),
        UiElement(8, "ViewGroup", null, Box(242, 1169, 373, 1290), clickable = true, viewId = "otp-input"),
        UiElement(9, "ViewGroup", null, Box(421, 1169, 552, 1290), clickable = true, viewId = "otp-input"),
        UiElement(10, "ViewGroup", null, Box(600, 1169, 731, 1290), clickable = true, viewId = "otp-input"),
        UiElement(13, "Button", "Show MoMo balance", Box(686, 242, 749, 312), clickable = true),
        UiElement(16, "Button", "Send MoMo", Box(63, 777, 286, 983), clickable = true)), 0L)

    @Test fun myMtnsPinCellsAreAPinScreenAndThePadFillsTheirHiddenBox() {
        assertEquals(listOf(6, 8, 9, 10), AppPin.pinCellRow(mymtnPinSheet().elements))
        assertEquals(true, com.tekyerema.policy.TargetSemantics.isCredentialScreen(mymtnPinSheet()))
        val k = AppPin.find(mymtnPinSheet())
        assertNotNull(k)
        assertEquals(6, k!!.hiddenBoxCell)
        assertNull(k.field)
    }

    @Test fun theWatchArmsOnAnyAppsPinScreenButNotOnACalculator() {
        assertNotNull(com.tekyerema.money.PinWatch.appPinScreen(mymtnPinSheet()))
        assertNotNull(com.tekyerema.money.PinWatch.appPinScreen(keypad()))                      // "Enter your MoMo PIN" + 0–9
        val calc = UiSnapshot("com.android.calculator2", "Calculator", keypad().elements.filter { it.id != 13 }, 0L)
        assertNull(com.tekyerema.money.PinWatch.appPinScreen(calc))
        val form = UiSnapshot("com.mtngh.mymtn", "Send", listOf(
            UiElement(1, "EditText", "5", Box(0, 400, 1000, 500), editable = true, hint = "Amount", focused = true)), 0L)
        assertNull(com.tekyerema.money.PinWatch.appPinScreen(form))
    }

    @Test fun theLayoutLogNeverHoldsWhatABoxHolds() {
        val els = listOf(UiElement(1, "TextView", "Enter your PIN", Box(0, 0, 10, 10)),
            UiElement(2, "EditText", "4821", Box(0, 20, 10, 30), editable = true, hint = "PIN"),
            UiElement(3, "TextView", "• • 8 •", Box(0, 40, 10, 50)),
            UiElement(4, "EditText", "laptop", Box(0, 60, 10, 70), editable = true))
        val lines = AppPin.layout(UiSnapshot("com.mtngh.mymtn", "PIN", els, 0L))
        assertEquals(4, lines.size)
        assertEquals(true, lines.none { l -> l.substringBefore(' ').let { _ -> l.replace(Regex("#\\d+|\\d+,\\d+,\\d+,\\d+|<\\d+ chars>"), "") }.any { it.isDigit() } })
        assertEquals(true, lines.none { "laptop" in it })
        assertEquals(true, "'Enter your PIN'" in lines[0] && "hint='PIN'" in lines[1] && "EDIT" in lines[1])
    }

    @Test fun theReferenceIsTheUsersWordsOnly() {
        val screen = org.json.JSONObject().put("app", "com.mtngh.mymtn").put("elements", org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 15).put("label", "Reference")).put(org.json.JSONObject().put("id", 13).put("label", "Amount")))
        fun q(text: String, said: String, id: Int = 15) = com.tekyerema.money.MtnApp.referenceQuestion("type",
            org.json.JSONObject().put("element_id", id).put("text", text), screen, said)
        assertEquals("What should the reference say? You can say none.", q("Transfer", "send 5 cedis to 0552093690"))
        assertNull(q("laptop", "send 5 cedis to 0552093690\nlaptop"))       // the user's answer
        assertNull(q("Transfer", "send 5 cedis to 0552093690\nnone"))        // they said none: the planner's blank/default is not theirs to argue
        assertNull(q("5", "send 5 cedis", id = 13))                           // not a reference field
    }
}
