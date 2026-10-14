package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentStep
import com.tekyerema.agent.AgentSteps
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.ui.assistant.PlaceQuery
import com.tekyerema.ui.assistant.StepLabels
import com.tekyerema.ui.assistant.Summaries
import com.tekyerema.ui.assistant.SurfaceModel
import com.tekyerema.ui.assistant.SurfaceModel.Mode
import com.tekyerema.ui.assistant.SurfaceModel.Preview
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.VoiceUiState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assistant surface's state model (docs/assistant-ux, Stage 1): only the real agent's steps, of the current run. */
class AssistantSurfaceModelTest {
    private val now = 1_000_000_000L
    private val idleVoice = VoiceUiState()
    private fun result(status: AgentStatus, summary: String, goal: String, run: Long) =
        JSONObject().put("status", status.name).put("summary", summary).put("goal", goal).put("runSeq", run).toString()
    private fun resultLog(text: String, at: Long = now - 1_000) = LogEntry(at, LogEntry.Kind.RESULT, text)

    // ------------------------------------------------------------------ the step list (engine bookkeeping)

    @Test fun steps_of_another_run_never_appear_and_late_or_duplicate_events_change_nothing() {
        var steps = AgentSteps.started(emptyList(), runSeq = 4, index = 1, tool = "open_app", subject = "WhatsApp")
        steps = AgentSteps.started(steps, runSeq = 5, index = 1, tool = "web_search", subject = "nearest mall")   // a new run
        assertEquals("a list only ever describes one run", listOf(5L), steps.map { it.runSeq })
        // a late finish of run 4's step: ignored
        assertEquals(steps, AgentSteps.finished(steps, runSeq = 4, index = 1, ok = true))
        // a finish for a step never started: ignored
        assertEquals(steps, AgentSteps.finished(steps, runSeq = 5, index = 9, ok = true))
        // the same step started twice (a replay): one entry
        steps = AgentSteps.started(steps, runSeq = 5, index = 1, tool = "web_search", subject = "nearest mall")
        assertEquals(1, steps.size)
        steps = AgentSteps.finished(steps, runSeq = 5, index = 1, ok = true)
        // finishing twice keeps the first outcome
        assertEquals(steps, AgentSteps.finished(steps, runSeq = 5, index = 1, ok = false))
        assertEquals(AgentStep.State.DONE, steps.single().state)
        // and the surface filters by the agent's CURRENT run too
        val agent = AgentUiState(status = AgentStatus.ACTING, runSeq = 6, steps = steps)
        assertTrue(SurfaceModel.reduce(idleVoice, agent, now = now).steps.isEmpty())
    }

    @Test fun a_late_step_from_a_stopped_run_never_replaces_the_newer_runs_steps() {
        // Run 4 was stopped; its coroutine can still be unwinding after run 5 began (a cancelled job stops counting as active
        // before its code stops). Its late "step started" must not wipe run 5's list. (The engine also writes a step only while
        // its own run owns the state — AgentEngine, runCtx.seq.)
        val five = AgentSteps.started(emptyList(), runSeq = 5, index = 1, tool = "web_search", subject = "osmosis")
        assertEquals(five, AgentSteps.started(five, runSeq = 4, index = 3, tool = "open_app", subject = "Settings"))
        assertEquals(five, AgentSteps.finished(five, runSeq = 4, index = 1, ok = false))
        assertEquals("Searching the web for “osmosis”", SurfaceModel.reduce(idleVoice,
            AgentUiState(status = AgentStatus.ACTING, runSeq = 5, steps = five), now = now).title)
    }

    @Test fun a_new_request_replaces_the_previous_result_and_the_replies_before_it() {
        // UX-6: when the user starts a new request, the host records the run on screen and the moment.
        val done = AgentUiState(status = AgentStatus.COMPLETED, runSeq = 5, goal = "explain osmosis",
            lastResult = result(AgentStatus.COMPLETED, "Water moves across a membrane.", "x", 5), log = listOf(resultLog("x", now - 5_000)))
        assertEquals(Mode.PRESENTING, SurfaceModel.reduce(idleVoice, done, now = now).mode)
        val replaced = SurfaceModel.Local(dismissedRun = 5, dismissedAt = now - 1_000)
        assertEquals(Mode.HIDDEN, SurfaceModel.reduce(idleVoice, done, replaced, now = now).mode)
        // a quick reply from before the new request stays gone; the one after it is shown as itself
        val before = VoiceUiState(spoken = "It's 5 pm.", spokenAt = now - 2_000, lastCanonical = "what's the time")
        assertEquals(Mode.HIDDEN, SurfaceModel.reduce(before, done, replaced, now = now).mode)
        val after = VoiceUiState(spoken = "The battery is at 80 percent.", spokenAt = now - 500, lastCanonical = "battery level")
        assertEquals("The battery is at 80 percent.", (SurfaceModel.reduce(after, done, replaced, now = now).preview as Preview.Answer).body)
        // and the next run's result is never hidden by the old run's dismissal
        val next = done.copy(runSeq = 6, log = listOf(resultLog("x", now - 100)))
        assertEquals(Mode.PRESENTING, SurfaceModel.reduce(idleVoice, next, replaced, now = now).mode)
    }

