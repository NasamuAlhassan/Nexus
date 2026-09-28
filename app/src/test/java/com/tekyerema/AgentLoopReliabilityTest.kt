package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.Exploration
import com.tekyerema.agent.Futility
import com.tekyerema.agent.LoopGuard
import com.tekyerema.agent.Progress
import com.tekyerema.agent.Progress.Verdict
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.tools.TargetResolver
import com.tekyerema.verifier.Expectation
import com.tekyerema.verifier.Expects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent loop reliability (G-21 / G-12 / C-21): exploration vs. stuckness, goal-directed scrolling and tolerant claims —
 * driven by the A20e trace of "open Settings and tell me which Android version this phone has" (One UI: Settings ›
 * About phone › Software information).
 */
class AgentLoopReliabilityTest {

    private fun el(id: Int, label: String?, clickable: Boolean = true, editable: Boolean = false, scrollable: Boolean = false,
                   checkable: Boolean = false, checked: Boolean = false, role: String = "LinearLayout",
                   source: ElementSource = ElementSource.TREE) =
        UiElement(id, role, label, Box(0, id * 100, 100, id * 100 + 90), source = source, clickable = clickable,
            editable = editable, scrollable = scrollable, checkable = checkable, checked = checked)

    private fun screen(pkg: String, vararg labels: String) =
        UiSnapshot(pkg, "Settings", labels.mapIndexed { i, l -> el(i, l) }, 0L)

    // ---------------------------------------------------------------- Progress verdicts

    private fun classify(tool: String = "scroll", mutating: Boolean = true, success: Boolean = true, verified: Boolean = false,
                         confirmed: Boolean = false, consequential: Boolean = false, vacuous: Boolean = false,
                         sameApp: Boolean = true, changed: Boolean = true, novel: Int = 6) =
        Progress.classify(tool, mutating, success, verified, confirmed, consequential, vacuous, sameApp, changed, novel,
            navTools = setOf("tap", "scroll", "scroll_to_find", "swipe", "key_event"), minNew = 2)

    @Test fun a_scroll_that_reveals_new_rows_is_progress_not_failure() {
        assertEquals(Verdict.PARTIAL, classify())
        assertEquals(Verdict.PARTIAL, classify(tool = "tap"))                 // into a sub-menu that isn't the claimed one
        assertEquals(Verdict.VERIFIED, classify(verified = true))
        assertEquals(Verdict.NO_CHANGE, classify(changed = false))
        assertEquals(Verdict.FAILED, classify(success = false))
        assertEquals(Verdict.OBSERVED, classify(tool = "read_state", mutating = false))
        assertEquals(Verdict.FAILED, classify(tool = "read_sms", mutating = false, success = false))
    }

    @Test fun progress_is_never_granted_where_it_could_hide_a_real_miss() {
        assertEquals("a clock tick is not new content", Verdict.UNVERIFIED, classify(novel = 1))
        assertEquals("a flip to another app is not progress", Verdict.UNVERIFIED, classify(sameApp = false))
        assertEquals("an approved send/call/pay must verify", Verdict.UNVERIFIED, classify(tool = "tap", confirmed = true))
        assertEquals(Verdict.UNVERIFIED, classify(tool = "tap", consequential = true))
        assertEquals("no concrete claim, no benefit of the doubt", Verdict.UNVERIFIED, classify(vacuous = true))
        assertEquals("typing is not navigation", Verdict.UNVERIFIED, classify(tool = "type"))
    }

    @Test fun exploring_settings_no_longer_asks_keep_trying_or_stop() {
        // The A20e run: open_app ✓, read_state, scroll (new rows), scroll (new rows), tap into About phone (no "Android version" yet).
        val guard = LoopGuard(maxFailures = 3, maxEscalations = 1)
        var streak = 0
        for (v in listOf(Verdict.VERIFIED, Verdict.OBSERVED, Verdict.PARTIAL, Verdict.PARTIAL, Verdict.PARTIAL)) {
            streak = Progress.nextFailStreak(streak, v)
            assertFalse("escalated on exploration after $v", guard.shouldEscalate(streak))
        }
        assertEquals(0, streak)
        // Real stuckness still escalates — and a read in between no longer wipes the streak.
        streak = 0
        for (v in listOf(Verdict.FAILED, Verdict.OBSERVED, Verdict.NO_CHANGE, Verdict.OBSERVED, Verdict.UNVERIFIED))
            streak = Progress.nextFailStreak(streak, v)
        assertEquals(3, streak)
        assertTrue(guard.shouldEscalate(streak))
        assertEquals(0, Progress.nextFailStreak(5, Verdict.VERIFIED))
    }

