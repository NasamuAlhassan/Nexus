package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.core.config.AppConfig
import com.tekyerema.operator.Capability
import com.tekyerema.operator.Disclosure
import com.tekyerema.operator.ExecutionMode
import com.tekyerema.operator.FastGate
import com.tekyerema.operator.FastHold
import com.tekyerema.operator.OpCommand
import com.tekyerema.operator.OperatorCommands
import com.tekyerema.operator.OperatorPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** WS-C: the operator's controls by voice/typing (local fast path, no model) and the fast path's own gate. */
class OperatorVoiceTest {
    @Before fun setUp() { AppConfig.loadFrom(emptyList()); OperatorPolicy.resetForTest() }
    @After fun tearDown() { OperatorPolicy.resetForTest() }

    private fun c(s: String) = OperatorCommands.classify(s)

    @Test fun offline_and_back() {
        for (s in listOf("Switch to offline mode", "go offline", "Offline mode.", "turn off the cloud", "cloud off",
            "don’t use the internet", "Please switch to offline mode", "keep everything on my phone"))
            assertEquals(s, OpCommand.Cloud(false), c(s))
        for (s in listOf("Use the cloud again", "use the cloud", "turn the cloud back on", "go back online", "online mode",
            "turn off offline mode", "exit offline mode"))
            assertEquals(s, OpCommand.Cloud(true), c(s))
        val off = OperatorCommands.applyControl(c("switch to offline mode")!!)!!
        assertFalse(OperatorPolicy.cloudAllowed)
        assertTrue(off.contains("Nothing leaves your phone"))
        OperatorCommands.applyControl(c("use the cloud again")!!)
        assertTrue(OperatorPolicy.cloudAllowed)
    }

    @Test fun modes_by_voice() {
        for (s in listOf("Ask me before every step", "ask me before each step", "always ask me before every step",
            "ask before every step", "ask me before you do anything", "step by step mode"))
            assertEquals(s, OpCommand.Mode(ExecutionMode.ASK_EVERY_STEP), c(s))
        for (s in listOf("guide only", "guide mode", "switch to guide only mode", "just guide me", "don't touch my phone"))
            assertEquals(s, OpCommand.Mode(ExecutionMode.GUIDE_ONLY), c(s))
        for (s in listOf("stop asking before every step", "standard mode", "stop guiding me", "you can do things for me again"))
            assertEquals(s, OpCommand.Mode(ExecutionMode.STANDARD), c(s))
        assertEquals(OpCommand.Mode(ExecutionMode.STANDARD, ambiguous = true), c("normal mode"))
        assertEquals(OpCommand.ModeQuery, c("What mode are you in?"))

        assertTrue(OperatorCommands.applyControl(c("ask me before every step")!!)!!.contains("before every step"))
        assertEquals(ExecutionMode.ASK_EVERY_STEP, OperatorPolicy.executionMode)
        OperatorCommands.applyControl(c("normal mode")!!)
        assertEquals(ExecutionMode.STANDARD, OperatorPolicy.executionMode)
        // Already normal: a bare "normal mode" is left to the ringer command ("put the phone on normal mode").
        assertNull(OperatorCommands.applyControl(c("normal mode")!!))
        assertTrue(OperatorCommands.applyControl(OpCommand.ModeQuery)!!.startsWith("I'm in normal mode"))
    }

    @Test fun data_usage_and_disclosure_questions() {
        for (s in listOf("What do you keep about me?", "what do you store", "What data do you keep about me",
            "what information are you keeping"))
            assertEquals(s, OpCommand.Inventory, c(s))
        for (s in listOf("How much have you used today?", "how much did you use today", "what's my usage",
            "how much budget is left", "how many tokens did you use today"))
            assertEquals(s, OpCommand.Usage, c(s))
        for (s in listOf("Where does my data go?", "Which AI do you use?", "what model are you using"))
            assertEquals(s, OpCommand.Providers, c(s))
        assertEquals(OpCommand.DeleteAll, c("Delete everything"))
        assertEquals(OpCommand.DeleteAll, c("wipe all my data"))
        assertEquals(OpCommand.Export(false), c("Export my history"))
        assertEquals(OpCommand.Export(false), c("send me my activity log"))
        assertEquals(OpCommand.Export(true), c("export my history with the message text"))
    }

