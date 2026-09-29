package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.operator.Capabilities
import com.tekyerema.operator.Capability
import com.tekyerema.operator.ExecutionMode
import com.tekyerema.operator.OperatorCommands
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.operator.OperatorStops
import com.tekyerema.policy.PolicyDecision
import com.tekyerema.policy.PolicyEngine
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.Tool
import com.tekyerema.tools.defaultTools
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WS-C: execution modes and switched-off capabilities in the policy engine. They only ever RESTRICT: Standard is today's
 * behaviour, and no combination of settings turns a BLOCK or a CONFIRM into anything weaker.
 */
class PolicyOperatorTest {
    private val tools: Map<String, Tool> by lazy { defaultTools().associateBy { it.name } }
    private fun t(name: String) = tools.getValue(name)
    private fun engine(v: OperatorPolicy.View) = PolicyEngine(operator = { v })
    private fun eval(v: OperatorPolicy.View, tool: String, args: JSONObject = JSONObject(), targetTier: RiskTier? = null,
                     reason: String? = null, label: String? = null, app: String? = null): PolicyDecision =
        engine(v).evaluate(t(tool), args, targetTier = targetTier,
            context = PolicyEngine.ActionContext(label, reason, app))

    private val standard = OperatorPolicy.View()
    private val guide = OperatorPolicy.View(mode = ExecutionMode.GUIDE_ONLY)
    private val askEvery = OperatorPolicy.View(mode = ExecutionMode.ASK_EVERY_STEP)

    @Before fun setUp() { AppConfig.loadFrom(emptyList()); OperatorPolicy.resetForTest() }
    @After fun tearDown() { OperatorPolicy.resetForTest() }

    @Test fun standard_is_exactly_todays_behaviour() {
        val today = PolicyEngine(operator = { OperatorPolicy.View() })
        for (tool in tools.values) for (tier in listOf(null, RiskTier.TIER2, RiskTier.TIER3)) {
            val a = today.evaluate(tool, JSONObject(), targetTier = tier)
            val b = engine(standard).evaluate(tool, JSONObject(), targetTier = tier)
            assertEquals(tool.name, a.outcome, b.outcome)
            assertEquals(tool.name, null, b.operatorRule)
        }
        assertEquals(PolicyOutcome.ALLOW, eval(standard, "open_app", JSONObject().put("app", "WhatsApp")).outcome)
        assertEquals(PolicyOutcome.CONFIRM, eval(standard, "make_call", JSONObject().put("contact", "Ama")).outcome)
    }

    @Test fun guide_only_blocks_every_step_that_changes_the_phone_and_explains_how_instead() {
        for (name in listOf("open_app", "tap", "type", "scroll", "swipe", "set_alarm", "make_call", "send_sms", "open_setting", "ask_app")) {
            val d = eval(guide, name)
            assertEquals(name, PolicyOutcome.BLOCK, d.outcome)
            assertEquals("mode:GUIDE_ONLY", d.operatorRule)
            assertTrue(d.reason.contains("explain step by step"))
        }
        // Reading and a web search still work — that's how it guides.
        for (name in listOf("read_state", "read_screen", "read_sms", "read_notifications", "web_search", "ask_user", "cannot_do"))
            assertEquals(name, PolicyOutcome.ALLOW, eval(guide, name).outcome)
    }

    @Test fun ask_every_step_confirms_every_change_with_a_read_back() {
        val d = eval(askEvery, "open_app", JSONObject().put("app", "WhatsApp"))
        assertEquals(PolicyOutcome.CONFIRM, d.outcome)
        assertEquals("mode:ASK_EVERY_STEP", d.operatorRule)
        assertTrue(d.confirmationPrompt!!.contains("WhatsApp"))
        assertEquals(PolicyOutcome.CONFIRM, eval(askEvery, "tap", JSONObject().put("label", "Wi-Fi")).outcome)
        assertEquals(PolicyOutcome.CONFIRM, eval(askEvery, "scroll", JSONObject().put("direction", "down")).outcome)
        // Reads aren't changes; consequential steps stay confirmed as before (not double-asked, not weakened).
        assertEquals(PolicyOutcome.ALLOW, eval(askEvery, "read_state").outcome)
        assertEquals(PolicyOutcome.ALLOW, eval(askEvery, "web_search", JSONObject().put("query", "weather")).outcome)
        val call = eval(askEvery, "make_call", JSONObject().put("contact", "Ama"))
        assertEquals(PolicyOutcome.CONFIRM, call.outcome)
        assertEquals(null, call.operatorRule)
    }

