package com.tekyerema

import android.media.AudioDeviceInfo
import com.tekyerema.notifications.IndexedMessage
import com.tekyerema.notifications.NotificationDigest
import com.tekyerema.notifications.PrivateReading
import com.tekyerema.notifications.PrivateReading.Mode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Private reading on the loudspeaker (2026-09-29): with it on and no headset, reading messages says who and how many, not
 * what they say; "read them" reads them; a balance is read only when asked about.
 */
class PrivateReadingTest {
    @org.junit.Before fun freshCoaching() { com.tekyerema.voice.Coaching.resetForTest() }   // the shrinking tail starts full

    @After fun clear() = PrivateReading.clear()

    @Test fun the_decision_who_only_just_when_on_and_on_the_loudspeaker() {
        assertEquals(Mode.WHO_ONLY, PrivateReading.mode(privateOn = true, privateOutput = false))
        assertEquals(Mode.FULL, PrivateReading.mode(privateOn = true, privateOutput = true))
        assertEquals("off by default = read as before", Mode.FULL, PrivateReading.mode(privateOn = false, privateOutput = false))
        assertEquals(Mode.FULL, PrivateReading.mode(privateOn = false, privateOutput = true))
    }

    @Test fun headsets_and_bluetooth_audio_are_private_the_phones_speaker_is_not() {
        assertFalse(PrivateReading.isPrivateOutput(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        assertTrue(PrivateReading.isPrivateOutput(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET)))
        assertTrue(PrivateReading.isPrivateOutput(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertTrue(PrivateReading.isPrivateOutput(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertTrue(PrivateReading.isPrivateOutput(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        assertTrue(PrivateReading.isPrivateOutput(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
        assertFalse("a Bluetooth speaker is a loudspeaker", PrivateReading.isPrivateOutput(listOf(27 /* TYPE_BLE_SPEAKER */)))
        assertFalse(PrivateReading.isPrivateOutput(emptyList()))
    }

    @Test fun who_and_how_many_never_the_words() {
        assertEquals("You have 3 new messages: from Ama, Kofi and MTN. Say “read them” to hear them.",
            PrivateReading.whoOnly(listOf("Ama", "Kofi", "MTN")))
        assertEquals("You have 4 new messages: from Ama and Kofi. Say “read them” to hear them.",
            PrivateReading.whoOnly(listOf("Ama", "Kofi", "ama", "Kofi")))
        assertEquals("You have 6 new messages: from Ama, Kofi, MTN and 2 others. Say “read them” to hear them.",
            PrivateReading.whoOnly(listOf("Ama", "Kofi", "MTN", "Esi", "Yaw", null)))
        assertEquals("You have a new message from Ama. Say “read it” to hear it.", PrivateReading.whoOnly(listOf("Ama")))
        assertEquals("You have 2 new messages. Say “read them” to hear them.", PrivateReading.whoOnly(listOf(null, " ")))
        assertEquals("Your last 3 text messages are from MobileMoney and Ama. Say “read them” to hear them.",
            PrivateReading.whoOnly(listOf("MobileMoney", "Ama", "MobileMoney"), noun = "text message", latest = true))
        assertEquals("Your last text message is from Ama. Say “read it” to hear it.",
            PrivateReading.whoOnly(listOf("Ama"), noun = "text message", latest = true))
    }

    @Test fun a_balance_is_hidden_unless_asked_for_and_other_amounts_stay() {
        val momo = "Payment received for GHS 50.00 from AMA MENSAH. Current Balance: GHS 1,234.56 . Available Balance: GHS 1,234.56."
        assertEquals("Payment received for GHS 50.00 from AMA MENSAH. Current Balance: hidden . Available Balance: hidden.",
            PrivateReading.maskBalances(momo))
        assertEquals("Your new balance is hidden.", PrivateReading.maskBalances("Your new balance is GHS 12.50."))
        assertEquals("Bal: hidden.", PrivateReading.maskBalances("Bal: 99.00 cedis."))
        assertEquals("Are you coming?", PrivateReading.maskBalances("Are you coming?"))
        assertTrue(PrivateReading.asksForBalance("what's my momo balance"))
        assertTrue(PrivateReading.asksForBalance(null, "how much money do I have"))
        assertFalse(PrivateReading.asksForBalance("read my messages", null))
    }

    @Test fun voice_toggles_and_read_them() {
        assertEquals(true, PrivateReading.toggleOf("Read my messages privately."))
        assertEquals(true, PrivateReading.toggleOf("turn on private reading"))
        assertEquals(false, PrivateReading.toggleOf("read my messages out loud"))
        assertEquals(false, PrivateReading.toggleOf("turn off private reading"))
        assertNull(PrivateReading.toggleOf("read my messages"))
        for (s in listOf("read them", "Read them.", "okay, read them", "yes read it", "read them to me please", "go ahead and read them"))
            assertTrue(s, PrivateReading.isReadThem(s))
        for (s in listOf("read my screen", "read the news", "read them the riot act"))
            assertFalse(s, PrivateReading.isReadThem(s))
    }

    @Test fun read_them_reads_what_was_held_once_while_fresh() {
        assertNull(PrivateReading.take(0L))
        PrivateReading.hold("Ama: are you coming?", now = 1_000L)
        assertEquals("Ama: are you coming?", PrivateReading.take(2_000L))
        assertNull("once", PrivateReading.take(3_000L))
        PrivateReading.hold("old", now = 0L)
        assertNull("five minutes later it's stale", PrivateReading.take(6 * 60_000L))
    }

    @Test fun the_catch_up_without_the_quote_and_its_read_out() {
        val msgs = listOf(
            IndexedMessage(3_000L, "com.whatsapp", "WhatsApp", "Ama", "Ama", "Are you coming?"),
            IndexedMessage(2_000L, "com.whatsapp", "WhatsApp", "Kofi", "Kofi", "I'm outside"),
            IndexedMessage(1_000L, "com.mtn.momo", "MoMo", null, "MobileMoney", "Your new balance is GHS 20.00"))
        val brief = NotificationDigest.build(msgs, emptyList(), "since you last asked", quoteNewest = false)
        assertFalse(brief, brief.contains("Are you coming"))
        assertTrue(brief, brief.contains("from Ama"))
        assertTrue(NotificationDigest.build(msgs, emptyList(), "since you last asked").contains("Are you coming"))
        // One at a time (2026-09-30): who, where and when, a pause, the words; a longer pause before the next.
        val out = PrivateReading.readOut(msgs)
        val items = out.split(com.tekyerema.voice.SpokenText.BREAK)
        assertEquals(3, items.size)
        assertTrue(items[0], items[0].startsWith("From Ama, on WhatsApp, ") && items[0].endsWith("${com.tekyerema.voice.SpokenText.PAUSE}Are you coming?"))
        assertTrue(items[1], items[1].startsWith("From Kofi, on WhatsApp, ") && items[1].endsWith("I'm outside."))
        assertTrue(items[2], items[2].startsWith("From MobileMoney, on MoMo, "))
        val one = com.tekyerema.voice.SpokenText.plain(PrivateReading.readOut(msgs, per = 1))
        assertTrue(one, one.startsWith("From Ama, on WhatsApp, ") && one.endsWith("There are 2 more — say continue."))
    }
}
