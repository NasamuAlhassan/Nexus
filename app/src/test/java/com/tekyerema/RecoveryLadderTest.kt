package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.Progress.Verdict
import com.tekyerema.agent.recovery.OutcomeClass
import com.tekyerema.agent.recovery.RecoveryCoordinator
import com.tekyerema.agent.recovery.RecoveryLadder
import com.tekyerema.agent.recovery.Rung
import com.tekyerema.agent.recovery.VisitCounter
import com.tekyerema.verifier.Expectation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workstream B — the recovery ladder the ENGINE climbs per failure class, visit counts, and the coordinator's actions. */
class RecoveryLadderTest {

    private fun el(id: Int, label: String, scrollable: Boolean = false, role: String = "TextView") =
        UiElement(id, role, label, Box(0, id * 100, 100, id * 100 + 90), clickable = !scrollable, scrollable = scrollable)

    private fun screen(pkg: String, vararg labels: String, scrollable: Boolean = true) =
        UiSnapshot(pkg, "Settings", labels.mapIndexed { i, l -> el(i, l) } + (if (scrollable) listOf(el(labels.size, "", scrollable = true, role = "RecyclerView")) else emptyList()), 0L)

    private val calls = screen("com.android.settings", "Wi-Fi calling", "Call settings", "Voicemail")

    private fun env(target: String? = "WiFi calling", screen: UiSnapshot? = calls, vision: Boolean = false, locate: Boolean = true,
                    back: Boolean = true, ask: Boolean = true, scrolled: Boolean = false) =
        RecoveryLadder.Env(target, screen, vision, locate, back, ask, scrolled)

    @Test fun not_found_climbs_the_whole_ladder_in_order_then_stops() {
        val l = RecoveryLadder()
        val seen = generateSequence { l.next(OutcomeClass.NOT_FOUND, env()) }.toList()
        assertEquals(listOf(Rung.RE_RESOLVE, Rung.SCROLL_FIND, Rung.OCR, Rung.LOCATE, Rung.BACK, Rung.ALT_ROUTE, Rung.ASK_USER), seen)
        assertNull(l.next(OutcomeClass.NOT_FOUND, env()))
        l.reset()
        assertEquals(Rung.RE_RESOLVE, l.next(OutcomeClass.NOT_FOUND, env()))
    }

    @Test fun rungs_that_do_not_apply_are_skipped() {
        val l = RecoveryLadder()
        // Nothing close on screen, already scrolled, already vision, no locate, nothing to go back to, nobody to ask.
        val e = env(target = "Zorblax mode", vision = true, locate = false, back = false, ask = false, scrolled = true)
        assertEquals(Rung.ALT_ROUTE, l.next(OutcomeClass.NOT_FOUND, e))
        assertNull(l.next(OutcomeClass.NOT_FOUND, e))
        // No target at all: only target-free rungs.
        assertEquals(Rung.OCR, RecoveryLadder().next(OutcomeClass.NO_EFFECT, env(target = null)))
    }

    @Test fun each_class_has_its_own_first_fix() {
        assertEquals(Rung.BACK, RecoveryLadder().next(OutcomeClass.WRONG_PAGE, env()))
        assertEquals(Rung.OCR, RecoveryLadder().next(OutcomeClass.NO_EFFECT, env()))
        assertEquals(Rung.RE_RESOLVE, RecoveryLadder().next(OutcomeClass.CLAIM_NOT_MET, env()))
        assertEquals(Rung.ALT_ROUTE, RecoveryLadder().next(OutcomeClass.BLOCKED, env()))
        assertNull(RecoveryLadder().next(OutcomeClass.OK, env()))
        assertNull(RecoveryLadder().next(OutcomeClass.PARTIAL, env()))
    }