    @Test fun switched_off_messages_block_the_tool_and_the_gui_route() {
        val v = OperatorPolicy.View(revoked = setOf(Capability.MESSAGES))
        assertEquals(PolicyOutcome.BLOCK, eval(v, "send_sms", JSONObject().put("contact", "Ama").put("message", "hi")).outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "reply_to_notification", JSONObject().put("sender", "Ama")).outcome)
        // WhatsApp's Send button, as TargetSemantics sees it.
        val send = eval(v, "tap", targetTier = RiskTier.TIER2, reason = "target 'Send' is a consequential control", label = "Send",
            app = "com.whatsapp")
        assertEquals(PolicyOutcome.BLOCK, send.outcome)
        assertEquals("off:MESSAGES", send.operatorRule)
        assertTrue(send.reason.contains("you can send messages again"))
        // type + submit in a chat box, an unlabelled icon while composing
        assertEquals(PolicyOutcome.BLOCK, eval(v, "type", targetTier = RiskTier.TIER2,
            reason = "type+submit fires the field's action (can send) on 'Message'").outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "tap", targetTier = RiskTier.TIER2,
            reason = "an unlabelled control tapped while composing a message may send it").outcome)
        // Ordinary taps in the same app, and calls, are unaffected.
        assertEquals(PolicyOutcome.ALLOW, eval(v, "tap", label = "Chats", app = "com.whatsapp").outcome)
        assertEquals(PolicyOutcome.CONFIRM, eval(v, "make_call", JSONObject().put("contact", "Ama")).outcome)
    }

    @Test fun switched_off_calls_block_calls_but_never_emergency_help() {
        val v = OperatorPolicy.View(revoked = setOf(Capability.CALLS))
        assertEquals(PolicyOutcome.BLOCK, eval(v, "make_call", JSONObject().put("contact", "Ama")).outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "tap", targetTier = RiskTier.TIER2, reason = "dialer keypad key ('5') — a tap here can dial").outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "tap", targetTier = RiskTier.TIER2,
            reason = "target '024 123 4567' is a phone number — tapping it can place a call").outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "tap", targetTier = RiskTier.TIER2, reason = "target 'Call' is a consequential control", label = "Call").outcome)
        assertEquals(PolicyOutcome.CONFIRM, eval(v, "emergency").outcome)   // still asked, never switched off
    }

    @Test fun switched_off_money_blocks_transfers_money_codes_and_spend_buttons() {
        val v = OperatorPolicy.View(revoked = setOf(Capability.MONEY))
        assertEquals(PolicyOutcome.BLOCK, eval(v, "momo_send", JSONObject().put("number", "0241234567").put("amount", "5")).outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "mobile_money").outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "run_ussd", JSONObject().put("code", "*170#")).outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "tap", targetTier = RiskTier.TIER3, reason = "target 'Pay GH₵ 50' spends money", label = "Pay GH₵ 50").outcome)
        assertEquals(PolicyOutcome.CONFIRM, eval(v, "run_ussd", JSONObject().put("code", "*124#")).outcome)   // a balance check isn't money
    }

    @Test fun switched_off_settings_block_settings_changes_but_not_reading_them() {
        val v = OperatorPolicy.View(revoked = setOf(Capability.SETTINGS))
        assertEquals(PolicyOutcome.BLOCK, eval(v, "open_setting", JSONObject().put("target", "wifi")).outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "tap", label = "Wi-Fi", app = "com.android.settings").outcome)
        assertEquals(PolicyOutcome.BLOCK, eval(v, "tap", label = "Bluetooth", app = "com.android.systemui").outcome)
        assertEquals(PolicyOutcome.ALLOW, eval(v, "read_state", app = "com.android.settings").outcome)
        assertEquals(PolicyOutcome.ALLOW, eval(v, "tap", label = "Chats", app = "com.whatsapp").outcome)
    }

    @Test fun switched_off_web_and_cloud_off_block_the_online_tools() {
        val noWeb = OperatorPolicy.View(revoked = setOf(Capability.WEB))
        assertEquals(PolicyOutcome.BLOCK, eval(noWeb, "web_search", JSONObject().put("query", "weather")).outcome)
        val off = OperatorPolicy.View(cloudAllowed = false)
        val w = eval(off, "web_search", JSONObject().put("query", "weather"))
        assertEquals(PolicyOutcome.BLOCK, w.outcome); assertEquals("cloud-off", w.operatorRule)
        assertEquals(PolicyOutcome.BLOCK, eval(off, "describe_screen").outcome)
        assertEquals(PolicyOutcome.ALLOW, eval(off, "open_app", JSONObject().put("app", "Clock")).outcome)   // on-phone work goes on
        val spent = OperatorPolicy.View(budgetSpent = true)
        assertEquals("budget", eval(spent, "web_search", JSONObject().put("query", "x")).operatorRule)
    }

    @Test fun no_setting_ever_lowers_the_floor() {
        fun rank(o: PolicyOutcome) = when (o) { PolicyOutcome.ALLOW -> 0; PolicyOutcome.CONFIRM -> 1; PolicyOutcome.BLOCK -> 2 }
        val revokedSets = listOf(emptySet<Capability>(), Capability.entries.toSet()) + Capability.entries.map { setOf(it) }
        val views = ExecutionMode.entries.flatMap { m ->
            revokedSets.flatMap { r -> listOf(
                OperatorPolicy.View(m, r), OperatorPolicy.View(m, r, cloudAllowed = false), OperatorPolicy.View(m, r, budgetSpent = true)) }
        }
        var checked = 0
        for (tool in tools.values) for (tier in listOf(null, RiskTier.TIER2, RiskTier.TIER3)) for (block in listOf(null, "PIN field"))
            for (untrusted in listOf(false, true)) {
                val base = engine(standard).evaluate(tool, JSONObject(), untrusted, tier, block)
                for (v in views) {
                    val d = engine(v).evaluate(tool, JSONObject(), untrusted, tier, block)
                    assertTrue("${tool.name} tier=$tier block=$block $v: ${base.outcome} → ${d.outcome}", rank(d.outcome) >= rank(base.outcome))
                    if (block != null) assertEquals(PolicyOutcome.BLOCK, d.outcome)
                    if (base.outcome == PolicyOutcome.CONFIRM) assertTrue(d.outcome != PolicyOutcome.ALLOW)
                    checked++
                }
            }
        assertTrue(checked > 10_000)
    }

    @Test fun a_switched_off_capability_ends_the_task_with_the_users_reason_and_guide_only_explains_first() {
        assertNull(OperatorStops.afterBlock(1, null))   // an ordinary safety block: the model carries on as before
        assertEquals(Capabilities.revokedLine(Capability.MESSAGES), OperatorStops.afterBlock(1, "off:MESSAGES"))
        assertNull(OperatorStops.afterBlock(2, "mode:GUIDE_ONLY"))                                  // first: the model explains how
        assertEquals(OperatorStops.GUIDE_STOP_LINE, OperatorStops.afterBlock(2, "mode:GUIDE_ONLY"))   // tries to act again: stop
        assertNull(OperatorStops.afterBlock(3, "mode:GUIDE_ONLY"))                                  // a new task starts fresh
        assertNull(OperatorStops.afterBlock(3, "cloud-off"))
        assertTrue(Capabilities.revokedLine(Capability.MONEY).startsWith("You've switched off “Money”, so I won't move money for you."))
    }

    @Test fun the_default_engine_reads_the_users_live_choices() {
        val engine = PolicyEngine()   // what AgentEngine and the fast path construct
        val open = t("open_app"); val args = JSONObject().put("app", "WhatsApp")
        assertEquals(PolicyOutcome.ALLOW, engine.evaluate(open, args).outcome)
        OperatorPolicy.executionMode = ExecutionMode.ASK_EVERY_STEP
        assertEquals(PolicyOutcome.CONFIRM, engine.evaluate(open, args).outcome)   // the next decision, same engine
        OperatorPolicy.executionMode = ExecutionMode.GUIDE_ONLY
        assertEquals(PolicyOutcome.BLOCK, engine.evaluate(open, args).outcome)
        OperatorPolicy.executionMode = ExecutionMode.STANDARD
        assertEquals(PolicyOutcome.ALLOW, engine.evaluate(open, args).outcome)
    }

    @Test fun a_voice_command_changes_what_the_engine_does_next() {
        val engine = PolicyEngine()
        val sms = t("send_sms"); val args = JSONObject().put("contact", "Ama").put("message", "hi")
        assertEquals(PolicyOutcome.CONFIRM, engine.evaluate(sms, args).outcome)
        OperatorCommands.applyControl(OperatorCommands.classify("Never send messages for me")!!)
        assertEquals(PolicyOutcome.BLOCK, engine.evaluate(sms, args).outcome)
        OperatorCommands.applyControl(OperatorCommands.classify("You can send messages again")!!)
        assertEquals(PolicyOutcome.CONFIRM, engine.evaluate(sms, args).outcome)

        OperatorCommands.applyControl(OperatorCommands.classify("Ask me before every step")!!)
        assertEquals(PolicyOutcome.CONFIRM, engine.evaluate(t("tap"), JSONObject().put("label", "Wi-Fi")).outcome)
        OperatorCommands.applyControl(OperatorCommands.classify("normal mode")!!)
        assertEquals(PolicyOutcome.ALLOW, engine.evaluate(t("tap"), JSONObject().put("label", "Wi-Fi")).outcome)

        OperatorCommands.applyControl(OperatorCommands.classify("switch to offline mode")!!)
        assertEquals(PolicyOutcome.BLOCK, engine.evaluate(t("web_search"), JSONObject().put("query", "news")).outcome)
        OperatorCommands.applyControl(OperatorCommands.classify("use the cloud again")!!)
        assertEquals(PolicyOutcome.ALLOW, engine.evaluate(t("web_search"), JSONObject().put("query", "news")).outcome)
    }
}
