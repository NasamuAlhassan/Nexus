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