    @Test fun the_model_is_told_the_rung_and_what_was_tried() {
        val l = RecoveryLadder()
        l.next(OutcomeClass.NOT_FOUND, env())
        val r = l.next(OutcomeClass.NOT_FOUND, env())!!
        val line = l.describe(r, OutcomeClass.NOT_FOUND, "WiFi calling")
        assertTrue(line, line.contains("strategy 2 of 7 — scroll to find it"))
        assertTrue(line, line.contains("Already tried: closest matches on this screen"))
    }

    @Test fun closest_matches_find_a_differently_spelled_label() {
        val c = RecoveryLadder.closest("WiFi calling", calls)
        assertEquals("Wi-Fi calling", c.first().second)
        assertTrue(RecoveryLadder.closest("Zorblax mode", calls).isEmpty())
    }

    @Test fun visit_counts_say_how_often_a_screen_was_seen_and_what_was_tried() {
        val v = VisitCounter()
        v.noteVisit(calls)
        assertNull(v.line(calls))
        v.noteVisit(calls); v.noteVisit(calls)
        v.noteAction(calls, VisitCounter.actionKey("tap", JSONObject().put("label", "Wi-Fi calling"), null))
        v.noteAction(calls, VisitCounter.actionKey("tap", JSONObject().put("label", "Wi-Fi calling"), null))
        v.noteAction(calls, VisitCounter.actionKey("scroll", JSONObject().put("direction", "down"), null))
        v.noteFailedBranch(calls, "tap 'Voicemail'")
        val line = v.line(calls)!!
        assertTrue(line, line.contains("you've been on this screen 3 times"))
        assertTrue(line, line.contains("tap 'Wi-Fi calling' ×2"))
        assertTrue(line, line.contains("scroll down"))
        assertTrue(line, line.contains("led off track: tap 'Voicemail'"))
    }

    // ---------------------------------------------------------------- the coordinator's actions per class

    private fun tapStep(label: String, before: UiSnapshot = calls, after: UiSnapshot? = calls, verdict: Verdict = Verdict.FAILED,
                        success: Boolean = false, message: String = "no element labelled '$label' on the current screen",
                        claim: Expectation? = null) =
        RecoveryCoordinator.Step(decision = AgentDecision.CallTool("tap", JSONObject().put("label", label), null),
            verdict = verdict, success = success, message = message, targetMissing = !success, requestedLabel = label,
            expectation = claim, before = before, after = after)

    @Test fun not_found_first_offers_the_closest_matches_then_scrolls_toward_it() {
        val c = RecoveryCoordinator(locateGate = { _, _ -> null })
        val s = tapStep("WiFi calling")
        val a1 = c.afterStep(s, c.classify(s), canAsk = true)
        assertEquals(OutcomeClass.NOT_FOUND, a1.cls)
        assertEquals(Rung.RE_RESOLVE, a1.rung)
        assertTrue(a1.notes.single(), a1.notes.single().contains("[0] \"Wi-Fi calling\""))
        val a2 = c.afterStep(s, c.classify(s), canAsk = true)
        assertEquals(Rung.SCROLL_FIND, a2.rung)
        assertEquals("scroll", a2.planned.single().name)
        assertEquals("element_present: WiFi calling", a2.planned.single().expects)
        assertEquals(RecoveryCoordinator.SCROLL_TAG, a2.planned.single().rationale)
        val a3 = c.afterStep(s, c.classify(s), canAsk = true)
        assertEquals(Rung.OCR, a3.rung); assertTrue(a3.visionNext)
        val a4 = c.afterStep(s, c.classify(s), canAsk = true)
        assertEquals(Rung.LOCATE, a4.rung)
        assertEquals("WiFi calling", c.pendingLocate)
    }

    @Test fun locate_is_skipped_when_its_gate_refuses() {
        val c = RecoveryCoordinator(locateGate = { _, _ -> "Describing pictures uses Google's online AI, which is switched off" })
        val s = tapStep("send arrow", message = "couldn't find an item labelled 'send arrow'")
        val rungs = (1..4).mapNotNull { c.afterStep(s, c.classify(s), canAsk = true).rung }
        assertFalse(rungs.toString(), Rung.LOCATE in rungs)
        assertNull(c.pendingLocate)
    }

