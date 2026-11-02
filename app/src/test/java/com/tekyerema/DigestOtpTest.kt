package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.notifications.Importance
import com.tekyerema.notifications.IndexedMessage
import com.tekyerema.notifications.NotificationDigest
import com.tekyerema.notifications.OtpRelay
import com.tekyerema.voice.SpokenText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MASTER_STATUS #2 importance in "what did I miss" and #37 one-time code to the waiting box (2 Nov 2026). */
class DigestOtpTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    private fun m(t: Long, pkg: String, app: String, sender: String?, title: String?, text: String) = IndexedMessage(t, pkg, app, sender, title, text)
    private val WA = "com.whatsapp"
    private val SMS = "com.samsung.android.messaging"

    /** Twenty notifications: four that matter, sixteen that don't. */
    private val fixture = buildList {
        add(m(100, WA, "WhatsApp", "Ama", "Ama", "Are you coming at 4 today?"))
        add(m(90, SMS, "Messages", "MobileMoney", "MobileMoney", "You have received GHS 50.00 from KOFI MENSAH. Current Balance GHS 72.40."))
        add(m(80, WA, "WhatsApp", "Mum", "Mum", "Call me when you wake up"))
        add(m(70, SMS, "Messages", "Clinic", "Clinic", "Reminder: your appointment is on Thursday."))
        for (i in 1..12) add(m(60L - i, WA, "WhatsApp", "Kwesi", "Church Youth", "Good morning family, blessings $i"))
        add(m(40, "com.google.android.youtube", "YouTube", null, "New video", "Watch the latest upload"))
        add(m(39, SMS, "Messages", "MobileMoney", "MobileMoney", "Y'ello! Get 20% bonus on your next Mashup. Dial *170#."))
        add(m(38, "com.android.vending", "Play Store", null, "Updates", "3 apps updated"))
        add(m(37, WA, "WhatsApp", "Adwoa", "Old Students", "Who has the photos?"))
    }

    @Test fun importance_rules() {
        val contact = { n: String -> n == "Ama" }
        assertEquals(Importance.Why.WHEN, Importance.of(fixture[0], contact))       // a time today beats the contact
        assertEquals(Importance.Why.MONEY, Importance.of(fixture[1], contact))
        assertEquals(Importance.Why.RELATION, Importance.of(fixture[2], contact))
        assertEquals(Importance.Why.WHEN, Importance.of(fixture[3], contact))
        assertNull(Importance.of(fixture[4], contact))                              // a group chat's chatter
        assertNull(Importance.of(fixture[17], contact))                             // MoMo's own promotion: no money moved
        assertNull(Importance.of(fixture[19], contact))
        assertEquals(Importance.Why.PERSON, Importance.of(m(1, WA, "WhatsApp", "Ama", "Ama", "ok"), contact))
        assertNull(Importance.of(m(1, WA, "WhatsApp", "Ama", "Family", "ok"), contact))   // Ama in a group: not for who wrote it
    }

    @Test fun the_digest_opens_with_what_matters_and_counts_the_rest() {
        val s = SpokenText.plain(NotificationDigest.buildPrioritised(fixture, emptyList(), "in the last 12 hours", { it == "Ama" }))
        assertTrue(s, s.startsWith("In the last 12 hours: MobileMoney: You have received GHS 50.00 from"))
        assertTrue(s, s.contains("Ama: Are you coming at 4 today?"))
        assertFalse(s, s.contains("?."))
        assertTrue(s, s.contains("And 18 others, most in WhatsApp."))
        assertFalse(s, s.contains("blessings"))
        assertTrue("about 25 words: ${s.split(' ').size}", s.split(Regex("\\s+")).size <= 30)
    }

    @Test fun missed_calls_come_first_and_nothing_new_is_said_plainly() {
        val s = SpokenText.plain(NotificationDigest.buildPrioritised(fixture.take(1), listOf(NotificationDigest.MissedCall(5, "Kofi")), "today", { false }))
        assertTrue(s, s.startsWith("Today: 1 missed call, from Kofi"))
        assertEquals("Nothing new today.", NotificationDigest.buildPrioritised(emptyList(), emptyList(), "today"))
    }

    // ---------------------------------------------------------------- #37

    @Test fun a_code_is_found_only_where_it_is_one() {
        assertEquals("123456", OtpRelay.extract("Your Google verification code is 123456. Don't share it."))
        assertEquals("482913", OtpRelay.extract("G-482913 is your Google verification code."))
        assertEquals("123456", OtpRelay.extract("Your Uber code: 123 456. Never share your PIN or this code."))   // advice about a PIN is fine
        assertNull(OtpRelay.extract("Your new PIN is 4821."))                                                  // a PIN given: never
        assertNull(OtpRelay.extract("Use 778201 to approve your MoMo transaction of GHS 50."))                // money approval: never
        assertNull(OtpRelay.extract("Meet me at 1530 near the station"))                                       // no code words
        assertNull(OtpRelay.extract("Code 1234 or 5678 will work"))                                            // two codes: unsure
    }

    @Test fun the_offer_says_a_count_unless_private_and_enter_types_once() {
        assertEquals("A 6-digit code arrived from Google. Say \"enter the code\" to type it into Chrome.",
            OtpRelay.offer(6, "Google", "Chrome", null))
        assertEquals("A code arrived: 1 2 3 4. Say \"enter the code\" to type it.", OtpRelay.offer(4, null, null, "1234"))
        assertTrue(OtpRelay.isEnter("Enter the code"))
        assertTrue(OtpRelay.isEnter("type it in please"))
        assertFalse(OtpRelay.isEnter("what's the code"))
        var typed: CharSequence? = null
        OtpRelay.focus("com.android.chrome", "Chrome") { t -> typed = t; true }
        OtpRelay.offerForTest("123456", now = 1_000L)
        assertEquals("I typed the code.", OtpRelay.enter(now = 2_000L))
        assertEquals("123456", typed.toString())
        assertNull(OtpRelay.enter(now = 3_000L))                       // once
        OtpRelay.offerForTest("654321", now = 1_000L)
        assertEquals("That code has expired. Ask for a new one.", OtpRelay.enter(now = 1_000L + OtpRelay.TTL_MS + 1))
        OtpRelay.blur()
        OtpRelay.offerForTest("654321", now = 1_000L)
        assertTrue(OtpRelay.enter(now = 2_000L)!!.startsWith("The box for the code isn't open"))
    }

    @Test fun code_boxes_are_asked_for_and_never_a_pin() {
        assertTrue(OtpRelay.isCodeBox(0x00000002, "Enter verification code"))
        assertTrue(OtpRelay.isCodeBox(0x00000001, "OTP"))
        assertFalse(OtpRelay.isCodeBox(0x00000002, "Enter PIN"))
        assertFalse(OtpRelay.isCodeBox(0x00000012, "code"))             // a number password box
        assertFalse(OtpRelay.isCodeBox(0x00000002, null))               // a bare number box may be an amount
        assertFalse(OtpRelay.isCodeBox(0x00000002, "Amount"))
    }
}
