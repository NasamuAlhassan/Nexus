package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.teach.HandOverWhy
import com.tekyerema.teach.ReplayEngine
import com.tekyerema.teach.ReplayHost
import com.tekyerema.teach.ReplayResult
import com.tekyerema.teach.StepKind
import com.tekyerema.teach.StepMatcher
import com.tekyerema.teach.StepRun
import com.tekyerema.teach.TeachSkill
import com.tekyerema.teach.TeachStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teach mode: replay checks the screen and the element before every step, and never taps blindly. */
class TeachReplayTest {
    private val mtn = "com.mtn.momo"

    private fun el(id: Int, label: String?, viewId: String? = null, clickable: Boolean = true, editable: Boolean = false,
                   hint: String? = null, scrollable: Boolean = false, enabled: Boolean = true) =
        UiElement(id, if (editable) "EditText" else "Button", label, Box(0, id * 100, 500, id * 100 + 80), clickable = clickable,
            editable = editable, viewId = viewId, hint = hint, scrollable = scrollable, enabled = enabled)

    private fun screen(pkg: String?, vararg els: UiElement) = UiSnapshot(pkg, "Screen", els.toList(), 0L)

    private fun click(label: String?, viewId: String? = null, sensitive: Boolean = false) =
        TeachStep(StepKind.CLICK, mtn, appName = "MyMTN", label = label, viewId = viewId, sensitive = sensitive)
    private val open = TeachStep(StepKind.OPEN_APP, mtn, appName = "MyMTN")

    // ---------------------------------------------------------------- matching

    @Test fun the_wrong_app_in_front_is_not_a_match() {
        val m = StepMatcher.find(click("Data"), screen("com.whatsapp", el(1, "Data")))
        assertTrue(m is StepMatcher.Match.WrongApp)
    }

    @Test fun an_element_is_found_by_its_resource_id_even_when_the_label_moved() {
        val m = StepMatcher.find(click("Data bundle balance GHS 12.50", "$mtn:id/data_tile"), screen(mtn, el(1, "Data bundle balance GHS 8.00", "$mtn:id/data_tile")))
        assertEquals(1, (m as StepMatcher.Match.Found).element.id)
    }

    @Test fun a_steady_label_that_disagrees_with_the_id_is_a_different_thing() {
        val m = StepMatcher.find(click("Data", "$mtn:id/tile"), screen(mtn, el(1, "Voice", "$mtn:id/tile")))
        assertTrue(m is StepMatcher.Match.Missing)
    }

    @Test fun a_shared_id_is_told_apart_by_label() {
        val m = StepMatcher.find(click("Ama", "$mtn:id/row"), screen(mtn, el(1, "Kofi", "$mtn:id/row"), el(2, "Ama", "$mtn:id/row")))
        assertEquals(2, (m as StepMatcher.Match.Found).element.id)
    }

    @Test fun a_label_is_grounded_like_any_tap_and_a_paragraph_is_not_a_match() {
        assertEquals(2, ((StepMatcher.find(click("Balance"), screen(mtn, el(1, "Home"), el(2, "Balance"))) as StepMatcher.Match.Found).element.id))
        val m = StepMatcher.find(click("Balance"), screen(mtn, el(1, "Your balance is low, top up soon to keep your bundle running through the month")))
        assertTrue(m is StepMatcher.Match.Missing)
    }

    @Test fun a_disabled_control_is_not_matched() {
        assertTrue(StepMatcher.find(click("Data"), screen(mtn, el(1, "Data", enabled = false))) is StepMatcher.Match.Missing)
    }

