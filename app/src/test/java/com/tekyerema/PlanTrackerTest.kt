package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.TaskMemory
import com.tekyerema.agent.plan.PlanParser
import com.tekyerema.agent.plan.PlanTracker
import com.tekyerema.agent.plan.SubgoalStatus
import com.tekyerema.tools.ToolArgs
import com.tekyerema.tools.ToolRegistry
import com.tekyerema.tools.defaultTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workstream B — the explicit plan: parsing, the `plan` argument's path, ticking from verified outcomes, replans, the gate. */
class PlanTrackerTest {

    private fun el(id: Int, label: String, editable: Boolean = false) =
        UiElement(id, "TextView", label, Box(0, id * 100, 100, id * 100 + 90), clickable = true, editable = editable)

    private fun screen(pkg: String, vararg labels: String) = UiSnapshot(pkg, null, labels.mapIndexed { i, l -> el(i, l) }, 0L)

    private val WA = "com.whatsapp"
    private val planText = "Open WhatsApp => foreground_is: whatsapp; Open Ama's chat => element_present: Ama Mensah; " +
        "Type the message => field_contains: |I'm late; Send it => new_element: I'm late"

    // ---------------------------------------------------------------- parsing

    @Test fun parses_subgoals_with_checks() {
        val p = PlanParser.parse(planText)!!
        assertEquals(4, p.size)
        assertEquals("Open WhatsApp", p[0].text)
        assertEquals("foreground_is: whatsapp", p[0].check)
        assertEquals(listOf(1, 2, 3, 4), p.map { it.id })
        assertTrue(p.all { it.status == SubgoalStatus.PENDING })
    }

    @Test fun parses_numbered_lines_arrows_and_missing_checks() {
        val p = PlanParser.parse("1. Open Settings -> foreground_is: settings\n2) Find About phone → element_present: About phone\n- Read the version")!!
        assertEquals(3, p.size)
        assertEquals("Find About phone", p[1].text)
        assertEquals("element_present: About phone", p[1].check)
        assertNull(p[2].check)
        assertFalse(p[2].checkable)
    }

    @Test fun inline_numbering_without_separators_is_split() {
        val p = PlanParser.parse("1. open Chrome => foreground_is: chrome 2. search for waakye => element_present: waakye")!!
        assertEquals(2, p.size)
        assertEquals("open Chrome", p[0].text)
    }

    @Test fun a_single_step_is_not_a_plan_and_more_than_seven_are_cut() {
        assertNull(PlanParser.parse("Open WhatsApp => foreground_is: whatsapp"))
        assertNull(PlanParser.parse("   "))
        assertNull(PlanParser.parse(null))
        val many = (1..10).joinToString("; ") { "step $it => element_present: x$it" }
        assertEquals(PlanParser.MAX_ITEMS, PlanParser.parse(many)!!.size)
    }

    @Test fun skip_marks_a_subgoal_waived_with_its_reason() {
        val p = PlanParser.parse("Open WhatsApp => foreground_is: whatsapp; skip: send a copy to Kofi — he already has it; " +
            "Send it => element_present: Sent")!!
        assertEquals(SubgoalStatus.SKIPPED, p[1].status)
        assertEquals("send a copy to Kofi", p[1].text)
        assertTrue(p[1].evidence!!.contains("he already has it"))
        val q = PlanParser.parse("open the menu => skip: not needed; tap Send => element_present: Sent")!!
        assertEquals(SubgoalStatus.SKIPPED, q[0].status)
    }

    @Test fun a_plan_sent_as_a_json_array_is_accepted() {
        val strings = JSONArray().put("Open WhatsApp => foreground_is: whatsapp").put("Send it => element_present: Sent")
        assertEquals(2, PlanParser.parse(PlanParser.flatten(strings))!!.size)
        val objects = JSONArray().put(JSONObject().put("subgoal", "Open Settings").put("check", "foreground_is: settings"))
            .put(JSONObject().put("step", "Open About phone").put("check", "element_present: About phone"))
        val p = PlanParser.parse(PlanParser.flatten(objects))!!
        assertEquals("foreground_is: settings", p[0].check)
    }

