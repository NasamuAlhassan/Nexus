package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.Routines
import com.tekyerema.agent.StepAside
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overnight [S] fast-path intents (docs/agent/research/overnight_features.md): each is a whole utterance, so a
 * compound request still reaches the agent, and none of them shadows an existing fast intent.
 */
class OvernightFastPathTest {
    private fun c(s: String) = FastIntentClassifier.classify(s)
    @After fun reset() { Routines.clearForTest(); com.tekyerema.android.Relations.clear() }

    @Test fun catch_up_phrasings() {
        for (s in listOf("What did I miss?", "What have I missed", "Catch me up.", "Any new messages?", "Did I miss anything?",
            "Do I have any new notifications?", "Tekyerema, what did I miss?", "Nkra bɛn na aba?"))
            assertEquals(s, FastIntent.Digest("last"), c(s))
        for (s in listOf("Read my messages.", "Check my notifications", "Read all my new messages", "Summarise my notifications"))
            assertEquals(s, FastIntent.Digest("recent"), c(s))
        assertEquals(FastIntent.Digest("today"), c("What did I miss today?"))
        // A person, or a second instruction, is not the catch-up.
        assertNull(c("What did I miss and call Ama back"))
        assertFalse(c("read my messages from Ama") is FastIntent.Digest)
        // Unchanged neighbours.
        assertEquals(FastIntent.LastSms, c("read my last text message"))
        assertEquals(FastIntent.Notifications, c("open my notifications"))
        assertEquals(FastIntent.CallHistory(false), c("did anyone call me"))
    }

    @Test fun relations_by_voice() {
        assertEquals(FastIntent.SetRelation("mother", "mother", "Ama Mensah", true), c("Remember that Ama Mensah is my mother."))
        assertEquals(FastIntent.SetRelation("mother", "mum", "Akos", false), c("My mum is Akos"))
        assertEquals(FastIntent.RelationQuery("mother"), c("Who is my mother?"))
        assertEquals(FastIntent.RelationQuery(""), c("who are my family"))
        assertEquals(FastIntent.ForgetRelation("mother"), c("Forget who my mother is"))
        // Not relations: the W8 memory rule and ordinary talk keep their meaning.
        assertEquals(FastIntent.Forget("person", "Ama"), c("forget Ama"))
        assertFalse(c("my mother is sick") is FastIntent.SetRelation)
    }

    @Test fun help_resume_describe() {
        assertEquals(FastIntent.Help(true), c("What can I say here?"))
        assertEquals(FastIntent.Help(false), c("What can you do?"))
        assertFalse(c("help me") is FastIntent.Help)
        assertEquals(FastIntent.Resume, c("Where did I leave off?"))
        assertEquals(FastIntent.DescribeScreen(null, true), c("Describe this picture."))
        assertEquals(FastIntent.DescribeScreen(null, true), c("What's in this photo?"))
        assertEquals(FastIntent.DescribeScreen(null, false), c("Describe the screen"))
        assertEquals(FastIntent.DescribeScreen("how many people are in this photo", true), c("How many people are in this photo?"))
        // The camera reader keeps its own phrasings.
        assertEquals(FastIntent.Camera("describe"), c("what's in front of me"))
        assertTrue(StepAside.forFast(FastIntent.DescribeScreen(null, true)))   // our own screen is never the picture
    }

    @Test fun battery_data_settings_and_installed_apps() {
        assertEquals(FastIntent.SaverQuery("battery"), c("Is battery saver on?"))
        assertEquals(FastIntent.SaverQuery("data"), c("is data saver on"))
        assertEquals(FastIntent.SaveHelp("data"), c("Help me save data"))
        assertEquals(FastIntent.SaveHelp("data"), c("I'm running out of data"))
        assertEquals(FastIntent.SaveHelp("battery"), c("My battery is dying"))
        assertEquals(FastIntent.Battery, c("What's my battery?"))
        assertEquals(FastIntent.QuickToggle("battery_saver", true), c("turn on battery saver"))
        assertEquals(FastIntent.QuickToggle("data_saver", true), c("Turn on data saver"))
        assertEquals(FastIntent.OpenSetting("bluetooth"), c("Open Bluetooth settings"))
        assertEquals(FastIntent.OpenSetting("wi-fi"), c("take me to the Wi-Fi settings"))
        assertEquals(FastIntent.OpenApp("settings"), c("open settings"))
        assertEquals(FastIntent.OpenApp("whatsapp"), c("open WhatsApp"))
        assertEquals(FastIntent.AppInstalled("whatsapp"), c("Is WhatsApp installed?"))
        assertEquals(FastIntent.AppInstalled("telegram"), c("do I have Telegram installed"))
        assertNull(c("open bluetooth settings and turn it on"))
    }

