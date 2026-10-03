package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.agent.helper.HelperCommands
import com.tekyerema.agent.helper.HelperCommands.Cmd
import com.tekyerema.agent.helper.HelperGate
import com.tekyerema.agent.helper.HelperHooks
import com.tekyerema.agent.helper.HelperOffer
import com.tekyerema.agent.helper.HelperPlan
import com.tekyerema.agent.helper.HelperSummary
import com.tekyerema.agent.helper.LastTask
import com.tekyerema.agent.helper.TaskRecap
import com.tekyerema.agent.helper.TrustFast
import com.tekyerema.android.ContactResolver
import com.tekyerema.android.Relations
import com.tekyerema.android.Relations.SetRequest
import com.tekyerema.audit.AuditEntry
import com.tekyerema.audit.AuditLog
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.tools.ToolContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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

/**
 * A trusted helper, one step away (2026-09-29): one contact the user names, reachable by "call my helper" and "tell my
 * helper what you did" — confirmed like any call or send, never at a PIN, and the summary never carries a secret.
 */
class TrustedHelperTest {
    @Before fun reset() { HelperGate.resetForTest(); HelperOffer.resetForTest(); LastTask.clear(); Relations.clear() }
    @After fun clean() { HelperGate.resetForTest(); HelperOffer.resetForTest(); LastTask.clear(); Relations.clear() }

    // ---------------------------------------------------------------- the words

    @Test fun helper_commands_are_recognised_and_nothing_else_is() {
        for (s in listOf("call my helper", "Please call my helper.", "ring my trusted helper", "can you call my helper now"))
            assertEquals(s, Cmd.Call, HelperCommands.parse(s))
        for (s in listOf("tell my helper what you did", "send my helper a summary", "let my helper know what happened",
                "send a summary to my helper", "update my helper", "text my helper what you just did"))
            assertEquals(s, Cmd.Summary(whatsApp = false), HelperCommands.parse(s))
        for (s in listOf("tell my helper on WhatsApp what you did", "WhatsApp my helper a summary", "send my helper a summary on whatsapp"))
            assertEquals(s, Cmd.Summary(whatsApp = true), HelperCommands.parse(s))
        for (s in listOf("who is my helper", "my helper is Kofi", "remove my helper", "call my mother", "call Kofi", "tell me a joke"))
            assertNull(s, HelperCommands.parse(s))
        // Bringing the helper in by any words (the PIN rule covers them all); talking ABOUT the helper doesn't.
        assertTrue(HelperCommands.involvesHelper("text my helper saying I'm home"))
        assertTrue(HelperCommands.involvesHelper("call my helper"))
        assertFalse(HelperCommands.involvesHelper("who is my helper"))
        assertFalse(HelperCommands.involvesHelper("set my helper to Prince"))
    }

    @Test fun the_fast_path_hands_helper_commands_to_the_agent() {
        for (s in listOf("call my helper", "tell my helper what you did", "send my helper a summary", "whatsapp my helper a summary"))
            assertNull(s, FastIntentClassifier.classify(s))
    }

    @Test fun the_helper_is_its_own_relation_set_asked_and_removed_by_voice() {
        assertEquals(SetRequest("helper", "helper", "Kofi", true), Relations.parseSet("my helper is Kofi"))
        assertEquals(SetRequest("helper", "helper", "Prince", true), Relations.parseSet("set my helper to Prince"))
        assertEquals(SetRequest("helper", "helper", "Kofi Mensah", true), Relations.parseSet("make Kofi Mensah my helper"))
        assertEquals(SetRequest("helper", "helper", "Ama", true), Relations.parseSet("Ama is my helper"))
        assertNull("in passing, not a name", Relations.parseSet("my helper is coming"))
        assertEquals("helper", Relations.parseQuery("who is my helper"))
        assertEquals("helper", Relations.parseForget("remove my helper"))
        assertEquals("caregiver", Relations.canonical("my carer"))
        Relations.set("helper", "Kofi Mensah")
        assertEquals("Kofi Mensah", HelperPlan.currentHelper())
        assertEquals("Kofi Mensah", ContactResolver.aliasesFor("my helper").first())   // "call my helper" reaches him
        assertEquals("your helper is Kofi Mensah", Relations.spokenAll())
    }

    // ---------------------------------------------------------------- the PIN rule

