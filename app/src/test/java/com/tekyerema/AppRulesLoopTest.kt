package com.tekyerema

import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.journal.EffectLedger
import com.tekyerema.agent.journal.JournalBoot
import com.tekyerema.agent.journal.TaskJournal
import com.tekyerema.agent.journal.WorldCheck
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import com.tekyerema.operator.AppRule
import com.tekyerema.operator.MemoryOperatorStore
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.policy.ScreenPrivacy
import com.tekyerema.tools.TapTool
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolParam
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.TypeTool
import com.tekyerema.verifier.VerificationResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

/**
 * D8 through the REAL AgentEngine loop: an app the user said "never touch" is neither read (read_state refused, the screen
 * the model gets is blank) nor acted on; a "guide only" app is read, but a tap or typing there is blocked with the
 * guide-only line — the same gate (ScreenPrivacy.appRule) the MCP remote path goes through.
 */
class AppRulesLoopTest {
    private lateinit var dir: File
    private lateinit var ctrl: FakeController
    private val reader = FakeReadState()

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("tky-apprules").toFile()
        val tasks = File(dir, "tasks")
        SecureStore.keyProvider = { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        loadAppConfig()
        OperatorPolicy.resetForTest(MemoryOperatorStore())
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        TaskJournal.load(tasks); EffectLedger.load(tasks)
        JournalBoot.world = WorldCheck.SCREEN_ONLY
        ctrl = FakeController(Screens.chat(composer = null, bubbles = listOf("my account number is 5566")))
        AgentEngine.installForTest(FakeContext(dir), listOf(TapTool, TypeTool, reader))
        AgentEngine.attachController(ctrl)
    }

    @After fun tearDown() {
        AgentEngine.detachController()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        SecureStore.keyProvider = null
        OperatorPolicy.resetForTest()
        AppConfig.loadFrom(emptyList())
        dir.deleteRecursively()
    }

    private fun tapSend() = tool("tap", JSONObject().put("element_id", Screens.SEND_ID))
    private fun typeHi() = tool("type", JSONObject().put("element_id", Screens.COMPOSER_ID).put("text", "hi"))

    @Test fun a_never_touch_app_is_neither_read_nor_operated() = runTest {
        OperatorPolicy.setAppRule(Screens.APP, "WhatsApp", AppRule.NEVER)
        val brain = ScriptedBrain({ tool("read_state", JSONObject()) }, { tapSend() }, { typeHi() }, { AgentDecision.Done("done") })
        val job = launch { AgentEngine.runForTest("read my WhatsApp chat with Ama", brain) }
        advanceUntilIdle(); job.join()
        assertEquals("read_state must never run inside a never-touch app", 0, reader.calls)
        assertTrue(ctrl.actions.toString(), ctrl.actions.none { it.startsWith("click:") || it.startsWith("type:") })
        // What the model saw: which app is in front, and nothing on it.
        for (ctx in brain.seen) assertFalse(ctx.screen.toString(), ctx.screen.toString().contains("5566"))
        val history = brain.seen.last().history
        assertTrue(history.toString(), history.any { it.startsWith("DENIED APP") })
        assertTrue(history.toString(), history.any { it.startsWith("read_state") && it.contains("BLOCKED: ${ScreenPrivacy.DENIED}") })
    }

    @Test fun a_guide_only_app_is_read_but_taps_and_typing_are_blocked_with_the_guide_only_line() = runTest {
        OperatorPolicy.setAppRule(Screens.APP, "WhatsApp", AppRule.GUIDE)
        val brain = ScriptedBrain({ tool("read_state", JSONObject()) }, { tapSend() }, { typeHi() }, { AgentDecision.Done("Tap Send.") })
        val job = launch { AgentEngine.runForTest("help me send hi to Ama on WhatsApp", brain) }
        advanceUntilIdle(); job.join()
        assertEquals(1, reader.calls)
        assertTrue(ctrl.actions.toString(), ctrl.actions.none { it.startsWith("click:") || it.startsWith("type:") })
        assertTrue(brain.seen[0].screen.toString().contains("5566"))   // read and explained
        val history = brain.seen.last().history
        assertTrue(history.toString(), history.any { it.startsWith("tap") && it.contains("BLOCKED: ${ScreenPrivacy.GUIDE_ONLY}") })
        assertTrue(history.toString(), history.any { it.startsWith("type") && it.contains("BLOCKED: ${ScreenPrivacy.GUIDE_ONLY}") })
    }

    @Test fun without_a_rule_the_same_steps_run() = runTest {
        val brain = ScriptedBrain({ tool("read_state", JSONObject()) }, { AgentDecision.Done("done") })
        val job = launch { AgentEngine.runForTest("read my WhatsApp chat with Ama", brain) }
        advanceUntilIdle(); job.join()
        assertEquals(1, reader.calls)
        assertTrue(brain.seen[0].screen.toString().contains("5566"))
    }
}

/** Stands in for read_state (a SCREEN_READER by name): counts how often it actually ran. */
class FakeReadState : Tool {
    var calls = 0
    override val name = "read_state"
    override val description = "Read the screen."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = emptyList<ToolParam>()
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult { calls++; return ToolResult(true, "the screen", channel = "none") }
    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        VerificationResult(result.success, "observation")
}
