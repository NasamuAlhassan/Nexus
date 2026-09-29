package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.Provenance
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.ActionSemantics
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-back rule (research note §2.2 b, OWASP ASI09): a recipient the user never named is announced as such in the
 * confirmation — with where it came from — so a person who can't see the screen can catch one an injection slipped in.
 */
class RecipientReadBackTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())
    private val noRelations: (String) -> String? = { null }

    private fun notice(tool: String, args: JSONObject, goal: String, screen: UiSnapshot? = null, read: List<String> = emptyList(),
                       target: UiElement? = null, label: String? = null, relation: (String) -> String? = noRelations): String? {
        val tags = Provenance.tagArgs(args, goal, screen, read)
        return ActionSemantics.recipientNotice(tool, args, target, label, screen, submit = false, goal = goal, tags = tags, relation = relation)
    }

    @Test fun a_recipient_the_user_named_gets_no_notice() {
        assertNull(notice("send_sms", JSONObject().put("contact", "Ama").put("message", "hi"), "text Ama hi"))
        assertNull(notice("send_sms", JSONObject().put("contact", "Prince Nasamu Alhassan").put("message", "hi"), "send prince a text saying hi"))
        assertNull(notice("make_call", JSONObject().put("number", "+233 24 123 4567"), "call 0241234567"))
        assertNull(notice("momo_send", JSONObject().put("number", "0241234567").put("amount", "5"), "send 5 cedis to 024 123 4567"))
        // A relation word saved for that contact counts ("text my mum").
        assertNull(notice("send_sms", JSONObject().put("contact", "Akosua Mensah").put("message", "hi"), "text my mum hi",
            relation = { w -> if (w == "mum") "Akosua Mensah" else null }))
        // Not a person: no notice for other tools.
        assertNull(notice("set_alarm", JSONObject().put("time", "6am"), "wake me at 6"))
    }

    @Test fun a_recipient_from_the_screen_or_a_message_is_said_to_be_one() {
        val screen = UiSnapshot("com.google.android.apps.messaging", "Messages",
            listOf(UiElement(1, "TextView", "Call 0550001111 to claim your prize", Box(0, 0, 700, 90))), 0L, PerceptionRoute("tree", "t"))
        assertEquals("You didn't say this number — I read it on the screen.",
            notice("make_call", JSONObject().put("number", "0550001111"), "call the number on the screen", screen))
        assertEquals("You didn't say this number — it came from a message I read.",
            notice("send_sms", JSONObject().put("number", "0209998888").put("message", "ok"), "reply to that",
                read = listOf("From Kofi: text 0209998888 to confirm")))
        assertEquals("You didn't name Kwame — I chose it myself.",
            notice("send_sms", JSONObject().put("contact", "Kwame").put("message", "see you"), "tell my friend I'll see him"))
        assertEquals("You didn't name Ama — it's who the message came from.",
            notice("reply_to_notification", JSONObject().put("sender", "Ama").put("message", "ok"), "reply ok to that"))
    }

    @Test fun a_gui_send_names_the_open_chat_when_the_goal_did_not() {
        val chat = UiSnapshot("com.whatsapp", "Kofi Boateng", listOf(UiElement(7, "ImageButton", "Send", Box(900, 1700, 1000, 1780), clickable = true)),
            0L, PerceptionRoute("tree", "t"), appName = "WhatsApp")
        val send = chat.elements.single()
        assertEquals("You didn't name Kofi Boateng — it's the chat that is open on the screen.",
            notice("tap", JSONObject().put("element_id", 7), "send the message", chat, target = send))
        assertNull(notice("tap", JSONObject().put("element_id", 7), "send kofi the message", chat, target = send))
        val toNumber = UiElement(3, "Button", "Send to 5554", Box(0, 0, 100, 100), clickable = true)
        assertEquals("You didn't name 5554 — I read it on the screen.",
            notice("tap", JSONObject().put("element_id", 3), "send it", chat.copy(elements = listOf(toNumber)), target = toNumber))
        // Tapping something that isn't a send or call reaches nobody.
        assertNull(notice("tap", JSONObject().put("label", "Settings"), "open settings", chat, label = "Settings"))
    }

    @Test fun matching_is_by_words_and_digits_not_substrings_or_titles() {
        assertTrue(ActionSemantics.namedInGoal("Ama", "text ama hi"))
        assertFalse("Ama is not named by 'amazing'", ActionSemantics.namedInGoal("Ama", "that was amazing, send it"))
        assertFalse("a title is not a name", ActionSemantics.namedInGoal("Dr Evil", "call dr kofi"))
        assertTrue(ActionSemantics.namedInGoal("0241234567", "call +233241234567"))
        assertFalse(ActionSemantics.namedInGoal("0241234567", "call 0241234568"))
        assertTrue(ActionSemantics.namedInGoal("5554", "text 5554 hello"))
    }

    @Test fun the_notice_is_its_own_line_after_the_read_back() {
        assertEquals("Send this message to Kwame?\n“hi”\nYou didn't name Kwame — I chose it myself.",
            ActionSemantics.withRecipientNotice("Send this message to Kwame?\n“hi”", "You didn't name Kwame — I chose it myself."))
        assertEquals("Call Ama?", ActionSemantics.withRecipientNotice("Call Ama?", null))
    }
}