    // ---------------------------------------------------------------- the `plan` argument's path

    @Test fun plan_is_stripped_from_the_tool_args_before_validation_and_execution() {
        val args = JSONObject().put("app", "WhatsApp").put("expects", "foreground_is: whatsapp").put("plan", planText)
        val d = AgentDecision.fromToolCall("open_app", args, null) as AgentDecision.CallTool
        assertEquals(planText, d.plan)
        assertEquals("foreground_is: whatsapp", d.expects)
        assertFalse(d.args.has("plan"))
        assertFalse(d.args.has("expects"))
        val tool = ToolRegistry(defaultTools()) { true }.get("open_app")!!
        assertNull("the remaining args are valid", ToolArgs.validate(tool, d.args))
        // An array-valued plan is flattened, not rejected.
        val arr = AgentDecision.fromToolCall("tap", JSONObject().put("label", "Chats")
            .put("plan", JSONArray().put("a => element_present: a").put("b => element_present: b")), null) as AgentDecision.CallTool
        assertEquals(2, PlanParser.parse(arr.plan)!!.size)
        assertFalse(arr.args.has("plan"))
    }

    @Test fun acting_tools_declare_the_plan_argument_and_reads_do_not() {
        val tools = ToolRegistry(defaultTools()) { true }.openAiTools()
        fun props(name: String) = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == name }.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props("tap").has("plan"))
        assertTrue(props("open_app").has("plan"))
        assertTrue(props("tap_sequence").has("plan"))
        assertFalse(props("read_state").has("plan"))
        assertEquals("string", props("tap").getJSONObject("plan").getString("type"))
    }

    // ---------------------------------------------------------------- tracking

    @Test fun a_subgoal_is_ticked_only_from_a_verified_outcome_whose_check_holds() {
        val t = PlanTracker(replanAfterFailures = 2, maxGateRefusals = 2)
        assertNotNull(t.offer(planText))
        val home = screen("com.sec.android.app.launcher", "WhatsApp", "Chrome")
        val chats = screen(WA, "Chats", "Ama Mensah", "Kofi")
        // An unverified step never ticks, even when the check happens to hold.
        assertTrue(t.onStep(PlanTracker.StepEvidence(verified = false, read = false, failed = true, before = home, after = chats)).isEmpty())
        // A verified step ticks the active subgoal AND the next one whose check also holds on this screen.
        val done = t.onStep(PlanTracker.StepEvidence(verified = true, read = false, failed = false, before = home, after = chats))
        assertEquals(listOf(1, 2), done.map { it.id })
        assertEquals(3, t.active!!.id)
        assertEquals("check held", t.items[0].evidence)
    }

    @Test fun a_verified_step_does_not_tick_a_subgoal_whose_check_fails() {
        val t = PlanTracker()
        t.offer(planText)
        val stillHome = screen("com.sec.android.app.launcher", "WhatsApp")
        assertTrue(t.onStep(PlanTracker.StepEvidence(true, false, false, stillHome, stillHome)).isEmpty())
        assertEquals(1, t.active!!.id)
    }

    @Test fun a_subgoal_without_a_checkable_claim_is_ticked_by_the_step_that_verified_while_it_was_active() {
        val t = PlanTracker()
        t.offer("Read the latest SMS; Tell the user what it says")
        val s = screen("com.samsung.android.messaging", "Messages")
        val done = t.onStep(PlanTracker.StepEvidence(verified = false, read = true, failed = false, before = s, after = null))
        assertEquals(listOf(1), done.map { it.id })   // only the first — never a whole run of unchecked subgoals
        assertEquals("read succeeded", done[0].evidence)
    }

    @Test fun two_consecutive_failures_on_a_subgoal_ask_for_a_replan_once() {
        val t = PlanTracker(replanAfterFailures = 2)
        t.offer(planText)
        val s = screen("com.sec.android.app.launcher", "WhatsApp")
        t.onStep(PlanTracker.StepEvidence(false, false, true, s, s))
        assertFalse(t.replanPending)
        t.onStep(PlanTracker.StepEvidence(false, false, true, s, s))
        assertTrue(t.replanPending)
        val block = t.promptBlock()
        assertTrue(block, block.contains("REPLAN: subgoal 1"))
        assertFalse("shown once", t.promptBlock().contains("REPLAN"))
        assertEquals(1, t.replansRequested)
    }

    @Test fun a_plan_running_past_twice_its_size_is_rethought_once() {
        val t = PlanTracker(replanAfterFailures = 99)
        t.offer("Open Settings => foreground_is: settings; Open About phone => element_present: Software information")
        assertEquals(6, t.softBudget())   // 2 per subgoal + 2, at least 6
        val s = screen("com.sec.android.app.launcher", "Settings")
        repeat(5) { t.onStep(PlanTracker.StepEvidence(false, false, false, s, s)) }   // exploration: no ticks, no failures
        assertFalse(t.replanPending)
        t.onStep(PlanTracker.StepEvidence(false, false, false, s, s))
        assertTrue(t.replanPending)
        assertTrue(t.promptBlock().contains("taking longer than planned (6 steps for 2 subgoals"))
        repeat(10) { t.onStep(PlanTracker.StepEvidence(false, false, false, s, s)) }
        assertFalse("once per plan version", t.replanPending)
    }

    @Test fun a_schema_mismatch_gets_a_nudge_that_names_the_argument() {
        val body = """{"error":{"message":"tool call validation failed: parameters for tool open_app did not match schema: errors: [`/plan`: expected string, but got array]","type":"invalid_request_error","code":"tool_use_failed"}}"""
        val n = com.tekyerema.agent.OpenAiCompatBrain.toolUseFailedNudge(body)
        assertTrue(n, n.contains("didn't match the tool's schema") && n.contains("/plan") && n.contains("ONE string"))
        val unknown = com.tekyerema.agent.OpenAiCompatBrain.toolUseFailedNudge("""{"error":{"message":"Failed to call a function. Please adjust your prompt.","code":"tool_use_failed"}}""")
        assertTrue(unknown.contains("does not exist"))
    }

    @Test fun a_finished_subgoal_prompts_a_proactive_check_of_the_plan() {
        val t = PlanTracker()
        t.offer(planText)
        t.onStep(PlanTracker.StepEvidence(true, false, false, screen("x"), screen(WA, "Chats")))
        val block = t.promptBlock()
        assertTrue(block, block.contains("[x] 1. Open WhatsApp"))
        assertTrue(block, block.contains("[>] 2. Open Ama's chat — check: element_present: Ama Mensah"))
        assertTrue(block, block.contains("Subgoal 1 done. Now 2"))
    }

    @Test fun a_replan_keeps_what_is_done_and_notes_what_it_dropped() {
        val t = PlanTracker()
        t.offer(planText)
        t.onStep(PlanTracker.StepEvidence(true, false, false, screen("x"), screen(WA, "Chats")))
        val log = t.offer("Search for Ama => element_present: Ama Mensah; Open her chat => element_present: Message; Send it => new_element: I'm late")!!
        assertTrue(log, log.startsWith("replan v2"))
        assertEquals("Open WhatsApp", t.items[0].text)
        assertEquals(SubgoalStatus.DONE, t.items[0].status)          // kept, though the new plan left it out
        assertEquals(listOf("Open WhatsApp", "Search for Ama", "Open her chat", "Send it"), t.items.map { it.text })
        assertTrue(t.items.drop(1).all { it.open })                  // new subgoals start open — statuses are the engine's
        val block = t.promptBlock()
        assertTrue(block, block.contains("dropped at replan: Open Ama's chat; Type the message"))
    }

    @Test fun the_plan_argument_costs_little_schema() {
        val reg = ToolRegistry(defaultTools()) { true }
        val sel = com.tekyerema.agent.ToolSelector.select("open chrome", emptySet(), emptySet(), reg.all.map { it.name })
        val withPlan = reg.openAiTools(sel).toString().length to reg.openAiTools().toString().length
        com.tekyerema.core.config.AppConfig.loadFrom(emptyList(), """{"agent":{"plan":{"enabled":false}}}""")
        val without = try { reg.openAiTools(sel).toString().length to reg.openAiTools().toString().length }
            finally { com.tekyerema.core.config.AppConfig.loadFrom(emptyList()) }
        println("[PROMPT-BUDGET] plan arg: subset ${without.first} → ${withPlan.first} chars, full ${without.second} → ${withPlan.second}")
        assertTrue("per-step subset grew by ${withPlan.first - without.first}", withPlan.first - without.first <= 300)
        assertTrue(withPlan.first > without.first)
    }

    // ---------------------------------------------------------------- completion gate

    @Test fun done_is_refused_while_a_subgoal_is_open_and_says_what_is_missing() {
        val t = PlanTracker(maxGateRefusals = 2)
        t.offer(planText)
        t.onStep(PlanTracker.StepEvidence(true, false, false, screen("x"), screen(WA, "Chats", "Ama Mensah")))
        val g = t.gate(screen(WA, "Ama Mensah", "Type a message"))
        assertTrue(g is PlanTracker.Gate.Refuse)
        g as PlanTracker.Gate.Refuse
        assertEquals(listOf(3, 4), g.open.map { it.id })
        assertTrue(g.note, g.note.contains("3. Type the message") && g.note.contains("skip:"))
    }

    @Test fun the_gate_checks_rules_first_on_the_final_screen() {
        val t = PlanTracker()
        t.offer("Open Settings => foreground_is: settings; Find the version => element_present: Android version")
        // Nothing ticked during the run, but the final screen shows both.
        val g = t.gate(screen("com.android.settings", "Software information", "Android version 11"))
        assertEquals(PlanTracker.Gate.Accept, g)
        assertTrue(t.items.all { it.evidence == "check held on the final screen" })
    }

    @Test fun skipped_subgoals_pass_the_gate_and_repeated_refusals_end_unverified() {
        val t = PlanTracker(maxGateRefusals = 1)
        t.offer("Open WhatsApp => foreground_is: whatsapp; skip: attach a photo — none was taken")
        t.onStep(PlanTracker.StepEvidence(true, false, false, screen("x"), screen(WA, "Chats")))
        assertEquals(PlanTracker.Gate.Accept, t.gate(screen(WA, "Chats")))
        val u = PlanTracker(maxGateRefusals = 1)
        u.offer(planText)
        assertTrue(u.gate(screen("x")) is PlanTracker.Gate.Refuse)
        assertTrue(u.gate(screen("x")) is PlanTracker.Gate.Unverified)
        assertEquals(PlanTracker.Gate.Accept, PlanTracker().gate(screen("x")))   // no plan: nothing to gate
    }

    @Test fun the_plan_goes_to_the_journal_as_subgoals() {
        val t = PlanTracker()
        t.offer(planText)
        t.onStep(PlanTracker.StepEvidence(true, false, false, screen("x"), screen(WA, "Chats")))
        val sg = t.toSubgoals()
        assertEquals(4, sg.size)
        assertTrue(sg[0].done)
        assertFalse(sg[3].done)
        assertEquals("new_element: I'm late", sg[3].successCheck)
    }

    @Test fun multi_part_goals_get_a_plan_nudge_and_task_memory_renders_the_plan() {
        assertTrue(PlanTracker.looksMultiStep("open WhatsApp and send Ama the photo I just took"))
        assertFalse(PlanTracker.looksMultiStep("open WhatsApp"))
        val m = TaskMemory()
        m.wantPlan = true
        assertTrue(m.render().contains("Add the `plan` argument"))
        m.plan.offer(planText)
        val r = m.render()
        assertTrue(r, r.startsWith("PLAN (x=done"))
        assertFalse(r.contains("Add the `plan` argument"))
        m.visitLine = "VISITS: you've been on this screen 2 times"
        assertTrue(m.render().contains("VISITS:"))
    }
}
