package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.triggers.StandingGrant
import com.tekyerema.agent.triggers.StandingOrder
import com.tekyerema.agent.triggers.StandingOrderWords
import com.tekyerema.notifications.NotificationRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingOrdersTest {
    private fun rec(sender: String?, title: String?, text: String?, app: String = "Telegram", pkg: String = "org.telegram.messenger", ongoing: Boolean = false) =
        NotificationRecord("k", pkg, title, text, 0L, false, null, app, sender, ongoing = ongoing)

    private val lydia = StandingOrder("order:lydia:voice_note", "Lydia", "voice_note", null, "Saved Messages", "Telegram")

    @Test fun the_owners_sentence_becomes_an_order() {
        val o = StandingOrderWords.parse("When Lydia sends a voice note, send it to my saved messages on Telegram immediately.")
        assertNotNull(o)
        assertEquals("Lydia", o!!.who)
        assertEquals("voice_note", o.kind)
        assertEquals("Saved Messages", o.destination)
        assertEquals("Telegram", o.toApp)
        assertTrue(o.toSelf)
    }

    // A20e 6 Nov 2026: this sentence was classified, then deferred to the brain as "more than a command" — no order stored.
    @Test fun an_order_is_never_deferred_to_the_brain_for_its_length() {
        val said = "when Prince sends a voice note, send it to my saved messages on Telegram"
        val intent = FastIntentClassifier.classify(said)
        assertTrue(intent is FastIntent.SetStandingOrder)
        assertTrue("the size test alone would defer it", com.tekyerema.agent.BrainFirst.deferToBrain(said, brainReachable = true))
        assertTrue(FastIntent.ownsWholeSentence(intent!!))
        assertTrue(FastIntent.ownsWholeSentence(FastIntent.ListStandingOrders))
        assertFalse(FastIntent.ownsWholeSentence(FastIntent.Time))
    }

    @Test fun the_owners_sentence_as_he_typed_it() {
        val o = StandingOrderWords.parse("when lydia sends a voice note... Send it to my saved messages on telegram immediately")
        assertEquals("Lydia", o?.who)
        assertEquals("Saved Messages", o?.destination)
    }

    @Test fun other_wordings_parse() {
        listOf(
            "whenever Lydia sends me a voice message on WhatsApp, forward it to saved messages on telegram",
            "every time Lydia sends an audio, save it in my saved messages",
            "if Lydia drops a voice note then forward that to my saved messages right away",
            "when my Lydia sends a voice note send it to saved messages"
        ).forEach { assertEquals(it, "Lydia", StandingOrderWords.parse(it)?.who) }
    }

    @Test fun ordinary_requests_are_not_orders() {
        listOf(
            "send a voice note to Lydia",
            "when I say good morning, tell me the time",
            "when is Lydia's birthday",
            "forward the last voice note to my saved messages",
            "when someone sends a voice note, forward it to my saved messages",
            "if anyone sends a photo, send it to saved messages"
        ).forEach { assertNull(it, StandingOrderWords.parse(it)) }
    }

    @Test fun only_the_users_own_space_can_run_alone() {
        assertTrue(StandingOrderWords.parse("when Lydia sends a voice note, send it to my saved messages")!!.toSelf)
        assertFalse(StandingOrderWords.parse("when Lydia sends a voice note, send it to Kofi")!!.toSelf)
        assertFalse(StandingOrderWords.isOwnSpace("Saved Messages Kofi"))
        assertFalse(StandingOrderWords.isOwnSpace("me"))
    }

    @Test fun voice_notes_are_known_by_the_whole_line() {
        listOf("🎤 Voice message", "🎤 Voice message (0:12)", "Voice note", "Audio", "Lydia: 🎤 Voice message", "Lydia sent you a voice message")
            .forEach { assertEquals(it, "voice_note", StandingOrderWords.kindOfText(it)) }
        listOf("please send me a voice note", "I'll send a voice message later", "hello", "", null)
            .forEach { assertEquals("$it", "message", StandingOrderWords.kindOfText(it)) }
    }

    @Test fun only_the_named_sender_fires_it() {
        assertTrue(StandingOrderWords.matches(lydia, rec("Lydia Mensah", "Lydia Mensah", "🎤 Voice message (0:04)")))
        assertFalse("another person", StandingOrderWords.matches(lydia, rec("Kofi", "Kofi", "🎤 Voice message")))
        assertFalse("a typed message", StandingOrderWords.matches(lydia, rec("Lydia", "Lydia", "see you soon")))
        assertFalse("a longer name", StandingOrderWords.matches(lydia, rec("Lydiana", "Lydiana", "🎤 Voice message")))
        assertFalse("a group named after her", StandingOrderWords.matches(lydia, rec("Kofi", "Lydia's birthday", "🎤 Voice message")))
        assertFalse("her name inside the words", StandingOrderWords.matches(lydia, rec("Kofi", "Kofi", "Lydia: 🎤 Voice message")))
        assertFalse("ongoing", StandingOrderWords.matches(lydia, rec("Lydia", "Lydia", "🎤 Voice message", ongoing = true)))
        val onWhatsApp = lydia.copy(fromApp = "WhatsApp")
        assertFalse("the wrong app", StandingOrderWords.matches(onWhatsApp, rec("Lydia", "Lydia", "🎤 Voice message")))
        assertTrue(StandingOrderWords.matches(onWhatsApp, rec("Lydia", "Lydia", "🎤 Voice message", "WhatsApp", "com.whatsapp")))
    }

    @Test fun the_goal_holds_only_the_users_words() {
        val g = StandingOrderWords.goal(lydia, "Telegram", 1)
        assertTrue(g, g.contains("Forward the newest voice note from Lydia to Saved Messages on Telegram"))
        assertTrue(StandingOrderWords.goal(lydia, "Telegram", 2).contains("the 2 newest voice notes"))
    }

    private val grant = StandingGrant(lydia.id, "Saved Messages", setOf("org.telegram.messenger"))
    private val tg = "org.telegram.messenger"

    @Test fun the_grant_adds_only_the_screen_tools() {
        listOf("tap", "long_press", "scroll", "swipe", "node_action").forEach { assertTrue(it, grant.mayCall(it, JSONObject())) }
        listOf("momo_send", "mobile_money", "run_ussd", "make_call", "send_sms", "reply_to_notification", "share_text", "install_app", "emergency", "open_intent")
            .forEach { assertFalse(it, grant.mayCall(it, JSONObject())) }
    }

    @Test fun the_grant_types_only_the_destination_name() {
        assertTrue(grant.mayCall("type", JSONObject().put("text", "saved messages")))
        assertFalse(grant.mayCall("type", JSONObject().put("text", "hello Kofi")))
        assertFalse(grant.mayCall("type", JSONObject().put("text", "Saved Messages").put("submit", true)))
    }

    @Test fun the_grant_says_yes_only_to_its_own_taps() {
        assertTrue(grant.approves("tap", "Forward", tg, ""))
        assertTrue(grant.approves("tap", "Saved Messages", tg, ""))
        assertTrue(grant.approves("tap", "Send", tg, "Forward to Saved Messages"))
        assertFalse("send with another chat picked", grant.approves("tap", "Send", tg, "Forward to Kofi"))
        assertFalse("another person in the picker", grant.approves("tap", "Kofi", tg, "Saved Messages"))
        assertFalse("delete", grant.approves("tap", "Delete", tg, "Saved Messages"))
        assertFalse("another app", grant.approves("tap", "Send", "com.android.mms", "Saved Messages"))
        assertFalse("no app known", grant.approves("tap", "Forward", null, ""))
        assertFalse("not a tap", grant.approves("type", "Send", tg, "Saved Messages"))
        assertFalse("no label", grant.approves("tap", null, tg, "Saved Messages"))
    }

    @Test fun the_classifier_remembers_instead_of_sending() {
        val i = FastIntentClassifier.classify("When Lydia sends a voice note, send it to my saved messages on Telegram immediately.")
        assertTrue("$i", i is FastIntent.SetStandingOrder)
        assertTrue(FastIntentClassifier.classify("stop forwarding Lydia's voice notes") is FastIntent.StopStandingOrder)
        assertTrue(FastIntentClassifier.classify("what standing orders do I have") is FastIntent.ListStandingOrders)
        listOf("stop the music", "don't read my messages", "do not send Kofi a message", "cancel my message to Kofi", "stop sending messages")
            .forEach { assertFalse(it, FastIntentClassifier.classify(it) is FastIntent.StopStandingOrder) }
    }

    // A20e 6 Nov 2026: "what rules have I set" went to the brain.
    @Test fun the_user_asks_for_the_list_in_their_own_words() {
        listOf("what rules have I set", "What rules have I set?", "what orders have I given you", "what standing orders do I have",
            "what are my rules", "what are you watching for", "list my rules", "show me my rules", "tell me my standing orders",
            "what instructions did I give you", "read me all my rules", "list the standing orders")
            .forEach { assertTrue(it, FastIntentClassifier.classify(it) is FastIntent.ListStandingOrders) }
        listOf("what are the rules of football", "tell me the rules of chess", "show me my orders", "what are my orders on Jumia",
            "list my contacts", "read my messages")
            .forEach { assertFalse(it, FastIntentClassifier.classify(it) is FastIntent.ListStandingOrders) }
    }

    @Test fun orders_survive_storage() {
        assertEquals(listOf(lydia), StandingOrderWords.decode(StandingOrderWords.encode(listOf(lydia))))
        assertTrue(StandingOrderWords.decode("not json").isEmpty())
    }
}
