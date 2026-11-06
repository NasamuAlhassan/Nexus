package com.tekyerema

import com.tekyerema.agent.DictatedBody
import org.junit.Assert.assertEquals
import org.junit.Test

/** A dictated message body never steers the route (A20e, 6 Nov 2026). */
class DictatedBodyTest {
    @Test fun the_body_is_cut_off() {
        assertEquals("text Prince", DictatedBody.head("text Prince: Nexus here. On WhatsApp, send this phone a message, then a voice note on Telegram."))
        assertEquals("tell Ama", DictatedBody.head("tell Ama that I'm opening Telegram now"))
        assertEquals("message Kofi", DictatedBody.head("message Kofi saying open YouTube and play Sarkodie"))
        assertEquals("send Prince a WhatsApp", DictatedBody.head("send Prince a WhatsApp saying I'm in Settings"))
    }

    @Test fun requests_that_dictate_nothing_are_left_whole() {
        for (g in listOf("open WhatsApp", "what was the last message Richmond sent me on telegram", "text Prince",
            "play Adonai by Sarkodie", "send 1 cedi to Prince", "tell me the time"))
            assertEquals(g, DictatedBody.head(g))
    }
}
