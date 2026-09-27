package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.ConfirmPhase
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.Confirmation.Answer
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.VoiceState
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
 * Confirmation by voice: the question is spoken, the answer is heard, only a clear YES proceeds.
 * The agent fake pauses at WAITING_CONFIRMATION exactly like the real policy gate and resolves
 * on confirm(), so the whole voice turn — request → gate → answer → result — runs end to end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmationTest {

    // ------------------------------------------------------------------ interpretation
    @Test fun clear_yes_in_english_and_twi() {
        listOf("Yes", "yes please", "Yeah, go ahead.", "Okay do it", "sure", "Aane", "yoo", "kɔ so", "Ko so.").forEach {
            assertEquals(it, Answer.YES, Confirmation.interpret(it))
        }
    }

    @Test fun clear_no_in_english_and_twi() {
        listOf("No", "no thanks", "Don't.", "stop", "cancel that", "never mind", "Daabi", "gyae", "dabi dabi").forEach {
            assertEquals(it, Answer.NO, Confirmation.interpret(it))
        }
    }

    @Test fun mixed_or_unrelated_is_unclear() {
        listOf("yes no", "no wait yes", "what?", "", "call my mother", "yes… actually don't").forEach {
            assertEquals(it, Answer.UNCLEAR, Confirmation.interpret(it))
        }
    }

    @Test fun question_is_phrased_naturally() {
        assertEquals("You want me to call Prince. Should I go ahead?", Confirmation.question("Call Prince?"))
        assertEquals("You want me to send this message to Prince — “See you at 5”. Should I go ahead?",
            Confirmation.question("Send this message to Prince?\n“See you at 5”"))
        assertEquals("You want me to make_call. Should I go ahead?", Confirmation.question("Proceed with make_call?"))
        assertEquals("Should I go ahead?", Confirmation.question("  "))
    }

    // ------------------------------------------------------------------ fakes
    private class SeqRecorder(vararg results: CaptureResult) : Recorder {
        private val queue = ArrayDeque(results.toList())
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            onListening(); onSpeechStart(); return queue.removeFirstOrNull() ?: CaptureResult.NoSpeech
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
    /** Pauses at the safety gate like the real PolicyEngine path; resolves on confirm(). */
    private class GatedAgent(private val result: String) : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        var confirmed: Boolean? = null
        var stopped = false
        override fun submitGoal(goal: String) {
            _s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, goal = goal, pendingConfirmation = "Call Prince?")
        }
        override fun confirm(approved: Boolean) {
            confirmed = approved
            val goal = _s.value.goal
            _s.value = if (approved) AgentUiState(status = AgentStatus.COMPLETED, goal = goal,
                log = listOf(LogEntry(0, LogEntry.Kind.RESULT, result)))
            else AgentUiState(status = AgentStatus.STOPPED, goal = goal)
        }
        override fun stop() { stopped = true }
    }
    /** Like GatedAgent, but a non-yes/no reply during confirmation re-plans: it proposes a CORRECTED
     *  action (a different prompt) which is then confirmed — mirroring the real brain re-plan loop. */
    private class RevisableAgent(private val result: String) : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        var confirmed: Boolean? = null
        var revised: String? = null
        override fun submitGoal(goal: String) {
            _s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, goal = goal, pendingConfirmation = "Send this to Kwame?")
        }
        override fun revise(text: String) {
            revised = text
            val goal = _s.value.goal
            _s.value = _s.value.copy(status = AgentStatus.THINKING, pendingConfirmation = null)   // re-planning
            _s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, goal = goal, pendingConfirmation = "Send this to Kofi?")
        }
        override fun confirm(approved: Boolean) {
            confirmed = approved
            val goal = _s.value.goal
            _s.value = if (approved) AgentUiState(status = AgentStatus.COMPLETED, goal = goal,
                log = listOf(LogEntry(0, LogEntry.Kind.RESULT, result)))
            else AgentUiState(status = AgentStatus.STOPPED, goal = goal)
        }
        override fun stop() {}
    }

    private fun revisableController(scope: kotlinx.coroutines.CoroutineScope, agent: RevisableAgent, tts: LogTts, vararg heard: String) =
        VoiceController(
            asr = SeqAsr(*heard), tts = tts,
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = scope, hasMicPermission = { true }, agent = agent,
            mic = SeqRecorder(CaptureResult.Success(ByteArray(100), 600)),
            answerMic = SeqRecorder(
                CaptureResult.Success(ByteArray(40), 300), CaptureResult.Success(ByteArray(40), 300)
            )
        )

    private fun controller(scope: kotlinx.coroutines.CoroutineScope, agent: GatedAgent, tts: LogTts, vararg heard: String) =
        VoiceController(
            asr = SeqAsr(*heard), tts = tts,
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = scope, hasMicPermission = { true }, agent = agent,
            mic = SeqRecorder(CaptureResult.Success(ByteArray(100), 600)),
            answerMic = SeqRecorder(
                CaptureResult.Success(ByteArray(40), 300), CaptureResult.Success(ByteArray(40), 300)
            )
        )

    // ------------------------------------------------------------------ the flow
    @Test fun spoken_yes_answers_the_gate_and_the_result_is_spoken() = runTest {
        val agent = GatedAgent("Prince is calling.")
        val tts = LogTts()
        val vc = controller(this, agent, tts, "call Prince", "yes")
        vc.start(); advanceUntilIdle()
        assertEquals(true, agent.confirmed)
        assertEquals("You want me to call Prince. Should I go ahead?", tts.spoken[0])
        assertTrue("acknowledges the yes", tts.spoken.contains("Okay."))
        assertEquals("Prince is calling.", tts.spoken.last())
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        assertEquals(ConfirmPhase.NONE, vc.state.value.confirmPhase)
        vc.shutdown()
    }

    @Test fun spoken_no_declines_and_is_not_announced_twice() = runTest {
        val agent = GatedAgent("unused")
        val tts = LogTts()
        val vc = controller(this, agent, tts, "call Prince", "no, don't")
        vc.start(); advanceUntilIdle()
        assertEquals(false, agent.confirmed)
        assertTrue(tts.spoken.contains("Okay, I won't."))
        assertFalse("the turn's 'Stopped.' must not follow a voice decline", tts.spoken.contains("Stopped."))
        vc.shutdown()
    }

    @Test fun two_unclear_answers_decline_safely() = runTest {
        val agent = GatedAgent("unused")
        val tts = LogTts()
        val vc = controller(this, agent, tts, "call Prince", "what", "hmm")
        vc.start(); advanceUntilIdle()
        assertEquals("never proceeds without a clear yes", false, agent.confirmed)
        assertTrue(tts.spoken.contains(Confirmation.REPEAT))
        assertTrue(tts.spoken.contains(Confirmation.DECLINED_UNCLEAR))
        vc.shutdown()
    }

    @Test fun a_spoken_correction_reroutes_to_the_brain_then_the_corrected_action_confirms() = runTest {
        // §8/§9/§14 — during "Send this to Kwame?" the user answers with a correction, not yes/no.
        // It must go to the brain (revise), NOT be treated as a decline; the corrected proposal then confirms.
        val agent = RevisableAgent("Message sent to Kofi.")
        val tts = LogTts()
        val vc = revisableController(this, agent, tts, "text Kwame I'm on my way", "send it to Kofi instead", "yes")
        vc.start(); advanceUntilIdle()
        assertEquals("the correction was routed to the brain", "send it to Kofi instead", agent.revised)
        assertEquals("the corrected action was then approved", true, agent.confirmed)
        assertTrue("acknowledges the change", tts.spoken.contains("Okay, let me change that."))
        assertEquals("Message sent to Kofi.", tts.spoken.last())
        vc.shutdown()
    }

    @Test fun a_short_filler_is_still_treated_as_unclear_not_a_revision() = runTest {
        // "huh" is noise, not an instruction: it must NOT reroute to the brain; the safe ask-again path stands.
        val agent = RevisableAgent("unused")
        val tts = LogTts()
        val vc = revisableController(this, agent, tts, "call Prince", "huh", "no")
        vc.start(); advanceUntilIdle()
        assertNull("a filler must never be sent to the brain as a revision", agent.revised)
        assertEquals(false, agent.confirmed)
        vc.shutdown()
    }

    @Test fun the_original_request_never_counts_as_the_answer() = runTest {
        // "yes call Prince" as the REQUEST must not pre-approve the gate: a fresh answer is required.
        val agent = GatedAgent("unused")
        val tts = LogTts()
        val vc = controller(this, agent, tts, "yes call Prince", "", "")
        vc.start(); advanceUntilIdle()
        assertEquals(false, agent.confirmed)
        assertNull(agent.confirmed?.takeIf { it })
        vc.shutdown()
    }
}
