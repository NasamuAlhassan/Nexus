package com.tekyerema

import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.journal.EffectLedger
import com.tekyerema.agent.journal.JournalBoot
import com.tekyerema.agent.journal.TaskJournal
import com.tekyerema.agent.journal.WorldCheck
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.MoMoSendTool
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolParam
import com.tekyerema.tools.ToolResult
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
 * Money safety (2026-09-29) through the REAL AgentEngine loop: a spoken fix to one group of the number is applied
 * exactly and read back in full before anything runs, and the task ends at the Mobile Money step — MTN's confirmation
 * is the reply, said before the PIN, and nothing plans past it.
 */
class MoneySafetyLoopTest {
    private lateinit var dir: File
    private lateinit var ctrl: FakeController
    private val momo = FakeMomoSend()

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("tky-money").toFile()
        val tasks = File(dir, "tasks")
        SecureStore.keyProvider = { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        loadAppConfig()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        TaskJournal.load(tasks); EffectLedger.load(tasks)
        JournalBoot.world = WorldCheck.SCREEN_ONLY
        ctrl = FakeController(Screens.chatList())
        AgentEngine.installForTest(FakeContext(dir), listOf(momo))
        AgentEngine.attachController(ctrl)
    }

    @After fun tearDown() {
        AgentEngine.detachController()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        SecureStore.keyProvider = null
        AppConfig.loadFrom(emptyList())
        dir.deleteRecursively()
    }

    private fun state() = AgentEngine.state.value
    private fun result() = state().log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text
    private fun send(number: String) = tool("momo_send", JSONObject().put("number", number).put("amount", "0.1").put(MoMoSendTool.ARG_SIM, "mtn"))

    @Test fun a_corrected_group_is_read_back_in_full_and_only_the_corrected_number_is_sent() = runTest {
        val brain = ScriptedBrain({ send("0553238011") })
        val job = launch { AgentEngine.runForTest("send 0.1 cedis to 0553238011", brain) }
        advanceUntilIdle()
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        assertTrue(state().pendingConfirmation!!, state().pendingConfirmation!!.contains("0 5 5, 3 2 3, 8 0 1 1, an MTN number"))
        // The user fixes the middle group instead of saying yes.
        AgentEngine.revise("no, the middle is 4 5 6")
        advanceUntilIdle()
        // Nothing ran; the SAME action with the fixed number is asked again, in full — without a planning call.
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        val again = state().pendingConfirmation!!
        assertTrue(again, again.contains("0 5 5, 4 5 6, 8 0 1 1, an MTN number"))
        assertFalse("the user said this number: no 'you didn't say it' notice", again.contains("didn't say"))
        assertEquals(0, momo.sent.size)
        assertEquals(1, brain.seen.size)
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(listOf("0554568011"), momo.sent)
        // The task ended at the PIN with MTN's own words — no planning past it.
        assertEquals(AgentStatus.COMPLETED, state().status)
        assertTrue(result()!!, result()!!.startsWith("MTN says this sends 0.10 cedis to Zelia Awini"))
        assertEquals(1, brain.seen.size)
    }

    @Test fun a_cancelled_send_ends_the_task_with_what_mtn_showed() = runTest {
        momo.cancel = true
        val brain = ScriptedBrain({ send("0553238011") })
        val job = launch { AgentEngine.runForTest("send 0.1 cedis to 0553238011", brain) }
        advanceUntilIdle()
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.CANNOT_DO, state().status)
        assertTrue(result()!!, result()!!.endsWith("so I pressed Cancel. Nothing was sent."))
        assertEquals("no planner improvises inside a money menu", 1, brain.seen.size)
    }
}

/** momo_send's shape (name, tier, read-back) with the USSD walk replaced by a scripted MTN screen. */
class FakeMomoSend : Tool {
    val sent = ArrayList<String>()
    var cancel = false
    override val name = "momo_send"
    override val description = "Send money."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER3
    override val params = listOf(ToolParam("number", "string", "number"), ToolParam("amount", "string", "amount"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        sent.add(args.optString("number"))
        val amount = if (cancel) "GHS 10.00" else "GHS 0.1"
        val v = com.tekyerema.tools.MtnConfirm.verdict("Transfer to AMA MENSAH for $amount with Reference: Payment. Fee is GHS 0.00, " +
            "Tax amount is GHS 0.00. Total Amount is GHS 0.10. Enter MM # for next", args.optString("amount"), args.optString("number"), true)
        return when (v) {
            is com.tekyerema.tools.MtnConfirm.Verdict.HandOff -> ToolResult(true, v.spoken, channel = "native")
            is com.tekyerema.tools.MtnConfirm.Verdict.Cancel ->
                ToolResult(false, com.tekyerema.tools.MtnConfirm.cancelledLine(v.why, true), channel = "native", recoverable = false)
        }
    }
    override fun confirmationPrompt(args: JSONObject) = MoMoSendTool.confirmationPrompt(args)
}
