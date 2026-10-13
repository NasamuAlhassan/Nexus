package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.journal.EffectLedger
import com.tekyerema.agent.journal.JournalBoot
import com.tekyerema.agent.journal.TaskJournal
import com.tekyerema.agent.journal.WorldCheck
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import com.tekyerema.tools.TapTool
import com.tekyerema.tools.TypeTool
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

/**
 * P1-14 through the real engine loop: a "done" from the Settings LIST after "go to the battery page" is refused once and then
 * reported as unconfirmed, even though the only step verified (the app stayed in front); the same "done" from the Battery
 * page completes.
 */
class DoneCheckEngineTest {
    private lateinit var dir: File
    private lateinit var ctrl: FakeController

    private fun el(id: Int, label: String, y: Int, clickable: Boolean = true) =
        UiElement(id, "android.widget.TextView", label, Box(0, y, 1080, y + 100), clickable = clickable)

    /** Android's Settings list: a "Battery" row is on it, but it is not the Battery page. */
    private fun settingsList() = UiSnapshot("com.android.settings", "Settings", listOf(
        el(0, "Settings", 100, clickable = false), el(1, "Search settings", 220), el(2, "Network & internet", 400),
        el(3, "Connected devices", 520), el(4, "Battery", 640), el(5, "Display", 760)), 1L, appName = "Settings")

    private fun batteryPage() = UiSnapshot("com.android.settings", "Battery", listOf(
        el(0, "Navigate up", 60), el(1, "Battery", 100, clickable = false), el(2, "100%", 300, clickable = false),
        el(3, "Battery Saver", 500), el(4, "Adaptive Battery", 620)), 1L, appName = "Settings")

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("tky-done").toFile()
        SecureStore.keyProvider = { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        loadAppConfig()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        TaskJournal.load(File(dir, "tasks")); EffectLedger.load(File(dir, "tasks"))
        JournalBoot.world = WorldCheck.SCREEN_ONLY
        ctrl = FakeController(settingsList())
        AgentEngine.installForTest(FakeContext(dir), listOf(TapTool, TypeTool))
        AgentEngine.attachController(ctrl)
    }

    @After fun tearDown() {
        AgentEngine.detachController()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        JournalBoot.world = WorldCheck.SCREEN_ONLY
        SecureStore.keyProvider = null
        AppConfig.loadFrom(emptyList())
        dir.deleteRecursively()
    }

    private fun state() = AgentEngine.state.value
    private fun infoLines() = state().log.filter { it.kind == LogEntry.Kind.INFO }.map { it.text }

    /** A tap that verifies on the app alone — the way a planner's tap on the wrong row still "verifies". */
    private fun tapRow(id: Int) = tool("tap", JSONObject().put("element_id", id), expects = "foreground_is: com.android.settings")

    @Test fun done_from_the_settings_list_is_refused_once_then_unconfirmed() = runTest {
        // The tap lands on "Display" (the planner picked the wrong row); the fake app stays on the list.
        val brain = ScriptedBrain({ tapRow(5) }, { AgentDecision.Done("I opened the battery page.") }, { AgentDecision.Done("I opened the battery page.") })
        val job = launch { AgentEngine.runForTest("open settings and go to the battery page", brain) }
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.COMPLETED_UNVERIFIED, state().status)
        assertTrue(infoLines().joinToString("\n"), infoLines().any { it.contains("not on the final screen ('Settings')") && it.contains("not done yet") })
        // The planner was told once, with the screen's own name, and asked again.
        assertTrue(brain.seen.size >= 3)
        assertTrue(brain.seen.last().history.any { it.contains("The screen in front is \"Settings\", not the battery page.") })
    }

    @Test fun done_from_the_battery_page_completes() = runTest {
        ctrl.onClick = { e -> if (e.label == "Battery") ctrl.screen = batteryPage() }
        val brain = ScriptedBrain({ tapRow(4) }, { AgentDecision.Done("You're on the battery page.") })
        val job = launch { AgentEngine.runForTest("open settings and go to the battery page", brain) }
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.COMPLETED, state().status)
        assertTrue(infoLines().none { it.contains("not done yet") })
    }

    @Test fun a_request_that_names_no_page_is_untouched() = runTest {
        val brain = ScriptedBrain({ tapRow(5) }, { AgentDecision.Done("Done.") })
        val job = launch { AgentEngine.runForTest("tap display", brain) }
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.COMPLETED, state().status)
    }
}