    @Test fun never_at_a_pin_until_done_or_a_later_task() {
        assertFalse(HelperGate.blocked(secureNow = false, credentialOnScreen = false))
        assertTrue(HelperGate.blocked(secureNow = true, credentialOnScreen = false))
        assertTrue(HelperGate.blocked(secureNow = false, credentialOnScreen = true))
        // Secure Input stopped before "done": the last task stopped at the PIN.
        HelperGate.runStarted(); HelperGate.atPin(1_000L); HelperGate.runEnded()
        assertTrue(HelperGate.pinPending(2_000L))
        // …until a later task finishes.
        HelperGate.runStarted(); HelperGate.runEnded()
        assertFalse(HelperGate.pinPending(3_000L))
        // "done" after the PIN ends it at once.
        HelperGate.runStarted(); HelperGate.atPin(4_000L); HelperGate.pinDone()
        assertFalse(HelperGate.pinPending(5_000L))
        // A USSD PIN prompt is long gone after the hold (10 minutes).
        HelperGate.atPin(1L)
        assertTrue(HelperGate.pinPending(60_000L))
        assertFalse(HelperGate.pinPending(11 * 60_000L))
    }

    @Test fun the_tools_refuse_the_helper_at_a_pin_by_name_whatever_the_words() = runBlocking {
        Relations.set("helper", "Kofi Mensah")
        val dir = kotlin.io.path.createTempDirectory("tky-helper").toFile()
        try {
            // No controller: the screen can't be seen, so the hold stays for the whole PIN window.
            val ctx = ToolContext(FakeContext(dir), null, NotificationStore)
            assertNull(TrustFast.toolRefusal(ctx, "Kofi Mensah", "0244123456"))
            HelperGate.atPin()
            assertEquals(HelperGate.PIN_LINE, TrustFast.toolRefusal(ctx, "Kofi Mensah", "0244123456")?.message)
            assertNull("anyone else is not the helper", TrustFast.toolRefusal(ctx, "Ama", "0551234567"))
            // A credential screen in front: still refused. The PIN screen gone (a chat list is in front): the helper can
            // be reached again, and the hold ends (QA TW03, 3 October 2026 — a text to the helper refused minutes after
            // a send had stopped at MTN's PIN and been cancelled).
            val atBank = ToolContext(FakeContext(dir), FakeController(Screens.signIn()), NotificationStore)
            assertEquals(HelperGate.PIN_LINE, TrustFast.toolRefusal(atBank, "Kofi Mensah", "0244123456")?.message)
            val elsewhere = ToolContext(FakeContext(dir), FakeController(Screens.chatList()), NotificationStore)
            assertNull(TrustFast.toolRefusal(elsewhere, "Kofi Mensah", "0244123456"))
            assertFalse(HelperGate.pinPending())
        } finally { dir.deleteRecursively() }
    }

    @Test fun the_router_plans_the_helper_and_a_momo_pin_handoff_blocks_it() = runBlocking {
        Relations.set("helper", "Kofi Mensah")
        val r = RoutingBrain(LocalBrain(), null) { false }   // offline, no cloud: planned on the phone all the same
        val d = r.decide(BrainContext("call my helper", JSONObject(), emptyList(), JSONArray(), JSONArray()))
        assertEquals("make_call", (d as AgentDecision.CallTool).name)
        assertEquals("Kofi Mensah", d.args.getString("contact"))
        // Mobile Money stopped at the PIN (RoutingBrain.pinHandoff): the helper is refused now.
        val momo = RoutingBrain(LocalBrain(), null) { false }
        val done = momo.decide(BrainContext("send 5 cedis to 0244123456", JSONObject(),
            listOf("momo_send({\"number\":\"0244123456\",\"amount\":\"5\"}) → success [a11y]; verified=true (at the PIN); " +
                "The Mobile Money PIN screen is up — enter your PIN yourself."), JSONArray(), JSONArray()))
        assertTrue(done is AgentDecision.Done)
        val refused = HelperPlan.decide("call my helper", emptyList())
        assertEquals(HelperGate.PIN_LINE, (refused as AgentDecision.CannotDo).detail)
    }

    // ---------------------------------------------------------------- the plan