    @Test fun completion_gate_ignores_navigation_but_still_needs_the_last_change_verified() {
        // open_app ✓, 3 partial scrolls/taps, tap "Software information" ✓ → done: verified.
        assertTrue(Progress.substantiveOk(lastVerified = true, mutating = 5, verified = 2, partial = 3))
        // Without the partial exemption this was 2/5 — "…but I couldn't confirm it" on a correct answer.
        assertFalse(Progress.substantiveOk(lastVerified = true, mutating = 5, verified = 2, partial = 0))
        // A partial LAST step leaves the gate shut.
        assertFalse(Progress.substantiveOk(lastVerified = false, mutating = 4, verified = 3, partial = 1))
        assertFalse(Progress.substantiveOk(lastVerified = null, mutating = 0, verified = 0, partial = 0))
    }

    // ---------------------------------------------------------------- Exploration / Futility

    @Test fun novelty_counts_only_labels_this_task_has_not_seen_in_that_app() {
        val x = Exploration()
        val top = screen("com.android.settings", "Connections", "Sounds and vibration", "Notifications", "Display")
        val mid = screen("com.android.settings", "Display", "Wallpaper", "Themes", "Home screen")
        x.note(top)
        assertEquals(3, x.novelty(mid))
        x.note(mid)
        assertEquals("scrolled back up: nothing new", 0, x.novelty(top))
        assertEquals("the same words in another app are new there", 4, x.novelty(screen("com.other", "Connections", "Sounds and vibration", "Notifications", "Display")))
        assertEquals(0, x.novelty(null))
    }

    @Test fun futility_weighs_exploring_misses_half_and_forgets_a_label_that_turns_up() {
        val f = Futility(nudgeAt = 3, stopAt = 5)
        assertEquals(Futility.Signal.NONE, f.miss("Zorblax mode", partial = false))
        assertEquals(Futility.Signal.NONE, f.miss("Zorblax mode", partial = false))
        assertEquals(Futility.Signal.NUDGE, f.miss("Zorblax mode", partial = false))
        assertEquals(Futility.Signal.NONE, f.miss("Zorblax mode", partial = false))
        assertEquals(Futility.Signal.STOP, f.miss("Zorblax mode", partial = false))

        val walk = Futility(nudgeAt = 3, stopAt = 5)
        repeat(5) { assertEquals("scrolling a long list toward it", Futility.Signal.NONE, walk.miss("About phone", partial = true)) }
        assertEquals(Futility.Signal.NUDGE, walk.miss("About phone", partial = true))
        walk.observed(screen("com.android.settings", "Accounts and backup", "About phone"))
        assertEquals("it exists: the count starts over", Futility.Signal.NONE, walk.miss("About phone", partial = false))
    }

    @Test fun a_page_echoing_the_search_does_not_count_as_finding_it() {
        val f = Futility(nudgeAt = 3, stopAt = 5)
        repeat(3) { f.miss("Zorblax mode", partial = false) }
        f.observed(UiSnapshot("com.android.settings", "Search", listOf(
            el(0, "Zorblax mode", editable = true),                        // what the model typed
            el(1, "No results for \"Zorblax mode\" in Settings", clickable = false)), 0L))
        assertEquals(Futility.Signal.NONE, f.miss("Zorblax mode", partial = false))
        assertEquals("count kept: stop on the 5th miss", Futility.Signal.STOP, f.miss("Zorblax mode", partial = false))
        assertTrue(f.ownItem("android version 11", "android version"))
        assertFalse(f.ownItem("no results for \"zorblax mode\" in settings", "zorblax mode"))
    }

