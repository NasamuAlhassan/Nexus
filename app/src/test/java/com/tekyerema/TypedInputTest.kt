package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.InputSource
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed input is a thin layer over the existing voice architecture: the keyboard text enters the
 * agent through VoiceController.runTurn — the same function an ASR transcript goes through — and
 * never touches ASR. These tests pin that down so the feature stays a clean experimental control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedInputTest {

    private class CountingAsr(private val text: String = "spoken text") : AsrProvider {
        override val name = "fake"
        var calls = 0
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            calls++; return Transcript(raw = text, canonical = text, language = Lang.GH_EN)
        }
    }
    private class FixedRecorder(private val result: CaptureResult) : Recorder {
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult { onListening(); return result }
    }
    /** A microphone that stays open until cancelled — like a real one with nobody speaking. */
    private class OpenRecorder : Recorder {
        var opened = false
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            opened = true; onListening(); awaitCancellation()
        }
    }
    private class LogTts : TtsProvider {
        override val name = "fake"
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: String): Boolean { spoken += text; return true }
        override fun stop() {}
        override fun shutdown() {}
    }
    /** Completes immediately with a result — the normal agent pipeline's terminal state. */
    private class RecordingAgent(private val result: String, private val terminal: AgentStatus = AgentStatus.COMPLETED) : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val submitted = mutableListOf<String>()
        override fun submitGoal(goal: String) {
            submitted += goal
            _s.value = AgentUiState(status = terminal, goal = goal, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, result)))
        }
        override fun stop() {}
    }
    /** Pauses at the safety gate exactly like the real PolicyEngine path; resolves on confirm(). */
    private class GatedAgent : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        var confirmed: Boolean? = null
        override fun submitGoal(goal: String) {
            _s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, goal = goal, pendingConfirmation = "Call Prince?")
        }
        override fun confirm(approved: Boolean) {
            confirmed = approved
            _s.value = AgentUiState(status = if (approved) AgentStatus.COMPLETED else AgentStatus.STOPPED, goal = _s.value.goal,
                log = listOf(LogEntry(0, LogEntry.Kind.RESULT, "Prince is calling.")))
        }
        override fun stop() {}
    }

    private fun controller(
        scope: CoroutineScope, agent: AgentBridge, asr: AsrProvider, tts: TtsProvider,
        mic: Recorder = FixedRecorder(CaptureResult.Success(ByteArray(100), 600)),
        answerMic: Recorder = mic
    ) = VoiceController(
        asr = asr, tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
        scope = scope, hasMicPermission = { true }, agent = agent, mic = mic, answerMic = answerMic
    )

    // ---------------------------------------------------------------- the one entry point
    @Test fun typed_text_reaches_the_agent_exactly_and_never_touches_asr() = runTest {
        val agent = RecordingAgent("Prince has been asked to call you.")
        val asr = CountingAsr()
        val tts = LogTts()
        val vc = controller(this, agent, asr, tts)
        assertTrue(vc.submitText("  Tell Prince to call me  "))
        advanceUntilIdle()
        assertEquals(listOf("Tell Prince to call me"), agent.submitted)   // trimmed, otherwise verbatim
        assertEquals("ASR must not run for typed input", 0, asr.calls)
        assertEquals(InputSource.TYPED, vc.state.value.inputSource)
        assertEquals("Tell Prince to call me", vc.state.value.lastCanonical)
        assertEquals("the reply is spoken exactly as for a voice turn", "Prince has been asked to call you.", tts.spoken.last())
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        vc.shutdown()
    }

    @Test fun voice_and_typed_input_converge_on_the_same_agent_call() = runTest {
        val agent = RecordingAgent("done")
        val asr = CountingAsr("tell prince to call me")
        val vc = controller(this, agent, asr, LogTts())
        vc.start(); advanceUntilIdle()                          // voice: mic → ASR → agent
        assertEquals(1, asr.calls)
        assertEquals(InputSource.ASR, vc.state.value.inputSource)
        vc.submitText("tell prince to call me"); advanceUntilIdle()   // typed: keyboard → agent
        assertEquals(1, asr.calls)                              // still one: typed input skipped ASR
        assertEquals(InputSource.TYPED, vc.state.value.inputSource)
        assertEquals("identical text reached the agent both times",
            listOf("tell prince to call me", "tell prince to call me"), agent.submitted)
        vc.shutdown()
    }

    @Test fun typed_instruction_uses_the_existing_confirmation_gate() = runTest {
        val agent = GatedAgent()
        val tts = LogTts()
        // The answer microphone hears "yes" (through the same ASR the voice confirmation uses).
        val vc = controller(this, agent, CountingAsr("yes"), tts,
            answerMic = FixedRecorder(CaptureResult.Success(ByteArray(40), 300)))
        assertTrue(vc.submitText("call Prince")); advanceUntilIdle()
        assertEquals("You want me to call Prince. Should I go ahead?", tts.spoken.first())
        assertEquals(true, agent.confirmed)
        assertEquals("Prince is calling.", tts.spoken.last())
        vc.shutdown()
    }

    @Test fun long_and_complex_instructions_pass_through_verbatim() = runTest {
        val agent = RecordingAgent("done")
        val vc = controller(this, agent, CountingAsr(), LogTts())
        val text = "Find the message Prince sent me this morning, read it, and reply that I will call him after class."
        vc.submitText(text); advanceUntilIdle()
        assertEquals(listOf(text), agent.submitted)
        vc.shutdown()
    }

    // ---------------------------------------------------------------- edge cases
    @Test fun empty_input_does_nothing() = runTest {
        val agent = RecordingAgent("done")
        val vc = controller(this, agent, CountingAsr(), LogTts())
        assertFalse(vc.submitText(""))
        assertFalse(vc.submitText("   \n"))
        advanceUntilIdle()
        assertTrue(agent.submitted.isEmpty())
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        vc.shutdown()
    }

    @Test fun typing_closes_an_open_microphone_and_keeps_the_session() = runTest {
        val agent = RecordingAgent("done")
        val asr = CountingAsr()
        val mic = OpenRecorder()
        val vc = controller(this, agent, asr, LogTts(), mic = mic)
        vc.start(); advanceUntilIdle()
        assertTrue(mic.opened)
        assertEquals(VoiceState.LISTENING, vc.state.value.state)
        vc.beginTyping(); advanceUntilIdle()                    // keyboard opens → the mic must not compete
        assertEquals(VoiceState.TYPING, vc.state.value.state)
        assertFalse(vc.isRunning)
        assertEquals("no capture, no transcription", 0, asr.calls)
        assertTrue(vc.submitText("Tell Prince to call me")); advanceUntilIdle()
        assertEquals(listOf("Tell Prince to call me"), agent.submitted)
        assertEquals(VoiceState.IDLE, vc.state.value.state)      // back to the normal resting state
        vc.shutdown()
    }

    @Test fun cancelling_typing_returns_to_idle_without_submitting() = runTest {
        val agent = RecordingAgent("done")
        val vc = controller(this, agent, CountingAsr(), LogTts())
        vc.beginTyping(); assertEquals(VoiceState.TYPING, vc.state.value.state)
        vc.endTyping(); assertEquals(VoiceState.IDLE, vc.state.value.state)
        advanceUntilIdle()
        assertTrue(agent.submitted.isEmpty())
        vc.shutdown()
    }

    @Test fun typed_input_during_a_voice_turn_replaces_it_and_is_never_lost() = runTest {
        // C-12 (was: refused). A turn still listening has acted on nothing yet: the newest, explicit request wins —
        // one turn at a time, never two stacked, and never a silent drop.
        val agent = RecordingAgent("done")
        val asr = CountingAsr()
        val vc = controller(this, agent, asr, LogTts(), mic = OpenRecorder())
        vc.start(); advanceUntilIdle()                          // a voice turn is listening
        assertTrue(vc.submitText("open settings"))
        advanceUntilIdle()
        assertEquals(listOf("open settings"), agent.submitted)
        assertEquals("the abandoned listen was never transcribed", 0, asr.calls)
        assertNull(agent.submitted.firstOrNull { it == "spoken text" })
        vc.shutdown()
    }
}