    @Test fun call_and_summary_are_one_confirmed_tool_call_then_the_tools_own_result() {
        val recap = TaskRecap("text Ama see you at 6", AgentStatus.COMPLETED.name, "WhatsApp shows your message to Ama in the chat.",
            listOf("texted Ama"), emptyList(), 0L)
        val call = HelperPlan.decide("call my helper", emptyList(), helper = "Kofi Mensah", recap = null, blocked = false)
        assertEquals("make_call", (call as AgentDecision.CallTool).name)
        assertEquals("Kofi Mensah", call.args.getString("contact"))
        val sum = HelperPlan.decide("tell my helper on WhatsApp what you did", emptyList(), helper = "Kofi Mensah", recap = recap, blocked = false)
        sum as AgentDecision.CallTool
        assertEquals("send_sms", sum.name)
        assertEquals("whatsapp", sum.args.getString("via"))
        assertEquals(HelperSummary.compose(recap), sum.args.getString("message"))

        assertEquals(HelperGate.PIN_LINE, (HelperPlan.decide("call my helper", emptyList(), "Kofi", null, blocked = true) as AgentDecision.CannotDo).detail)
        assertEquals(HelperPlan.NO_HELPER, (HelperPlan.decide("call my helper", emptyList(), null, null, false) as AgentDecision.CannotDo).detail)
        assertEquals(HelperPlan.NOTHING_YET, (HelperPlan.decide("send my helper a summary", emptyList(), "Kofi", null, false) as AgentDecision.CannotDo).detail)
        assertNull(HelperPlan.decide("call my mother", emptyList(), "Kofi", recap, false))

        // Step two speaks what the tool reported; a failure is a plain "couldn't", a revision goes back to the brains.
        val called = "make_call({\"contact\":\"Kofi Mensah\"}) → success [native]; verified=true (a dialer is in front); Calling Kofi Mensah (0244123456)."
        assertEquals("Calling your helper, Kofi Mensah.", (HelperPlan.decide("call my helper", listOf(called), "Kofi Mensah", null, false) as AgentDecision.Done).summary)
        val texted = "send_sms({\"contact\":\"Kofi Mensah\"}) → success [a11y]; verified=true (it shows in the chat); WhatsApp shows your message to Kofi Mensah in the chat."
        assertEquals("WhatsApp shows your message to Kofi Mensah in the chat.",
            (HelperPlan.decide("tell my helper what you did", listOf(texted), "Kofi Mensah", recap, false) as AgentDecision.Done).summary)
        val notSeen = "send_sms({\"contact\":\"Kofi Mensah\"}) → fail [a11y]; verified=false (not shown); I pressed Send, but I couldn't see the message in the chat, so I can't confirm it went. Check before sending again."
        assertTrue((HelperPlan.decide("tell my helper what you did", listOf(notSeen), "Kofi Mensah", recap, false) as AgentDecision.CannotDo).detail.startsWith("I pressed Send, but"))
        val blocked = "make_call({\"contact\":\"Kofi Mensah\"}) → BLOCKED by policy: calls are off. Do not retry it; if the goal needs it, call cannot_do."
        assertEquals("I couldn't do that just now.", (HelperPlan.decide("call my helper", listOf(blocked), "Kofi Mensah", null, false) as AgentDecision.CannotDo).detail)
        assertNull(HelperPlan.decide("call my helper", listOf("You proposed: \"Call Kofi?\". The user did NOT approve it — they said: \"call Ama\"."), "Kofi", null, false))
    }

    // ---------------------------------------------------------------- the summary

    @Test fun the_summary_is_short_plain_and_built_from_the_last_task() {
        val entries = listOf(
            AuditEntry(0, 7, "agent", AuditLog.DONE, "opened WhatsApp", tool = "open_app"),
            AuditEntry(0, 7, "agent", AuditLog.DONE, "tapped “Send”", tool = "tap"),
            AuditEntry(0, 7, "agent", AuditLog.APPROVED, "Text Ama Mensah on 024 412 3456: \"see you at 6\"?", tool = "send_sms"),
            AuditEntry(0, 7, "agent", AuditLog.DONE, "texted Ama Mensah", tool = "send_sms"))
        val r = LastTask.from("text Ama see you at 6", AgentStatus.COMPLETED,
            "WhatsApp shows your message to Ama Mensah — 'see you at 6' — in the chat. Anything else?", entries, 0L)
        assertEquals(listOf("opened WhatsApp", "texted Ama Mensah"), r.done)
        assertEquals(listOf("0244123456"), r.confirmedNumbers)
        val s = HelperSummary.compose(r)
        assertEquals("Tekyerema update: I was asked to \"text Ama see you at 6\". Done: opened WhatsApp and texted Ama Mensah. " +
            "Result: WhatsApp shows your message to Ama Mensah - 'see you at 6' - in the chat.", s)
        assertTrue("GSM-7 only (one SMS rate)", s.all { it.code in 0x20..0x7E })
        val failed = HelperSummary.compose(TaskRecap("turn on zorblax mode", AgentStatus.CANNOT_DO.name, "I couldn't find Zorblax mode on this phone.",
            emptyList(), emptyList(), 0L))
        assertEquals("Tekyerema update: I was asked to \"turn on zorblax mode\". Nothing was changed on the phone. " +
            "It couldn't be done: I couldn't find Zorblax mode on this phone.", failed)
        val screenOnly = LastTask.from("scroll down", AgentStatus.COMPLETED, "Scrolled down.",
            listOf(AuditEntry(0, 8, "agent", AuditLog.DONE, "scrolled", tool = "scroll")), 0L)
        assertEquals(listOf("worked on the phone's screen"), screenOnly.done)
        val long = HelperSummary.compose(TaskRecap("x ".repeat(200), AgentStatus.COMPLETED.name, "y ".repeat(200), emptyList(), emptyList(), 0L))
        assertTrue(long.length <= 300)
    }

