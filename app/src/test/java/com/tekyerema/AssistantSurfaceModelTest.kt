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
    private fun result(status: AgentStatus, summary: String, goal: String) =
        JSONObject().put("status", status.name).put("summary", summary).put("goal", goal).toString()
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
            lastResult = result(AgentStatus.COMPLETED, "Photosynthesis makes glucose from light. Respiration breaks it down for energy.", "x"),
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
            lastResult = result(AgentStatus.COMPLETED, "The closest is Legon City Mall.", "x"), log = listOf(resultLog("x")))
        val a = SurfaceModel.reduce(idleVoice, agent, now = now).preview as Preview.Answer
        assertEquals("closest mall", a.mapQuery)
        assertNull("no map link was returned by any step", a.mapLink)
    }

    @Test fun a_device_action_a_failure_and_a_stop_each_say_what_really_happened() {
        var steps = AgentSteps.started(emptyList(), 4, 1, "open_setting", "wifi")
        steps = AgentSteps.finished(steps, 4, 1, ok = true)
        val done = AgentUiState(status = AgentStatus.COMPLETED, runSeq = 4, steps = steps, goal = "turn on the wifi",
            lastResult = result(AgentStatus.COMPLETED, "Turned Wi-Fi on.", "x"), log = listOf(resultLog("Turned Wi-Fi on.")))
        val action = SurfaceModel.reduce(idleVoice, done, now = now).preview as Preview.Action
        assertEquals("Turned Wi-Fi on.", action.summary); assertTrue(action.verified)
        val unverified = SurfaceModel.reduce(idleVoice, done.copy(status = AgentStatus.COMPLETED_UNVERIFIED), now = now).preview as Preview.Action
        assertFalse("never claimed as verified", unverified.verified)
        val failed = SurfaceModel.reduce(idleVoice, done.copy(status = AgentStatus.FAILED,
            lastResult = result(AgentStatus.FAILED, "WhatsApp didn't open.", "x")), now = now)
        assertEquals(Mode.FAILED, failed.mode)
        assertEquals("turn on the wifi", (failed.preview as Preview.Problem).retryGoal)
        val cannot = SurfaceModel.reduce(idleVoice, done.copy(status = AgentStatus.CANNOT_DO,
            lastResult = result(AgentStatus.CANNOT_DO, "I can't pay bills yet.", "x")), now = now)
        assertNull("trying again can't help", (cannot.preview as Preview.Problem).retryGoal)
        assertEquals(Mode.CANCELLED, SurfaceModel.reduce(idleVoice, done.copy(status = AgentStatus.STOPPED,
            lastResult = result(AgentStatus.STOPPED, "Stopped by user.", "x")), now = now).mode)
    }

    @Test fun a_dismissed_or_stale_result_is_gone_and_a_quick_answer_is_shown_as_itself() {
        val done = AgentUiState(status = AgentStatus.COMPLETED, runSeq = 4, goal = "explain photosynthesis",
            lastResult = result(AgentStatus.COMPLETED, "Plants make food from light.", "x"), log = listOf(resultLog("x")))
        assertEquals(Mode.PRESENTING, SurfaceModel.reduce(idleVoice, done, now = now).mode)
        assertEquals(Mode.HIDDEN, SurfaceModel.reduce(idleVoice, done, SurfaceModel.Local(dismissedRun = 4, dismissedAt = now - 10), now = now).mode)
        assertEquals("an hour later", Mode.HIDDEN, SurfaceModel.reduce(idleVoice, done.copy(log = listOf(resultLog("x", now - 3_600_000))), now = now).mode)
        // the user asked something the phone answered by itself ("what's the time") after dismissing — that reply, not the old run
        val voice = VoiceUiState(state = VoiceState.SPEAKING, spoken = "It's 3:05 in the afternoon.", spokenAt = now - 500,
            lastCanonical = "what's the time")
        val s = SurfaceModel.reduce(voice, done, SurfaceModel.Local(dismissedRun = 4, dismissedAt = now - 2_000), now = now)
        assertEquals(Mode.PRESENTING, s.mode)
        assertEquals("reply:${now - 500}", s.key)
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
        val long = "Photosynthesis uses light energy to turn carbon dioxide and water into glucose and releases oxygen. " +
            "Respiration breaks down glucose to release energy for the cell and consumes oxygen. Both happen in plants."
        val c = Summaries.compact(long)
        assertTrue(c, c.length <= 180 && c.startsWith("Photosynthesis uses light energy"))
        assertEquals("Short.", Summaries.compact("Short."))
        assertEquals("Explain photosynthesis", SurfaceModel.titleOf("okay so can you explain photosynthesis?"))
    }
}