    @Test fun reminders_in_minutes_reach_the_schedule_path() {
        val s = "Remind me in 20 minutes to check the food."
        assertEquals(FastIntent.CreateSchedule(s), c(s))
    }

    @Test fun routines_are_taught_and_run_by_voice() {
        val s = "When I say good morning, tell me the time, the weather and my messages."
        assertEquals(FastIntent.CreateRoutine("good morning", listOf("tell me the time", "the weather", "my messages")), c(s))
        assertEquals(Routines.Definition("good night", listOf("turn on do not disturb", "lock the phone")),
            Routines.parseDefinition("when I say good night turn on do not disturb and lock the phone"))
        assertEquals(Routines.Definition("evening", listOf("reads the time", "my messages")),
            Routines.parseDefinition("create a routine called evening that reads the time and my messages"))
        assertNull("\"home\" is already a command", Routines.parseDefinition("create a routine called home that reads the time"))
        // Before it exists, "good morning" is only a greeting (the agent's).
        assertNull(c("good morning"))
        Routines.put(Routines.Routine("good morning", listOf("tell me the time")))
        assertEquals(FastIntent.RunRoutine("good morning"), c("Good morning!"))
        assertEquals(FastIntent.RunRoutine("good morning"), c("run my good morning routine"))
        assertEquals(FastIntent.RunRoutine("good morning"), c("good morning, Tekyerema"))
        assertNull(c("good morning and call Ama"))
        assertEquals(FastIntent.ListRoutines, c("What routines do I have?"))
        assertEquals(FastIntent.DeleteRoutine("good morning"), c("delete the good morning routine"))
    }

    @Test fun a_routine_step_is_reading_or_a_reversible_setting_never_a_send_call_or_pay() {
        assertEquals(Routines.Step.Fast(FastIntent.Time, "tell me the time"), Routines.resolve("tell me the time"))
        assertEquals(Routines.Step.Fast(FastIntent.Time, "the time"), Routines.resolve("the time"))
        assertEquals(Routines.Step.Fast(FastIntent.Digest("recent"), "my messages"), Routines.resolve("my messages"))
        assertEquals(Routines.Step.Fast(FastIntent.QuickToggle("dnd", true), "turn on do not disturb"), Routines.resolve("turn on do not disturb"))
        assertEquals(Routines.Step.Fast(FastIntent.Time, "read the time"), Routines.resolve("reads the time"))
        assertEquals(Routines.Step.Web("the weather today in Ghana", "the weather"), Routines.resolve("the weather"))
        assertEquals(Routines.Step.Web("the news today", "read the news"), Routines.resolve("read the news"))
        for (s in listOf("send Ama a message", "call my mother", "pay my light bill", "take a photo", "buy airtime",
            "install WhatsApp", "delete my photos", "forget everything", "call the ambulance"))
            assertNull(s, Routines.resolve(s))
    }

    @Test fun routine_names_cannot_shadow_commands_or_confirmations() {
        for (n in listOf("yes", "stop", "cancel", "help", "what time is it", "lock the phone", "what can you do"))
            assertFalse(n, Routines.validName(n))
        assertTrue(Routines.validName("good morning"))
        // review #12: a routine "music" must not take over "play music", nor "camera" "launch camera"
        assertNull(Routines.match("play music", listOf("music")))
        assertNull(Routines.match("launch camera", listOf("camera")))
        assertEquals("music", Routines.match("start my music routine", listOf("music")))
        assertEquals("music", Routines.match("run music", listOf("music")))
        assertEquals("music", Routines.match("music", listOf("music")))
        assertNull(Routines.parseDefinition("if I say yes, will you send it"))
    }
}
