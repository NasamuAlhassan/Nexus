package com.tekyerema

import com.tekyerema.notifications.IndexedMessage
import com.tekyerema.notifications.NotificationDigest
import com.tekyerema.notifications.NotificationDigest.MissedCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E-15 / H-09: "what did I miss?" — one spoken catch-up grouped by app and person, built on the phone. */
class NotificationDigestTest {
    private fun m(t: Long, pkg: String, app: String?, sender: String?, title: String?, text: String?) =
        IndexedMessage(t, pkg, app, sender, title, text)

    @Test fun groups_by_app_then_person_busiest_first_and_quotes_the_newest() {
        val msgs = listOf(
            m(10, "com.whatsapp", "WhatsApp", "Ama", "Ama", "Are you coming?"),
            m(9, "com.whatsapp", "WhatsApp", "Ama", "Ama", "Hello"),
            m(8, "com.whatsapp", "WhatsApp", "Kojo", "Family", "Food is ready"),
            m(7, "org.telegram.messenger", "Telegram", "Kofi", "Kofi", "ok"))
        // Plain form: on the system voice the group breaks and the breath before the quote are real pauses (SpokenText).
        assertEquals("Since you last asked: 4 notifications. WhatsApp: 2 from Ama, 1 in Family. Telegram: 1 from Kofi. " +
            "The newest is from Ama on WhatsApp. Are you coming?",
            com.tekyerema.voice.SpokenText.plain(NotificationDigest.build(msgs, emptyList(), "since you last asked")))
    }

    @Test fun missed_calls_come_first_and_name_each_caller_once() {
        val calls = listOf(MissedCall(5, "Ama"), MissedCall(4, "Kofi"), MissedCall(3, "Ama"))
        assertEquals("Today: 3 missed calls, from Ama and Kofi.", NotificationDigest.build(emptyList(), calls, "today"))
    }

    @Test fun nothing_new_is_said_plainly() {
        assertEquals("Nothing new since you last asked, at 9:40 AM.",
            NotificationDigest.build(emptyList(), emptyList(), "since you last asked, at 9:40 AM"))
    }

    @Test fun apps_beyond_the_cap_are_counted_not_listed() {
        val msgs = (1..4).map { i -> m(i.toLong(), "p$i", "App$i", "P$i", "P$i", "hi") }
        val s = NotificationDigest.build(msgs, emptyList(), "in the last 12 hours", maxApps = 3)
        assertTrue(s, s.contains("And 1 more from other apps"))
        assertFalse(s, s.contains("App1:"))   // the oldest, least busy app is the one left out
    }

    @Test fun summaries_system_chatter_and_our_own_notifications_are_noise() {
        assertTrue(NotificationDigest.isNoise(m(1, "com.whatsapp", "WhatsApp", null, "WhatsApp", "5 messages from 3 chats"), "com.tekyerema"))
        assertTrue(NotificationDigest.isNoise(m(1, "com.tekyerema", "Tɛkyerɛma", null, "Listening", "x"), "com.tekyerema"))
        assertTrue(NotificationDigest.isNoise(m(1, "com.android.systemui", null, null, "Charging", "USB"), "com.tekyerema"))
        assertFalse(NotificationDigest.isNoise(m(1, "com.whatsapp", "WhatsApp", "Ama", "Ama", "Hello"), "com.tekyerema"))
    }

    @Test fun the_phone_apps_missed_call_notifications_become_calls_not_messages() {
        val dialer = m(3, "com.samsung.android.dialer", "Phone", null, "Missed call", "Ama")
        assertEquals(listOf(MissedCall(3, "Ama")), NotificationDigest.callsFromNotifications(listOf(dialer)))
        // …and are never counted again as a "Phone" notification.
        assertEquals("Today: 1 missed call, from Ama.",
            NotificationDigest.build(listOf(dialer), NotificationDigest.callsFromNotifications(listOf(dialer)), "today"))
    }