    @Test fun goal_directed_scroll_keeps_going_only_toward_a_named_target_on_a_moving_list() {
        fun go(tool: String = "scroll", verified: Boolean = false, sought: String? = "About phone", sameApp: Boolean = true,
               moved: Boolean = true, repeats: Int = 0) =
            Progress.keepScrolling(tool, success = true, verified = verified, sought = sought, sameApp = sameApp, moved = moved,
                repeats = repeats, maxRepeats = 4)
        assertTrue(go())
        assertFalse(go(verified = true))
        assertFalse("no target named", go(sought = null))
        assertFalse("end of the list", go(moved = false))
        assertFalse("left the app", go(sameApp = false))
        assertFalse("bounded", go(repeats = 4))
        assertFalse("only scroll", go(tool = "swipe"))
        assertEquals("About phone", Progress.soughtLabel(Expects.parse("element_present: About phone")))
        assertNull(Progress.soughtLabel(Expects.parse("checked: Wi-Fi")))
    }

    @Test fun a_sparse_screen_right_after_an_app_switch_is_still_loading() {
        assertTrue(Progress.stillLoading(appSwitched = true, actionable = 4, maxSparse = 5))
        assertFalse(Progress.stillLoading(appSwitched = true, actionable = 27, maxSparse = 5))
        assertFalse(Progress.stillLoading(appSwitched = false, actionable = 0, maxSparse = 5))
    }

    // ---------------------------------------------------------------- tolerant claims

    @Test fun claims_in_quotes_or_plain_words_are_understood() {
        assertEquals(Expectation.ElementPresent("About phone"), Expects.parse("element_present: 'About phone'"))
        assertEquals(Expectation.ElementPresent("About phone"), Expects.parse("element_present: “About phone”"))
        assertEquals(Expectation.ElementPresent("Open"), Expects.parse("element containing 'Open'"))
        assertEquals(Expectation.ElementPresent("YouTube Music"), Expects.parse("an item labelled \"YouTube Music\""))
        assertEquals(Expectation.ElementPresent("Android version"), Expects.parse("text: Android version"))
        assertEquals(Expectation.FieldContains(3, "Calculator"), Expects.parse("field_contains: 3|'Calculator'"))
        assertEquals(Expectation.ForegroundIs("Play Store"), Expects.parse("foreground_is: \"Play Store\""))
        // Unchanged: vacuous and unknown claims stay vacuous; an empty target stays unparsed.
        assertTrue(Expects.parse("screen changed") is Expectation.ScreenChanged)
        assertTrue(Expects.parse("the screen changes somehow") is Expectation.Unparsed)
        assertTrue(Expects.parse("element_present: ''") is Expectation.Unparsed)
    }

    // ---------------------------------------------------------------- scroll-to-find

    @Test fun label_search_scrolls_to_the_end_then_back_up_then_stops() {
        val s = TargetResolver.ScrollSearch(12)
        assertEquals("down", s.next("A"))
        assertEquals("down", s.next("B"))
        assertEquals("the list stopped moving → turn", "up", s.next("B"))
        assertEquals("up", s.next("A"))
        assertNull("stopped at the top too → done", s.next("A"))
        assertTrue(s.reversed)

        val refused = TargetResolver.ScrollSearch(12)
        assertEquals("down", refused.next("A"))
        refused.blocked()                                  // already at the bottom
        assertEquals("up", refused.next("A"))
        refused.blocked()                                  // …and at the top
        assertNull(refused.next("A"))

        val bounded = TargetResolver.ScrollSearch(3)
        assertEquals("down", bounded.next("1")); assertEquals("down", bounded.next("2")); assertEquals("down", bounded.next("3"))
        assertNull(bounded.next("4"))
    }

    @Test fun a_partial_step_hands_a_local_routine_to_the_planner() {
        assertTrue(RoutingBrain.deviated(listOf("tap({}) → success [node]; verified=partial (x; but it moved on)")))
        assertFalse(RoutingBrain.deviated(listOf("tap({}) → success [node]; verified=true (found it)")))
    }

}
