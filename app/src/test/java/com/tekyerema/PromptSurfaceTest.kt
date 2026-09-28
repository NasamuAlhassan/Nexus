package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Lang
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Owner (A20e, 2026-09-28): "when it needs feedback, sometimes the thing doesn't appear for you to talk back".
 * The reply panel follows the agent's state with buttons that fit the prompt, and an unanswered question stays open
 * for the user to answer later instead of ending the task after two silences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptSurfaceTest {

    @Test fun every_prompt_gets_a_panel_with_the_right_buttons() {
        val c = PromptSurface.of(AgentStatus.WAITING_CONFIRMATION, "Send Kofi “hi”?", null)!!
        assertEquals(PromptSurface.Kind.CONFIRM, c.kind)
        assertEquals(listOf("No", "Yes, do it"), PromptSurface.buttons(c.kind))
        val q = PromptSurface.of(AgentStatus.ASKING, null, "Which app did you mean?")!!
        assertEquals(PromptSurface.Kind.QUESTION, q.kind)
        assertFalse("a question never offers Yes/No", PromptSurface.buttons(q.kind).any { it.startsWith("Yes") })
        assertEquals(PromptSurface.Kind.SECURE, PromptSurface.of(AgentStatus.SECURE_INPUT, null, null)!!.kind)
        for (s in listOf(AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.COMPLETED, AgentStatus.STOPPED, AgentStatus.IDLE))
            assertNull(s.name, PromptSurface.of(s, "x", "y"))
    }

    @Test fun no_panel_in_the_gap_after_the_reply() {
        // The engine clears the prompt a moment before it leaves the waiting status; re-showing a generic panel there
        // would sit over the app while the approved action runs.
        assertNull(PromptSurface.of(AgentStatus.WAITING_CONFIRMATION, null, null))
        assertNull(PromptSurface.of(AgentStatus.ASKING, null, " "))
    }

    // ------------------------------------------------------------------ an unanswered question stays open
    private class SeqRecorder(vararg results: CaptureResult) : Recorder {
        private val queue = ArrayDeque(results.toList())
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            onListening(); return queue.removeFirstOrNull() ?: CaptureResult.NoSpeech
        }
    }
    private class SeqAsr(vararg texts: String) : AsrProvider {
        private val queue = ArrayDeque(texts.toList())
        override val name = "fake"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            val t = queue.removeFirstOrNull() ?: ""
            return Transcript(raw = t, canonical = t, language = Lang.GH_EN)
        }
    }
    private class LogTts : TtsProvider {
        override val name = "fake"
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: String): Boolean { spoken += text; return true }
        override fun stop() {}
        override fun shutdown() {}
    }
    private class AskingAgent : AgentBridge {
        val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val answers = mutableListOf<String?>()
        override fun submitGoal(goal: String) {}
        override fun stop() {}
        override val busy: Boolean get() = true
        override fun answer(text: String?) {
            answers += text
            _s.value = AgentUiState(status = AgentStatus.THINKING)
        }
    }

    @Test fun two_silences_leave_the_question_open_and_respond_listens_again() = runTest {
        val agent = AskingAgent()
        val tts = LogTts()
        val vc = VoiceController(
            asr = SeqAsr("WhatsApp"), tts = tts,
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { true }, agent = agent,
            mic = SeqRecorder(),
            answerMic = SeqRecorder(CaptureResult.NoSpeech, CaptureResult.NoSpeech, CaptureResult.Success(ByteArray(40), 300))
        )
        agent._s.value = AgentUiState(status = AgentStatus.ASKING, pendingQuestion = "Which app did you mean?")
        advanceUntilIdle()
        assertTrue("no answer(null): the task is not ended by silence", agent.answers.isEmpty())
        assertTrue("the user can reopen the question", vc.canRespond)
        assertTrue(tts.spoken.any { it.startsWith("I'll wait") })

        vc.respond()   // the panel's Answer button / "Hey Tɛkyerɛma"
        advanceUntilIdle()
        assertEquals(listOf<String?>("WhatsApp"), agent.answers)
        assertFalse(vc.canRespond)
        vc.shutdown()
    }
}