    @Test fun scopes_by_voice() {
        assertEquals(OpCommand.Revoke(Capability.MESSAGES), c("Never send messages for me"))
        assertEquals(OpCommand.Revoke(Capability.MESSAGES), c("don't send any messages"))
        assertEquals(OpCommand.Revoke(Capability.CALLS), c("stop making calls for me"))
        assertEquals(OpCommand.Revoke(Capability.MONEY), c("never touch my money"))
        assertEquals(OpCommand.Revoke(Capability.SETTINGS), c("don't change my settings"))
        assertEquals(OpCommand.Revoke(Capability.WEB), c("stop searching the web"))
        assertEquals(OpCommand.Restore(Capability.MESSAGES), c("You can send messages again"))
        assertEquals(OpCommand.Restore(Capability.CALLS), c("you can make calls for me again"))
        OperatorCommands.applyControl(c("never touch my money")!!)
        assertEquals(setOf(Capability.MONEY), OperatorPolicy.revokedCapabilities)
        OperatorCommands.applyControl(c("you can handle money again")!!)
        assertTrue(OperatorPolicy.revokedCapabilities.isEmpty())
    }

    @Test fun ordinary_requests_are_not_operator_commands() {
        for (s in listOf("turn on airplane mode", "put my phone on normal mode", "what do you remember about me",
            "open WhatsApp", "send a message to Ama", "don't send it", "delete that message", "clear my history",
            "what time is it", "save my data", "turn on wifi", "am I online", "call Ama", "yes"))
            assertNull(s, c(s))
    }

    @Test fun our_phrases_dont_collide_with_the_fast_path_ones_we_leave_alone() {
        // The existing memory/ringer/connectivity commands still classify as before.
        assertEquals(FastIntent.MemoryQuery, FastIntentClassifier.classify("what do you remember about me", routines = false))
        assertTrue(FastIntentClassifier.classify("put my phone on normal mode", routines = false) is FastIntent.Ringer)
    }

    @Test fun delete_everything_is_asked_first() {
        val now = 1_000_000L
        assertFalse(OperatorCommands.deletePending(now))
        assertEquals(true, OperatorCommands.answerToDelete("Yes, delete everything"))
        assertEquals(true, OperatorCommands.answerToDelete("yes"))
        assertEquals(true, OperatorCommands.answerToDelete("aane"))
        assertEquals(false, OperatorCommands.answerToDelete("No"))
        assertEquals(false, OperatorCommands.answerToDelete("keep it"))
        assertNull(OperatorCommands.answerToDelete("what time is it"))
        OperatorCommands.askDelete(now)
        assertTrue(OperatorCommands.deletePending(now + 30_000))
        assertFalse(OperatorCommands.deletePending(now + OperatorCommands.CONFIRM_WINDOW_MS + 1))   // a late "yes" can't delete
        OperatorCommands.clearDelete()
        assertFalse(OperatorCommands.deletePending(now + 1))
    }

    @Test fun usage_is_spoken_plainly() {
        assertEquals("I haven't used the online AI today. There's no daily limit set.", OperatorCommands.usageLine(emptyMap(), 0, 0))
        val by = linkedMapOf("groq" to OperatorPolicy.ProviderUsage(12, 31_200), "gemini" to OperatorPolicy.ProviderUsage(2, 4_000))
        val line = OperatorCommands.usageLine(by, 50, 0)
        assertTrue(line, line.startsWith("Today I've made 14 calls to the online AI — 12 to Groq and 2 to Google Gemini, using about 35 thousand tokens."))
        assertTrue(line, line.endsWith("You have 36 of your 50 daily calls left."))
        assertEquals("1.5 million", OperatorCommands.spokenTokens(1_500_000))
        assertEquals("2.5 thousand", OperatorCommands.spokenTokens(2_500))
    }

