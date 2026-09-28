package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.ConfirmGate
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** J-02: only a fresh, explicit yes to the confirmation pending NOW approves anything. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmGateTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    @Test fun a_yes_counts_only_for_the_confirmation_it_answered() {
        assertTrue(ConfirmGate.accepts(approved = true, waiting = true, currentSeq = 7, replySeq = 7))
        assertFalse("a yes given to an earlier question", ConfirmGate.accepts(true, true, 7, 6))
        assertFalse("a panel not drawn for a confirmation", ConfirmGate.accepts(true, true, 7, ConfirmGate.NO_CONFIRMATION))
        assertFalse("nothing is waiting", ConfirmGate.accepts(true, false, 7, 7))
        // A "no" can only stop things: it is honoured for whatever is pending.
        assertTrue(ConfirmGate.accepts(false, true, 7, 6))
        assertFalse(ConfirmGate.accepts(false, false, 7, 7))
        // The ADB test hook can't know the number (debug builds only).
        assertTrue(ConfirmGate.accepts(true, true, 7, null))
    }

    @Test fun the_panel_yes_needs_the_panel_to_have_been_up_for_a_moment() {
        assertFalse("a tap already on its way when the panel appeared", ConfirmGate.armed(shownAtMs = 1_000, nowMs = 1_150))
        assertTrue(ConfirmGate.armed(1_000, 1_000 + ConfirmGate.ARM_MS))
        // Config may only make it more careful.
        AppConfig.loadFrom(listOf("policy.json" to """{"policy":{"confirm":{"armMs":50}}}"""))
        assertEquals(ConfirmGate.ARM_MS, ConfirmGate.armMs())
        AppConfig.loadFrom(listOf("policy.json" to """{"policy":{"confirm":{"armMs":1200}}}"""))
        assertEquals(1200L, ConfirmGate.armMs())
    }

    @Test fun two_confirmations_with_the_same_words_are_two_panels() {
        val a = PromptSurface.of(AgentStatus.WAITING_CONFIRMATION, "Send this message?", null, seq = 1)
        val b = PromptSurface.of(AgentStatus.WAITING_CONFIRMATION, "Send this message?", null, seq = 2)
        assertNotEquals("the panel is redrawn (and re-bound) for the second one", a, b)
    }

    // ---- voice: the spoken yes names the confirmation that was spoken ----
    private class OneRecorder : Recorder {
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult =
            CaptureResult.Success(ByteArray(40), 300)
    }
    private class Says(private val text: String) : AsrProvider {
        override val name = "fake"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?) =
            Transcript(raw = text, canonical = text, language = Lang.GH_EN)
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

    @Test fun a_spoken_yes_carries_the_number_of_the_question_asked() = runTest {
        val agent = Agent()
        val vc = VoiceController(asr = Says("yes"), tts = Quiet(), sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { true }, agent = agent, mic = OneRecorder(), answerMic = OneRecorder())
        agent.s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, pendingConfirmation = "Call Prince?", confirmationSeq = 41)
        advanceUntilIdle()
        assertEquals(listOf(41L to true), agent.answers)
        vc.shutdown()
    }

    @Test fun nothing_is_asked_about_a_confirmation_already_answered() = runTest {
        val agent = Agent()
        val vc = VoiceController(asr = Says("yes"), tts = Quiet(), sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = this, hasMicPermission = { true }, agent = agent, mic = OneRecorder(), answerMic = OneRecorder())
        // The engine clears the prompt once it has its answer, and stays WAITING while it acts on it.
        agent.s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, pendingConfirmation = null, confirmationSeq = 3)
        advanceUntilIdle()
        assertTrue("no 'Should I go ahead?' about nothing, and no yes queued for the next one", agent.answers.isEmpty())
        vc.shutdown()
    }
}
