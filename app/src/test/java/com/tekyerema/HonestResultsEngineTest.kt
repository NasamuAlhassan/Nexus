package com.tekyerema

import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.helper.HelperGate
import com.tekyerema.agent.helper.HelperOffer
import com.tekyerema.agent.helper.LastTask
import com.tekyerema.agent.journal.EffectLedger
import com.tekyerema.agent.journal.JournalBoot
import com.tekyerema.agent.journal.TaskJournal
import com.tekyerema.agent.journal.WorldCheck
import com.tekyerema.android.Relations
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.SendEvidence
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

/**
 * Honest results + the helper offer, through the REAL AgentEngine loop (EngineLoopHarness): the spoken result of a send is
 * what the screen showed, never the planner's "I sent it"; two failures in a row add the helper once.
 */
class HonestResultsEngineTest {
    private lateinit var dir: File
    private lateinit var ctrl: FakeController
    private val sms = EvidenceSmsTool()

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("tky-honest").toFile()
        SecureStore.keyProvider = { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        loadAppConfig()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        TaskJournal.load(File(dir, "tasks")); EffectLedger.load(File(dir, "tasks"))
        JournalBoot.world = WorldCheck.SCREEN_ONLY
        HelperGate.resetForTest(); HelperOffer.resetForTest(); LastTask.clear(); Relations.clear()
        ctrl = FakeController(Screens.chat(composer = "see you at 6"))
        AgentEngine.installForTest(FakeContext(dir), listOf(TapTool, TypeTool, sms))
        AgentEngine.attachController(ctrl)
    }

    @After fun tearDown() {
        AgentEngine.detachController()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        HelperGate.resetForTest(); HelperOffer.resetForTest(); LastTask.clear(); Relations.clear()
        SecureStore.keyProvider = null
        AppConfig.loadFrom(emptyList())
        dir.deleteRecursively()
    }

    private fun state() = AgentEngine.state.value
    private fun result() = state().log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text
    private fun tapSend() = tool("tap", JSONObject().put("element_id", Screens.SEND_ID), expects = "element_present: see you at 6")

    @Test fun a_gui_send_is_reported_from_the_bubble_not_the_planners_words() = runTest {
        ctrl.onClick = { e -> if (e.label == "Send") ctrl.screen = Screens.chat(composer = null, bubbles = listOf("see you at 6")) }
        val job = launch { AgentEngine.runForTest("send Ama see you at 6", ScriptedBrain({ tapSend() }, { AgentDecision.Done("Sent it to Ama.") })) }
        advanceUntilIdle()
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.COMPLETED, state().status)
        assertEquals("WhatsApp shows your message to Ama — 'see you at 6' — in the chat.", result())
    }

    @Test fun a_send_the_chat_never_showed_is_not_claimed() = runTest {
        // The box empties but no bubble appears (the app swallowed it): the planner still says "Sent".
        ctrl.onClick = { e -> if (e.label == "Send") ctrl.screen = Screens.chat(composer = null) }
        val job = launch { AgentEngine.runForTest("send Ama see you at 6", ScriptedBrain({ tapSend() }, { AgentDecision.Done("Sent it to Ama.") })) }
        advanceUntilIdle()
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.COMPLETED_UNVERIFIED, state().status)
        assertEquals(SendEvidence.line(SendEvidence.Seen.NotSeen, "WhatsApp", "Ama"), result())
        assertTrue(result()!!.contains("can't confirm"))
    }

    @Test fun send_sms_speaks_its_own_evidence_and_the_rest_of_the_summary_stays() = runTest {
        // (ClaimCheck, 2026-09-29: this fixture used to claim "and set an alarm for 6" with no alarm step — now refused as a
        // false claim; the remainder here is a true sentence.)
        val job = launch { AgentEngine.runForTest("text Ama see you at 6", ScriptedBrain(
            { tool("send_sms", JSONObject().put("contact", "Ama").put("message", "see you at 6")) },
            { AgentDecision.Done("I've texted Ama. She'll see it when her phone is on.") })) }
        advanceUntilIdle()
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals("The phone network accepted your text to Ama — 'see you at 6'. She'll see it when her phone is on.", result())
    }

    @Test fun two_failures_in_a_row_offer_the_helper_once() = runTest {
        Relations.set("helper", "Kofi Mensah")
        suspend fun cannot(goal: String) = AgentEngine.runForTest(goal, ScriptedBrain({ AgentDecision.CannotDo("not_found", "I couldn't find that.") }),
            journal = false)
        cannot("turn on zorblax mode")
        assertEquals("I couldn't find that.", result())
        cannot("open the zorblax app")
        assertEquals("I couldn't find that. ${HelperOffer.LINE}", result())
        cannot("find zorblax")
        assertEquals("once per run of failures", "I couldn't find that.", result())
        // …and never at a PIN: the second failure ends with the PIN still pending.
        HelperOffer.resetForTest()
        cannot("a")
        HelperGate.atPin()
        cannot("b")
        assertEquals("I couldn't find that.", result())
    }
}

/** A native SMS tool whose result carries its evidence line, the way SendSmsTool's does. */
class EvidenceSmsTool : Tool {
    override val name = "send_sms"
    override val description = "Send an SMS."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER2
    override val params = listOf(ToolParam("contact", "string", "who", required = false), ToolParam("message", "string", "what"))
    override val verifiesOutcome = true
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val line = SendEvidence.radioLine(args.optString("contact"), args.optString("message"))
        return ToolResult(true, line, data = JSONObject().put("sent", true).put("shown", true).put("evidence", line), channel = "native")
    }
    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        VerificationResult(true, "the radio accepted the text")
    override fun confirmationPrompt(args: JSONObject) = "Text ${args.optString("contact")}: “${args.optString("message")}”?"
}
