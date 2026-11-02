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
        // Samsung One UI (A20e 2026-09-30): a plain button, commas for spaces, the state in the label.
        val air = com.tekyerema.agent.QuickTiles.byKey("airplane")!!
        val off = com.tekyerema.agent.QuickTiles.find(snap(el(7, "Wi-Fi,On.,Button", false), el(9, "Flight,mode,Off.,Button", false)), air)
        assertEquals(9, off?.id)
        org.junit.Assert.assertFalse(com.tekyerema.agent.QuickTiles.isOn(off!!))
        assertTrue(com.tekyerema.agent.QuickTiles.isOn(el(9, "Flight,mode,On.,Button", false)))
        assertTrue(com.tekyerema.agent.QuickTiles.isOn(el(3, "Do Not Disturb, Off", true, checked = true)))   // checkable: its state
    }

    /** A20e 2026-09-30: "turn on airplane mode" succeeded, then the user heard "paused because the internet dropped". */
    @Test fun a_request_that_cuts_the_connection_is_recognised() {
        val g = com.tekyerema.agent.ConnectivityGoal
        assertEquals(com.tekyerema.agent.ConnectivityGoal.Want.AIRPLANE_ON, g.asks("turn on airplane mode"))
        assertEquals(com.tekyerema.agent.ConnectivityGoal.Want.AIRPLANE_ON, g.asks("switch on flight mode"))
        assertEquals(com.tekyerema.agent.ConnectivityGoal.Want.AIRPLANE_ON, g.asks("put the phone in airplane mode".replace("the phone in ", "")))
        assertEquals(com.tekyerema.agent.ConnectivityGoal.Want.WIFI_OFF, g.asks("turn off wifi"))
        assertEquals(com.tekyerema.agent.ConnectivityGoal.Want.WIFI_OFF, g.asks("switch the Wi-Fi off"))
        assertEquals(com.tekyerema.agent.ConnectivityGoal.Want.DATA_OFF, g.asks("turn off mobile data"))
        assertNull(g.asks("turn off airplane mode"))
        assertNull(g.asks("turn on wifi"))
        assertNull(g.asks("open WhatsApp"))
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
        // A20e 2026-09-29 (voice): "stop the timer" went to a spent cloud AI.
        for (s in listOf("stop the timer", "Stop the timer.", "cancel my timer", "turn off the timer please"))
            assertEquals(s, FastIntent.StopTimer, c(s))
        assertNotEquals(FastIntent.StopTimer, c("set a timer for 3 minutes"))
        // 2 Nov 2026: "set a timer for 5 minutes" reached the agent (right, but slow) — now the fast path's set_timer.
        assertEquals(FastIntent.SetTimer(180), c("set a timer for 3 minutes"))
        assertEquals(FastIntent.SetTimer(300), c("Set a timer for 5 minutes."))
        assertEquals(FastIntent.SetTimer(600), c("start a 10 minute timer"))
        assertEquals(FastIntent.SetTimer(30), c("timer for 30 seconds"))
        assertEquals(FastIntent.SetTimer(3600), c("please set a timer for 1 hour"))
        for (s in listOf("set a timer", "what's the time", "set an alarm for 6 am", "how long is left on my timer"))
            assertNotEquals(s, true, c(s) is FastIntent.SetTimer)
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
        for (s in listOf("show me locations to the Accra mall", "navigate to Accra Mall", "directions to Kumasi",
            "can you start the journey to legon Hall", "take me to the airport", "show me where legon Hall is",
            "how do I get to Makola Market"))
            assertTrue(s, c(s) is FastIntent.MapSearch)
        assertEquals("Accra Mall", (c("navigate to Accra Mall") as FastIntent.MapSearch).place)
        assertEquals("legon Hall", (c("can you start the journey to legon Hall") as FastIntent.MapSearch).place)
        assertEquals("the airport", (c("show me directions to the airport please") as FastIntent.MapSearch).place)
        // "take me to X" may name an app: the router opens X when it is exactly an installed app, else searches the map.
        assertTrue((c("take me to the airport") as FastIntent.MapSearch).appFirst)
        assertEquals(FastIntent.MapSearch("WhatsApp", appFirst = true), c("take me to WhatsApp"))
        assertTrue(!(c("navigate to Accra Mall") as FastIntent.MapSearch).appFirst)
        // Not places (2026-10-01 review of the first rule): the user's own things, parts of the phone, a bare "where is"
        // question (the answer is spoken, Maps isn't opened), and "go to X" (an app or a screen).
        for (s in listOf("where is my phone", "find my last message", "where is the settings", "how do I get to the settings",
            "show me where my messages are", "navigate to my notifications", "find my keys", "where is Kumasi",
            "take me to the next page", "take me to my messages", "search for places near me"))
            assertTrue("$s -> ${c(s)}", c(s) !is FastIntent.MapSearch)
        assertEquals(FastIntent.OpenApp("settings"), c("go to settings"))
        assertEquals(FastIntent.OpenApp("whatsapp"), c("go to WhatsApp"))
        assertEquals(FastIntent.Home, c("take me to the home screen"))
        assertTrue(FastIntentClassifier.isPhoneThing("my Settings page"))
        assertTrue(!FastIntentClassifier.isPhoneThing("Makola Market"))
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

    // ---- Black-box audit, A20e 2026-10-01 ----

    /** S23: "close notifications" went to the agent, which tapped Clear and wiped every notification. */
    @Test fun close_notifications_closes_the_shade_and_never_clears() {
        for (s in listOf("close notifications", "Close the notifications.", "close my notifications", "close the notification panel",
            "close the notification shade", "hide notifications", "close the panel", "can you close the notifications please"))
            assertEquals(s, FastIntent.CloseShade, c(s))
        // Clearing is a different thing (and the agent's, behind a confirmation) — never this rule.
        for (s in listOf("clear notifications", "clear all notifications", "clear my notifications", "dismiss notifications",
            "delete my notifications", "close all notifications", "remove the notifications"))
            assertNotEquals(s, FastIntent.CloseShade, c(s))
        assertEquals("opening is still opening", FastIntent.Notifications, c("open the notification panel"))
    }

    /** S20: "close this app" went to the agent, which pressed Recents' "Close all". */
    @Test fun close_this_app_is_a_fast_command() {
        for (s in listOf("close this app", "Close the app.", "exit this app", "leave this app", "quit the application", "close app",
            "please close this app for me"))
            assertEquals(s, FastIntent.CloseApp(null), c(s))
        assertEquals(FastIntent.CloseApp("WhatsApp"), c("close WhatsApp"))      // the router checks it is an installed app
        assertEquals(FastIntent.CloseApp("calculator"), c("Close the calculator app"))
        // Things people close that are not apps, and anything about ALL apps, stay with the agent.
        for (s in listOf("close the door", "close all apps", "close this", "close it", "close the tab", "close the keyboard",
            "close my eyes", "leave the group", "close everything"))
            assertTrue("$s -> ${c(s)}", c(s) !is FastIntent.CloseApp)
        assertNull("compound → agent", c("close whatsapp and open telegram"))
    }

    /** L10, L11, L04: "on the torch" took 7 s, "off the light" 13 s and Twi "sɔ kanea no" 30 s through the cloud model. */
    @Test fun ghanaian_english_switches_without_a_verb() {
        for (s in listOf("on the torch", "On the flashlight.", "on the light", "put on the torch", "put on the light",
            "abeg on the torch", "chale on the light for me", "turn on the light", "light on", "open the light",
            "sɔ kanea no", "sɔ kanea", "so kanea no"))
            assertEquals(s, FastIntent.FlashlightOn, c(s))
        for (s in listOf("off the light", "off the torch", "off the flashlight", "put off the light", "put off the torch",
            "switch off the light", "light off", "close the light", "close the torch", "dum kanea no", "dum kanea"))
            assertEquals(s, FastIntent.FlashlightOff, c(s))
        assertEquals(FastIntent.BluetoothOn, c("on the bluetooth"))
        assertEquals(FastIntent.BluetoothOff, c("off the bluetooth"))
        assertEquals(FastIntent.BluetoothOn, c("put on bluetooth"))
        assertEquals(FastIntent.WifiOn, c("on the wifi"))
        assertEquals(FastIntent.WifiOff, c("off the wi-fi"))
        // "light" is the torch ONLY as an on/off command: the camera's question and ordinary sentences are untouched.
        assertEquals(FastIntent.Camera("color"), c("is the light on"))
        for (s in listOf("the light is too bright", "light a candle", "what is light", "on the way", "off the record"))
            assertTrue("$s -> ${c(s)}", c(s) != FastIntent.FlashlightOn && c(s) != FastIntent.FlashlightOff)
    }

    /** SEC03: "read my last four text messages" read one. */
    @Test fun a_count_of_texts_is_kept() {
        assertEquals(FastIntent.LastSmsN(4), c("read my last four text messages"))
        assertEquals(FastIntent.LastSmsN(3), c("Read my last 3 texts"))
        assertEquals(FastIntent.LastSmsN(2), c("read my two latest texts"))
        assertEquals(FastIntent.LastSmsN(5), c("check my last five sms"))
        assertEquals(FastIntent.LastSmsN(3), c("read my last few texts"))
        assertEquals("never more than ten", FastIntent.LastSmsN(10), c("read my last 25 text messages"))
        for (s in listOf("Read my last text", "read my last 1 text message", "any new texts"))
            assertEquals(s, FastIntent.LastSms, c(s))
        assertNull("a time window is not a count", FastIntentClassifier.smsCount("check my texts from the last 2 days"))
        assertEquals(4, FastIntentClassifier.smsCount("read 4 text messages"))
    }
}
