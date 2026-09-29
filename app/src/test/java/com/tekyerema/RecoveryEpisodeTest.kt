package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.Progress.Verdict
import com.tekyerema.agent.TaskMemory
import com.tekyerema.agent.plan.PlanTracker
import com.tekyerema.agent.recovery.Backtracker
import com.tekyerema.agent.recovery.OutcomeClass
import com.tekyerema.agent.recovery.RecoveryCoordinator
import com.tekyerema.verifier.Expects
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream B — one scripted episode through the plan tracker and the recovery coordinator in the order AgentEngine
 * calls them each step (observe → classify → afterStep → plan.onStep → apply), with injected faults: a tap that lands
 * on the home screen (→ engine backtrack), a carrier popup (→ closed through the pipeline), then the completion gate.
 * Not the engine itself (Workstream A's loop harness covers that) — the B pieces together, deterministically.
 */
class RecoveryEpisodeTest {

    private fun el(id: Int, label: String, role: String = "TextView", clickable: Boolean = true) =
        UiElement(id, role, label, Box(0, id * 100, 100, id * 100 + 90), clickable = clickable)

    private fun screen(pkg: String, title: String?, vararg labels: String) =
        UiSnapshot(pkg, title, labels.mapIndexed { i, l -> el(i, l) }, 0L, appName = pkg.substringAfterLast('.'))

    private val S = "com.android.settings"
    private val home = screen("com.sec.android.app.launcher", null, "Phone", "Messages", "Settings", "Chrome")
    private val settings = screen(S, "Settings", "Connections", "Display", "About phone")
    private val about = screen(S, "About phone", "Phone name", "Software information", "Status")
    private val software = screen(S, "Software information", "Android version 11", "One UI version", "Build number")

    private class Ops(private val after: List<UiSnapshot>) : Backtracker.Ops {
        var presses = 0
        override suspend fun back(): Boolean { presses++; return true }
        override suspend fun observe(): UiSnapshot? = after[minOf(presses - 1, after.lastIndex)]
    }

    @Test fun wrong_page_is_backtracked_a_popup_is_closed_and_done_waits_for_every_subgoal() = runBlocking {
        val memory = TaskMemory()
        val rec = RecoveryCoordinator()
        val history = ArrayList<String>()
        val planned = ArrayList<AgentDecision.CallTool>()

        suspend fun step(d: AgentDecision.CallTool, before: UiSnapshot, after: UiSnapshot?, verdict: Verdict, label: String? = null): OutcomeClass {
            val s = RecoveryCoordinator.Step(decision = d, verdict = verdict, success = true, resolvedLabel = label,
                requestedLabel = d.args.optString("label").ifBlank { null }, expectation = Expects.parse(d.expects),
                before = before, after = after, launcherPkg = home.appPackage)
            val cls = rec.classify(s)
            val acts = rec.afterStep(s, cls, canAsk = true)
            val ticked = memory.plan.onStep(PlanTracker.StepEvidence(verdict == Verdict.VERIFIED, false, cls.failure, before, after))
            if (ticked.isNotEmpty()) rec.ladder.reset()
            history.addAll(acts.notes); planned.addAll(acts.planned)
            if (acts.backtrack) {
                val r = rec.backtracker.run(after ?: before, Ops(listOf(about)))
                history.add("BACKTRACK: $r")
            }
            return cls
        }

        // Step 1: the model opens Settings and sends its plan with that first action.
        rec.onObserved(home)
        val open = AgentDecision.fromToolCall("open_app", JSONObject().put("app", "Settings").put("expects", "foreground_is: settings")
            .put("plan", "Open Settings => foreground_is: settings; Open About phone => element_present: Software information; " +
                "Open Software information => element_present: Android version"), null) as AgentDecision.CallTool
        assertTrue(memory.plan.offer(open.plan) != null)
        assertEquals(OutcomeClass.OK, step(open, home, settings, Verdict.VERIFIED))
        // Step 2: into About phone.
        val tapAbout = AgentDecision.CallTool("tap", JSONObject().put("label", "About phone"), null, "element_present: Software information")
        assertEquals(OutcomeClass.OK, step(tapAbout, settings, about, Verdict.VERIFIED, "About phone"))
        assertEquals(3, memory.plan.active!!.id)
        // Step 3 (fault): the tap lands on the home screen → WRONG_PAGE → the engine goes Back to About phone.
        val tapSw = AgentDecision.CallTool("tap", JSONObject().put("label", "Software information"), null, "element_present: Android version")
        assertEquals(OutcomeClass.WRONG_PAGE, step(tapSw, about, home, Verdict.UNVERIFIED, "Software information"))
        assertTrue(history.last(), history.last().contains("Reached") && history.last().contains("about phone"))
        // Step 4 (fault): a carrier popup covers the screen → it is closed with its OK, through the normal pipeline.
        val popup = UiSnapshot("com.android.phone", null, listOf(el(0, "Your SMS cost GHS 0.05", clickable = false),
            el(1, "OK", role = "Button")), 0L)
        assertEquals(OutcomeClass.DIALOG, step(tapSw, about, popup, Verdict.UNVERIFIED, "Software information"))
        assertEquals("OK", planned.single().args.getString("label"))
        // A `done` now is refused: subgoal 3 is still open — the model is told what's missing.
        val g = memory.plan.gate(about)
        assertTrue(g is PlanTracker.Gate.Refuse)
        assertTrue((g as PlanTracker.Gate.Refuse).note.contains("3. Open Software information"))
        // Two failures in a row on subgoal 3 asked for a replan in the next prompt.
        assertTrue(memory.render().contains("REPLAN: subgoal 3"))
        // Step 5: the retry works; the plan completes and the gate accepts.
        assertEquals(OutcomeClass.OK, step(tapSw, about, software, Verdict.VERIFIED, "Software information"))
        assertEquals(PlanTracker.Gate.Accept, memory.plan.gate(software))
        assertTrue(memory.plan.toSubgoals().all { it.done })
    }
}
