package com.tekyerema

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.BrainPrompts
import com.tekyerema.agent.ClaimCheck
import com.tekyerema.agent.journal.EffectGate
import com.tekyerema.agent.journal.EffectKeys
import com.tekyerema.agent.journal.EffectLedger
import com.tekyerema.agent.journal.EffectState
import com.tekyerema.agent.journal.WorldCheck
import com.tekyerema.tools.KeyEventTool
import com.tekyerema.tools.SetAlarmTool
import com.tekyerema.tools.TapTool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.TypeTool
import com.tekyerema.verifier.Expects
import com.tekyerema.verifier.OutcomeVerifier
import com.tekyerema.verifier.SentCheck
import com.tekyerema.verifier.VerificationResult
import com.tekyerema.verifier.Verify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * A20e audit 2026-10-01, fixes 4 and 6: a message is "sent" only when it left the box and shows in the chat (C10: the
 * text sat unsent in Telegram's box, the claim matched the BOX, the ledger called it done, and the user heard "I sent
 * it"); and a tool that knows its own outcome says so (seven screenshots, an opened Calculator and a set alarm all ended
 * "NOT verified").
 */
class AuditVerifyFixesTest {
    private val words = "hello from the Tekyerema audit"
    private val ctx = ToolContext(android.content.ContextWrapper(null), null, com.tekyerema.notifications.NotificationStore)

    @Before fun setUp() { EffectLedger.resetForTest() }
    @After fun tearDown() { EffectLedger.resetForTest() }

    private val empty get() = Screens.chat(composer = null, title = "Richmond")
    private val drafted get() = Screens.chat(composer = words, title = "Richmond")
    private val sent get() = Screens.chat(composer = null, bubbles = listOf(words), title = "Richmond")

    // ---------------------------------------------------------------- the one sent-message rule

    @Test fun sent_needs_an_empty_box_and_a_bubble() {
        assertEquals(SentCheck.State.SENT, SentCheck.state(drafted, sent, words))
        assertEquals(SentCheck.State.STILL_IN_BOX, SentCheck.state(empty, drafted, words))
        assertEquals("the box emptied but nothing shows: can't tell", SentCheck.State.UNKNOWN, SentCheck.state(drafted, empty, words))
        // The same words were already in the chat before: one old bubble is not a new send.
        val old = Screens.chat(composer = words, bubbles = listOf(words), title = "Richmond")
        val stillOld = Screens.chat(composer = null, bubbles = listOf(words), title = "Richmond")
        assertEquals(SentCheck.State.UNKNOWN, SentCheck.state(old, stillOld, words))
        val v = SentCheck.verdict(empty, drafted, words)!!
        assertFalse(v.verified); assertTrue(v.stillInField); assertTrue(v.authoritative)
        assertTrue(SentCheck.verdict(drafted, sent, words)!!.verified)
        assertNull(SentCheck.verdict(drafted, empty, words))
    }

    @Test fun type_with_submit_that_only_typed_says_so() = runBlocking {
        val args = JSONObject().put("text", words).put("submit", true).put("element_id", Screens.COMPOSER_ID)
        val r = TypeTool.verify(args, ctx, empty, drafted, ToolResult(true, "Entered text"))
        assertFalse(r.verified); assertTrue(r.stillInField)
        assertTrue(r.detail, r.detail.contains("still in the text box"))
        // …and when the app did send on Enter, it is verified as before.
        assertTrue(TypeTool.verify(args, ctx, empty, sent, ToolResult(true, "Entered text")).verified)
    }

    @Test fun a_claim_met_only_by_the_text_box_is_not_met() {
        val stuck = VerificationResult(false, "typed, but the words are still in the text box", stillInField = true)
        for (claim in listOf("element_present: $words", "new_element: $words")) {
            val v = OutcomeVerifier.verify(Expects.parse(claim), empty, drafted, true, stuck, toolVerifiesOutcome = true)
            assertFalse(claim, v.verified); assertTrue(claim, v.stillInField)
        }
        // The same claim once the bubble is there — including the 1 → 1 move from the box to the chat.
        val weak = VerificationResult(false, "weak")
        assertTrue(OutcomeVerifier.verify(Expects.parse("element_present: $words"), drafted, sent, true, weak, false).verified)
        assertTrue(OutcomeVerifier.verify(Expects.parse("new_element: $words"), drafted, sent, true, weak, false).verified)
        // A claim about something else on the screen is still judged on its own.
        assertTrue(OutcomeVerifier.verify(Expects.parse("element_present: Send"), empty, drafted, true, stuck, true).verified)
        // Typing WITHOUT submit: the field holding the text is exactly the claim.
        val typed = VerificationResult(true, "field contains text=true")
        assertTrue(OutcomeVerifier.verify(Expects.parse("element_present: $words"), empty, drafted, true, typed, true).verified)
    }

