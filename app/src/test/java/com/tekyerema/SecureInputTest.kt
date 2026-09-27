package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.policy.TargetSemantics
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.Lang
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
 * Secure Input Mode (MoMo PIN boundary). The guarantee under test:
 *   the user's PIN never enters Tɛkyerɛma's LLM / screen-understanding pipeline.
 * The trigger is [TargetSemantics.isCredentialScreen]; once it fires, the AgentEngine loop suspends
 * observation BEFORE any log/serialize/brain call (verified by construction), and the voice layer only
 * speaks an instruction and listens for "done" — never the PIN.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecureInputTest {

    private fun el(id: Int, label: String?, editable: Boolean = false, password: Boolean = false) =
        UiElement(id, if (editable) "EditText" else "TextView", label, Box(0, id * 100, 500, id * 100 + 80),
            editable = editable, clickable = !editable, password = password)

    private fun snap(vararg e: UiElement, pkg: String = "com.android.phone", title: String? = null, secure: Boolean = false) =
        UiSnapshot(pkg, title, e.toList(), 0L, PerceptionRoute("tree", "test"), secureWindow = secure)

    // ---------------------------------------------------------------- detection (Tests 1/3/4 trigger)
    @Test fun momo_pin_prompt_is_a_credential_screen() {
        // MoMo USSD PIN dialog: an input field + text naming the PIN.
        assertTrue(TargetSemantics.isCredentialScreen(snap(el(1, "Enter your MoMo PIN"), el(2, null, editable = true))))
    }

    @Test fun password_field_is_a_credential_screen() {
        assertTrue(TargetSemantics.isCredentialScreen(snap(el(1, "Password"), el(2, null, editable = true, password = true))))
    }

    @Test fun otp_field_is_a_credential_screen() {
        assertTrue(TargetSemantics.isCredentialScreen(snap(el(1, "Enter the OTP we sent you"), el(2, null, editable = true))))
    }

    @Test fun flag_secure_window_is_a_credential_screen() {
        assertTrue(TargetSemantics.isCredentialScreen(snap(el(1, "•", editable = true), secure = true)))
    }

    @Test fun ordinary_screens_are_not_credential_screens() {
        // A normal amount-entry step: an input field, but no credential wording → NOT secure input.
        assertFalse(TargetSemantics.isCredentialScreen(snap(el(1, "Enter amount"), el(2, null, editable = true))))
        // A screen that merely mentions a word but has no input field.
        assertFalse(TargetSemantics.isCredentialScreen(snap(el(1, "Send money"), el(2, "Buy airtime"))))
        // The dialer keypad — digits, no credential text, no input field.
        assertFalse(TargetSemantics.isCredentialScreen(snap(el(1, "1"), el(2, "2"), el(3, "Call"))))
    }

    // ---------------------------------------------------------------- completion word (never a PIN)
    @Test fun done_words_are_completion_and_a_pin_is_not() {
        listOf("done", "I'm done", "finished", "okay done", "awie", "mawie").forEach {
            assertTrue(it, Confirmation.isCompletion(it))
        }
        // A spoken PIN must NEVER be read as completion.
        listOf("1234", "one two three four", "5 5 2 0", "").forEach {
            assertFalse(it, Confirmation.isCompletion(it))
        }
    }

    // ---------------------------------------------------------------- voice flow (Tests 5/6)
    private class SeqRecorder(vararg results: CaptureResult) : Recorder {
        private val queue = ArrayDeque(results.toList())
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            onListening(); onSpeechStart(); return queue.removeFirstOrNull() ?: CaptureResult.NoSpeech
        }
    }
    private class SeqAsr(vararg texts: String) : AsrProvider {
        private val queue = ArrayDeque(texts.toList())
        override val name = "fake"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?) =
            (queue.removeFirstOrNull() ?: "").let { Transcript(raw = it, canonical = it, language = Lang.GH_EN) }
    }
    private class LogTts : TtsProvider {
        override val name = "fake"; val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: String): Boolean { spoken += text; return true }
        override fun stop() {}; override fun shutdown() {}
    }
    /** Enters SECURE_INPUT on submit; secureInputDone() resolves to COMPLETED (the post-PIN screen was clean). */
    private class SecureAgent : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        var doneCalled = 0
        override fun submitGoal(goal: String) { _s.value = AgentUiState(status = AgentStatus.SECURE_INPUT, goal = goal) }
        override fun secureInputDone() { doneCalled++; _s.value = _s.value.copy(status = AgentStatus.COMPLETED) }
        override fun stop() { _s.value = _s.value.copy(status = AgentStatus.STOPPED) }
    }

    private fun controller(scope: kotlinx.coroutines.CoroutineScope, agent: SecureAgent, tts: LogTts, vararg heard: String) =
        VoiceController(
            asr = SeqAsr(*heard), tts = tts,
            sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {},
            scope = scope, hasMicPermission = { true }, agent = agent,
            mic = SeqRecorder(CaptureResult.Success(ByteArray(80), 500)),
            answerMic = SeqRecorder(CaptureResult.Success(ByteArray(40), 300), CaptureResult.Success(ByteArray(40), 300))
        )

    @Test fun secure_input_speaks_instruction_and_resumes_on_done() = runTest {
        val agent = SecureAgent(); val tts = LogTts()
        // request → agent goes SECURE_INPUT → user says "done".
        val vc = controller(this, agent, tts, "send 1 cedi to Kofi", "done")
        vc.start(); advanceUntilIdle()
        assertTrue("speaks a secure instruction", tts.spoken.any { it.contains("secure step", true) && it.contains("PIN", true) })
        assertFalse("never asks the user to say the PIN", tts.spoken.any { it.contains("say your pin", true) || it.contains("read", true) })
        assertEquals("resumes exactly once when the user says done", 1, agent.doneCalled)
        vc.shutdown()
    }

    @Test fun a_spoken_pin_never_resumes_and_is_never_echoed() = runTest {
        val agent = SecureAgent(); val tts = LogTts()
        // The user mistakenly speaks digits instead of "done": must NOT be treated as completion, must NOT be echoed.
        val vc = controller(this, agent, tts, "send 1 cedi to Kofi", "1234")
        vc.start(); advanceUntilIdle()
        assertEquals("a spoken PIN must never resume the agent", 0, agent.doneCalled)
        assertFalse("the PIN must never be echoed by TTS", tts.spoken.any { it.contains("1234") })
        vc.shutdown()
    }
}
