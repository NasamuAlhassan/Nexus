package com.tekyerema

import com.tekyerema.agent.MessageWho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageWhoTest {
    @Test fun theAppIsAFilterNotPartOfTheName() {
        assertEquals("Angela" to "WhatsApp", MessageWho.split("Angela on my WhatsApp"))
        assertEquals("Kofi" to "Telegram", MessageWho.split("Kofi in Telegram"))
        assertEquals("Ama Serwaa" to null, MessageWho.split("Ama Serwaa"))
        assertEquals(null to null, MessageWho.split(null))
    }

    @Test fun anAppsSummaryIsNeverAMessage() {
        assertTrue(MessageWho.isSummary("WhatsApp", "505 messages from 40 chats"))
        assertTrue(MessageWho.isSummary("Someone", "12 new messages from 3 chats"))
        assertFalse(MessageWho.isSummary("Angela", "Call me when you're free"))
    }

    @Test fun anAppNameNeverMatchesAPerson() {
        assertFalse(MessageWho.matches("WhatsApp", "Angela on my WhatsApp"))
        assertTrue(MessageWho.matches("Angela Mensah", "Angela"))
        assertTrue(MessageWho.matches("Angela", "Angela Mensah"))
        assertFalse(MessageWho.matches("A", "Angela"))
    }
}