    @Test fun a_typing_step_finds_its_box_by_hint_or_id() {
        val step = TeachStep(StepKind.TYPE, mtn, hint = "Search", viewId = "$mtn:id/q", textLength = 3)
        assertEquals(2, (StepMatcher.find(step, screen(mtn, el(1, "Go"), el(2, "data", "$mtn:id/q", editable = true, hint = "Search"))) as StepMatcher.Match.Found).element.id)
        val byHint = step.copy(viewId = null)
        assertEquals(3, (StepMatcher.find(byHint, screen(mtn, el(3, null, editable = true, hint = "Search"))) as StepMatcher.Match.Found).element.id)
        assertTrue(StepMatcher.find(byHint, screen(mtn, el(3, null, editable = true, hint = "Name"))) is StepMatcher.Match.Missing)
    }

    // ---------------------------------------------------------------- replay

    /** A phone with a few screens: [screens] by name, [now] is the one in front; acting on a label moves to [next]. */
    private class Phone(val screens: Map<String, UiSnapshot>, start: String?, val next: Map<String, String> = emptyMap()) : ReplayHost {
        var now: String? = start
        var privateScreen = false
        var blocked: String? = null
        var actAnswer: StepRun = StepRun.Ok
        val acted = ArrayList<String>()
        val opened = ArrayList<String>()
        override suspend fun snapshot(): UiSnapshot? = now?.let { screens[it] }
        override fun foregroundPackage(): String? = now?.let { screens[it]?.appPackage }
        override fun isPrivate(snap: UiSnapshot) = privateScreen
        override fun appBlocked(pkg: String?) = blocked.takeIf { pkg == "com.blocked" }
        override suspend fun openApp(step: TeachStep): StepRun { opened.add(step.pkg); now = "home"; return StepRun.Ok }
        override suspend fun act(step: TeachStep, element: UiElement?, snap: UiSnapshot): StepRun {
            if (actAnswer != StepRun.Ok) return actAnswer
            acted.add(step.kind.name + ":" + (element?.label ?: step.direction)); next[element?.label ?: ""]?.let { now = it }; return StepRun.Ok
        }
        override suspend fun reveal(step: TeachStep, snap: UiSnapshot): UiSnapshot? = null
        override suspend fun pause(ms: Long) {}
    }

    private fun phone(start: String? = null) = Phone(mapOf(
        "home" to screen(mtn, el(1, "Data"), el(2, "Voice")),
        "data" to screen(mtn, el(1, "Balance"), el(2, "Back")),
        "other" to screen("com.whatsapp", el(1, "Chats"))), start, mapOf("Data" to "data"))

    private fun run(skill: TeachSkill, p: Phone) = runBlocking { ReplayEngine.run(skill, p) }

    @Test fun a_taught_skill_replays_step_by_step() {
        val p = phone()
        val r = run(TeachSkill("data bundle", listOf(open, click("Data"), click("Balance"))), p)
        assertEquals(ReplayResult.Done(ran = 3, skipped = 0, endsAtPrivate = false), r)
        assertEquals(listOf(mtn), p.opened)
        assertEquals(listOf("CLICK:Data", "CLICK:Balance"), p.acted)
    }

    @Test fun opening_an_app_that_is_already_in_front_is_skipped() {
        val p = phone("home")
        val r = run(TeachSkill("x", listOf(open, click("Data"))), p) as ReplayResult.Done
        assertEquals(1, r.skipped); assertTrue(p.opened.isEmpty())
    }

    @Test fun a_missing_element_stops_and_hands_the_rest_to_the_agent_without_tapping() {
        val p = phone("home")
        val steps = listOf(click("Data"), click("Balance"), click("Back"))
        // "Balance" is not on the second screen of this phone: pretend the app changed.
        val changed = Phone(mapOf("home" to p.screens["home"]!!, "data" to screen(mtn, el(1, "Usage"))), "home", mapOf("Data" to "data"))
        val r = run(TeachSkill("x", steps), changed) as ReplayResult.HandOver
        assertEquals(HandOverWhy.MISSING, r.why)
        assertEquals(1, r.index)
        assertEquals(steps.drop(1), r.remaining)
        assertTrue(r.line.contains("Balance"))
        assertEquals(listOf("CLICK:Data"), changed.acted)     // nothing was tapped blindly
    }

