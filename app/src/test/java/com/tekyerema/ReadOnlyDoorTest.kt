package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.ReadOnlyDoor
import com.tekyerema.agent.triggers.TriggerSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only requests from other doors (a schedule, the offline queue) run their fast-path handler, never an engine task. */
class ReadOnlyDoorTest {
    @Test fun only_phone_data_reads_are_allowed() {
        assertTrue(ReadOnlyDoor.allows(FastIntent.LastSms))
        assertTrue(ReadOnlyDoor.allows(FastIntent.LastSmsN(3)))
        assertTrue(ReadOnlyDoor.allows(FastIntent.Digest("last")))
        assertTrue(ReadOnlyDoor.allows(FastIntent.Digest("recent", messagesOnly = true)))
        assertTrue(ReadOnlyDoor.allows(FastIntent.CallHistory(missedOnly = true)))
        assertTrue(ReadOnlyDoor.allows(FastIntent.CalendarToday))
    }

    @Test fun nothing_that_acts_is_ever_allowed() {
        for (i in listOf<FastIntent?>(null, FastIntent.Notifications, FastIntent.Back, FastIntent.Home, FastIntent.LockScreen,
            FastIntent.Screenshot, FastIntent.VolumeUp, FastIntent.FlashlightOn, FastIntent.OpenApp("whatsapp"),
            FastIntent.PlayMusic("highlife"), FastIntent.ChatMessages("whatsapp", latestOnly = true), FastIntent.ReadMessage("Ama"),
            FastIntent.Time, FastIntent.Battery, FastIntent.CreateSchedule("remind me at 7 to pray"), FastIntent.ClearSchedules))
            assertFalse("$i", ReadOnlyDoor.allows(i))
    }

    @Test fun the_classifier_picks_the_reads_out_of_a_goal() {
        assertEquals(FastIntent.CalendarToday, ReadOnlyDoor.intentFor("what's on my calendar"))
        assertEquals(FastIntent.Digest("last"), ReadOnlyDoor.intentFor("what did I miss"))
    }

    @Test fun anything_else_goes_to_the_engine_as_before() {
        for (g in listOf("open WhatsApp", "go back", "take a screenshot", "open the notifications", "call Ama", "send a message to Ama",
            "what's the weather", "play some music", "set an alarm for 7", "", "   "))
            assertNull(g, ReadOnlyDoor.intentFor(g))
    }

    @Test fun a_scheduled_goal_must_still_pass_the_unattended_safety_check_first() {
        // The door sits AFTER TriggerSafety.isReadOnlyGoal in the scheduler: a read with an action riding on it never gets here.
        assertTrue(TriggerSafety.isReadOnlyGoal("what's on my calendar"))
        assertFalse(TriggerSafety.isReadOnlyGoal("what did I miss and reply to Ama"))
    }
}
