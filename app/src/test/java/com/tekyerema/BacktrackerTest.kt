package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.Progress.Verdict
import com.tekyerema.agent.recovery.Backtracker
import com.tekyerema.agent.recovery.OutcomeClass
import com.tekyerema.agent.recovery.RecoveryCoordinator
import com.tekyerema.agent.recovery.ScreenKey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workstream B — engine backtracking: Back to the last verified screen, else relaunch + replay; never across a consequence. */
class BacktrackerTest {

    private fun el(id: Int, label: String, editable: Boolean = false) =
        UiElement(id, "TextView", label, Box(0, id * 100, 100, id * 100 + 90), clickable = true, editable = editable)

    private fun screen(pkg: String, title: String?, vararg labels: String, editable: String? = null) =
        UiSnapshot(pkg, title, labels.mapIndexed { i, l -> el(i, l) } + listOfNotNull(editable?.let { el(labels.size, it, editable = true) }),
            0L, appName = pkg.substringAfterLast('.'))

    private val S = "com.android.settings"
    private val settingsHome = screen(S, "Settings", "Connections", "Sounds and vibration", "Display", "About phone")
    private val about = screen(S, "About phone", "Phone name", "Software information", "Battery information")
    private val software = screen(S, "Software information", "Android version", "One UI version", "Build number")
    private val wrongApp = screen("com.sec.android.app.launcher", null, "Phone", "Messages", "Chrome")

    private fun tap(label: String, expects: String? = null) = AgentDecision.CallTool("tap", JSONObject().put("label", label), null, expects)
    private fun open(app: String) = AgentDecision.CallTool("open_app", JSONObject().put("app", app), null, "foreground_is: settings")

    /** Back presses walk through [screens]; null in the list = a secure screen. */
    private class FakeOps(private val screens: List<UiSnapshot?>) : Backtracker.Ops {
        var presses = 0
        override suspend fun back(): Boolean { presses++; return true }
        override suspend fun observe(): UiSnapshot? = screens[minOf(presses - 1, screens.size - 1)]
    }

    private fun trackerThroughSoftware(): Backtracker {
        val b = Backtracker(maxPresses = 3, maxDepth = 8)
        b.onVerifiedNavigation(settingsHome, open("Settings"))
        b.onVerifiedNavigation(about, tap("About phone", "element_present: Software information"))
        b.onVerifiedNavigation(software, tap("Software information", "element_present: Android version"))
        return b
    }

    @Test fun screen_keys_match_the_same_screen_across_readings() {
        val a = ScreenKey.of(about)
        assertTrue(a.similar(ScreenKey.of(screen(S, "About phone", "Phone name", "Software information", "Battery information", "Status"))))
        assertFalse(a.similar(ScreenKey.of(software)))
        assertFalse(a.similar(ScreenKey.of(screen("com.other", "About phone", "Phone name", "Software information", "Battery information"))))
    }

    @Test fun back_presses_until_a_checkpoint_matches() = runBlocking {
        val b = trackerThroughSoftware()
        val ops = FakeOps(listOf(about))   // one Back from the wrong screen lands on About phone
        val r = b.run(screen(S, "Battery", "Battery usage", "Power saving"), ops)
        assertTrue(r is Backtracker.Result.Reached)
        r as Backtracker.Result.Reached
        assertEquals(1, r.presses)
        assertEquals("about phone", r.to.key.title)
        assertEquals(2, b.checkpoints.size)   // Software information popped: we're above it now
    }

    @Test fun back_stops_at_three_presses_then_relaunches_and_replays_the_verified_route() = runBlocking {
        val b = trackerThroughSoftware()
        val lost = screen("com.android.chrome", "Chrome", "Search", "Tabs")
        val ops = FakeOps(listOf(lost, lost, lost))
        val r = b.run(lost, ops)
        assertTrue(r.toString(), r is Backtracker.Result.Replay)
        r as Backtracker.Result.Replay
        assertEquals(3, ops.presses)
        assertEquals(listOf("open_app", "tap", "tap"), r.steps.map { it.name })
        assertEquals("About phone", r.steps[1].args.getString("label"))
        assertEquals("element_present: Android version", r.steps[2].expects)
        assertTrue(r.steps.all { it.rationale == Backtracker.REPLAY_TAG })
    }

    @Test fun leaving_for_the_home_screen_stops_the_back_presses() = runBlocking {
        val b = trackerThroughSoftware()
        val ops = FakeOps(listOf(wrongApp, wrongApp, wrongApp))
        b.run(screen("com.whatsapp", null, "Chats"), ops)
        assertEquals(1, ops.presses)
    }

    @Test fun replaying_to_the_home_screen_presses_home_instead_of_opening_an_app_called_the_home_screen() = runBlocking {
        // Pixel emulator, 13 October 2026: the only checkpoint was the launcher, Back never reached it, and the replay
        // was open_app("the home screen") — "I couldn't find an app called home screen on this phone".
        val b = Backtracker(maxPresses = 2, maxDepth = 8)
        val home = screen("com.google.android.apps.nexuslauncher", null, "Phone", "Chrome")
        b.base(home)
        val lost = screen("com.google.android.settings.intelligence", "Search settings", "Search", "Recent")
        val r = b.run(lost, FakeOps(listOf(lost, lost)))
        assertTrue(r.toString(), r is Backtracker.Result.Replay)
        r as Backtracker.Result.Replay
        assertEquals(1, r.steps.size)
        assertEquals("key_event", r.steps[0].name)
        assertEquals("home", r.steps[0].args.getString("key"))
        assertEquals(Backtracker.REPLAY_TAG, r.steps[0].rationale)
    }

