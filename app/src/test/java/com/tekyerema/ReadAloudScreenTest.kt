package com.tekyerema

import com.tekyerema.notifications.MessageReading
import com.tekyerema.notifications.ReadAloudScreen
import com.tekyerema.ui.assistant.ScreenText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Private reading on the screen (owner's order, 2 Nov 2026): an answer quoting messages read aloud shows who and when only. */
class ReadAloudScreenTest {
    private val now = 1_000_000L

    @Before fun start() = ReadAloudScreen.clear()
    @After fun end() = ReadAloudScreen.clear()

    private fun note(who: String?, where: String?, whenSpoken: String?, text: String, at: Long = now) =
        ReadAloudScreen.note(who, where, whenSpoken, listOf(text), at)

    @Test fun `a reading on screen is covered with who and when, in reading order`() {
        note("Ama", "WhatsApp", "10 minutes ago", "Are you coming to the meeting tonight?")
        note("Kofi", "SMS", "an hour ago", "Please call me when you land at Kotoka")
        val shown = "Your messages: 2.\nFrom Kofi, an hour ago.\nPlease call me when you land at Kotoka.\n\nFrom Ama, on WhatsApp, " +
            "10 minutes ago.\nAre you coming to the meeting tonight?"
        val c = ReadAloudScreen.coverFor(shown, now + 1_000)
        assertNotNull(c)
        assertEquals(listOf("Kofi · SMS · an hour ago", "Ama · WhatsApp · 10 minutes ago"), c!!.lines.map { it.shown })
        assertEquals("Reading 2 messages aloud", c.title)
        assertEquals("2 messages, read aloud", c.doneTitle)
        assertFalse("the cover never carries the words", c.text.contains("coming") || c.description.contains("Kotoka"))
    }

    @Test fun `the who-only line of private reading and ordinary answers are not covered`() {
        note("Ama", "WhatsApp", "10 minutes ago", "Are you coming to the meeting tonight?")
        assertNull(ReadAloudScreen.coverFor("You have a new message from Ama. Say “read it” to hear it.", now))
        assertNull(ReadAloudScreen.coverFor("The battery is at 80 percent.", now))
        assertNull(ReadAloudScreen.coverFor("", now))
    }

    @Test fun `a short message needs its sender on screen too`() {
        note("Ama", null, null, "Ok")
        assertNull("'ok' alone could be anything", ReadAloudScreen.coverFor("Ok, I set an alarm for 6 AM.", now))
        assertNotNull(ReadAloudScreen.coverFor("From Ama. Ok", now))
    }

    @Test fun `a long message cut on screen is still recognised by its first words`() {
        val long = "Hello Prince, the committee has moved the meeting to Thursday at the main hall because the lab is closed " + "x ".repeat(200)
        note("Esi", null, "today", long)
        assertNotNull(ReadAloudScreen.coverFor("From Esi, today. Hello Prince, the committee has moved the meeting…", now))
    }

    @Test fun `a reading goes stale after fifteen minutes`() {
        note("Ama", null, null, "Are you coming to the meeting tonight?", at = now)
        assertNotNull(ReadAloudScreen.coverFor("Are you coming to the meeting tonight?", now + ReadAloudScreen.FRESH_MS - 1))
        assertNull(ReadAloudScreen.coverFor("Are you coming to the meeting tonight?", now + ReadAloudScreen.FRESH_MS))
    }

    @Test fun `a balance hidden by private reading still matches`() {
        ReadAloudScreen.note("MobileMoney", null, null, listOf("Your new balance is GHS 12.50 thank you",
            com.tekyerema.notifications.PrivateReading.maskBalances("Your new balance is GHS 12.50 thank you")), now)
        assertNotNull(ReadAloudScreen.coverFor("From MobileMoney. Your new balance is hidden thank you", now))
    }

    @Test fun `what MessageReading reads out is what the screen recognises`() {
        val page = MessageReading.page("Your messages: 2.", listOf(
            MessageReading.Msg("Ama", "WhatsApp", "10 minutes ago", "Are you coming? 😀 See https://x.co/abc"),
            MessageReading.Msg("Kofi", null, "an hour ago", "Call me & bring the keys")))
        val c = ReadAloudScreen.coverFor(ScreenText.of(page.text), now)
        assertNotNull(c)
        assertEquals(listOf("Ama", "Kofi"), c!!.lines.map { it.who })
        assertTrue(c.description.startsWith("Reading 2 messages aloud. From Ama, on WhatsApp, 10 minutes ago."))
    }
}