    @Test fun data_disclosure_says_what_each_provider_does() {
        val s = Disclosure.spoken(cloudOn = true, providers = listOf("groq", "gemini"))
        assertTrue(s.startsWith("I use Groq, then Google Gemini."))
        assertTrue(s.contains("up to 30 days"))
        assertTrue(s.contains("people at Google may read it"))
        assertTrue(Disclosure.spoken(cloudOn = false, providers = listOf("groq")).contains("nothing you say leaves your phone"))
    }

    // ------------------------------------------------------------------ the fast path's own commands

    private fun gate(i: FastIntent, action: Boolean, v: OperatorPolicy.View) = FastGate.check(i, action, v)

    @Test fun normal_mode_runs_fast_commands_as_before() {
        val v = OperatorPolicy.View()
        assertEquals(FastHold.Proceed, gate(FastIntent.VolumeUp, true, v))
        assertEquals(FastHold.Proceed, gate(FastIntent.WifiOn, true, v))
        assertEquals(FastHold.Proceed, gate(FastIntent.Time, false, v))
    }

    @Test fun ask_every_step_hands_changes_to_the_agent_which_asks_first() {
        val v = OperatorPolicy.View(mode = ExecutionMode.ASK_EVERY_STEP)
        assertEquals(FastHold.ToAgent, gate(FastIntent.VolumeUp, true, v))
        assertEquals(FastHold.ToAgent, gate(FastIntent.OpenApp("WhatsApp"), true, v))
        assertEquals(FastHold.ToAgent, gate(FastIntent.RunRoutine("good morning"), false, v))
        assertEquals(FastHold.Proceed, gate(FastIntent.Battery, false, v))                               // a question
        assertEquals(FastHold.Proceed, gate(FastIntent.Preference("rate", "slower"), true, v))           // Tɛkyerɛma's own setting
    }

    @Test fun guide_only_explains_instead_of_acting() {
        val v = OperatorPolicy.View(mode = ExecutionMode.GUIDE_ONLY)
        val vol = gate(FastIntent.VolumeUp, true, v) as FastHold.Say
        assertTrue(vol.line.startsWith("I'm in guide-only mode, so I won't do it myself."))
        assertTrue(vol.line.contains("volume key"))
        assertFalse(vol.refusal)
        assertTrue((gate(FastIntent.OpenApp("Telegram"), true, v) as FastHold.Say).line.contains("Telegram"))
        assertEquals(FastHold.ToAgent, gate(FastIntent.Screenshot, true, v))   // no canned guide: the agent explains
        assertEquals(FastHold.Proceed, gate(FastIntent.Time, false, v))
    }

    @Test fun switched_off_capabilities_and_cloud_bind_the_fast_path_too() {
        val noSettings = OperatorPolicy.View(revoked = setOf(Capability.SETTINGS))
        val wifi = gate(FastIntent.WifiOn, true, noSettings) as FastHold.Say
        assertTrue(wifi.refusal); assertTrue(wifi.line.contains("you can change settings again"))
        assertTrue(gate(FastIntent.QuickToggle("airplane", true), true, noSettings) is FastHold.Say)
        assertTrue(gate(FastIntent.OpenSetting("bluetooth"), true, noSettings) is FastHold.Say)
        assertEquals(FastHold.Proceed, gate(FastIntent.VolumeUp, true, noSettings))
        val off = OperatorPolicy.View(cloudAllowed = false)
        assertTrue((gate(FastIntent.DescribeScreen(null, false), false, off) as FastHold.Say).line.contains("use the cloud again"))
        assertTrue(gate(FastIntent.Camera("describe"), false, off) is FastHold.Say)
        assertEquals(FastHold.Proceed, gate(FastIntent.Camera("text"), false, off))   // on-device OCR
    }
}
