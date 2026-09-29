package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.agent.journal.EffectLedger
import com.tekyerema.agent.journal.JournalBoot
import com.tekyerema.agent.journal.TaskJournal
import com.tekyerema.agent.journal.WorldCheck
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.BankSession
import com.tekyerema.money.BankUssdTool
import com.tekyerema.money.MoMoRetry
import com.tekyerema.money.MoneyLimits
import com.tekyerema.operator.MemoryOperatorStore
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.tools.MoMoSendTool
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import javax.crypto.spec.SecretKeySpec

/**
 * Money safety round 2 through the REAL AgentEngine loop (RoutingBrain in front, as on the phone): a send over the user's
 * limit is refused before any read-back; "try again" re-proposes the same send through the full confirmation; the guided
 * bank menu reads each screen, types only what the user says (a long number read back first) and hands over at the
 * payment confirmation without submitting it.
 */
class MoneyRound2LoopTest {
    private lateinit var dir: File
    private lateinit var ctrl: FakeController

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("tky-money2").toFile()
        val tasks = File(dir, "tasks")
        SecureStore.keyProvider = { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        loadAppConfig()
        OperatorPolicy.resetForTest(MemoryOperatorStore())
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        TaskJournal.load(tasks); EffectLedger.load(tasks)
        JournalBoot.world = WorldCheck.SCREEN_ONLY
        ctrl = FakeController(home())
        MoMoRetry.clear(); BankSession.end()
    }

    @After fun tearDown() {
        AgentEngine.detachController()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        SecureStore.keyProvider = null
        OperatorPolicy.resetForTest()
        MoMoRetry.clear(); BankSession.end()
        com.tekyerema.voice.SecureStep.end()
        com.tekyerema.agent.helper.HelperGate.resetForTest()
        AppConfig.loadFrom(emptyList())
        dir.deleteRecursively()
    }

    private fun install(vararg tools: com.tekyerema.tools.Tool) {
        AgentEngine.installForTest(FakeContext(dir), tools.toList())
        AgentEngine.attachController(ctrl)
    }

    /** Let the loop run up to its next wait for the user — without running out a question's 2-minute timeout. */
    private fun TestScope.step() { advanceTimeBy(20_000); runCurrent() }

    private fun state() = AgentEngine.state.value
    private fun result() = state().log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text
    private fun router(local: ScriptedBrain = ScriptedBrain()) = RoutingBrain(local, null) { false }

    private fun home() = UiSnapshot("com.sec.android.app.launcher", "Home", listOf(
        UiElement(1, "android.widget.TextView", "Phone", Box(0, 1800, 200, 2000), clickable = true),
        UiElement(2, "android.widget.TextView", "Messages", Box(200, 1800, 400, 2000), clickable = true)), 1L, appName = "the home screen")

    /** The phone's own USSD dialog: the bank's words, the entry box, Cancel and Send. */
    private fun ussd(text: String) = UiSnapshot("com.android.phone", null, listOf(
        UiElement(10, "android.widget.TextView", text, Box(80, 700, 1000, 1100)),
        UiElement(11, "android.widget.EditText", null, Box(80, 1120, 1000, 1220), clickable = true, editable = true),
        UiElement(12, "android.widget.Button", "Cancel", Box(80, 1240, 500, 1340), clickable = true),
        UiElement(13, "android.widget.Button", "Send", Box(540, 1240, 1000, 1340), clickable = true)), 1L, appName = "Phone")

