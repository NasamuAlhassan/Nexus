@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.tekyerema

import com.tekyerema.voice.AgentBridge
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.ConfirmRetry
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen learns WHY a spoken confirmation is asked again (docs/assistant-ux DECISIONS V2-6: the one voice-file change of
 * the v2 surface), and nothing about how an answer is decided changes: the same yes/no reading, the same confirmation number.
 */
class ConfirmRetryTest {
    private class OneRecorder : Recorder {
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult =
            CaptureResult.Success(ByteArray(40), 300)
    }
    /** Says each line in turn, then the last one forever. */
    private class Says(private vararg val lines: String) : AsrProvider {
        private var i = 0
        override val name = "fake"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            val t = lines[minOf(i++, lines.lastIndex)]
            return Transcript(raw = t, canonical = t, language = Lang.GH_EN)
        }
    }
    private class Quiet : TtsProvider {
        override val name = "fake"
        override suspend fun speak(text: String, language: String) = true
        override fun stop() {}
        override fun shutdown() {}
    }
    private class Agent : AgentBridge {
        val s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = s
        val answers = ArrayList<Pair<Long, Boolean>>()
        override fun submitGoal(goal: String) {}
        override fun stop() {}
        override fun confirmFor(seq: Long, approved: Boolean) {
            answers += seq to approved
            s.value = AgentUiState(status = AgentStatus.COMPLETED)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.run(vararg heard: String): Pair<List<Pair<Long, Boolean>>, List<ConfirmRetry>> {
        val agent = Agent()
        val vc = VoiceController(asr = Says(*heard), tts = Quiet(), sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { true }, agent = agent, mic = OneRecorder(), answerMic = OneRecorder())
        val seen = ArrayList<ConfirmRetry>()
        val watch = launch(UnconfinedTestDispatcher(testScheduler)) { vc.state.collect { seen += it.confirmRetry } }
        agent.s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, pendingConfirmation = "Call Prince?", confirmationSeq = 7)
        advanceUntilIdle()
        watch.cancel(); vc.shutdown()
        return agent.answers to seen
    }

    @Test fun an_unclear_answer_is_asked_again_and_the_screen_hears_why() = runTest {
        val (answers, seen) = run("purple monkey", "yes")
        assertEquals("the yes still names the confirmation it answers", listOf(7L to true), answers)
        assertTrue("the screen saw it was not understood", ConfirmRetry.UNCLEAR in seen)
        assertFalse(ConfirmRetry.HOLD in seen)
    }

    @Test fun wait_is_a_pause_not_a_miss() = runTest {
        val (answers, seen) = run("wait", "no")
        assertEquals(listOf(7L to false), answers)
        assertTrue(ConfirmRetry.HOLD in seen)
        assertFalse("a 'wait' is never shown as 'didn't catch that'", ConfirmRetry.UNCLEAR in seen)
    }

    @Test fun a_clear_first_answer_is_never_marked_as_asked_again() = runTest {
        val (answers, seen) = run("yes")
        assertEquals(listOf(7L to true), answers)
        assertTrue(seen.all { it == ConfirmRetry.NONE })
    }
}