    @Test fun a_step_recorded_as_sensitive_is_never_done_by_the_replay() {
        val p = phone("home")
        val r = run(TeachSkill("x", listOf(click("Data", sensitive = true))), p) as ReplayResult.HandOver
        assertEquals(HandOverWhy.NEEDS_YES, r.why)
        assertTrue(p.acted.isEmpty())
    }

    @Test fun a_step_the_policy_would_ask_about_goes_to_the_agent_for_its_read_back() {
        val p = phone("home"); p.actAnswer = StepRun.NeedsYes("Send this?")
        val r = run(TeachSkill("x", listOf(click("Data"))), p) as ReplayResult.HandOver
        assertEquals(HandOverWhy.NEEDS_YES, r.why)
        assertEquals(1, r.remaining.size)
        val b = phone("home"); b.actAnswer = StepRun.Blocked("guide only")
        assertEquals(HandOverWhy.BLOCKED, (run(TeachSkill("x", listOf(click("Data"))), b) as ReplayResult.HandOver).why)
        val f = phone("home"); f.actAnswer = StepRun.Failed("nope")
        assertEquals(HandOverWhy.FAILED, (run(TeachSkill("x", listOf(click("Data"))), f) as ReplayResult.HandOver).why)
    }

    @Test fun a_private_screen_stops_the_replay_before_anything_is_done() {
        val p = phone("home"); p.privateScreen = true
        val r = run(TeachSkill("x", listOf(click("Data"))), p)
        assertEquals(ReplayResult.Private(0), r)
        assertTrue(p.acted.isEmpty())
    }

    @Test fun the_wrong_app_in_front_hands_over() {
        val p = phone("other")
        val r = run(TeachSkill("x", listOf(click("Data"))), p) as ReplayResult.HandOver
        assertEquals(HandOverWhy.WRONG_SCREEN, r.why)
        assertTrue(p.acted.isEmpty())
    }

    @Test fun an_unreadable_screen_hands_over() {
        val p = phone(null)
        assertEquals(HandOverWhy.UNREADABLE, (run(TeachSkill("x", listOf(click("Data"))), p) as ReplayResult.HandOver).why)
    }

    @Test fun a_button_that_was_never_named_hands_over() {
        val p = phone("home")
        assertEquals(HandOverWhy.UNNAMED, (run(TeachSkill("x", listOf(click(null))), p) as ReplayResult.HandOver).why)
        assertTrue(click(null).unnamed); assertFalse(click(null, "a:id/b").unnamed)
    }

    @Test fun an_app_on_the_deny_list_is_not_operated() {
        val p = Phone(mapOf("b" to screen("com.blocked", el(1, "Data"))), "b"); p.blocked = "deny"
        val step = TeachStep(StepKind.CLICK, "com.blocked", label = "Data")
        assertEquals(HandOverWhy.BLOCKED, (run(TeachSkill("x", listOf(step)), p) as ReplayResult.HandOver).why)
        assertTrue(p.acted.isEmpty())
    }

    @Test fun typing_needs_the_words_to_have_been_kept() {
        val screens = mapOf("s" to screen(mtn, el(1, null, editable = true, hint = "Search")))
        val noText = TeachStep(StepKind.TYPE, mtn, hint = "Search", textLength = 4)
        val p = Phone(screens, "s")
        val r = run(TeachSkill("x", listOf(noText)), p) as ReplayResult.HandOver
        assertEquals(HandOverWhy.NEEDS_TEXT, r.why)
        assertTrue(p.acted.isEmpty())
        val p2 = Phone(screens, "s")
        assertEquals(ReplayResult.Done(1, 0, false), run(TeachSkill("x", listOf(noText.copy(text = "data"))), p2))
        assertEquals(listOf("TYPE:null"), p2.acted)
    }