    @Test fun a_tap_on_send_knows_whether_the_message_went() {
        val send = drafted.element(Screens.SEND_ID)
        assertTrue(TapTool.sendTapVerdict(send, null, drafted, sent)!!.verified)
        val still = TapTool.sendTapVerdict(send, null, drafted, drafted)!!
        assertFalse(still.verified); assertTrue(still.stillInField)
        assertNull("no draft in the box → an ordinary tap", TapTool.sendTapVerdict(send, null, empty, empty))
        assertNull("not a Send control", TapTool.sendTapVerdict(drafted.element(0), null, drafted, sent))
        // An observed outcome counts even when the model's claim was vacuous or unparseable…
        val v = OutcomeVerifier.verify(Expects.parse("message_sent"), drafted, sent, true, TapTool.sendTapVerdict(send, null, drafted, sent)!!, toolVerifiesOutcome = false)
        assertTrue(v.verified)
        // …and an ordinary tap with no claim is still never verified by a screen change alone.
        assertFalse(OutcomeVerifier.verify(null, drafted, sent, true, VerificationResult(false, "screen changed=true (weak signal only)"), false).verified)
    }

    // ---------------------------------------------------------------- the ledger records a send only when it went

    @Test fun a_submit_that_left_the_words_in_the_box_is_not_recorded_as_sent() {
        val gate = EffectGate("t1", { WorldCheck.SCREEN_ONLY })
        val spec = EffectKeys.describe("type", JSONObject().put("text", words).put("submit", true),
            empty.element(Screens.COMPOSER_ID), null, true, empty)!!
        val e = gate.intend(spec, "type")
        gate.dispatched(e, true, "Entered text")
        // A looser claim said "verified" — the chat says the words are still in the box.
        gate.settled(e, verified = true, detail = "found '$words'", after = drafted)
        assertEquals(EffectState.FAILED, EffectLedger.get(e.id)!!.state)
        assertNotNull("the yes already given can carry over to the real Send", gate.approvedButNotDone(spec))
        // So the app's own Send button may be pressed: same consequence, not refused as a repeat.
        val tap = EffectKeys.describe("tap", JSONObject().put("element_id", Screens.SEND_ID), drafted.element(Screens.SEND_ID), null, false, drafted)!!
        assertTrue(gate.check(tap, drafted) is EffectGate.Verdict.Proceed)
        val e2 = gate.intend(tap, "tap")
        gate.dispatched(e2, true, "Tapped 'Send'")
        gate.settled(e2, verified = true, detail = "the message left the text box", after = sent)
        assertEquals(EffectState.CONFIRMED, EffectLedger.get(e2.id)!!.state)
        assertTrue("and now a second Send is a repeat", gate.check(tap, sent) is EffectGate.Verdict.Refuse)
        assertNull(EffectGate("t2", { WorldCheck.SCREEN_ONLY }).approvedButNotDone(spec))
    }

    @Test fun a_send_tool_with_its_own_evidence_is_not_second_guessed_by_the_screen() {
        val gate = EffectGate("t1", { WorldCheck.SCREEN_ONLY })
        val spec = EffectKeys.describe("send_sms", JSONObject().put("contact", "Richmond").put("message", words), null, null, false, null)!!
        val e = gate.intend(spec, "send_sms")
        gate.settled(e, verified = true, detail = "the radio reported every part as sent", after = drafted)
        assertEquals(EffectState.CONFIRMED, EffectLedger.get(e.id)!!.state)
    }

    @Test fun i_sent_it_is_refused_when_the_only_step_left_the_words_in_the_box() {
        val goal = "send a Telegram message to Richmond saying $words"
        val typedOnly = listOf("type({element_id=5, text=$words, submit=true}) → success [node]; verified=false (typed, but the words are " +
            "still in the text box and nowhere else — nothing was sent); Entered text into element 5 and submitted.")
        assertNotNull(ClaimCheck.check(goal, "I sent the Telegram message to Richmond.", typedOnly))
        val thenSent = typedOnly + "tap({element_id=6}) → success [node]; verified=true (the message left the text box and now shows in the chat); Tapped 'Send'"
        assertNull(ClaimCheck.check(goal, "I sent the Telegram message to Richmond.", thenSent))
        assertNull("an honest reply is untouched", ClaimCheck.check(goal, "I typed it but couldn't send it.", typedOnly))
    }

    // ---------------------------------------------------------------- tools that know their own outcome

    @Test fun a_screenshot_is_verified_by_the_tool_whatever_the_model_claimed() = runBlocking {
        val args = JSONObject().put("key", "screenshot")
        val own = KeyEventTool.verify(args, ctx, empty, empty, ToolResult(true, KeyEventTool.SCREENSHOT_DONE))
        assertTrue(own.verified)
        for (claim in listOf("screenshot_taken", "foreground_is: com.android.systemui", "element_present: Screenshot saved"))
            assertTrue(claim, OutcomeVerifier.verify(Expects.parse(claim), empty, empty, true, own, KeyEventTool.verifiesOutcome).verified)
        assertFalse(KeyEventTool.verify(args, ctx, empty, empty, ToolResult(false, "screenshot failed.")).verified)
        assertTrue(KeyEventTool.SCREENSHOT_DONE.contains("Don't take it again"))
        // The pull-down panel is System UI's window.
        val shade = UiSnapshot("com.android.systemui", null, listOf(UiElement(0, "android.widget.Switch", "Wi-Fi", Box(0, 100, 200, 200), checkable = true)), 1L)
        assertTrue(KeyEventTool.verify(JSONObject().put("key", "quick_settings"), ctx, empty, shade, ToolResult(true, "Pressed quick_settings.")).verified)
        assertFalse(KeyEventTool.verify(JSONObject().put("key", "back"), ctx, empty, shade, ToolResult(true, "Pressed back.")).verified)
    }

