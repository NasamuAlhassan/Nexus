package com.tekyerema

import android.media.AudioManager
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.DuplexAudio
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-07 — barge-in on the voice-communication path. On the A20e the reply played on the media stream and the echo
 * canceller (which only references voice-communication playback) let it through: every long reply cut itself off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BargeInTest {

    @Test fun duplex_is_entered_only_from_an_idle_phone_without_a_headset() {
        assertEquals(DuplexAudio.Plan.ENTER, DuplexAudio.plan(true, AudioManager.MODE_NORMAL, headset = false))
        assertEquals(DuplexAudio.Plan.SKIP_OFF, DuplexAudio.plan(false, AudioManager.MODE_NORMAL, headset = false))
        for (m in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE))
            assertEquals("never over a call or a ringing phone", DuplexAudio.Plan.SKIP_NOT_IDLE, DuplexAudio.plan(true, m, headset = false))
        assertEquals("no echo to cancel on a headset", DuplexAudio.Plan.SKIP_HEADSET, DuplexAudio.plan(true, AudioManager.MODE_NORMAL, headset = true))
    }

    @Test fun the_call_volume_matches_the_level_the_reply_normally_plays_at() {
        assertEquals(4, DuplexAudio.matchedIndex(index = 8, max = 15, callMin = 1, callMax = 7))   // ~53 % → 4 of 7
        assertEquals(7, DuplexAudio.matchedIndex(15, 15, 1, 7))
        assertEquals("the call stream can't go silent", 1, DuplexAudio.matchedIndex(0, 15, 1, 7))
        assertEquals(7, DuplexAudio.matchedIndex(3, 0, 1, 7))
    }

    // ------------------------------------------------------------------ the controller
    private class HoldingTts : TtsProvider {
        override val name = "fake"
        val spoken = mutableListOf<String>()
        private var gate: CompletableDeferred<Boolean>? = null
        override suspend fun speak(text: String, language: String): Boolean {
            spoken += text
            if (text.length < 60) return true
            val g = CompletableDeferred<Boolean>().also { gate = it }
            return g.await()
        }
        override fun stop() { gate?.complete(false) }
        override fun shutdown() {}
    }
    /** The user starts talking over the reply. */
    private class TalkingBargeMic : Recorder {
        var captures = 0
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            captures++; onListening(); onSpeechStart(); return CaptureResult.Success(ByteArray(40), 400)
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
    private class Agent : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val submitted = mutableListOf<String>()
        override fun submitGoal(goal: String) {
            submitted += goal
            val reply = if (submitted.size == 1) "You're in Messages. Kofi says the meeting moved to Thursday afternoon, and Ama says see you at six."
                else "It's ten o'clock."
            _s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = goal, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, reply)))
        }
        override fun stop() {}
    }

    private val events = mutableListOf<String>()
    private fun controller(scope: CoroutineScope, agent: AgentBridge, tts: TtsProvider, asr: AsrProvider, barge: Recorder, duplexOk: Boolean = true) =
        VoiceController(
            asr = asr, tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = scope,
            hasMicPermission = { true }, agent = agent, mic = object : Recorder {
                override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit) = CaptureResult.NoSpeech
            },
            bargeMic = barge, bargeInEnabled = { true },
            duplexBegin = { events += "begin"; duplexOk }, duplexEnd = { events += "end" }
        )

    @Test fun a_long_reply_is_spoken_duplex_and_what_the_user_said_over_it_is_the_next_turn() = runTest {
        val agent = Agent()
        val tts = HoldingTts()
        val barge = TalkingBargeMic()
        val vc = controller(this, agent, tts, SeqAsr("what's the time"), barge)
        vc.submitText("read the screen"); advanceUntilIdle()
        assertEquals(1, barge.captures)
        assertEquals("entered before the reply, left once it ended", listOf("begin", "end"), events.take(2))
        assertEquals(listOf("read the screen", "what's the time"), agent.submitted)
        assertEquals("It's ten o'clock.", tts.spoken.last())
        vc.shutdown()
    }

    @Test fun stop_said_over_the_reply_just_stops_it() = runTest {
        val agent = Agent()
        val tts = HoldingTts()
        val vc = controller(this, agent, tts, SeqAsr("stop"), TalkingBargeMic())
        vc.submitText("read the screen"); advanceUntilIdle()
        assertEquals("\"stop\" is not a new request", listOf("read the screen"), agent.submitted)
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        assertEquals(listOf("begin", "end"), events)
        vc.shutdown()
    }

    @Test fun duplex_not_entered_is_not_left() = runTest {
        val vc = controller(this, Agent(), HoldingTts(), SeqAsr("stop"), TalkingBargeMic(), duplexOk = false)
        vc.submitText("read the screen"); advanceUntilIdle()
        assertEquals(listOf("begin"), events)
        vc.shutdown()
    }

    @Test fun short_replies_and_confirmations_never_open_the_barge_in_mic() = runTest {
        val barge = TalkingBargeMic()
        val agent = object : AgentBridge {
            private val _s = MutableStateFlow(AgentUiState())
            override val state: StateFlow<AgentUiState> = _s
            override fun submitGoal(goal: String) {
                _s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = goal, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, "Done.")))
            }
            override fun stop() {}
        }
        val vc = controller(this, agent, HoldingTts(), SeqAsr(), barge)
        vc.submitText("torch on"); advanceUntilIdle()
        assertEquals(0, barge.captures)
        assertTrue(events.isEmpty())
        vc.shutdown()
    }
}
