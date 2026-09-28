package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local Fast Path classification (§13). Every deterministic category must classify to its intent; a complex
 * agentic request must NOT be intercepted (classify → null so it falls through to the agent unchanged).
 */
class FastIntentClassifierTest {
    private fun c(s: String) = FastIntentClassifier.classify(s)

    @Test fun time() {
        for (s in listOf("What time is it?", "What's the time?", "Tell me the time.",
            "Can you tell me the time?", "What is the current time?", "Check the time."))
            assertEquals(s, FastIntent.Time, c(s))
    }

    @Test fun date() {
        for (s in listOf("What's today's date?", "What date is it?", "Tell me today's date."))
            assertEquals(s, FastIntent.Date, c(s))
    }

    @Test fun day() {
        for (s in listOf("What day is today?", "Is today Monday?", "Tell me the day."))
            assertEquals(s, FastIntent.Day, c(s))
    }

    @Test fun battery() {
        for (s in listOf("What's my battery?", "How much battery do I have?", "What's my battery percentage?",
            "Check my battery.", "How much battery is left?", "Is my battery low?"))
            assertEquals(s, FastIntent.Battery, c(s))
    }

    @Test fun quick_settings_toggles() {
        assertEquals(FastIntent.QuickToggle("dnd", true), c("Turn on Do Not Disturb."))
        assertEquals(FastIntent.QuickToggle("dnd", false), c("turn off do not disturb"))
        assertEquals(FastIntent.QuickToggle("rotate", true), c("Turn on auto-rotate"))
        assertEquals(FastIntent.QuickToggle("rotate", false), c("switch auto rotate off"))
        assertEquals(FastIntent.QuickToggle("airplane", true), c("Turn on airplane mode"))
        assertEquals(FastIntent.QuickToggle("battery_saver", true), c("Enable battery saver"))
        assertEquals(FastIntent.QuickToggle("dark", false), c("disable dark mode"))
        assertEquals(FastIntent.QuickToggle("location", true), c("please turn on location"))
        // Not a tile: a feature inside an app, or compound → the agent.
        for (s in listOf("turn on location sharing in WhatsApp", "turn on notifications for Telegram",
            "turn on do not disturb and open WhatsApp"))
            assertTrue(s, c(s) !is FastIntent.QuickToggle)
    }

    @Test fun quick_tile_is_found_by_name_and_must_be_checkable() {
        val t = com.tekyerema.agent.QuickTiles.byKey("dnd")!!
        fun el(id: Int, label: String, checkable: Boolean, checked: Boolean = false) = com.tekyerema.accessibility.UiElement(
            id, "Switch", label, com.tekyerema.accessibility.Box(0, 0, 10, 10), checkable = checkable, checked = checked, clickable = true)
        fun snap(vararg e: com.tekyerema.accessibility.UiElement) = com.tekyerema.accessibility.UiSnapshot(
            "com.android.systemui", null, e.toList(), 0L)
        // the real emulator tile: label carries the state
        assertEquals(3, com.tekyerema.agent.QuickTiles.find(snap(el(1, "Internet, AndroidWifi", true),
            el(2, "Do Not Disturb settings", false), el(3, "Do Not Disturb, Off", true)), t)?.id)
        assertNull(com.tekyerema.agent.QuickTiles.find(snap(el(2, "Do Not Disturb settings", false)), t))
    }

    @Test fun storage_question_is_fast_but_storage_actions_are_not() {
        for (s in listOf("How much storage space is left on my phone?", "How much storage do I have?", "Check my storage.",
            "Do I have enough space?", "What's my storage?", "How much space is left?"))
            assertEquals(s, FastIntent.Storage, c(s))
        for (s in listOf("Free up some space.", "Clear storage for WhatsApp.", "Open storage settings.", "Delete old photos to make space."))
            assertTrue(s, c(s) !is FastIntent.Storage)
    }

    @Test fun volume_up() {
        for (s in listOf("Increase the volume.", "Turn up the volume.", "Make it louder.",
            "Raise the volume.", "Increase my volume.", "Turn the volume up."))
            assertEquals(s, FastIntent.VolumeUp, c(s))
    }

    @Test fun volume_down() {
        for (s in listOf("Decrease the volume.", "Turn down the volume.", "Make it quieter.",
            "Lower the volume.", "Reduce the volume."))
            assertEquals(s, FastIntent.VolumeDown, c(s))
    }