    @Test fun a_launch_the_event_tracker_missed_is_seen_in_the_window_itself() {
        // A20e: Calculator was in front; the tracked foreground still said the launcher.
        val stale = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AccessibilityController::class.java)) { _, m, _ ->
            when (m.name) {
                "foregroundPackage" -> "com.sec.android.app.launcher"
                "activeWindowPackage" -> "com.google.android.calculator"
                else -> null
            }
        } as AccessibilityController
        val (ok, fg) = runBlocking { Verify.pollLaunched(stale, "com.google.android.calculator", "com.sec.android.app.launcher", attempts = 10, intervalMs = 1) }
        assertTrue(ok); assertEquals("com.google.android.calculator", fg)
        val nothing = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AccessibilityController::class.java)) { _, m, _ ->
            if (m.name == "foregroundPackage" || m.name == "activeWindowPackage") "com.sec.android.app.launcher" else null
        } as AccessibilityController
        assertFalse(runBlocking { Verify.pollLaunched(nothing, "com.google.android.calculator", "com.sec.android.app.launcher", attempts = 10, intervalMs = 1) }.first)
    }

    @Test fun an_alarm_row_is_recognised_in_any_clock_format() {
        assertTrue(SetAlarmTool.showsTime("06 30 , Friday, 2 October, On, Double tap to edit.", 6, 30))
        assertTrue(SetAlarmTool.showsTime("6:30 am", 6, 30))
        assertTrue(SetAlarmTool.showsTime("Alarm 18:30", 18, 30))
        assertTrue(SetAlarmTool.showsTime("6:30 pm", 18, 30))
        assertFalse(SetAlarmTool.showsTime("16:30", 6, 30))
        assertFalse(SetAlarmTool.showsTime("06 , Friday, 2 October, Off", 6, 30))
        assertTrue(SetAlarmTool.verifiesOutcome)
    }

    @Test fun a_plain_tap_that_opened_another_page_landed() {
        fun page(title: String, vararg labels: String) = UiSnapshot("com.android.settings", title,
            labels.mapIndexed { i, l -> UiElement(i, "android.widget.TextView", l, Box(0, i * 100, 500, i * 100 + 80), clickable = true) }, 1L)
        val settings = page("Settings", "Connections", "Sounds and vibration", "Notifications", "Display")
        val connections = page("Connections", "Wi-Fi", "Bluetooth", "Flight mode", "Mobile networks")
        assertTrue(Verify.navigationLanded(settings, connections))
        assertFalse("nothing moved", Verify.navigationLanded(settings, settings))
        val ticking = page("Settings", "Connections", "Sounds and vibration", "Notifications", "Display", "18:44")
        assertFalse("the same page with one more line is not a landing", Verify.navigationLanded(settings, ticking))
        assertTrue(Verify.navigationLanded(settings, UiSnapshot("com.whatsapp", "WhatsApp", settings.elements, 1L)))
    }

    // ---------------------------------------------------------------- the messaging rules ride only with a messaging goal

    @Test fun messaging_rules_are_added_only_when_the_goal_is_about_a_message_or_a_call() {
        for (g in listOf("send a Telegram message to Richmond saying hello", "tell Prince I'll call him later", "call Kwabena", "reply to Ama"))
            assertNotNull(g, BrainPrompts.commsRules(g))
        for (g in listOf("what time is it", "open settings", "tell me a joke", "set an alarm for 6"))
            assertNull(g, BrainPrompts.commsRules(g))
        val rules = BrainPrompts.COMMS_RULES
        assertTrue(rules.contains("bot") && rules.contains("Start") && rules.contains("exact chat title") && rules.contains("still in the box"))
        assertTrue("paid on every step of a messaging task — keep it short", rules.length <= 480)
        val ctx = com.tekyerema.agent.BrainContext("send Richmond hello on Telegram", Screens.chatList().toCompactJson(), emptyList(),
            org.json.JSONArray(), org.json.JSONArray())
        val t = BrainPrompts.userText(ctx)
        assertTrue(t.indexOf("USER GOAL") < t.indexOf("MESSAGING RULES") && t.indexOf("MESSAGING RULES") < t.indexOf("ACTIONS SO FAR"))
        assertFalse(BrainPrompts.userText(ctx.copy(goal = "open settings")).contains("MESSAGING RULES"))
    }
}
