package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Lang
import com.tekyerema.voice.NewRequest
import com.tekyerema.voice.NewRequest.Route
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-12 — a new request is never silently dropped. Owner-reported: a request typed while the previous reply was still
 * being read aloud was refused without a word. Now it interrupts the reply; typed while a task works it goes to the
 * task; typed while a prompt waits it is the answer (a typed yes only when it is clearly a yes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewRequestTest {

    // ------------------------------------------------------------------ the routing table (pure)
    @Test fun routing_table() {
        fun r(s: AgentStatus, busy: Boolean, turn: Boolean, v: VoiceState) = NewRequest.route(s, busy, turn, v)
        assertEquals(Route.START, r(AgentStatus.IDLE, false, false, VoiceState.IDLE))
        assertEquals(Route.START, r(AgentStatus.COMPLETED, false, false, VoiceState.IDLE))
        // our own reply (or a listen with nothing acted on yet) is in the way → the new request wins
        assertEquals(Route.PREEMPT, r(AgentStatus.COMPLETED, false, true, VoiceState.SPEAKING))
        assertEquals(Route.PREEMPT, r(AgentStatus.COMPLETED, false, true, VoiceState.LISTENING))
        assertEquals(Route.PREEMPT, r(AgentStatus.IDLE, false, true, VoiceState.TRANSCRIBING))
        // the fast path may be changing a setting this instant → wait, don't cut it in half
        assertEquals(Route.WAIT, r(AgentStatus.IDLE, false, true, VoiceState.UNDERSTANDING))
        // a task working → an interjection (stop / pause / status / a correction)
        assertEquals(Route.INTERJECT, r(AgentStatus.ACTING, true, true, VoiceState.EXECUTING))
        assertEquals(Route.INTERJECT, r(AgentStatus.PAUSED, true, false, VoiceState.IDLE))
        // a task waiting on the user → the answer, whatever else is going on
        for (p in listOf(AgentStatus.WAITING_CONFIRMATION, AgentStatus.ASKING, AgentStatus.SECURE_INPUT))
            assertEquals(p.name, Route.ANSWER, r(p, true, true, VoiceState.CONFIRMATION_REQUIRED))
    }

    // ------------------------------------------------------------------ fakes
    private class Asr(private val text: String = "what's the time") : AsrProvider {
        override val name = "fake"
        var calls = 0
        var onTranscribe: () -> Unit = {}
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            calls++; onTranscribe(); return Transcript(raw = text, canonical = text, language = Lang.GH_EN)
        }
    }
    private class SeqRecorder(vararg results: CaptureResult) : Recorder {
        private val queue = ArrayDeque(results.toList())
        var captures = 0
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            captures++; onListening(); return queue.removeFirstOrNull() ?: CaptureResult.NoSpeech
        }
    }
    /** Speaks instantly, except lines matching [hold]: those play until stop() or cancellation — a long reading. */
    private class HoldingTts(private val hold: (String) -> Boolean) : TtsProvider {
        override val name = "fake"
        val spoken = mutableListOf<String>()
        var stops = 0
        private var gate: CompletableDeferred<Boolean>? = null
        val holding: Boolean get() = gate != null
        override suspend fun speak(text: String, language: String): Boolean {
            spoken += text
            if (!hold(text)) return true
            val g = CompletableDeferred<Boolean>().also { gate = it }
            return try { g.await() } finally { if (gate === g) gate = null }
        }
        override fun stop() { stops++; gate?.complete(false) }
        override fun shutdown() {}
    }
    /** Completes each goal at once with "<reply for goal>". */
    private class Agent(private val reply: (String) -> String) : AgentBridge {
        val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val submitted = mutableListOf<String>()
        val steered = mutableListOf<String>()
        var stopped = 0
        var busyFlag = false
        override val busy: Boolean get() = busyFlag
        var confirmed: Boolean? = null
        var revised: String? = null
        var secureDone = 0
        override fun submitGoal(goal: String) {
            submitted += goal
            _s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = goal, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, reply(goal))))
        }
        override fun stop() { stopped++; busyFlag = false; _s.value = _s.value.copy(status = AgentStatus.STOPPED) }
        override fun steer(text: String) { steered += text }
        override fun statusLine() = "I'm opening an app, working on: call mum."
        override fun confirm(approved: Boolean) {
            if (_s.value.status != AgentStatus.WAITING_CONFIRMATION) return
            confirmed = approved
            _s.value = _s.value.copy(status = if (approved) AgentStatus.ACTING else AgentStatus.STOPPED, pendingConfirmation = null)
        }
        override fun revise(text: String) { revised = text }
        override fun secureInputDone() { secureDone++ }
    }

    private val longReading = "You're in Messages. " + "Kofi says the meeting moved to Thursday afternoon. ".repeat(8)

    private fun controller(
        scope: CoroutineScope, agent: AgentBridge, tts: TtsProvider, asr: AsrProvider = Asr(),
        mic: Recorder = SeqRecorder(CaptureResult.Success(ByteArray(100), 600)), voiceConfirm: Boolean = false
    ) = VoiceController(
        asr = asr, tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = scope,
        hasMicPermission = { true }, agent = agent, mic = mic, answerMic = mic, voiceConfirmation = { voiceConfirm }
    )

    // ------------------------------------------------------------------ typed while the reply is read
    @Test fun typed_request_while_a_reply_is_read_stops_the_reading_and_runs() = runTest {
        val agent = Agent { g -> if (g == "read the screen") longReading else "Opening Settings." }
        val tts = HoldingTts { it.startsWith("You're in Messages") }
        val vc = controller(this, agent, tts)
        assertTrue(vc.submitText("read the screen")); runCurrent()
        assertTrue("the reading is playing", tts.holding)
        assertEquals(VoiceState.SPEAKING, vc.state.value.state)

        val r = vc.submitTyped("open settings")
        assertEquals(Route.PREEMPT, r.route)
        assertTrue("accepted — the field clears, nothing is lost", r.accepted)
        advanceUntilIdle()
        assertFalse("the reading was cut", tts.holding)
        assertEquals(listOf("read the screen", "open settings"), agent.submitted)
        assertEquals("Opening Settings.", tts.spoken.last())
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        vc.shutdown()
    }

    // ------------------------------------------------------------------ the mic while the reply is read
    @Test fun talking_while_a_reply_is_read_stops_it_and_listens() = runTest {
        val agent = Agent { g -> if (g == "read the screen") longReading else "It's ten o'clock." }
        val tts = HoldingTts { it.startsWith("You're in Messages") }
        val mic = SeqRecorder(CaptureResult.Success(ByteArray(100), 600))
        val vc = controller(this, agent, tts, asr = Asr("what's the time"), mic = mic)
        vc.submitText("read the screen"); runCurrent()
        assertTrue(vc.canPreempt)

        vc.start()                                   // opening Tɛkyerɛma / the mic button
        advanceUntilIdle()
        assertEquals("the mic opened for the new request", 1, mic.captures)
        assertEquals(listOf("read the screen", "what's the time"), agent.submitted)
        assertEquals("It's ten o'clock.", tts.spoken.last())
        vc.shutdown()
    }

    @Test fun a_tap_while_it_talks_listens_quietly_and_silence_just_rests() = runTest {
        val agent = Agent { longReading }
        val tts = HoldingTts { it.startsWith("You're in Messages") }
        val mic = SeqRecorder(CaptureResult.NoSpeech)
        val vc = controller(this, agent, tts, mic = mic)
        vc.submitText("read the screen"); runCurrent()

        vc.interrupt()
        advanceUntilIdle()
        assertEquals(1, mic.captures)
        assertFalse("no \"I didn't hear anything\" nag after a tap meant as 'be quiet'", tts.spoken.any { it.startsWith("I didn't hear") })
        assertEquals(0, agent.stopped)
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        vc.shutdown()
    }

    @Test fun a_tap_while_a_task_works_is_still_the_kill_switch() = runTest {
        val agent = Agent { "x" }.apply { busyFlag = true; _s.value = AgentUiState(status = AgentStatus.ACTING, goal = "call mum") }
        val vc = controller(this, agent, HoldingTts { false })
        vc.interrupt()
        advanceUntilIdle()
        assertEquals(1, agent.stopped)
        vc.shutdown()
    }

    // ------------------------------------------------------------------ typed while a task works
    @Test fun typed_while_a_task_works_goes_to_the_task() = runTest {
        val agent = Agent { "x" }.apply { busyFlag = true; _s.value = AgentUiState(status = AgentStatus.ACTING, goal = "send 20 to Kofi") }
        val tts = HoldingTts { false }
        val vc = controller(this, agent, tts)
        val r = vc.submitTyped("make it 50")
        advanceUntilIdle()
        assertEquals(Route.INTERJECT, r.route)
        assertEquals(listOf("make it 50"), agent.steered)
        assertTrue("a correction never becomes a second task", agent.submitted.isEmpty())
        assertEquals("Got it, I'll change that.", r.notice)
        assertTrue(tts.spoken.contains("Got it, I'll change that."))

        assertEquals("I'm opening an app, working on: call mum.", vc.submitTyped("what are you doing?").notice)
        vc.submitTyped("stop"); advanceUntilIdle()
        assertEquals(1, agent.stopped)
        vc.shutdown()
    }

    // ------------------------------------------------------------------ typed while a prompt waits
    @Test fun a_typed_yes_answers_a_confirmation_but_a_sentence_never_does() = runTest {
        val agent = Agent { "x" }.apply {
            busyFlag = true
            _s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, goal = "call Prince", pendingConfirmation = "Call Prince?")
        }
        val vc = controller(this, agent, HoldingTts { false })
        // A sentence holding a yes-word is never a yes: kept in the field, with the reason.
        val sentence = vc.submitTyped("ok sure thing")
        assertFalse("kept in the field", sentence.accepted)
        assertNull("a sentence containing 'ok' must never approve what is waiting", agent.confirmed)
        assertTrue(sentence.notice.contains("yes or no"))
        // One that names someone the read-back doesn't is a correction (re-planned and confirmed again — DECISIONS V2-14).
        vc.submitTyped("ok call mum")
        assertEquals("ok call mum", agent.revised)
        assertNull("a new request containing 'ok' must never approve what is waiting", agent.confirmed)
        assertFalse(vc.submitTyped("hmm").accepted)
        assertNull(agent.confirmed)

        assertTrue(vc.submitTyped("Yes").accepted)
        assertEquals(true, agent.confirmed)
        vc.shutdown()
    }

    @Test fun typed_no_and_corrections_answer_a_confirmation() = runTest {
        val agent = Agent { "x" }.apply {
            busyFlag = true
            _s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, goal = "send 20 to Kofi", pendingConfirmation = "Send 20 to Kofi?")
        }
        val vc = controller(this, agent, HoldingTts { false })
        vc.submitTyped("send it to Ama instead")
        assertEquals("send it to Ama instead", agent.revised)
        assertNull(agent.confirmed)
        vc.submitTyped("no")
        assertEquals(false, agent.confirmed)
        vc.shutdown()
    }

    @Test fun the_secure_step_only_takes_done_and_never_forwards_other_text() = runTest {
        val agent = Agent { "x" }.apply { busyFlag = true; _s.value = AgentUiState(status = AgentStatus.SECURE_INPUT, goal = "send 20 to Kofi") }
        val vc = controller(this, agent, HoldingTts { false })
        val pin = vc.submitTyped("1234")
        assertTrue("cleared from the field (it may be a PIN typed in the wrong box)", pin.accepted)
        assertEquals(0, agent.secureDone)
        assertTrue(agent.steered.isEmpty() && agent.submitted.isEmpty())
        assertTrue(pin.notice.contains("done"))
        vc.submitTyped("done")
        assertEquals(1, agent.secureDone)
        vc.shutdown()
    }

    // ------------------------------------------------------------------ a turn never waits on someone else's task
    @Test fun a_turn_that_finds_a_task_already_running_says_so_and_ends() = runTest {
        val agent = Agent { "x" }
        val asr = Asr("call mum")
        // A scheduled task starts while the user's words are being transcribed.
        asr.onTranscribe = { agent.busyFlag = true; agent._s.value = AgentUiState(status = AgentStatus.ACTING, goal = "morning briefing") }
        val tts = HoldingTts { false }
        val vc = controller(this, agent, tts, asr = asr)
        vc.start()
        advanceUntilIdle()
        assertTrue("the busy engine is never handed a second goal", agent.submitted.isEmpty())
        assertTrue("not folded into a task the user may not know about", agent.steered.isEmpty())
        assertTrue(tts.spoken.any { it.startsWith("I'm still working on another task") })
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        assertFalse(vc.isRunning)
        vc.shutdown()
    }

    // ------------------------------------------------------------------ one microphone user at a time
    @Test fun a_new_turn_waits_until_the_cancelled_one_has_released_the_mic() = runTest {
        var open = 0
        var overlapped = false
        val mic = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                if (open > 0) overlapped = true
                open++
                try { onListening(); awaitCancellation() }
                finally { withContext(NonCancellable) { delay(50) }; open-- }   // a recorder takes a moment to stop
            }
        }
        val vc = controller(this, Agent { "x" }, HoldingTts { false }, mic = mic)
        vc.start(); runCurrent()
        vc.cancel()
        vc.start(); runCurrent()
        advanceUntilIdle()
        assertFalse("two captures must never share the recorder and VAD", overlapped)
        assertEquals("the new turn is listening, not overwritten by the old one's \"Cancelled.\"", VoiceState.LISTENING, vc.state.value.state)
        vc.shutdown()
    }
}