    @Test fun mute_unmute() {
        for (s in listOf("Mute the volume.", "Mute my phone.", "Mute."))
            assertEquals(s, FastIntent.VolumeMute, c(s))
        for (s in listOf("Unmute.", "Turn the sound back on."))
            assertEquals(s, FastIntent.VolumeUnmute, c(s))
    }

    @Test fun volume_query() {
        for (s in listOf("What's the volume?", "What is my volume level?", "Check the volume."))
            assertEquals(s, FastIntent.VolumeQuery, c(s))
    }

    @Test fun brightness() {
        for (s in listOf("Increase the brightness.", "Turn up the brightness.", "Make the screen brighter."))
            assertEquals(s, FastIntent.BrightnessUp, c(s))
        for (s in listOf("Decrease the brightness.", "Turn down the brightness.", "Make the screen darker."))
            assertEquals(s, FastIntent.BrightnessDown, c(s))
    }

    @Test fun flashlight() {
        for (s in listOf("Turn on the flashlight.", "Switch on the torch.", "Turn the torch on."))
            assertEquals(s, FastIntent.FlashlightOn, c(s))
        for (s in listOf("Turn off the flashlight.", "Switch off the torch.", "Turn the torch off."))
            assertEquals(s, FastIntent.FlashlightOff, c(s))
    }

    @Test fun wifi_bluetooth() {
        assertEquals(FastIntent.WifiQuery, c("Is Wi-Fi on?"))
        assertEquals(FastIntent.WifiQuery, c("Check Wi-Fi."))
        assertEquals(FastIntent.WifiOn, c("Turn on Wi-Fi."))
        assertEquals(FastIntent.WifiOff, c("Turn off Wi-Fi."))
        assertEquals(FastIntent.BluetoothQuery, c("Is Bluetooth on?"))
        assertEquals(FastIntent.BluetoothOn, c("Turn Bluetooth on."))
        assertEquals(FastIntent.BluetoothOff, c("Turn Bluetooth off."))
    }

    @Test fun media() {
        assertEquals(FastIntent.MediaPlay, c("Play."))
        assertEquals(FastIntent.MediaPause, c("Pause."))
        assertEquals(FastIntent.MediaPause, c("Pause the music."))
        assertEquals(FastIntent.MediaStop, c("Stop."))
        assertEquals(FastIntent.MediaNext, c("Next song."))
        assertEquals(FastIntent.MediaNext, c("Skip."))
        assertEquals(FastIntent.MediaPrevious, c("Previous song."))
        assertEquals(FastIntent.MediaPlay, c("Resume."))
    }

    @Test fun navigation() {
        assertEquals(FastIntent.Back, c("Go back."))
        assertEquals(FastIntent.Home, c("Go home."))
        assertEquals(FastIntent.Recents, c("Show recent apps."))
        assertEquals(FastIntent.ScrollDown, c("Scroll down."))
        assertEquals(FastIntent.ScrollUp, c("Scroll up."))
        assertEquals(FastIntent.ScrollUp, c("Go to the top."))
        assertEquals(FastIntent.ScrollDown, c("Go to the bottom."))
        assertEquals(FastIntent.LockScreen, c("Lock the phone."))
        assertEquals(FastIntent.Notifications, c("Open notifications."))
        assertEquals(FastIntent.Notifications, c("Show my notifications."))
    }

    @Test fun open_app() {
        for ((s, app) in listOf(
            "Open WhatsApp." to "whatsapp", "Open YouTube." to "youtube", "Open Settings." to "settings",
            "Open Proton VPN." to "proton vpn", "Launch WhatsApp." to "whatsapp", "Start YouTube." to "youtube"
        )) {
            val r = c(s)
            assertTrue("$s -> $r", r is FastIntent.OpenApp)
            assertEquals(s, app, (r as FastIntent.OpenApp).query)
        }
    }

    // The whole point: complex/agentic requests must fall through to the agent (null), never be intercepted.
    @Test fun complex_requests_are_NOT_intercepted() {
        for (s in listOf(
            "Send Kwame a WhatsApp message saying I'll call him later.",
            "Open WhatsApp, find Kwame, send him a message saying I'll call him later, and then tell me whether he has replied.",
            "Read my notifications and tell me if there is anything important.",
            "Take a screenshot and send it to Ama.",
            "Find the cheapest option and open it.",
            "Reply to Ama that I'm on my way.",
            "Set an alarm for 6 am."   // handled by the existing agent/LocalBrain, not the fast path
        )) assertNull("must defer to agent: $s", c(s))
    }

    @Test fun empty_is_null() {
        assertNull(c(""))
        assertNull(c("   "))
    }
}