    @Test fun the_summary_never_carries_a_pin_a_code_money_or_a_long_number() {
        assertEquals("Your PIN is [code hidden] and the code is [code hidden].",
            HelperSummary.redact("Your PIN is 1234 and the code is 556677."))
        assertEquals("Balance: an amount. Current balance (hidden) left. Sent an amount to Ama.",
            HelperSummary.redact("Balance: GHS 1,234.50. Current balance 250.75 left. Sent 20 cedis to Ama."))
        // Long numbers are hidden — except the number of someone the user approved calling or texting in that task.
        assertEquals("Account (number hidden). Called 0 2 4 4 1 2 3 4 5 6. Ref 024-412-3456.",
            HelperSummary.redact("Account 1234567890123. Called 0 2 4 4 1 2 3 4 5 6. Ref 024-412-3456.", keepNumbers = listOf("0244123456")))
        // Without the approval, even a phone number is hidden.
        assertEquals("Called (number hidden).", HelperSummary.redact("Called 0244123456."))
        // Small numbers (times, counts) stay.
        assertEquals("At 10:30 I read 3 messages.", HelperSummary.redact("At 10:30 I read 3 messages."))
        // A goal that said the PIN out loud is hidden in the summary too.
        val s = HelperSummary.compose(TaskRecap("my pin is 4321 send money to Ama", AgentStatus.STOPPED.name, "", emptyList(), emptyList(), 0L))
        assertFalse(s, s.contains("4321"))
    }

    // ---------------------------------------------------------------- the offer after two failures

    @Test fun after_two_failures_in_a_row_the_helper_is_offered_once() {
        fun end(s: AgentStatus, helper: Boolean = true, blocked: Boolean = false, paused: Boolean = false) =
            HelperOffer.afterTask(s, paused, helper, blocked)
        assertNull(end(AgentStatus.CANNOT_DO))
        assertEquals(HelperOffer.LINE, end(AgentStatus.FAILED))
        assertNull("once", end(AgentStatus.CANNOT_DO))
        assertNull(end(AgentStatus.COMPLETED))
        assertNull(end(AgentStatus.CANNOT_DO)); assertNull("a stop is the user's choice", end(AgentStatus.STOPPED))
        assertEquals(HelperOffer.LINE, end(AgentStatus.CANNOT_DO))
        HelperOffer.resetForTest()
        assertNull(end(AgentStatus.CANNOT_DO, paused = true)); assertNull(end(AgentStatus.CANNOT_DO, paused = true))
        assertNull("a pause isn't a failure", end(AgentStatus.CANNOT_DO))
        HelperOffer.resetForTest()
        end(AgentStatus.CANNOT_DO, helper = false)
        assertNull("no helper chosen", end(AgentStatus.CANNOT_DO, helper = false))
        HelperOffer.resetForTest()
        end(AgentStatus.CANNOT_DO)
        assertNull("never at a PIN", end(AgentStatus.CANNOT_DO, blocked = true))
    }

    @Test fun the_engine_hook_adds_the_offer_to_the_spoken_result_and_remembers_the_task() {
        Relations.set("helper", "Kofi Mensah")
        fun log(result: String) = listOf(LogEntry(0, LogEntry.Kind.USER, "x"), LogEntry(1, LogEntry.Kind.RESULT, result),
            LogEntry(2, LogEntry.Kind.STATS, "s"))
        assertNull(HelperHooks.taskEnded(null, "turn on zorblax mode", AgentStatus.CANNOT_DO, false, null, log("I couldn't find it.")))
        assertEquals("turn on zorblax mode", LastTask.recent()?.goal)
        val second = HelperHooks.taskEnded(null, "open the zorblax app", AgentStatus.CANNOT_DO, false, null, log("There's no such app."))
        assertNotNull(second)
        assertEquals("There's no such app. ${HelperOffer.LINE}", second!!.first { it.kind == LogEntry.Kind.RESULT }.text)
        // The helper request itself is not "the last task" a summary would describe.
        HelperHooks.taskEnded(null, "tell my helper what you did", AgentStatus.COMPLETED, false, null, log("Sent."))
        assertEquals("open the zorblax app", LastTask.recent()?.goal)
    }
}
