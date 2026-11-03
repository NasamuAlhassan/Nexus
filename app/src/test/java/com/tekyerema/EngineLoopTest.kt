package com.tekyerema

import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.journal.EffectLedger
import com.tekyerema.agent.journal.EffectState
import com.tekyerema.agent.journal.JournalBoot
import com.tekyerema.agent.journal.PauseReason
import com.tekyerema.agent.journal.ResumeOffer
import com.tekyerema.agent.journal.TaskJournal
import com.tekyerema.agent.journal.TaskState
import com.tekyerema.agent.journal.WorldCheck
import com.tekyerema.agent.journal.WorldResult
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.TapTool
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolParam
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.TypeTool
import com.tekyerema.verifier.VerificationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

/**
 * WS-A — engine-loop tests: the REAL AgentEngine.runTask (policy gate, confirmation, Secure Input boundary, effect
 * ledger, task journal) driven on the JVM by a scripted brain against a fake accessibility controller. Before this no
 * test executed runTask (audit D6: "the gate ordering, D-09, secure boundary inside the loop … verified only on a device").
 */
class EngineLoopTest {
    private lateinit var dir: File
    private lateinit var tasks: File
    private lateinit var ctrl: FakeController
    private val sms = FakeSmsTool()
    private val money = FakeMoneyTool()

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("tky-loop").toFile()
        tasks = File(dir, "tasks")
        SecureStore.keyProvider = { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        loadAppConfig()
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        TaskJournal.load(tasks); EffectLedger.load(tasks)
        JournalBoot.world = WorldCheck.SCREEN_ONLY
        ctrl = FakeController(Screens.chat(composer = "see you at 6"))
        AgentEngine.installForTest(FakeContext(dir), listOf(TapTool, TypeTool, sms, money))
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
    private fun result() = state().log.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text
    private fun ledgerOnDisk(): String = SecureStore.read(File(tasks, "effects.enc")).orEmpty()

    /** A tap on Send: the "app" empties the composer and shows the bubble. */
    private fun sendWorks() {
        ctrl.onClick = { e -> if (e.label == "Send") ctrl.screen = Screens.chat(composer = null, bubbles = listOf("see you at 6")) }
    }

    private fun tapSend() = tool("tap", JSONObject().put("element_id", Screens.SEND_ID), expects = "element_present: see you at 6")

    // ---------------------------------------------------------------- policy + confirmation

    @Test fun policy_runs_before_execute_and_a_consequential_tap_is_confirmed_with_the_ledger_written_first() = runTest {
        var onDiskAtClick = ""
        ctrl.onClick = { e ->
            if (e.label == "Send") { onDiskAtClick = ledgerOnDisk(); ctrl.screen = Screens.chat(composer = null, bubbles = listOf("see you at 6")) }
        }
        val brain = ScriptedBrain({ tapSend() }, { AgentDecision.Done("Sent it to Ama.") })
        val job = launch { AgentEngine.runForTest("send Ama see you at 6", brain) }
        advanceUntilIdle()
        // The gate stopped the tap on Send BEFORE it ran: waiting for the user, nothing clicked, nothing in the ledger yet.
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        val prompt = state().pendingConfirmation!!
        assertTrue(prompt, prompt.contains("Ama") && prompt.contains("see you at 6"))
        assertEquals(0, ctrl.clicks("Send"))
        assertTrue(EffectLedger.all().isEmpty())
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.COMPLETED, state().status)
        assertEquals(1, ctrl.clicks("Send"))
        // INTENDED was on disk (fsync'd) when the tap happened; the row then settled as CONFIRMED (the bubble verified).
        assertTrue("INTENDED must be persisted before the tool runs: $onDiskAtClick", onDiskAtClick.contains("\"INTENDED\""))
        val row = EffectLedger.all().single()
        assertEquals(EffectState.CONFIRMED, row.state)
        assertEquals("ama", row.target)
        assertEquals(TaskState.COMPLETED, TaskJournal.latest()!!.state)
    }