    @Test fun a_route_with_an_id_only_tap_is_not_replayed() = runBlocking {
        val b = Backtracker(maxPresses = 1)
        b.onVerifiedNavigation(settingsHome, open("Settings"))
        b.onVerifiedNavigation(about, AgentDecision.CallTool("tap", JSONObject().put("element_id", 7), null))
        val lost = screen("com.android.chrome", "Chrome", "Search")
        val r = b.run(lost, FakeOps(listOf(lost)))
        assertTrue(r.toString(), r is Backtracker.Result.NotReached)
        // …but a tap on a resolved TREE element is kept in its label form, so it can be.
        assertEquals("About phone", Backtracker.replayForm(AgentDecision.CallTool("tap", JSONObject().put("element_id", 7), null),
            "About phone", fromTree = true)!!.args.getString("label"))
        assertEquals(null, Backtracker.replayForm(AgentDecision.CallTool("tap", JSONObject().put("x", 5).put("y", 5), null), "x", fromTree = false))
        assertEquals(null, Backtracker.replayForm(AgentDecision.CallTool("type", JSONObject().put("text", "hi"), null), null, true))
    }

    @Test fun never_back_across_a_consequential_step() = runBlocking {
        val b = trackerThroughSoftware()
        b.barrier(software)   // e.g. a confirmed send happened on this screen
        assertEquals(1, b.checkpoints.size)
        val r = b.run(screen(S, "Battery", "Battery usage"), FakeOps(listOf(about, settingsHome)))
        // Back can't match anything before the barrier: About phone / Settings are gone from the stack.
        assertFalse(r is Backtracker.Result.Reached)
        // A ledger effect recorded after the checkpoints refuses outright (and clears them).
        val withLedger = Backtracker(effectsSince = { true })
        withLedger.onVerifiedNavigation(settingsHome, open("Settings"))
        val refused = withLedger.run(about, FakeOps(listOf(settingsHome)))
        assertTrue(refused is Backtracker.Result.Refused)
    }

    @Test fun never_on_a_phone_session_over_a_typed_draft_or_into_a_secure_screen() = runBlocking {
        val b = trackerThroughSoftware()
        assertTrue(b.run(screen("com.android.phone", null, "MTN MoMo", "1. Send money"), FakeOps(listOf(about))) is Backtracker.Result.Refused)
        val draft = screen("com.whatsapp", "Ama", "Send", editable = "I'm running late")
        assertTrue(b.run(draft, FakeOps(listOf(about)), draftTexts = listOf("I'm running late")) is Backtracker.Result.Refused)
        val ops = FakeOps(listOf(null))
        val r = b.run(screen(S, "Battery", "Battery usage"), ops)
        assertTrue(r is Backtracker.Result.NotReached)
        assertEquals(1, ops.presses)
    }

    @Test fun already_on_the_last_checkpoint_does_nothing() = runBlocking {
        val b = trackerThroughSoftware()
        val ops = FakeOps(listOf(about))
        assertEquals(Backtracker.Result.AlreadyThere, b.run(software, ops))
        assertEquals(0, ops.presses)
        assertFalse(b.canBacktrack(software))
        assertTrue(b.canBacktrack(wrongApp))
    }

    @Test fun the_coordinator_keeps_checkpoints_and_barriers_from_step_outcomes() {
        val c = RecoveryCoordinator()
        c.onObserved(settingsHome)                                             // the task's first screen
        fun step(d: AgentDecision.CallTool, after: UiSnapshot, verdict: Verdict = Verdict.VERIFIED, consequential: Boolean = false,
                 confirmed: Boolean = false) = RecoveryCoordinator.Step(decision = d, verdict = verdict, success = true,
            resolvedLabel = d.args.optString("label"), before = settingsHome, after = after, consequential = consequential, confirmed = confirmed)
        c.afterStep(step(tap("About phone"), about), OutcomeClass.OK, canAsk = true)
        assertEquals(2, c.backtracker.checkpoints.size)
        assertEquals("About phone", c.backtracker.checkpoints.last().via!!.args.getString("label"))
        // Typing is a barrier (and remembered as a draft); so is a confirmed step.
        c.afterStep(step(AgentDecision.CallTool("type", JSONObject().put("text", "hello"), null), about), OutcomeClass.OK, canAsk = true)
        assertEquals(1, c.backtracker.checkpoints.size)
        assertEquals(listOf("hello"), c.typedTexts)
        c.afterStep(step(tap("Software information"), software), OutcomeClass.OK, canAsk = true)
        c.afterStep(step(tap("Send"), software, consequential = true, confirmed = true), OutcomeClass.OK, canAsk = true)
        assertEquals(1, c.backtracker.checkpoints.size)
        assertEquals(2, c.backtracker.barriers)
    }
}
