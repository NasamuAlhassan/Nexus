package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Demo journeys, A20e device suite 2026-09-30: "read my last WhatsApp message" went to the agent (25–42 s, one timeout). */
class ChatMessagesIntentTest {
    private fun c(s: String) = FastIntentClassifier.classify(s)

    @Test fun reading_whatsapp_messages_is_instant() {
        assertEquals(FastIntent.ChatMessages("WhatsApp", latestOnly = true), c("read my last WhatsApp message"))
        assertEquals(FastIntent.ChatMessages("WhatsApp", latestOnly = true), c("Read my latest WhatsApp message."))
        assertEquals(FastIntent.ChatMessages("WhatsApp", latestOnly = false), c("any new WhatsApp messages"))
        assertEquals(FastIntent.ChatMessages("WhatsApp", latestOnly = false), c("do I have any WhatsApp messages"))
        assertEquals(FastIntent.ChatMessages("WhatsApp", latestOnly = false), c("read my WhatsApp messages"))
        assertEquals(FastIntent.ChatMessages("WhatsApp", latestOnly = false, from = "ama"), c("read my WhatsApp messages from Ama"))
        assertEquals(FastIntent.ChatMessages("Telegram", latestOnly = true), c("read my last Telegram message"))
    }

    /** A20e 6 Nov 2026: "anything new on WhatsApp for me" had no rule online, so the app was opened and the brain wandered. */
    @Test fun the_same_read_said_without_the_word_messages() {
        for (s in listOf("anything new on WhatsApp for me", "Anything new on WhatsApp?", "what's new on WhatsApp", "any news from WhatsApp today",
            "is there anything new on whatsapp", "do I have anything new on WhatsApp"))
            assertEquals(s, FastIntent.ChatMessages("WhatsApp", latestOnly = false), c(s))
        assertEquals(FastIntent.ChatMessages("Telegram", latestOnly = false), c("anything new on Telegram"))
        // a send, or another app, is not a read
        assertFalse(c("send a WhatsApp message to Ama saying anything new") is FastIntent.ChatMessages)
        assertFalse(c("anything new on YouTube") is FastIntent.ChatMessages)
    }

    /** The two-way beat: read the last message, then "reply saying …" answers that person, on the same app. */
    @Test fun a_bare_reply_answers_the_person_just_read() {
        val r = com.tekyerema.agent.References
        assertEquals("reply to Prince Melanin on WhatsApp saying I'm on my way",
            r.rewrite("reply saying I'm on my way", person = "Prince Melanin", channel = "WhatsApp"))
        assertEquals("reply to Ama saying ok", r.rewrite("Reply: ok", person = "Ama", channel = "SMS"))
        assertEquals("reply to Ama on WhatsApp saying thank you", r.rewrite("reply back with thank you", person = "Ama", channel = "WhatsApp"))
        org.junit.Assert.assertNull("an app a reply can't go back to is never guessed",
            r.rewrite("reply saying ok", person = "Ama", channel = null))
        org.junit.Assert.assertNull("nobody read yet", r.rewrite("reply saying ok", person = null, channel = "WhatsApp"))
        org.junit.Assert.assertNull("a named person is not a bare reply", r.rewrite("reply to Kofi saying hi", person = "Ama", channel = "WhatsApp"))
        val sms = com.tekyerema.agent.LocalBrain.parseSms("reply to Prince Melanin on WhatsApp saying I'm on my way")!!
        assertEquals("Prince Melanin", sms.contact); assertEquals("I'm on my way", sms.message); org.junit.Assert.assertTrue(sms.whatsApp)
        assertEquals("WhatsApp", com.tekyerema.agent.LocalFastRouter.channelOf("com.whatsapp"))
        assertEquals("SMS", com.tekyerema.agent.LocalFastRouter.channelOf("com.samsung.android.messaging"))
        org.junit.Assert.assertNull(com.tekyerema.agent.LocalFastRouter.channelOf("org.telegram.messenger"))
    }

    @Test fun sending_or_opening_is_not_reading() {
        assertFalse(c("send a WhatsApp message to Ama") is FastIntent.ChatMessages)
        assertFalse(c("open WhatsApp") is FastIntent.ChatMessages)
        assertFalse(c("reply to Ama on WhatsApp saying yes") is FastIntent.ChatMessages)
        // Neighbours unchanged.
        assertEquals(FastIntent.LastSms, c("read my last text message"))
        assertEquals(FastIntent.Digest("recent", messagesOnly = true), c("read my messages"))
    }
}
