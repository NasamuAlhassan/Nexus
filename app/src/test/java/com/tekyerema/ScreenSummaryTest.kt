package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.core.ScreenSummary
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.SpokenReply
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MASTER_STATUS #35 "Where am I?" (ScreenSummary + the WHERE_ON_SCREEN fast path) and #20 SpokenReply, 2 Nov 2026. */
class ScreenSummaryTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    private val box = Box(0, 0, 100, 50)
    private fun el(id: Int, role: String, label: String?, editable: Boolean = false, focused: Boolean = false,
                   hint: String? = null, collection: String? = null, password: Boolean = false) =
        UiElement(id, role, label, box, editable = editable, clickable = !editable, focused = focused, hint = hint,
            collection = collection, password = password)
    private fun snap(app: String?, title: String?, vararg els: UiElement, pkg: String? = "com.example", secure: Boolean = false) =
        UiSnapshot(pkg, title, els.toList(), 0L, appName = app, secureWindow = secure)

    // ---------------------------------------------------------------- ScreenSummary.sentence(snapshot)

    @Test fun says_the_app_and_the_title() {
        assertEquals("You're in Settings, Wi-Fi.", ScreenSummary.sentence(snap("Settings", "Wi-Fi", el(1, "Switch", "Wi-Fi"))))
        assertEquals("You're in Settings.", ScreenSummary.sentence(snap("Settings", "Settings", el(1, "Button", "Network"))))
        assertEquals("You're on the home screen.", ScreenSummary.sentence(snap("the home screen", null, el(1, "TextView", "Phone"))))
    }

    @Test fun never_says_a_package_id() {
        val s = ScreenSummary.sentence(snap(null, null, el(1, "Button", "OK"), pkg = "com.whatsapp"))
        assertEquals("You're in an app.", s)
        assertFalse(s.contains("com."))
    }

    @Test fun the_keyboard_box_comes_first_and_its_contents_are_never_said() {
        val s = ScreenSummary.sentence(snap("WhatsApp", "Ama Serwaa",
            el(1, "EditText", "I'm on my way home", editable = true, focused = true, hint = "Message"),
            el(2, "RecyclerView", null, collection = "list 30 rows")))
        assertEquals("You're in WhatsApp, Ama Serwaa. The keyboard is on the Message box.", s)
        assertFalse(s.contains("on my way"))
        assertEquals("You're in Chrome. The keyboard is on a text box.",
            ScreenSummary.sentence(snap("Chrome", null, el(1, "EditText", "x", editable = true, focused = true))))
    }

    @Test fun a_list_is_counted() {
        assertEquals("You're in Messages. There's a list of 12 items.",
            ScreenSummary.sentence(snap("Messages", null, el(1, "RecyclerView", null, collection = "list 12 rows"))))
    }

    @Test fun private_screens_say_so_and_nothing_else() {
        val s = ScreenSummary.sentence(snap("MTN MoMo", "Enter PIN", el(1, "EditText", null, editable = true, focused = true, password = true), secure = true))
        assertEquals("You're in MTN MoMo, on a private screen.", s)
    }

    @Test fun titles_with_long_numbers_or_codes_are_left_out() {
        assertEquals("You're in MoMo.", ScreenSummary.sentence(snap("MoMo", "Balance GHS 1,240.50", el(1, "Button", "OK"))))
        assertEquals("You're in Bank.", ScreenSummary.sentence(snap("Bank", "Account 0241234567", el(1, "Button", "OK"))))
    }

    @Test fun no_screen_is_honest() {
        assertEquals("I can't see the screen right now.", ScreenSummary.sentence(null))
    }

    // ---------------------------------------------------------------- ScreenSummary.sentence(before, after)

    @Test fun after_an_action_only_what_changed() {
        val home = snap("the home screen", null, el(1, "TextView", "Phone"), pkg = "launcher")
        val settings = snap("Settings", null, el(1, "Button", "Network"), pkg = "com.android.settings")
        val wifi = snap("Settings", "Wi-Fi", el(1, "Switch", "Wi-Fi"), pkg = "com.android.settings")
        assertEquals("Now in Settings.", ScreenSummary.sentence(home, settings))
        assertEquals("Now on Wi-Fi.", ScreenSummary.sentence(settings, wifi))
        assertNull(ScreenSummary.sentence(wifi, wifi))
        assertEquals("You're in Settings, Wi-Fi.", ScreenSummary.sentence(null, wifi))
        assertNull(ScreenSummary.sentence(wifi, null))
        val typing = snap("Settings", "Wi-Fi", el(1, "EditText", null, editable = true, focused = true, hint = "Search"), pkg = "com.android.settings")
        assertEquals("The keyboard is on the Search box.", ScreenSummary.sentence(wifi, typing))
        val dialog = snap("Settings", "Wi-Fi", el(1, "Switch", "Wi-Fi"), el(2, "AlertDialog", "Forget network?"), pkg = "com.android.settings")
        assertEquals("A pop-up opened.", ScreenSummary.sentence(wifi, dialog))
        val pin = snap("MTN MoMo", null, el(1, "EditText", null, editable = true, focused = true, password = true), pkg = "com.mtn", secure = true)
        assertEquals("Now on a private screen in MTN MoMo.", ScreenSummary.sentence(wifi, pin))
        assertNull(ScreenSummary.sentence(pin, pin))
    }

    // ---------------------------------------------------------------- the fast path

    @Test fun the_phone_phrasings_are_the_screen_and_where_am_i_stays_the_street() {
        val c = { s: String -> FastIntentClassifier.classify(s) }
        for (s in listOf("Which app am I in?", "what app is this", "What app is open?", "What screen is this?",
                "What screen am I on?", "Where am I on the phone?", "where am I on my screen", "Where am I in the app?",
                "what page am I on")) assertEquals(s, FastIntent.ScreenWhere, c(s))
        assertEquals(FastIntent.WhereAmI, c("Where am I?"))
        assertEquals(FastIntent.WhereAmI, c("What street am I on?"))
        assertNotEquals(FastIntent.ScreenWhere, c("Tell Ama which app I am in"))
    }

    // ---------------------------------------------------------------- SpokenReply

    @Test fun planner_prose_is_shaped_for_the_ear() {
        assertEquals("Who should I send it to?", SpokenReply.shape("{\"question\": \"Who should I send it to?\"}"))
        assertEquals("I sent the message to Ama.", SpokenReply.shape("```\nSure! I successfully sent the message to Ama\n```"))
        assertEquals("Open Settings. Turn on Wi-Fi.", SpokenReply.shape("Step 1: Open Settings\nStep 2: Turn on Wi-Fi"))
        assertEquals("Ama's number is that number.", SpokenReply.shape("Ama's number is [NUM1]."))
        assertEquals("Your balance is private. Say read it.", SpokenReply.shape("your balance is private; say read it"))
        assertEquals("Okay.", SpokenReply.shape("Okay."))           // an opener alone is the whole answer
        assertEquals("", SpokenReply.shape("  "))
    }

    @Test fun clean_text_passes_through() {
        for (s in listOf("Wi-Fi is on.", "Calling Ama Serwaa.", "Send fifty cedis to Kwame?", "Which Ama? Ama Mensah or Ama Boateng?",
                "Done. I set an alarm for 6:30 am."))
            assertEquals(s, SpokenReply.shape(s))
    }

    @Test fun long_sentences_are_split_at_a_comma_and_nothing_is_lost() {
        val long = "I found three messages from Kwame about the meeting tomorrow at the office in Osu, " +
            "and he says the time has moved from nine in the morning to eleven because the room is booked"
        val out = SpokenReply.shape(long)
        assertTrue(out, out.startsWith("I found three messages from Kwame about the meeting tomorrow at the office in Osu. He says"))
        for (w in long.replace(",", "").split(' ').filter { it != "and" }) assertTrue(w, out.contains(w, ignoreCase = true))
    }
}
