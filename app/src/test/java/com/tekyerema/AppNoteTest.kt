package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.AppSwitch
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AppNote
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.SecureStep
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice-UX (2026-09-29): "In WhatsApp." — which app a task is working in, said once per app, never over a prompt. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNoteTest {
    @After fun clear() = SecureStep.end()

    private fun m(pkg: String, name: String? = "WhatsApp", taskMs: Long = 3_000, nowMs: Long = 3_000, working: Boolean = true,
                  micFree: Boolean = true, secure: Boolean = false) = AppNote.Moment(pkg, name, taskMs, nowMs, working, micFree, secure)

    @Test fun the_screen_title_follows_the_app_in_english_only() {
        assertEquals("In WhatsApp, Chats.", AppNote().next(m("com.whatsapp").copy(title = "Chats"), twi = false))
        assertEquals("In WhatsApp.", AppNote().next(m("com.whatsapp").copy(title = null), twi = false))
        assertEquals("In WhatsApp.", AppNote.line("WhatsApp", twi = false, title = " "))
    }

    @Test fun says_the_app_once_per_task() {
        val n = AppNote()
        assertEquals("In WhatsApp.", n.next(m("com.whatsapp"), twi = false))
        assertNull("once per app", n.next(m("com.whatsapp", nowMs = 4_000), twi = false))
        assertEquals("In YouTube Music.", n.next(m("com.google.android.apps.youtube.music", "YouTube Music"), twi = false))
        assertNull("back in WhatsApp later in the same task: already said", n.next(m("com.whatsapp", nowMs = 9_000), twi = false))
        assertEquals("a new task says it again", "In WhatsApp.", AppNote().next(m("com.whatsapp"), twi = false))
    }

    @Test fun never_over_a_prompt_a_pin_or_a_quick_task() {
        assertNull("confirmation / question / paused", AppNote().next(m("com.whatsapp", working = false), false))
        assertNull("the microphone is taking an answer", AppNote().next(m("com.whatsapp", micFree = false), false))
        assertNull("a PIN step", AppNote().next(m("com.whatsapp", secure = true), false))
        assertNull("a task that just started", AppNote().next(m("com.whatsapp", taskMs = 900), false))
    }

    @Test fun a_prompt_drops_the_note_and_a_busy_mic_holds_it() {
        val n = AppNote()
        assertNull(n.next(m("com.whatsapp", nowMs = 3_000, working = false), false))
        assertNull("the confirmation said where we are: never said after it", n.next(m("com.whatsapp", nowMs = 5_000), false))
        val held = AppNote()
        assertNull(held.next(m("com.whatsapp", nowMs = 3_000, micFree = false), false))
        assertEquals("the interjection is over", "In WhatsApp.", held.next(m("com.whatsapp", nowMs = 5_000), false))
        val late = AppNote()
        assertNull(late.next(m("com.whatsapp", nowMs = 3_000, micFree = false), false))
        assertNull("too late to be news", late.next(m("com.whatsapp", nowMs = 3_000 + AppNote.MAX_LATE_MS + 1), false))
    }

    @Test fun ours_the_home_screen_and_the_phones_pin_windows_are_never_named() {
        for (p in listOf("com.tekyerema", "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher", "com.android.systemui",
            "com.android.phone", "com.samsung.android.incallui"))
            assertTrue(p, AppNote.skip(p))
        assertFalse(AppNote.skip("com.whatsapp"))
        assertNull("no name to say", AppNote().next(m("com.example.x", name = null), false))
        assertNull(AppNote().next(m("com.example.x", name = "com.example.x"), false))
    }

    @Test fun twi_line() {
        assertEquals("Mewɔ WhatsApp mu.", AppNote.line("WhatsApp", twi = true))
    }

    // ------------------------------------------------------------------ the controller

    private class OneShotRecorder : Recorder {
        var used = false
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            onListening(); if (used) return CaptureResult.NoSpeech; used = true; return CaptureResult.Success(ByteArray(40), 300)
        }
    }
    private class FixedAsr(val text: String) : AsrProvider {
        override val name = "fake"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?) =
            Transcript(raw = text, canonical = text, language = Lang.GH_EN)
    }
    private class LogTts : TtsProvider {
        override val name = "fake"
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: String): Boolean { spoken += text; return true }
        override fun stop() {}
        override fun shutdown() {}
    }
    /** A task that opens [pkg] after [switchAfterMs] and finishes [doneAfterMs] in. */
    private class SwitchingAgent(private val scope: kotlinx.coroutines.CoroutineScope, private val pkg: String, private val name: String,
                                 private val switchAfterMs: Long, private val doneAfterMs: Long) : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        override fun submitGoal(goal: String) {
            val seq = _s.value.runSeq + 1
            _s.value = AgentUiState(status = AgentStatus.THINKING, goal = goal, runSeq = seq)
            scope.launch {
                delay(switchAfterMs)
                _s.value = _s.value.copy(status = AgentStatus.ACTING, appSwitch = AppSwitch(pkg, name, seq))
                delay(doneAfterMs - switchAfterMs)
                _s.value = _s.value.copy(status = AgentStatus.COMPLETED, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, "Sent.")))
            }
        }
        override fun stop() {}
    }

    @Test fun a_task_that_opens_an_app_says_so_once() = runTest {
        val tts = LogTts()
        val agent = SwitchingAgent(this, "com.whatsapp", "WhatsApp", switchAfterMs = 800, doneAfterMs = 5_000)
        val vc = VoiceController(asr = FixedAsr("send Ama a WhatsApp saying hi"), tts = tts, sessionLanguage = { Lang.GH_EN },
            onLanguageResolved = {}, scope = this, hasMicPermission = { true }, agent = agent, mic = OneShotRecorder())
        vc.start(); advanceUntilIdle()
        assertEquals(listOf("In WhatsApp.", "Sent."), tts.spoken)
        vc.shutdown()
    }

    @Test fun a_quick_task_says_nothing_extra() = runTest {
        val tts = LogTts()
        val agent = SwitchingAgent(this, "com.whatsapp", "WhatsApp", switchAfterMs = 300, doneAfterMs = 1_000)
        val vc = VoiceController(asr = FixedAsr("open whatsapp"), tts = tts, sessionLanguage = { Lang.GH_EN },
            onLanguageResolved = {}, scope = this, hasMicPermission = { true }, agent = agent, mic = OneShotRecorder())
        vc.start(); advanceUntilIdle()
        assertEquals(listOf("Sent."), tts.spoken)
        vc.shutdown()
    }
}
