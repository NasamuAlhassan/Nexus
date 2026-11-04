package com.tekyerema

import com.tekyerema.agent.SeenTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the request names is already on the screen → the brain is told, so it taps instead of searching (owner, 4 Nov 2026). */
class SeenTargetTest {
    private val chat: (String?) -> Boolean = { it == "org.telegram.messenger" || it == "com.whatsapp" }

    private fun screen(app: String, title: String? = null, vararg labels: String) = JSONObject().put("app", app).apply {
        if (title != null) put("screen", title)
        put("elements", JSONArray().apply {
            labels.forEachIndexed { i, l -> put(JSONObject().put("id", i + 1).put("role", "ViewGroup").put("label", l).put("actions", JSONArray().put("tap"))) }
        })
    }
    private fun hint(goal: String, s: JSONObject) = SeenTarget.hint(goal, emptyList(), s, chat)

    // ---- people, in chat apps
    @Test fun the_named_person_in_the_chat_list_is_pointed_out() {
        val s = screen("org.telegram.messenger", null, "Search Chats", "Ama Owusu, see you, 09:10", "Richmond Apenensu, Ok see you, 10:42")
        val h = hint("message Richmond on telegram that I'm late", s)
        assertNotNull(h); assertTrue(h!!, h.contains("[3] \"Richmond Apenensu, Ok see you") && h.contains("instead of searching"))
    }

    @Test fun already_inside_the_persons_chat_says_stay_here() {
        // A20e 3 Nov 07:41: Telegram opened straight into Richmond's chat; the brain pressed Go back and searched
        val h = hint("send a message to Richmond", screen("org.telegram.messenger", "Richmond Apenensu", "Go back", "Message", "Send"))
        assertNotNull(h); assertTrue(h!!, h.contains("already \"Richmond Apenensu\"") && h.contains("don't go back"))
    }

    @Test fun a_channel_is_never_offered_even_when_its_name_was_said() {
        val s = screen("org.telegram.messenger", null, "Reach Money with Moin, @Moin_Prediction, 36 subscribers")
        assertNull(hint("send my class schedule to reach money on telegram", s))
    }

    @Test fun two_people_that_fit_mean_ask_which() {
        val s = screen("com.whatsapp", null, "Kofi Mensah, ok", "Kofi Boateng, call me", "Ama Owusu")
        val h = hint("whatsapp Kofi that I'm coming", s)
        assertNotNull(h); assertTrue(h!!, h.contains("ask_user") && h.contains("[1]") && h.contains("[2]") && !h.contains("Ama"))
    }

    // ---- anything, in any app
    @Test fun a_song_on_screen_is_pointed_out() {
        val s = screen("com.google.android.apps.youtube.music", null, "Search", "Adonai • Sarkodie • 3:45", "Kpalongo • Mr Eazi")
        val h = hint("play Adonai by Sarkodie", s)
        assertNotNull(h); assertTrue(h!!, h.contains("[2] \"Adonai • Sarkodie") && !h.contains("Kpalongo"))
    }

    @Test fun a_setting_on_screen_is_pointed_out_and_its_own_page_says_stay() {
        val list = screen("com.android.settings", "Connections", "Search", "Wi-Fi", "Bluetooth", "Flight mode")
        val h = hint("turn on bluetooth", list)
        assertNotNull(h); assertTrue(h!!, h.contains("[3] \"Bluetooth\""))
        val page = hint("turn on bluetooth", screen("com.android.settings", "Bluetooth", "Navigate up", "On"))
        assertNotNull(page); assertTrue(page!!, page.contains("this screen is already \"Bluetooth\""))
    }

    @Test fun nothing_is_said_when_it_does_not_apply() {
        val list = screen("com.whatsapp", null, "Chats", "Ama Owusu", "Kofi Mensah")
        assertNull("a person nobody named", hint("send a message to Richmond", list))
        assertNull("the app's own controls", hint("send a message on chats", list))
        assertNull("the app's own name", hint("open telegram", screen("org.telegram.messenger", null, "Telegram", "Search Chats")))
        assertNull("a person outside a people app is not matched by sound",
            hint("message Richmond", screen("com.android.chrome", null, "Richard Mensah")))
        assertNull("words that match everywhere point at nothing",
            hint("play gospel", screen("com.google.android.apps.youtube.music", null, "Gospel • Joe Mettle", "Gospel • Diana Hamilton", "Gospel • Sonnie Badu", "Gospel • MOG")))
    }

    @Test fun the_row_name_stops_at_the_details() {
        assertEquals("Richmond Apenensu", SeenTarget.nameOf("Richmond Apenensu, Ok see you, 10:42"))
        assertEquals("Ama", SeenTarget.nameOf("Ama\n2 unread"))
        assertEquals("Adonai", SeenTarget.nameOf("Adonai • Sarkodie • 3:45"))
    }
}
