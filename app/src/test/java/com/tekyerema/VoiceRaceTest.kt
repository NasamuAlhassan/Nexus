package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Lang
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

/** C-12 / C-13 — the voice state machine under overlapping events: interjections, prompts, stops. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRaceTest {

    private class LogTts : TtsProvider {
        override val name = "fake"
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: String): Boolean { spoken += text; return true }
        override fun stop() {}
        override fun shutdown() {}
    }
    /** Transcribes from a queue; a null entry suspends until [release] (a slow transcription). */
    private class QueueAsr(vararg texts: String?) : AsrProvider {
        private val queue = ArrayDeque(texts.toList())
        override val name = "fake"
        val gate = CompletableDeferred<String>()
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            val t = if (queue.isEmpty()) "" else queue.removeFirst() ?: gate.await()
            return Transcript(raw = t, canonical = t, language = Lang.GH_EN)
        }
    }
    /** A task that works until stopped; confirmations and questions are driven by the test. */
    private class TaskAgent : AgentBridge {
        val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        var busyFlag = false
        override val busy: Boolean get() = busyFlag
        val steered = mutableListOf<String>()
        val answers = mutableListOf<String?>()
        var revised: String? = null
        var confirmed: Boolean? = null
        override fun submitGoal(goal: String) { busyFlag = true; _s.value = AgentUiState(status = AgentStatus.ACTING, goal = goal) }
        override fun stop() { busyFlag = false; _s.value = _s.value.copy(status = AgentStatus.STOPPED, pendingConfirmation = null) }
        override fun steer(text: String) { steered += text }
        override fun revise(text: String) { revised = text; _s.value = _s.value.copy(status = AgentStatus.THINKING, pendingConfirmation = null) }
        override fun confirm(approved: Boolean) {
            if (_s.value.status != AgentStatus.WAITING_CONFIRMATION) return
            confirmed = approved; _s.value = _s.value.copy(status = AgentStatus.ACTING, pendingConfirmation = null)
        }
        override fun answer(text: String?) { answers += text; _s.value = _s.value.copy(status = AgentStatus.THINKING, pendingQuestion = null) }
        fun ask(confirmation: String) { _s.value = _s.value.copy(status = AgentStatus.WAITING_CONFIRMATION, pendingConfirmation = confirmation) }
    }

    private fun controller(scope: CoroutineScope, agent: AgentBridge, tts: TtsProvider, asr: AsrProvider, answerMic: Recorder) =
        VoiceController(
            asr = asr, tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = scope,
            hasMicPermission = { true }, agent = agent, mic = answerMic, answerMic = answerMic
        )

    @Test fun a_confirmation_takes_the_answer_mic_from_a_listening_interjection() = runTest {
        var open = 0
        var overlapped = false
        var n = 0
        val mic = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                if (open > 0) overlapped = true
                open++
                try {
                    onListening()
                    if (n++ == 0) awaitCancellation()          // the interjection: nobody has spoken yet
                    return CaptureResult.Success(ByteArray(40), 300)
                } finally { withContext(NonCancellable) { delay(50) }; open-- }
            }
        }
        val agent = TaskAgent().apply { submitGoal("send 20 to Kofi") }
        val tts = LogTts()
        val vc = controller(this, agent, tts, QueueAsr("yes"), mic)
        vc.interject(); runCurrent()
        agent.ask("Send 20 cedis to Kofi?")
        advanceUntilIdle()
        assertFalse("the interjection and the confirmation never hold the recorder together", overlapped)
        assertTrue("the question was asked", tts.spoken.any { it.contains("Should I go ahead?") })
        assertEquals(true, agent.confirmed)
        assertTrue("the silent interjection handed nothing to the task", agent.steered.isEmpty())
        vc.shutdown()
    }

    @Test fun a_correction_heard_as_the_confirmation_arrives_revises_it_instead_of_queueing() = runTest {
        val mic = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                onListening(); onSpeechStart(); return CaptureResult.Success(ByteArray(40), 300)
            }
        }
        val asr = QueueAsr(null)                              // "make it 50" is still being transcribed…
        val agent = TaskAgent().apply { submitGoal("send 20 to Kofi") }
        val vc = controller(this, agent, LogTts(), asr, mic)
        vc.interject(); runCurrent()
        agent.ask("Send 20 cedis to Kofi?")                   // …when the task asks for its OK
        runCurrent()
        asr.gate.complete("make it 50")
        advanceUntilIdle()
        assertEquals("never lost, never a yes: a revision of the waiting proposal", "make it 50", agent.revised)
        assertTrue("not queued behind the old proposal", agent.steered.isEmpty())
        assertNull(agent.confirmed)
        assertFalse("speechDetected is cleared when the interjection ends", vc.state.value.speechDetected)
        vc.shutdown()
    }

    @Test fun stop_by_voice_is_said_once_and_a_later_stop_is_still_said() = runTest {
        val mic = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                onListening(); onSpeechStart(); return CaptureResult.Success(ByteArray(40), 300)
            }
        }
        val agent = TaskAgent()
        val tts = LogTts()
        val vc = controller(this, agent, tts, QueueAsr("stop"), mic)
        vc.submitText("call mum"); runCurrent()               // a task is working
        assertTrue(agent.busy)
        vc.interject(); runCurrent()                          // "Hey Tɛkyerɛma… stop"
        runCurrent()
        assertEquals("\"Stopped.\" once, not once from the interjection and again from the turn",
            1, tts.spoken.count { it == "Stopped." })
        assertEquals(VoiceState.IDLE, vc.state.value.state)

        vc.submitText("open settings"); runCurrent()          // the next task…
        agent.stop(); runCurrent()                            // …stopped from the notification
        assertEquals("a stale flag used to silence this one", 2, tts.spoken.count { it == "Stopped." })
        vc.shutdown()
    }

    @Test fun a_question_answered_without_a_voice_turn_does_not_leave_the_screen_asking() = runTest {
        val mic = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                onListening(); return CaptureResult.Success(ByteArray(40), 300)
            }
        }
        val agent = TaskAgent().apply { busyFlag = true }
        val vc = controller(this, agent, LogTts(), QueueAsr("WhatsApp"), mic)
        // A scheduled task (no voice turn waiting on it) asks a question.
        agent._s.value = AgentUiState(status = AgentStatus.ASKING, goal = "morning messages", pendingQuestion = "Which app?")
        advanceUntilIdle()
        assertEquals(listOf<String?>("WhatsApp"), agent.answers)
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        vc.shutdown()
    }

    @Test fun a_task_result_log_is_spoken_after_an_interjection_status() = runTest {
        // "What are you doing?" mid-task is answered, and the task's own result is still spoken at the end.
        val mic = object : Recorder {
            override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
                onListening(); return CaptureResult.Success(ByteArray(40), 300)
            }
        }
        val agent = object : AgentBridge {
            val _s = MutableStateFlow(AgentUiState())
            override val state: StateFlow<AgentUiState> = _s
            override val busy: Boolean get() = _s.value.status == AgentStatus.ACTING
            override fun submitGoal(goal: String) { _s.value = AgentUiState(status = AgentStatus.ACTING, goal = goal) }
            override fun stop() {}
            override fun statusLine() = "I'm opening an app, working on: call mum."
        }
        val tts = LogTts()
        val vc = controller(this, agent, tts, QueueAsr("what are you doing"), mic)
        vc.submitText("call mum"); runCurrent()
        vc.interject(); runCurrent()
        agent._s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = "call mum", log = listOf(LogEntry(0, LogEntry.Kind.RESULT, "Calling Mum.")))
        runCurrent()
        assertTrue(tts.spoken.contains("I'm opening an app, working on: call mum."))
        assertEquals("Calling Mum.", tts.spoken.last())
        vc.shutdown()
    }
}
