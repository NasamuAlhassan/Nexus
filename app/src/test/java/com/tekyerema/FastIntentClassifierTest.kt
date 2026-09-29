package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun brightness_levels() {
        assertEquals(FastIntent.BrightnessSet(1f), c("Set the screen brightness to maximum"))
        assertEquals(FastIntent.BrightnessSet(1f), c("full brightness please"))
        assertEquals(FastIntent.BrightnessSet(0f), c("set brightness to the lowest"))
        assertEquals(FastIntent.BrightnessSet(0.4f), c("set brightness to 40 percent"))
        assertEquals(FastIntent.BrightnessSet(0.75f), c("brightness 75%"))
        assertEquals(FastIntent.BrightnessUp, c("make it brighter"))
        assertTrue(c("what's the brightest star in the sky") !is FastIntent.BrightnessSet)
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
        for (s in listOf("Torch.", "Flashlight please", "Open the torch", "Torch on", "Can you turn on the torch?"))
            assertEquals(s, FastIntent.FlashlightOn, c(s))
        // C-02: a question is a query, never a switch; any other mention is not a torch command at all.
        for (s in listOf("Is my torch on?", "Is the flashlight off?", "Check the torch", "What's the flashlight doing"))
            assertEquals(s, FastIntent.FlashlightQuery, c(s))
        for (s in listOf("Why is my torch app so slow", "Where are the flashlight settings", "My torch broke yesterday"))
            assertTrue(s, c(s) != FastIntent.FlashlightOn && c(s) != FastIntent.FlashlightOff)
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
        for (s in listOf("home", "home screen", "go to the home screen", "take me home", "press the home button", "kɔ fie", "ko fie"))
            assertEquals(s, FastIntent.Home, c(s))
        // A sentence that merely MENTIONS home is not the Home command (2026-09-29): a message to send, a search.
        for (s in listOf("Bue WhatsApp ne ka kyerɛ Prince sɛ ɔmbra fie", "tell him to go home", "text Ama that I'm going home",
                "how far is my home from Accra", "ka kyerɛ Kofi sɛ ɔnkɔ fie"))
            assertNotEquals(s, FastIntent.Home, c(s))
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

    // ---- W4 deterministic phone actions ----
    @Test fun calls_answer_end_and_history() {
        for (s in listOf("Answer the call", "Pick up", "Answer", "Accept the call", "Take the call please"))
            assertEquals(s, FastIntent.AnswerCall, c(s))
        for (s in listOf("Hang up", "End the call", "Reject the call", "Decline this call"))
            assertEquals(s, FastIntent.EndCall, c(s))
        assertEquals(FastIntent.CallHistory(missedOnly = false), c("Who called me?"))
        assertEquals(FastIntent.CallHistory(missedOnly = true), c("Any missed calls?"))
        assertTrue(c("Accept") != FastIntent.AnswerCall)                  // a bare "accept" is a dialog button, not a call
    }

    @Test fun messages_location_network_media() {
        for (s in listOf("Read my last text", "Any new texts?", "Check my SMS", "What was my last text message"))
            assertEquals(s, FastIntent.LastSms, c(s))
        assertTrue(c("Read the text on the screen") != FastIntent.LastSms)
        for (s in listOf("Where am I?", "What's my current location")) assertEquals(s, FastIntent.WhereAmI, c(s))
        for (s in listOf("Am I online?", "Do I have internet", "Am I on WiFi")) assertEquals(s, FastIntent.Connectivity, c(s))
        assertEquals(FastIntent.NowPlaying, c("What's playing?"))
        assertEquals(FastIntent.CalendarToday, c("What's on my calendar today?"))
    }

    @Test fun ringer_alarms_screenshot_camera() {
        assertEquals(FastIntent.Ringer("vibrate"), c("Put my phone on vibrate"))
        assertEquals(FastIntent.Ringer("normal"), c("Set the phone to ring"))
        assertEquals(FastIntent.Ringer("silent"), c("Silent mode"))
        assertEquals(FastIntent.ShowAlarms, c("Show my alarms"))
        assertEquals(FastIntent.Screenshot, c("Take a screenshot"))
        assertEquals(FastIntent.PowerMenu, c("Open the power menu"))
        assertEquals(FastIntent.TakePhoto, c("Take a photo"))
        assertEquals(FastIntent.LockQuery, c("Is my phone locked?"))
        assertNull(c("Send a text to Ama and call Kofi"))                  // compound → agent
    }

    /** A20e: "opening the calculator." went to the cloud model, which paged the home screen looking for the icon. */
    @Test fun open_app_phrasings_stay_on_the_fast_path() {
        for ((s, app) in listOf("opening the calculator." to "calculator", "Can you please open the calculator" to "calculator",
                "open up WhatsApp" to "whatsapp", "Open my gallery app" to "gallery", "launching YouTube please" to "youtube",
                "I'd like to open Chrome" to "chrome", "bue WhatsApp" to "whatsapp"))
            assertEquals(s, FastIntent.OpenApp(app), c(s))
        assertNull(c("open WhatsApp and send Kofi hi"))                     // compound → agent
        assertTrue(c("opening hours of Melcom") !is FastIntent.OpenApp)     // not an app
    }

    @Test fun who_did_i_call_last_is_call_history() {
        for (s in listOf("Can you tell me who I called last", "Who did I call last?", "my last call"))
            assertEquals(s, FastIntent.CallHistory(missedOnly = false), c(s))
    }

    /** The A20e got stuck three screens deep in One UI Settings looking for this; the phone knows it. */
    @Test fun android_version_is_answered_by_the_phone() {
        for (s in listOf("Which Android version is this phone?", "what android version do I have", "What phone is this?", "what's my phone model"))
            assertEquals(s, FastIntent.DeviceInfo, c(s))
        assertNull("a compound request stays with the agent", c("open Settings and tell me which Android version this phone has"))
    }

    /** Found by the device suite: Twi's focus "na" isn't "and", and "ase" (down) had no rule. */
    @Test fun twi_time_question_and_volume_down() {
        assertEquals(FastIntent.Time, c("Bere bɛn na ɛyɛ?"))
        assertEquals(FastIntent.VolumeDown, c("ma volume no ase"))
        assertEquals(FastIntent.VolumeUp, c("ma volume no so"))
        assertNull("\"na\" still joins two commands", c("bue whatsapp na frɛ kofi"))
    }

    /** Device suite: "what is 15 times 12" took 38.7 s through the cloud; a sum needs no model and works offline. */
    @Test fun simple_sums_are_answered_on_the_phone() {
        fun ans(s: String) = (c(s) as? FastIntent.Calculate)?.answer
        assertEquals("15 times 12 is 180.", ans("What is 15 times 12?"))
        assertEquals("5 times 11 is 55.", ans("What is 5 times 11?"))
        assertEquals("250 divided by 5 is 50.", ans("250 divided by 5"))
        assertEquals("10 divided by 3 is 3.3333.", ans("what's 10 / 3"))
        assertEquals("20 percent of 150 is 30.", ans("what is 20 percent of 150"))
        assertEquals("7.5 plus 2.5 is 10.", ans("calculate 7.5 + 2.5"))
        assertEquals("You can't divide by zero.", ans("5 divided by 0"))
        assertNull("a rate question goes to the web", ans("how much is 5 dollars in cedis"))
        assertNull(ans("take my medicine 2 times a day"))
    }

    @Test fun fractions_powers_and_roots_are_answered_on_the_phone() {
        fun ans(s: String) = (c(s) as? FastIntent.Calculate)?.answer
        assertEquals("Half of 250 is 125.", ans("What is half of 250?"))
        assertEquals("A quarter of 80 is 20.", ans("a quarter of 80"))
        assertEquals("A third of 90 is 30.", ans("what's a third of 90"))
        assertEquals("Three quarters of 80 cedis is 60 cedis.", ans("3 quarters of 80 cedis"))
        assertEquals("20 percent of 150 cedis is 30 cedis.", ans("what is 20 percent of 150 cedis"))
        assertEquals("The square root of 144 is 12.", ans("what is the square root of 144"))
        assertEquals("The square root of 2 is about 1.4142.", ans("square root of 2"))
        assertEquals("12 squared is 144.", ans("what is 12 squared"))
        assertEquals("3 cubed is 27.", ans("3 cubed"))
        assertEquals("2 to the power of 10 is 1024.", ans("what's 2 to the power of 10"))
        assertNull(ans("square root of -4"))
        assertNull(ans("half of the class"))
    }

    /** On-phone answers with one right answer: dates, conversions, spelling, the time elsewhere. */
    @Test fun one_right_answer_questions_never_need_a_model() {
        for (s in listOf("How many days until Christmas?", "what day is 6 March 2027", "what's the date next Friday",
            "how old is someone born in 1990", "when is Farmers' Day", "is 2028 a leap year", "what day is it tomorrow",
            "what is the akan name for a boy born on friday"))
            assertTrue("$s -> ${c(s)}", c(s) is FastIntent.DateAnswer)
        for (s in listOf("convert 10 km to miles", "how many pounds is 5 kg", "30 degrees celsius in fahrenheit", "how many minutes in an hour"))
            assertTrue("$s -> ${c(s)}", c(s) is FastIntent.Convert)
        for (s in listOf("spell accommodation", "how do you spell necessary"))
            assertTrue("$s -> ${c(s)}", c(s) is FastIntent.Spell)
        for (s in listOf("what time is it in London", "what's the time in New York", "time in lagos", "can you tell me what time it is in Dubai"))
            assertTrue("$s -> ${c(s)}", c(s) is FastIntent.WorldTime)
    }

    /** The new answerers must not take what the existing intents own, and a question about elsewhere/another day that
     *  can't be answered must reach the model — not "The time is …" / "Today is …" for HERE and TODAY. */
    @Test fun on_phone_answers_do_not_steal_other_intents() {
        for (s in listOf("What time is it?", "What's the time?", "Tell me the time.", "What is the current time?", "what time is it now",
            "What's the time at the moment?"))
            assertEquals(s, FastIntent.Time, c(s))
        for (s in listOf("What day is today?", "What day is it?", "what day is it today", "Is today Monday?", "which day is today"))
            assertEquals(s, FastIntent.Day, c(s))
        for (s in listOf("What's today's date?", "What date is it?", "what's the date today", "what's the date"))
            assertEquals(s, FastIntent.Date, c(s))
        assertTrue(c("remind me in 20 minutes to take my medicine") is FastIntent.CreateSchedule)
        assertTrue(c("remind me in 20 minutes") is FastIntent.CreateSchedule)
        for (s in listOf("set a timer for 10 minutes", "set an alarm for 6 am", "how much is 5 dollars in cedis", "spell check",
            "convert 100 dollars to cedis", "how many days until my birthday", "when is eid"))
            assertTrue("$s -> ${c(s)}", c(s).let { it !is FastIntent.DateAnswer && it !is FastIntent.Convert && it !is FastIntent.Spell && it !is FastIntent.WorldTime })
        // Elsewhere / another day it can't work out: the model, never the local time or today's date.
        for (s in listOf("what time is it in Brazil", "what day is Eid", "what's the date of my exam", "what day is it in London"))
            assertNull(s, c(s))
        assertEquals(FastIntent.OpenApp("calculator"), c("open the calculator"))
        assertEquals(FastIntent.Battery, c("how much battery is left"))
    }
}