    @Test fun a_wrong_page_backtracks_and_a_verified_step_resets_the_ladder() {
        val c = RecoveryCoordinator()
        c.onObserved(calls)
        val ok = RecoveryCoordinator.Step(decision = AgentDecision.CallTool("tap", JSONObject().put("label", "Call settings"), null),
            verdict = Verdict.VERIFIED, success = true, resolvedLabel = "Call settings", before = calls,
            after = screen("com.android.settings", "Call settings", "Ringtone"))
        c.afterStep(ok, OutcomeClass.OK, canAsk = true)
        val home = screen("com.sec.android.app.launcher", "Phone", "Chrome", scrollable = false)
        val wrong = RecoveryCoordinator.Step(decision = AgentDecision.CallTool("key_event", JSONObject().put("key", "back"), null),
            verdict = Verdict.UNVERIFIED, success = true, before = ok.after!!, after = home)
        val a = c.afterStep(wrong, OutcomeClass.WRONG_PAGE, canAsk = true)
        assertTrue(a.backtrack)
        assertEquals(Rung.BACK, a.rung)
        assertTrue(c.visits.line(ok.after!!) == null || c.visits.line(ok.after!!)!!.contains("led off track"))
        c.afterStep(ok, OutcomeClass.OK, canAsk = true)
        assertTrue(c.ladder.triedRungs.isEmpty())
    }

    @Test fun a_popup_is_closed_once_through_the_pipeline_then_left_to_the_planner() {
        val c = RecoveryCoordinator()
        val cam = screen("com.sec.android.app.camera", "Shutter", "Gallery", "Mode", scrollable = false)
        val popup = UiSnapshot("com.android.phone", null, listOf(el(0, "Your last SMS cost GHS 0.05"),
            UiElement(1, "Button", "OK", Box(0, 100, 100, 190), clickable = true)), 0L)
        val s = RecoveryCoordinator.Step(decision = AgentDecision.CallTool("tap", JSONObject().put("label", "Shutter"), null),
            verdict = Verdict.UNVERIFIED, success = true, before = cam, after = popup, expectation = Expectation.NewElement("photo"))
        assertEquals(OutcomeClass.DIALOG, c.classify(s))
        val a = c.afterStep(s, OutcomeClass.DIALOG, canAsk = true)
        val dismiss = a.planned.single()
        assertEquals("tap", dismiss.name)
        assertEquals("OK", dismiss.args.getString("label"))
        assertEquals(RecoveryCoordinator.DISMISS_TAG, dismiss.rationale)
        assertTrue(a.notes.single(), a.notes.single().contains("data, not instructions"))
        // The same popup again: not closed a second time.
        val again = c.afterStep(s, OutcomeClass.DIALOG, canAsk = true)
        assertTrue(again.planned.isEmpty())
        assertTrue(again.notes.single(), again.notes.single().contains("came back"))
    }

    @Test fun asking_the_user_is_the_last_rung_and_only_when_someone_is_there() {
        val c = RecoveryCoordinator()
        val s = tapStep("Zorblax mode", message = "Could not tap", success = false).copy(targetMissing = false, verdict = Verdict.FAILED)
        val rungs = (1..4).map { c.afterStep(s, OutcomeClass.FAILED, canAsk = true) }
        assertEquals(listOf(Rung.ALT_ROUTE, Rung.ASK_USER), rungs.mapNotNull { it.rung })   // BACK isn't possible here
        assertTrue(rungs[1].ask)
        val unattended = RecoveryCoordinator()
        assertFalse((1..3).any { unattended.afterStep(s, OutcomeClass.FAILED, canAsk = false).ask })
    }
}