    @Test fun a_declined_confirmation_runs_nothing_and_records_no_effect() = runTest {
        sendWorks()
        val job = launch { AgentEngine.runForTest("send Ama see you at 6", ScriptedBrain({ tapSend() })) }
        advanceUntilIdle()
        AgentEngine.confirm(false, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(AgentStatus.STOPPED, state().status)
        assertEquals(0, ctrl.clicks("Send"))
        assertTrue(EffectLedger.all().isEmpty())
        assertEquals(TaskState.CANCELLED, TaskJournal.latest()!!.state)
    }

    @Test fun a_blocked_action_never_executes() = runTest {
        val brain = ScriptedBrain({ tool("pay_out", JSONObject().put("amount", "50")) }, { AgentDecision.Done("done") })
        val job = launch { AgentEngine.runForTest("pay 50 cedis", brain) }
        advanceUntilIdle(); job.join()
        assertEquals(0, money.calls)
        assertTrue(brain.seen[1].history.any { it.contains("BLOCKED by policy") })
        // A blocked state change is not a verified one: the Done that follows is not a clean completion.
        assertEquals(AgentStatus.COMPLETED_UNVERIFIED, state().status)
    }

    // ---------------------------------------------------------------- Secure Input

    @Test fun secure_input_stops_the_model_until_the_user_says_done() = runTest {
        ctrl.screen = Screens.signIn()
        val brain = ScriptedBrain({ AgentDecision.Done("You're signed in.") })
        val job = launch { AgentEngine.runForTest("sign in to my bank", brain) }
        advanceUntilIdle()
        assertEquals(AgentStatus.SECURE_INPUT, state().status)
        assertEquals("the brain is never asked while a credential screen is in front", 0, brain.seen.size)
        ctrl.screen = Screens.chatList()   // the user finished signing in themselves
        AgentEngine.secureInputDone()
        advanceUntilIdle(); job.join()
        assertEquals(1, brain.seen.size)
        assertFalse("the credential screen never reached the model", brain.seen.single().screen.toString().contains("com.bank.app"))
        assertEquals(AgentStatus.COMPLETED, state().status)
    }

    // ---------------------------------------------------------------- the effect ledger

    @Test fun the_same_send_twice_in_one_task_is_refused_whichever_path_it_takes() = runTest {
        sendWorks()
        val brain = ScriptedBrain(
            { tapSend() },
            // The model tries it again another way: typing the same words into the composer with submit.
            { tool("type", JSONObject().put("element_id", Screens.COMPOSER_ID).put("text", "See you at 6.").put("submit", true)) },
            { AgentDecision.Done("Sent.") })
        val job = launch { AgentEngine.runForTest("send Ama see you at 6", brain) }
        advanceUntilIdle()
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(1, ctrl.clicks("Send"))
        assertTrue("the retry never ran", ctrl.actions.none { it.startsWith("type:") })
        assertTrue(brain.seen[2].history.any { it.contains("NOT repeated") })
        assertEquals(1, EffectLedger.all().size)
    }

    @Test fun a_send_done_in_an_earlier_task_is_asked_again_with_the_time_even_after_a_restart() = runTest {
        sendWorks()
        // Task 1: sent and confirmed.
        var job = launch { AgentEngine.runForTest("send Ama see you at 6", ScriptedBrain({ tapSend() }, { AgentDecision.Done("Sent.") })) }
        advanceUntilIdle(); AgentEngine.confirm(true, state().confirmationSeq); advanceUntilIdle(); job.join()
        assertEquals(1, ctrl.clicks("Send"))
        val at = EffectLedger.all().single().at
        val hhmm = java.text.SimpleDateFormat("H:mm", java.util.Locale.US).format(java.util.Date(at))

        // Task 2 (the user asks again): the same consequence — the question says it already happened; the user says no.
        ctrl.screen = Screens.chat(composer = "see you at 6", bubbles = listOf("see you at 6"))
        job = launch { AgentEngine.runForTest("send Ama see you at 6", ScriptedBrain({ tapSend() })) }
        advanceUntilIdle()
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        assertTrue(state().pendingConfirmation!!, state().pendingConfirmation!!.startsWith("I already sent this to Ama at $hhmm — send it again?"))
        assertNull("the warning is never dropped for a Twi template", state().pendingConfirmationTwi)
        AgentEngine.confirm(false, state().confirmationSeq); advanceUntilIdle(); job.join()
        assertEquals("no second message", 1, ctrl.clicks("Send"))

        // A restart: the ledger is read back from disk by a "new process" — the protection survives it.
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        TaskJournal.load(tasks); EffectLedger.load(tasks)
        job = launch { AgentEngine.runForTest("send Ama see you at 6", ScriptedBrain({ tapSend() })) }
        advanceUntilIdle()
        assertTrue(state().pendingConfirmation!!.startsWith("I already sent this to Ama at $hhmm"))
        AgentEngine.confirm(false, state().confirmationSeq); advanceUntilIdle(); job.join()
        assertEquals(1, ctrl.clicks("Send"))
    }

    @Test fun an_sms_and_the_same_message_through_the_chat_are_one_consequence() = runTest {
        // Task 1 sends it natively; task 2 would type it into Ama's chat and submit — the ledger knows it's the same.
        var job = launch { AgentEngine.runForTest("text Ama see you at 6", ScriptedBrain(
            { tool("send_sms", JSONObject().put("contact", "Ama").put("message", "see you at 6")) }, { AgentDecision.Done("Sent.") })) }
        advanceUntilIdle(); AgentEngine.confirm(true, state().confirmationSeq); advanceUntilIdle(); job.join()
        assertEquals(1, sms.sent.size)
        ctrl.screen = Screens.chat(composer = null)
        job = launch { AgentEngine.runForTest("send Ama see you at 6", ScriptedBrain(
            { tool("type", JSONObject().put("element_id", Screens.COMPOSER_ID).put("text", "see you at 6").put("submit", true)) })) }
        advanceUntilIdle()
        assertTrue(state().pendingConfirmation!!, state().pendingConfirmation!!.startsWith("I already sent this to Ama at "))
        AgentEngine.confirm(false, state().confirmationSeq); advanceUntilIdle(); job.join()
        assertTrue(ctrl.actions.none { it.startsWith("type:") })
    }

    @Test fun an_unknown_send_is_checked_in_the_chat_not_by_any_read_and_then_retried_normally() = runTest {
        // The tap lands on a "Couldn't connect" dialog: nobody can tell whether it went → UNKNOWN.
        ctrl.onClick = { e ->
            when (e.label) {
                "Send" -> ctrl.screen = Screens.connectionDialog()
                "OK" -> ctrl.screen = Screens.chat(composer = "see you at 6")   // back in the chat: still in the box, unsent
            }
        }
        val brain = ScriptedBrain(
            { tapSend() },
            { tool("tap", JSONObject().put("element_id", 1), expects = "element_present: Ama") },   // "OK" — back to the chat
            { tapSend() },
            { AgentDecision.Done("Sent.") })
        val job = launch { AgentEngine.runForTest("send Ama see you at 6", brain) }
        advanceUntilIdle()
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle()
        // The chat on screen showed the message still in the box → that attempt is FAILED, so the retry is asked normally.
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        assertFalse(state().pendingConfirmation!!.startsWith("I "))
        assertEquals(EffectState.FAILED, EffectLedger.all().single().state)
        ctrl.onClick = { e -> if (e.label == "Send") ctrl.screen = Screens.chat(composer = null, bubbles = listOf("see you at 6")) }
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle(); job.join()
        assertEquals(2, ctrl.clicks("Send"))
        assertEquals(listOf(EffectState.FAILED, EffectState.CONFIRMED), EffectLedger.all().map { it.state })
    }

    @Test fun an_unknown_send_that_cannot_be_checked_is_the_users_call_never_a_blind_retry() = runTest {
        // The SMS went to the radio but nothing confirmed it (and the provider can't be read): UNKNOWN.
        JournalBoot.world = WorldCheck { _, _, _ -> WorldResult.UNDETERMINED }
        sms.verified = false
        ctrl.screen = Screens.chatList()
        val text = { tool("send_sms", JSONObject().put("contact", "Ama").put("message", "see you at 6")) }
        val brain = ScriptedBrain(
            { text() },
            { text() },   // same task, straight away: refused — look first
            { text() },   // again: now the user decides, told it may already have gone
            { AgentDecision.Done("ok") })
        val job = launch { AgentEngine.runForTest("text Ama see you at 6", brain) }
        advanceUntilIdle()
        AgentEngine.confirm(true, state().confirmationSeq)
        advanceUntilIdle()
        assertEquals(EffectState.UNKNOWN, EffectLedger.all().single().state)
        assertTrue(brain.seen[2].history.any { it.contains("NOT retried yet") })
        assertEquals(AgentStatus.WAITING_CONFIRMATION, state().status)
        assertTrue(state().pendingConfirmation!!, state().pendingConfirmation!!.startsWith("I may already have sent this to Ama at "))
        AgentEngine.confirm(false, state().confirmationSeq); advanceUntilIdle(); job.join()
        assertEquals("never a blind second text", 1, sms.sent.size)
    }

    // ---------------------------------------------------------------- pause / resume / interruption

    @Test fun a_quota_failure_pauses_with_a_checkpoint_and_carry_on_re_observes_and_continues() = runTest {
        ctrl.screen = Screens.chatList()
        ctrl.onClick = { e -> if (e.label == "Ama") ctrl.screen = Screens.chat(composer = null) }
        val brain = ScriptedBrain(
            { tool("tap", JSONObject().put("element_id", Screens.ROW_ID), expects = "element_present: Message") },
            { AgentDecision.Fail("HTTP 429: Rate limit reached for model openai/gpt-oss-120b (tokens per day). Please try again in 7m30s.") })
        var job = launch { AgentEngine.runForTest("send Ama see you at 6", brain) }
        advanceUntilIdle(); job.join()
        // Paused, honestly, with a checkpoint — not "failed".
        assertEquals(AgentStatus.CANNOT_DO, state().status)
        assertTrue(AgentEngine.isPausedResult(state().lastResult))
        assertTrue(result()!!, result()!!.startsWith("I've paused this because I've used up my AI allowance for now. It should be back around "))
        assertTrue(result()!!.endsWith("Say “carry on” when you want me to continue."))
        val rec = TaskJournal.resumable()!!
        assertEquals(TaskState.PAUSED, rec.state)
        assertEquals(PauseReason.QUOTA, rec.pauseReason)
        assertNotNull(rec.resumeAfterMs)
        assertEquals("tapped “Ama”", rec.progress)

        // "carry on" — deterministic: the phrase + the journal pick the task, no model involved.
        assertEquals(rec.id, AgentEngine.resumeTarget("carry on")!!.id)
        assertNull("nothing to resume for an ordinary request", AgentEngine.resumeTarget("send Kofi hello"))
        ctrl.screen = Screens.chat(composer = null)
        val looksBefore = ctrl.snapshots
        var looksAtFirstDecision = -1
        val brain2 = ScriptedBrain(
            { looksAtFirstDecision = ctrl.snapshots; tool("type", JSONObject().put("element_id", Screens.COMPOSER_ID).put("text", "see you at 6")) },
            { AgentDecision.Done("I typed it; say send when you're ready.") })
        ctrl.onSetText = { _, text, _ -> ctrl.screen = Screens.chat(composer = text) }
        job = launch { AgentEngine.runForTest("carry on", brain2, resume = rec, request = "carry on") }
        advanceUntilIdle(); job.join()
        assertTrue("the screen was observed afresh before the first decision", looksAtFirstDecision > looksBefore)
        val first = brain2.seen.first()
        assertTrue(first.screen.toString().contains("Message"))   // the CURRENT screen, not the saved one
        val note = first.history.first { it.startsWith("Resumed after a pause") }
        assertTrue(note, note.contains("Done so far: tapped “Ama”.") && note.contains("anything consequential needs a fresh confirmation"))
        assertTrue(first.history.any { it.startsWith("tap(") })   // the step history came back
        assertEquals(AgentStatus.COMPLETED, state().status)
        assertEquals("send Ama see you at 6", state().goal)
        assertEquals("carry on", state().request)
        assertEquals(TaskState.COMPLETED, TaskJournal.get(rec.id)!!.state)
        assertNull(TaskJournal.resumable())
    }

    @Test fun a_dropped_network_pauses_with_the_honest_line() = runTest {
        ctrl.screen = Screens.chatList()
        val job = launch { AgentEngine.runForTest("send Ama see you at 6",
            ScriptedBrain({ AgentDecision.CannotDo("network_lost", "The internet isn't working.") })) }
        advanceUntilIdle(); job.join()
        assertEquals("I've paused this because it needs the internet, and the phone is offline. Say “carry on” when you want me to continue.", result())
        assertEquals(PauseReason.NETWORK, TaskJournal.resumable()!!.pauseReason)
    }

    @Test fun workstream_b_reason_classes_pause_too() = runTest {
        for ((cls, why) in listOf("quota_exhausted" to PauseReason.QUOTA, "no_capable_model" to PauseReason.NO_CAPABLE_MODEL,
                "budget_exhausted" to PauseReason.BUDGET)) {
            val job = launch { AgentEngine.runForTest("open my messages", ScriptedBrain({ AgentDecision.CannotDo(cls, "x") })) }
            advanceUntilIdle(); job.join()
            assertEquals(cls, why, TaskJournal.resumable()!!.pauseReason)
        }
        // A real "can't" is not a pause.
        val job = launch { AgentEngine.runForTest("fly me to the moon", ScriptedBrain({ AgentDecision.CannotDo("impossible", "I can't do that.") })) }
        advanceUntilIdle(); job.join()
        assertNull(TaskJournal.resumable())
        assertFalse(AgentEngine.isPausedResult(state().lastResult))
    }

    @Test fun process_death_mid_task_leaves_an_interrupted_task_that_is_offered_once() = runTest {
        ctrl.screen = Screens.chatList()
        ctrl.onClick = { e -> if (e.label == "Ama") ctrl.screen = Screens.chat(composer = null) }
        val never = CompletableDeferred<AgentDecision>()
        val brain = ScriptedBrain(
            { tool("tap", JSONObject().put("element_id", Screens.ROW_ID), expects = "element_present: Message") },
            { never.await() })   // the model is thinking when the process dies
        // (the test clock races ahead; the decide time limit must not end the task before the "death")
        loadAppConfig("""{"agent":{"decideTimeoutMs":0}}""")
        val job = launch { AgentEngine.runForTest("send Ama see you at 6", brain) }
        advanceUntilIdle()
        // The process dies HERE: what is on disk at this instant is all the next process gets.
        val afterDeath = File(dir, "after-death").also { File(tasks, "journal.enc").copyTo(File(it, "tasks/journal.enc")) }
        job.cancel(); advanceUntilIdle()

        TaskJournal.resetForTest()
        val interrupted = TaskJournal.load(File(afterDeath, "tasks"), exitReason = "force-stopped or swiped away by the user")
        assertEquals(1, interrupted.size)
        val rec = TaskJournal.resumable()!!
        assertEquals(TaskState.INTERRUPTED, rec.state)
        assertEquals(PauseReason.PROCESS_DEATH, rec.pauseReason)
        assertEquals("force-stopped or swiped away by the user", rec.exitReason)
        assertTrue(rec.checkpoint!!.contains("tap("))
        val offer = ResumeOffer.take()!!.second
        assertEquals("Earlier I was working on “send Ama see you at 6” when the app was closed, so it didn't finish. " +
            "So far I tapped “Ama”. Say “carry on” when you want me to continue.", offer)
        assertNull("offered once", ResumeOffer.take())
        assertNotNull("…but still resumable by the user's word", AgentEngine.resumeTarget("continue the task"))
    }

    @Test fun a_stale_resumable_task_is_not_offered_or_resumed() {
        val rec = TaskJournal.begin("send Ama see you at 6", "agent", now = 1_000L)
        TaskJournal.pause(rec.id, PauseReason.NETWORK, null, now = 2_000L)
        assertNotNull(TaskJournal.resumable(now = 3_000L))
        val late = 2_000L + TaskJournal.resumeWindowMs() + 1
        assertNull(ResumeOffer.pending(now = late))
        assertNull(TaskJournal.resumable(now = late))
        assertEquals(TaskState.CANCELLED, TaskJournal.get(rec.id)!!.state)
    }

    @Test fun a_screen_free_question_and_an_unattended_run_are_not_journaled() = runTest {
        val job = launch { AgentEngine.runForTest("what time is it", ScriptedBrain({ AgentDecision.Done("Noon.") }), unattended = true, journal = false) }
        advanceUntilIdle(); job.join()
        assertNull(TaskJournal.latest())
    }

    // ---------------------------------------------------------------- integration (A + B + D, 2026-09-29)

    @Test fun a_plan_rides_on_the_journal_record_and_comes_back_on_carry_on() = runTest {
        ctrl.screen = Screens.chatList()
        ctrl.onClick = { e -> if (e.label == "Ama") ctrl.screen = Screens.chat(composer = null) }
        val brain = ScriptedBrain(
            { AgentDecision.CallTool("tap", JSONObject().put("element_id", Screens.ROW_ID), null, "element_present: Message",
                plan = "open Ama's chat => element_present: Message; type the message; send it") },
            { AgentDecision.Fail("HTTP 429: Rate limit reached (tokens per day). Please try again in 5m.") })
        var job = launch { AgentEngine.runForTest("send Ama see you at 6", brain) }
        advanceUntilIdle(); job.join()
        val rec = TaskJournal.resumable()!!
        assertEquals("one record per run — B's plan is on A's", rec.id, TaskJournal.latest()!!.id)
        assertEquals(listOf("open Ama's chat", "type the message", "send it"), rec.plan.map { it.text })
        assertEquals(listOf(true, false, false), rec.plan.map { it.done })

        val brain2 = ScriptedBrain({ AgentDecision.Done("stopping here") })
        job = launch { AgentEngine.runForTest("carry on", brain2, resume = rec, request = "carry on") }
        advanceUntilIdle(); job.join()
        val progress = brain2.seen.first().progress
        assertTrue(progress, progress.contains("[x] 1. open Ama's chat") && progress.contains("[>] 2. type the message"))
        // The completion gate still holds after the resume: open subgoals are never "done".
        assertEquals(AgentStatus.COMPLETED_UNVERIFIED, state().status)
    }

    @Test fun a_paired_computers_task_is_never_journaled_and_never_says_paused() = runTest {
        val job = launch { AgentEngine.runForTest("open my messages",
            ScriptedBrain({ AgentDecision.CannotDo(com.tekyerema.agent.routing.ModelRouting.NO_CAPABLE_MODEL,
                com.tekyerema.agent.routing.ModelRouting.PAUSED_TEXT) }), origin = "remote:laptop") }
        advanceUntilIdle(); job.join()
        assertNull("a remote task leaves no resumable record", TaskJournal.latest())
        assertFalse(AgentEngine.isPausedResult(state().lastResult))
        assertTrue(result()!!, result()!!.contains("so I stopped this task here") && !result()!!.contains("paused"))
    }

    @Test fun only_the_phone_users_own_carry_on_resumes_a_task() = runTest {
        val job = launch { AgentEngine.runForTest("send Ama see you at 6",
            ScriptedBrain({ AgentDecision.CannotDo("network_lost", "The internet isn't working.") })) }
        advanceUntilIdle(); job.join()
        assertNotNull(AgentEngine.resumeFor("carry on", unattended = false, origin = null))
        assertNull("a paired computer can't resume the user's task", AgentEngine.resumeFor("carry on", unattended = false, origin = "remote:laptop"))
        assertNull("nor can a trigger", AgentEngine.resumeFor("carry on", unattended = true, origin = null))
    }
}

/** A native SMS tool that records instead of sending (its own check says it went: the radio accepted it). */
class FakeSmsTool : Tool {
    val sent = ArrayList<String>()
    /** false = the send returns but its outcome can't be confirmed (no delivery report). */
    var verified = true
    override val name = "send_sms"
    override val description = "Send an SMS."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER2
    override val params = listOf(ToolParam("contact", "string", "who", required = false), ToolParam("number", "string", "number", required = false),
        ToolParam("message", "string", "what"))
    override val verifiesOutcome = true
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        sent.add(args.optString("message")); return ToolResult(true, "Sent to ${args.optString("contact")}.", channel = "native")
    }
    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        if (verified) VerificationResult(result.success, "the radio accepted the text") else VerificationResult(false, "no delivery report")
    override fun confirmationPrompt(args: JSONObject) = "Send “${args.optString("message")}” to ${args.optString("contact")}?"
}

/** A money tool outside the granted scopes: the policy must block it before it runs. */
class FakeMoneyTool : Tool {
    var calls = 0
    override val name = "pay_out"
    override val description = "Pay money."
    override val scope = CapabilityScope.FINANCIAL
    override val riskTier = RiskTier.TIER3
    override val params = listOf(ToolParam("amount", "string", "how much"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult { calls++; return ToolResult(true, "paid") }
}
