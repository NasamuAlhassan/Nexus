package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.LocalBrain
import com.tekyerema.tools.ReadChatTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Read the recent message from X on WhatsApp" opens the chat (a judge's request, 29 Oct 2026). */
class ReadChatTest {
    @Test fun thePersonInAWhatsAppReadRequest() {
        assertEquals("my wife", LocalBrain.chatPerson("read the recent message from my wife on WhatsApp"))
        assertEquals("Ama", LocalBrain.chatPerson("read the last WhatsApp message from Ama"))
        assertEquals("Kofi", LocalBrain.chatPerson("what did Kofi send me on WhatsApp"))
        assertEquals("Angela", LocalBrain.chatPerson("read the last message from Angela on my WhatsApp"))
        assertEquals("Prince", LocalBrain.chatPerson("open my chat with Prince on WhatsApp"))
        assertNull(LocalBrain.chatPerson("read my WhatsApp messages"))
        assertNull(LocalBrain.chatPerson("read the message from Ama"))            // no WhatsApp: the texts / notifications
        assertNull(LocalBrain.chatPerson("what did he send on WhatsApp"))
    }

    private fun bubble(id: Int, text: String, left: Int, top: Int) =
        UiElement(id, "TextView", text, Box(left, top, left + 500, top + 80), viewId = "com.whatsapp:id/message_text")

    @Test fun theirBubblesAreTheLeftOnes() {
        val s = UiSnapshot("com.whatsapp", "My Wife", listOf(
            bubble(1, "Are you coming?", 40, 300), bubble(2, "Yes, soon", 540, 420), bubble(3, "Buy bread please", 40, 540),
            UiElement(4, "EditText", null, Box(0, 2200, 1000, 2300), editable = true, viewId = "com.whatsapp:id/entry", hint = "Message")), 0L)
        assertEquals(true, ReadChatTool.isChat(s))
        assertEquals(listOf("Buy bread please"), ReadChatTool.theirLatest(s, 1))
        assertEquals(listOf("Are you coming?", "Buy bread please"), ReadChatTool.theirLatest(s, 2))
    }
}