    @Test fun a_quick_answer_shows_while_it_is_spoken_and_keeps_its_place_after() {
        // Owner on the A20e, 14 Oct 2026: "check the time" sat in the middle of Home while it was spoken — the words were only
        // in partialHint until the voice finished. Now the surface shows them as they are spoken, as one object.
        val idle = AgentUiState()
        val during = VoiceUiState(state = VoiceState.SPEAKING, partialHint = "It's 9:40 in the morning.", lastCanonical = "what's the time")
        val s1 = SurfaceModel.reduce(during, idle, now = now)
        assertEquals(Mode.PRESENTING, s1.mode); assertTrue(s1.speaking)
        val after = VoiceUiState(state = VoiceState.IDLE, spoken = "It's 9:40 in the morning.", spokenAt = now - 100, lastCanonical = "what's the time")
        val s2 = SurfaceModel.reduce(after, idle, now = now)
        assertEquals("the same key while spoken and after: no blink", s1.key, s2.key)
        // closed while still being spoken: it stays closed when the voice finishes
        assertEquals(Mode.HIDDEN, SurfaceModel.reduce(after, idle, SurfaceModel.Local(dismissedReplyKey = s1.key, dismissedAt = now - 200), now = now).mode)
    }

    @Test fun a_finished_run_marked_stopped_afterwards_keeps_what_it_really_did() {
        // Stop pressed with nothing running set STOPPED on a run that had already answered (emulator, 14 Oct 2026).
        val agent = AgentUiState(status = AgentStatus.STOPPED, runSeq = 4, goal = "explain osmosis",
            lastResult = result(AgentStatus.COMPLETED, "Water moves across a membrane.", "x", 4),
            log = listOf(resultLog("Water moves across a membrane.")))
        val s = SurfaceModel.reduce(idleVoice, agent, now = now)
        assertEquals(Mode.PRESENTING, s.mode)
        assertEquals("Water moves across a membrane.", (s.preview as Preview.Answer).summary)
        // a run stopped before it finished (no result of its own) still says it stopped
        assertEquals(Mode.CANCELLED, SurfaceModel.reduce(idleVoice, agent.copy(runSeq = 5), now = now).mode)
    }

    @Test fun a_stopped_run_never_shows_the_previous_runs_words() {
        // The emulator, 14 Oct 2026: run 5 answered; run 6 (the same request) was stopped before any result. A stopped run
        // writes no result, so lastResult still held run 5's — and the card showed run 5's words under "Stopped".
        val stopped = AgentUiState(status = AgentStatus.STOPPED, runSeq = 6, goal = "what are the latest headlines in Ghana today",
            lastResult = result(AgentStatus.COMPLETED, "I couldn't locate reliable headline listings.", "x", 5),
            log = listOf(LogEntry(now - 1_000, LogEntry.Kind.INFO, "Stopped by user.")))
        // the voice's last reply was run 5's, said before run 6 began
        val voice = VoiceUiState(spoken = "I couldn't locate reliable headline listings.", spokenAt = now - 30_000)
        val s = SurfaceModel.reduce(voice, stopped, now = now)
        assertEquals(Mode.CANCELLED, s.mode)
        val p = s.preview as Preview.Problem
        assertEquals("Stopped before it finished.", p.message)
        assertEquals("what are the latest headlines in Ghana today", p.retryGoal)
        // …and a run that finished still shows its own words
        val done = stopped.copy(status = AgentStatus.COMPLETED, lastResult = result(AgentStatus.COMPLETED, "Here are today's headlines.", "x", 6),
            log = listOf(resultLog("Here are today's headlines.")))
        assertEquals("Here are today's headlines.", (SurfaceModel.reduce(voice, done, now = now).preview as Preview.Answer).summary)
    }