    @Test fun a_scroll_that_only_brought_the_next_element_into_view_is_skipped_when_it_is_already_there() {
        val p = phone("home")
        val scroll = TeachStep(StepKind.SCROLL, mtn, direction = "down", optional = true)
        val r = run(TeachSkill("x", listOf(scroll, click("Data"))), p) as ReplayResult.Done
        assertEquals(1, r.skipped); assertEquals(listOf("CLICK:Data"), p.acted)
    }

    @Test fun a_scroll_is_done_when_the_next_element_is_not_in_view_yet() {
        val screens = mapOf("s" to screen(mtn, el(1, "Home", scrollable = true)))
        val p = Phone(screens, "s")
        val scroll = TeachStep(StepKind.SCROLL, mtn, direction = "down", optional = true)
        val r = run(TeachSkill("x", listOf(scroll, click("Data"))), p) as ReplayResult.HandOver
        assertEquals(listOf("SCROLL:Home"), p.acted)
        assertEquals(HandOverWhy.MISSING, r.why)
    }

    // ---------------------------------------------------------------- Back / Home keys

    private val back = TeachStep(StepKind.BACK, mtn, appName = "MyMTN")
    private val home = TeachStep(StepKind.HOME, mtn, appName = "MyMTN")

    @Test fun a_back_step_is_pressed_through_the_host_without_looking_for_an_element() {
        val p = phone("data")
        val r = run(TeachSkill("x", listOf(back, click("Balance"))), p)
        assertEquals(ReplayResult.Done(2, 0, false), r)
        assertEquals(listOf("BACK:null", "CLICK:Balance"), p.acted)
    }

    @Test fun back_in_the_wrong_app_hands_over_instead_of_leaving_it() {
        val p = phone("other")
        val r = run(TeachSkill("x", listOf(back)), p) as ReplayResult.HandOver
        assertEquals(HandOverWhy.WRONG_SCREEN, r.why)
        assertTrue(p.acted.isEmpty())
    }

    @Test fun home_works_from_any_app() {
        val p = phone("other")
        assertEquals(ReplayResult.Done(1, 0, false), run(TeachSkill("x", listOf(home)), p))
        assertEquals(listOf("HOME:null"), p.acted)
    }

    @Test fun a_key_step_stops_at_a_private_screen_and_at_a_denied_app() {
        val p = phone("data"); p.privateScreen = true
        assertEquals(ReplayResult.Private(0), run(TeachSkill("x", listOf(back)), p))
        assertTrue(p.acted.isEmpty())
        val b = Phone(mapOf("b" to screen("com.blocked", el(1, "Data"))), "b"); b.blocked = "deny"
        assertEquals(HandOverWhy.BLOCKED, (run(TeachSkill("x", listOf(TeachStep(StepKind.HOME, "com.blocked"))), b) as ReplayResult.HandOver).why)
        assertTrue(b.acted.isEmpty())
    }

    @Test fun a_key_step_the_gates_refuse_is_handed_over_not_done() {
        val p = phone("data"); p.actAnswer = StepRun.Blocked("keys are off")
        val r = run(TeachSkill("x", listOf(back, click("Balance"))), p) as ReplayResult.HandOver
        assertEquals(HandOverWhy.BLOCKED, r.why)
        assertEquals(listOf(back, click("Balance")), r.remaining)
    }

    @Test fun key_steps_are_never_unnamed_or_sensitive_and_have_nothing_to_match() {
        assertFalse(back.unnamed); assertFalse(home.unnamed)
        assertFalse(com.tekyerema.teach.StepWords.isSensitive(back)); assertFalse(com.tekyerema.teach.StepWords.isSensitive(home))
        assertTrue(StepMatcher.find(back, screen(mtn, el(1, "Back"))) is StepMatcher.Match.Missing)
    }

    @Test fun a_skill_that_ended_at_a_private_screen_says_so() {
        val p = phone("home")
        val r = run(TeachSkill("pay", listOf(click("Data")), endsAtPrivate = true), p)
        assertEquals(ReplayResult.Done(1, 0, true), r)
    }
}
