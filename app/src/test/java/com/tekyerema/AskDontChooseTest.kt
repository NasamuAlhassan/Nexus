package com.tekyerema

import com.tekyerema.policy.ActionSemantics
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The judge, 28 Oct 2026: when the person isn't clear the app asks — it never picks one itself. */
class AskDontChooseTest {
    private val none: (String) -> String? = { null }

    @Test fun aPersonTheUserNeverNamedIsAskedFor() {
        assertEquals("Who should I call? Say a name or a number.",
            ActionSemantics.unnamedRecipientQuestion("make_call", JSONObject().put("contact", "Kwame Mensah"), "call my friend", "", none))
        assertEquals("Who should I send it to? Say a name or a number.",
            ActionSemantics.unnamedRecipientQuestion("send_sms", JSONObject().put("number", "0244123456"), "text that I'm late", "", none))
    }

    @Test fun aNamedPersonAnAnswerTheScreenOrAReplyAreFine() {
        assertNull(ActionSemantics.unnamedRecipientQuestion("make_call", JSONObject().put("contact", "Kwame"), "call Kwame", "", none))
        assertNull(ActionSemantics.unnamedRecipientQuestion("send_sms", JSONObject().put("contact", "Ama Serwaa"),
            "text that I'm late\nAma", "", none))                                           // the user's answer named her
        assertNull(ActionSemantics.unnamedRecipientQuestion("make_call", JSONObject().put("contact", "Kofi Boateng"),
            "call him back", "", none))                                                     // the person just talked about
        assertNull(ActionSemantics.unnamedRecipientQuestion("send_sms", JSONObject().put("contact", "Yaw Darko"),
            "reply ok", "Chat with Yaw Darko", none))                                       // on the screen: the read-back says so
        assertNull(ActionSemantics.unnamedRecipientQuestion("reply_to_notification", JSONObject().put("sender", "Esi"), "say thanks", "", none))
        assertNull(ActionSemantics.unnamedRecipientQuestion("open_app", JSONObject().put("app", "WhatsApp"), "open whatsapp", "", none))
    }
}