    @Test fun a_send_over_the_limit_is_refused_before_any_read_back() = runTest {
        install(MoMoSendTool)
        MoneyLimits.set(MoneyLimits.Kind.PER_SEND, BigDecimal("200"))
        val brain = ScriptedBrain({ tool("momo_send", JSONObject().put("number", "0553238011").put("amount", "500")) })
        val job = launch { AgentEngine.runForTest("send 500 cedis to 0553238011", router(brain)) }
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.CANNOT_DO, state().status)
        assertEquals(MoneyLimits.perSendLine(BigDecimal("200")), result())
        assertTrue("never read back for a yes", state().log.none { it.kind == LogEntry.Kind.CONFIRM })
        assertEquals("no planner works round the limit", 1, brain.seen.size)
    }

    @Test fun try_again_proposes_the_same_send_and_it_is_read_back_in_full() = runTest {
        val momo = FakeMomoSend()
        install(momo)
        MoMoRetry.remember("0553238011", "0.1", null)
        val goal = MoMoRetry.rewrite("try again")!!
        val local = ScriptedBrain()
        val job = launch { AgentEngine.runForTest(goal, router(local)) }
        advanceUntilIdle()
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        val p = state().pendingConfirmation!!
        assertTrue(p, p.startsWith("Send 0.1 cedis to 0 5 5, 3 2 3, 8 0 1 1, an MTN number, with Mobile Money?"))
        assertFalse(p, p.contains("didn't say"))
        assertEquals("nothing runs before the yes", 0, momo.sent.size)
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(listOf("0553238011"), momo.sent)
        assertEquals(AgentStatus.COMPLETED, state().status)
        assertTrue(result()!!, result()!!.startsWith("MTN says this sends 0.10 cedis to Zelia Awini"))
        assertEquals("decided on the phone, no brain", 0, local.seen.size)
    }

    @Test fun the_bank_menu_is_read_the_user_chooses_and_the_payment_is_handed_over() = runTest {
        install(BankUssdTool, com.tekyerema.tools.AskUserTool)
        val menu = "Stanbic Mobile\n1. Transfer\n2. Balance"
        val account = "Enter account number"
        val amount = "Enter amount"
        val confirm = "Transfer GHS 200 to KOFI MENSAH 0123456789. Fee GHS 1.00\n1. Confirm\n2. Cancel"
        val next = mapOf(menu to account, account to amount, amount to confirm)
        ctrl.onClick = { e -> if (e.label == "Send") (ctrl.screen.elements.first().label)?.let { now -> next[now]?.let { ctrl.screen = ussd(it) } } }
        val local = ScriptedBrain()
        val job = launch { AgentEngine.runForTest("send money from my Stanbic account", router(local)) }
        step()
        // Opening the bank's code is confirmed first, and says it is guided.
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        assertEquals("Open Stanbic Bank's USSD menu, star 7 1 5 hash? It's guided: I read each screen, you choose, and you confirm " +
            "the payment and enter your PIN yourself.", state().pendingConfirmation)
        ctrl.screen = ussd(menu)   // the dialer shows the bank's first menu
        AgentEngine.confirm(true, state().confirmationSeq)
        step()
        // Each screen is the question; the user's choice is typed as they said it (a menu choice runs at once).
        assertEquals(state().log.joinToString("\n") { it.kind.name + ": " + it.text }, AgentStatus.ASKING, state().status)
        assertEquals("Stanbic Bank says: “Stanbic Mobile; 1. Transfer; 2. Balance”. What should I enter? You can say cancel to stop.",
            state().pendingQuestion)
        AgentEngine.answer("option 1")
        step()
        assertEquals("Stanbic Bank says: “Enter account number”. What should I enter?", state().pendingQuestion)
        AgentEngine.answer("0 1 2 3 4 5 6 7 8 9")
        step()
        // A long number is read back for a yes before it is entered.
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        assertEquals("Stanbic Bank's screen says “Enter account number”. Enter 0 1 2, 3 4 5, 6 7 8 9 and send it?", state().pendingConfirmation)
        AgentEngine.confirm(true, state().confirmationSeq)
        step()
        assertEquals("Stanbic Bank says: “Enter amount”. What should I enter?", state().pendingQuestion)
        AgentEngine.answer("200")
        step()
        assertEquals(state().log.joinToString("\n") { it.kind.name + ": " + it.text },
            "Stanbic Bank's screen says “Enter amount”. Enter 200 and send it?", state().pendingConfirmation)
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        // The payment confirmation is read out and handed over — never confirmed by Tɛkyerɛma.
        assertEquals(AgentStatus.COMPLETED, state().status)
        assertTrue(result()!!, result()!!.startsWith("Stanbic Bank's screen says: “Transfer GHS 200 to KOFI MENSAH 0123456789. Fee GHS 1.00; " +
            "1. Confirm; 2. Cancel”. If that's right, confirm it on the screen and enter your PIN yourself"))
        assertEquals(listOf("type:1", "click:Send", "type:0123456789", "click:Send", "type:200", "click:Send"), ctrl.actions)
        assertTrue("no microphone opened by itself at the payment", com.tekyerema.voice.SecureStep.active())
        assertEquals("no model decided any step", 0, local.seen.size)
    }
}
