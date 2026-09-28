package com.tekyerema

import com.tekyerema.agent.MoneyApproval
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** J-02: one approved Mobile-Money transfer covers the steps of THAT dialog — never a new dial or send. */
class MoneyApprovalScopeTest {
    private fun approved() = MoneyApproval().apply { approve(JSONObject().put("_number", "0241234567").put("_amount", "5")) }

    @Test fun the_dialogs_own_buttons_stay_covered() {
        val a = approved()
        assertTrue(a.covers("tap", JSONObject(), "Send"))
        assertTrue(a.covers("tap", JSONObject().put("element_id", 4), "OK"))
        assertTrue(a.covers("node_action", JSONObject(), "Reply"))
        assertTrue(a.covers("type", JSONObject().put("text", "1")))
    }

    @Test fun a_new_dial_or_send_is_confirmed_again() {
        val a = approved()
        // A one-shot USSD that sends 500 to someone else used to skip the read-back (every non-`type` tool was covered).
        assertFalse(a.covers("run_ussd", JSONObject().put("code", "*170*1*1*0559876543*500#")))
        assertFalse(a.covers("make_call", JSONObject().put("number", "0559876543")))
        assertFalse(a.covers("send_sms", JSONObject().put("number", "0241234567").put("message", "hi")))
        assertFalse(a.covers("momo_send", JSONObject().put("number", "0241234567").put("amount", "5")))
        // Dialling on the keypad, or pressing Call, is a new dial too.
        for (key in listOf("5", "*", "#", "0559876543")) assertFalse(key, a.covers("tap", JSONObject(), key))
        assertFalse(a.covers("tap", JSONObject(), "Call"))
        assertFalse(a.covers("tap", JSONObject(), "Dial"))
        assertFalse("an unnamed control can't be shown to be part of the dialog", a.covers("tap", JSONObject().put("element_id", 9), null))
    }
}