    /** A20e 2026-09-30: "read my messages" read "33 notifications", YouTube first, for ~50 s. Messages = chats and texts. */
    @Test fun read_my_messages_is_chats_and_texts_only() {
        val all = listOf(
            m(10, "com.google.android.youtube", "YouTube", null, "New video", "Kwadwo uploaded"),
            m(9, "com.whatsapp", "WhatsApp", "Ama", "Ama", "Are you coming?"),
            m(8, "com.samsung.android.messaging", "Messages", "MTN", "MTN", "Your balance is low"),
            m(7, "com.android.vending", "Play Store", null, null, "3 updates available"))
        val chats = all.filter { NotificationDigest.isMessage(it) }
        assertEquals(listOf("com.whatsapp", "com.samsung.android.messaging"), chats.map { it.pkg })
        val s = NotificationDigest.build(chats, emptyList(), "in the last 12 hours", noun = "message")
        assertTrue(s, s.startsWith("In the last 12 hours: 2 messages. "))
        assertFalse(s, s.contains("YouTube") || s.contains("Play Store") || s.contains("notification"))
        assertEquals("No new messages today.", NotificationDigest.build(emptyList(), emptyList(), "today", noun = "message"))
        assertTrue(NotificationDigest.isMessage(m(1, "com.example.chat", "Chat", "A", "A", "x"), extra = setOf("com.example.chat")))
    }

    @Test fun a_non_chat_notification_is_counted_without_a_person() {
        val s = NotificationDigest.build(listOf(m(1, "com.android.vending", "Play Store", null, null, "3 updates available")),
            emptyList(), "today")
        assertTrue(s, s.startsWith("Today: 1 notification. Play Store: 1 notification."))
    }

    // A20e, 2 Nov 2026: the digest summarised a look-alike MoMo link with no warning. Made-up messages.
    @Test fun a_scam_link_in_the_newest_message_is_warned_before_its_words() {
        val msgs = listOf(m(10, "com.google.android.apps.messaging", "Messages", "0241112222", "0241112222",
            "Your MoMo wallet is suspended. Verify now at mtn-gh-reward.xyz"))
        val s = com.tekyerema.voice.SpokenText.plain(NotificationDigest.build(msgs, emptyList(), "today"))
        val warn = s.indexOf("Careful: this may be a scam.")
        assertTrue(s, warn > 0)
        assertTrue(s, warn < s.indexOf("Your MoMo wallet"))
        assertFalse("the reason never repeats the link", s.substring(warn, s.indexOf("Your MoMo wallet")).contains("mtn-gh-reward"))
    }

    @Test fun a_scam_link_in_a_prioritised_line_is_warned_and_ordinary_lines_are_not() {
        val msgs = listOf(
            m(10, "com.google.android.apps.messaging", "Messages", "0241112222", "0241112222",
                "Pay GHS 5 now to unlock your MoMo at mtn-gh-reward.xyz"),
            m(9, "com.whatsapp", "WhatsApp", "Ama", "Ama", "I will pay you back 50 cedis tomorrow"))
        val s = com.tekyerema.voice.SpokenText.plain(NotificationDigest.buildPrioritised(msgs, emptyList(), "today", maxQuoted = 2))
        assertEquals(s, 1, Regex("Careful: this may be a scam").findAll(s).count())
        assertTrue(s, s.indexOf("Careful") < s.indexOf("Pay GHS") || s.indexOf("Careful") < s.indexOf("Pay"))
    }

    @Test fun a_sender_is_a_contact_by_whole_name_or_first_name_only() {
        val names = setOf("ama mensah", "kofi", "mtn mobile money").map { NotificationDigest.normName(it) }.toSet()
        assertTrue(NotificationDigest.isContactName("Ama Mensah", names))
        assertTrue(NotificationDigest.isContactName("Ama", names))
        assertTrue(NotificationDigest.isContactName("KOFI", names))
        assertFalse(NotificationDigest.isContactName("DSATuz", names))
        assertFalse(NotificationDigest.isContactName("Amah", names))          // no fuzzy matching
        assertFalse(NotificationDigest.isContactName("", names))
    }
}