    @Test fun a_paused_task_says_paused_and_a_stop_logged_after_the_clock_ticked_still_shows() {
        val paused = SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.PAUSED, runSeq = 3), now = now)
        assertEquals(Mode.PROCESSING, paused.mode); assertEquals("Paused", paused.title); assertTrue(paused.paused)
        // The host's clock ticks only when the surface changes. A stop at a question (the panel up, the surface hidden) is
        // logged after its last tick; that newer-than-the-clock end must count as fresh, not hide the "Stopped" card (review).
        val stopped = AgentUiState(status = AgentStatus.STOPPED, runSeq = 3, goal = "x",
            log = listOf(LogEntry(now + 5_000, LogEntry.Kind.INFO, "Stopped by user.")))
        assertEquals(Mode.CANCELLED, SurfaceModel.reduce(idleVoice, stopped, now = now).mode)
    }

    @Test fun an_answered_prompt_reads_as_working_never_a_blink_to_hidden() {
        // The engine has its answer (the prompt is cleared) but hasn't moved on yet.
        val s = SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, runSeq = 2,
            pendingConfirmation = null, confirmationSeq = 4), now = now)
        assertEquals(Mode.PROCESSING, s.mode)
        assertEquals(Mode.PROCESSING, SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.ASKING, runSeq = 2), now = now).mode)
    }

    @Test fun finished_steps_read_in_the_past_tense() {
        assertEquals("Opened battery usage settings", StepLabels.done("open_setting", "battery usage"))
        assertEquals("Searched the web for “osmosis”", StepLabels.done("web_search", "osmosis"))
        assertEquals("Texted Ama", StepLabels.done("send_sms", "Ama"))
        assertEquals("Did a step", StepLabels.done("some_new_tool", null))
        var steps = AgentSteps.started(emptyList(), 3, 1, "open_setting", "battery usage")
        steps = AgentSteps.finished(steps, 3, 1, ok = true)
        val view = SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.ACTING, runSeq = 3, steps = steps), now = now).steps.single()
        assertEquals("Opening battery usage settings", view.label)
        assertEquals("Opened battery usage settings", view.past)
    }

    @Test fun what_a_step_may_say_about_itself_is_whitelisted() {
        assertEquals("WhatsApp", AgentSteps.subjectOf("open_app", JSONObject().put("app", "WhatsApp")))
        assertEquals("Ama", AgentSteps.subjectOf("send_sms", JSONObject().put("contact", "Ama").put("message", "secret")))
        assertNull("a number is not shown", AgentSteps.subjectOf("make_call", JSONObject().put("contact", "0244123456")))
        assertNull("typed text is never shown", AgentSteps.subjectOf("type", JSONObject().put("text", "my PIN is 1234")))
        assertNull(AgentSteps.subjectOf("ussd_navigate", JSONObject().put("amount", "5").put("number", "0552093690")))
        val data = JSONObject().put("sources", JSONArray()
            .put(JSONObject().put("title", "BBC").put("url", "https://www.bbc.com/a"))
            .put(JSONObject().put("title", "dup").put("url", "https://www.bbc.com/a"))
            .put(JSONObject().put("title", "bad").put("url", "javascript:alert(1)"))
            .put(JSONObject().put("title", "").put("url", "https://www.graphic.com.gh/x")))
        val sources = AgentSteps.sourcesOf(data)
        assertEquals(listOf("BBC", "graphic.com.gh"), sources.map { it.title })
        assertTrue(AgentSteps.sourcesOf(null).isEmpty())
    }

    // ------------------------------------------------------------------ working

    @Test fun working_shows_the_real_active_step_and_never_ticks_a_superseded_one() {
        var steps = AgentSteps.started(emptyList(), 3, 1, "open_app", "YouTube")
        steps = AgentSteps.finished(steps, 3, 1, ok = true)
        steps = AgentSteps.started(steps, 3, 2, "tap", null)            // never finished (rejected): superseded
        steps = AgentSteps.started(steps, 3, 3, "web_search", "mkbhd latest video")
        val s = SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.ACTING, runSeq = 3, steps = steps), now = now)
        assertEquals(Mode.WORKING, s.mode)
        assertEquals("Searching the web for “mkbhd latest video”", s.title)
        assertEquals(listOf(AgentStep.State.DONE, AgentStep.State.RUNNING, AgentStep.State.RUNNING), s.steps.map { it.state })
        assertEquals(listOf(false, false, true), s.steps.map { it.active })
        // no step yet: a quiet processing state, nothing invented
        val p = SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.THINKING, runSeq = 3), now = now)
        assertEquals(Mode.PROCESSING, p.mode); assertTrue(p.steps.isEmpty())
        // bookkeeping tools are not activity
        val quiet = AgentSteps.started(emptyList(), 3, 1, "read_state", null)
        assertTrue(SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.ACTING, runSeq = 3, steps = quiet), now = now).steps.isEmpty())
    }

    // ------------------------------------------------------------------ waiting on the user

    @Test fun a_confirmation_or_a_question_is_the_agents_own_pending_one_with_its_number() {
        val c = SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, runSeq = 2,
            pendingConfirmation = "Send this message to Ama?\n“I'm on my way.”", confirmationSeq = 41), now = now)
        assertEquals(Mode.WAITING, c.mode)
        assertEquals(PromptSurface.Kind.CONFIRM, c.waiting!!.kind); assertEquals(41L, c.waiting!!.seq)
        assertTrue(c.waiting!!.text.contains("I'm on my way."))
        val q = SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.ASKING, runSeq = 2,
            pendingQuestion = "Which Richmond do you want to message? Richmond, or Richmond Apenensu?"), now = now)
        assertEquals(PromptSurface.Kind.QUESTION, q.waiting!!.kind)
        val sec = SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.SECURE_INPUT, runSeq = 2), now = now)
        assertEquals(PromptSurface.Kind.SECURE, sec.waiting!!.kind)
        // a cleared prompt (the reply was given, the action about to run) is no panel — nothing to answer on
        assertTrue(SurfaceModel.reduce(idleVoice, AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, runSeq = 2,
            pendingConfirmation = null), now = now).waiting == null)
    }

    // ------------------------------------------------------------------ results

    @Test fun a_search_answer_carries_only_the_sources_the_search_returned() {
        var steps = AgentSteps.started(emptyList(), 7, 1, "web_search", "photosynthesis vs respiration")
        steps = AgentSteps.finished(steps, 7, 1, ok = true, sources = listOf(AgentStep.Source("Britannica", "https://www.britannica.com/x")))
        val agent = AgentUiState(status = AgentStatus.COMPLETED, runSeq = 7, steps = steps,
            goal = "explain the difference between photosynthesis and respiration",
            lastResult = result(AgentStatus.COMPLETED, "Photosynthesis makes glucose from light. Respiration breaks it down for energy.", "x", 7),
            log = listOf(resultLog("Photosynthesis makes glucose from light.")))
        val s = SurfaceModel.reduce(idleVoice, agent, now = now)
        assertEquals(Mode.PRESENTING, s.mode)
        val a = s.preview as Preview.Answer
        assertEquals("Explain the difference between photosynthesis and respiration", a.title)
        assertEquals(listOf("Britannica"), a.sources.map { it.title })
        assertNull("not a place request", a.mapQuery)
        // a failed search contributes no sources
        val failed = AgentSteps.finished(AgentSteps.started(emptyList(), 8, 1, "web_search", "x"), 8, 1, ok = false,
            sources = listOf(AgentStep.Source("X", "https://x.com")))
        val noSrc = SurfaceModel.reduce(idleVoice, agent.copy(runSeq = 8, steps = failed), now = now).preview as Preview.Answer
        assertTrue(noSrc.sources.isEmpty())
    }

    @Test fun a_place_request_offers_the_maps_app_and_invents_nothing() {
        assertEquals("nearest shopping mall", PlaceQuery.of("Find the nearest shopping mall and show me how to get there"))
        assertEquals("closest mall", PlaceQuery.of("where is the closest mall from here"))
        assertEquals("Accra Mall", PlaceQuery.of("directions to Accra Mall please"))
        assertEquals("pharmacy", PlaceQuery.of("pharmacy near me"))
        assertNull(PlaceQuery.of("explain photosynthesis"))
        val agent = AgentUiState(status = AgentStatus.COMPLETED, runSeq = 9, goal = "where is the closest mall from here",
            lastResult = result(AgentStatus.COMPLETED, "The closest is Legon City Mall.", "x", 9), log = listOf(resultLog("x")))
        val a = SurfaceModel.reduce(idleVoice, agent, now = now).preview as Preview.Answer
        assertEquals("closest mall", a.mapQuery)
        assertNull("no map link was returned by any step", a.mapLink)
    }

    @Test fun a_device_action_a_failure_and_a_stop_each_say_what_really_happened() {
        var steps = AgentSteps.started(emptyList(), 4, 1, "open_setting", "wifi")
        steps = AgentSteps.finished(steps, 4, 1, ok = true)
        val done = AgentUiState(status = AgentStatus.COMPLETED, runSeq = 4, steps = steps, goal = "turn on the wifi",
            lastResult = result(AgentStatus.COMPLETED, "Turned Wi-Fi on.", "x", 4), log = listOf(resultLog("Turned Wi-Fi on.")))
        val action = SurfaceModel.reduce(idleVoice, done, now = now).preview as Preview.Action
        assertEquals("Turned Wi-Fi on.", action.summary); assertTrue(action.verified)
        val unverified = SurfaceModel.reduce(idleVoice, done.copy(status = AgentStatus.COMPLETED_UNVERIFIED), now = now).preview as Preview.Action
        assertFalse("never claimed as verified", unverified.verified)
        val failed = SurfaceModel.reduce(idleVoice, done.copy(status = AgentStatus.FAILED,
            lastResult = result(AgentStatus.FAILED, "WhatsApp didn't open.", "x", 4)), now = now)
        assertEquals(Mode.FAILED, failed.mode)
        assertEquals("turn on the wifi", (failed.preview as Preview.Problem).retryGoal)
        val cannot = SurfaceModel.reduce(idleVoice, done.copy(status = AgentStatus.CANNOT_DO,
            lastResult = result(AgentStatus.CANNOT_DO, "I can't pay bills yet.", "x", 4)), now = now)
        assertNull("trying again can't help", (cannot.preview as Preview.Problem).retryGoal)
        assertEquals(Mode.CANCELLED, SurfaceModel.reduce(idleVoice, done.copy(status = AgentStatus.STOPPED,
            lastResult = result(AgentStatus.STOPPED, "Stopped by user.", "x", 4)), now = now).mode)
    }

    @Test fun a_dismissed_or_stale_result_is_gone_and_a_quick_answer_is_shown_as_itself() {
        val done = AgentUiState(status = AgentStatus.COMPLETED, runSeq = 4, goal = "explain photosynthesis",
            lastResult = result(AgentStatus.COMPLETED, "Plants make food from light.", "x", 4), log = listOf(resultLog("x")))
        assertEquals(Mode.PRESENTING, SurfaceModel.reduce(idleVoice, done, now = now).mode)
        assertEquals(Mode.HIDDEN, SurfaceModel.reduce(idleVoice, done, SurfaceModel.Local(dismissedRun = 4, dismissedAt = now - 10), now = now).mode)
        assertEquals("an hour later", Mode.HIDDEN, SurfaceModel.reduce(idleVoice, done.copy(log = listOf(resultLog("x", now - 3_600_000))), now = now).mode)
        // the user asked something the phone answered by itself ("what's the time") after dismissing — that reply, not the old run
        val voice = VoiceUiState(state = VoiceState.SPEAKING, spoken = "It's 3:05 in the afternoon.", spokenAt = now - 500,
            lastCanonical = "what's the time")
        val s = SurfaceModel.reduce(voice, done, SurfaceModel.Local(dismissedRun = 4, dismissedAt = now - 2_000), now = now)
        assertEquals(Mode.PRESENTING, s.mode)
        assertTrue(s.key.startsWith("reply:"))
        assertEquals("What's the time", s.title)
        assertTrue(s.speaking)
        // …and that reply dismissed is gone too
        assertEquals(Mode.HIDDEN, SurfaceModel.reduce(voice, done, SurfaceModel.Local(dismissedRun = 4, dismissedAt = now - 2_000,
            dismissedReplyAt = now - 500), now = now).mode)
    }

    @Test fun labels_and_summaries_are_short_and_plain() {
        assertEquals("Opening WhatsApp", StepLabels.label("open_app", "WhatsApp"))
        assertEquals("Going through the Mobile Money menu", StepLabels.label("ussd_navigate", null))
        assertEquals("Working on it", StepLabels.label("some_new_tool", "x"))
        // a long search is cut at a word (the emulator showed “…in Ghana t”)
        assertEquals("Searching the web for “what are the latest headlines in Ghana…”",
            StepLabels.label("web_search", "what are the latest headlines in Ghana today"))
        val long = "Photosynthesis uses light energy to turn carbon dioxide and water into glucose and releases oxygen. " +
            "Respiration breaks down glucose to release energy for the cell and consumes oxygen. Both happen in plants."
        val c = Summaries.compact(long)
        assertTrue(c, c.length <= 180 && c.startsWith("Photosynthesis uses light energy"))
        assertEquals("Short.", Summaries.compact("Short."))
        assertEquals("Explain photosynthesis", SurfaceModel.titleOf("okay so can you explain photosynthesis?"))
    }
}
