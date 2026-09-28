package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.ScreenReadout
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Lang
import com.tekyerema.voice.ReadingPace
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-01 / B-13 — a long screen reading is paced for the ear: ~20 s parts, "continue" and "stop" by voice. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPaceTest {
    @After fun reset() { AppConfig.loadFrom(emptyList()); ReadingPace.rate = 1.0f }

    @Test fun parts_are_sized_in_seconds_at_the_users_rate() {
        assertEquals("20 s at 15 chars/s", 300, ReadingPace.partChars(1.0f))
        assertTrue("a faster voice says more in the same time", ReadingPace.partChars(1.25f) > ReadingPace.partChars(1.0f))
        assertTrue(ReadingPace.partChars(0.8f) < ReadingPace.partChars(1.0f))
        assertTrue("shorter than the old fixed 480 (~30 s)", ReadingPace.partChars(1.0f) < 480)
        AppConfig.loadFrom(listOf("c" to """{"speech":{"reading":{"partSeconds":60}}}"""))
        assertEquals("clamped: a part never becomes a monologue", 600, ReadingPace.partChars(1.0f))
        AppConfig.loadFrom(listOf("c" to """{"speech":{"reading":{"partSeconds":1}}}"""))
        assertEquals(160, ReadingPace.partChars(1.0f))
    }

    @Test fun a_part_that_invites_continue_is_recognised() {
        assertTrue(ReadingPace.invitesContinue("You're in Messages. Kofi says hi." + ScreenReadout.MORE))
        assertTrue(ReadingPace.invitesContinue("That's the first three. Say continue."))
        assertFalse(ReadingPace.invitesContinue("Kofi says continue the meeting tomorrow at ten."))
        assertFalse(ReadingPace.invitesContinue("Done."))
    }

    @Test fun enough_means_enough_only_as_the_whole_reply() {
        for (s in listOf("stop", "Stop.", "that's enough", "ok that's enough thanks", "be quiet", "never mind", "gyae"))
            assertTrue(s, ReadingPace.isEnough(s))
        for (s in listOf("stop the music on spotify", "continue", "read the next message", "that's enough money"))
            assertFalse(s, ReadingPace.isEnough(s))
    }

    // ------------------------------------------------------------------ the controller
    private class SeqRecorder(vararg results: CaptureResult) : Recorder {
        private val queue = ArrayDeque(results.toList())
        var captures = 0
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            captures++; onListening(); return queue.removeFirstOrNull() ?: CaptureResult.NoSpeech
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
    /** "read the screen" → part one (more to come); "continue" → the last part. */
    private class ReaderAgent : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val submitted = mutableListOf<String>()
        override fun submitGoal(goal: String) {
            submitted += goal
            val text = if (goal == "continue") "Ama says see you at six." else "You're in Messages. Kofi says the meeting moved." + ScreenReadout.MORE
            _s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = goal, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, text)))
        }
        override fun stop() {}
    }

    private var cards = 0
    private fun controller(scope: CoroutineScope, agent: AgentBridge, tts: TtsProvider, asr: AsrProvider, mic: Recorder) = VoiceController(
        asr = asr, tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = scope,
        hasMicPermission = { true }, agent = agent, mic = mic, showAsking = { cards++ }
    )

    @Test fun after_a_part_the_mic_reopens_and_continue_reads_on() = runTest {
        val agent = ReaderAgent()
        val mic = SeqRecorder(CaptureResult.Success(ByteArray(40), 300), CaptureResult.Success(ByteArray(40), 300))
        val tts = LogTts()
        val vc = controller(this, agent, tts, SeqAsr("read the screen", "continue"), mic)
        vc.start(); advanceUntilIdle()
        assertEquals("\"continue\" without a new trigger", listOf("read the screen", "continue"), agent.submitted)
        assertEquals("no listening after the last part", 2, mic.captures)
        assertEquals("a reading is not a question: no ask card", 0, cards)
        assertEquals("Ama says see you at six.", tts.spoken.last())
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        vc.shutdown()
    }

    @Test fun stop_after_a_part_ends_the_reading_quietly() = runTest {
        val agent = ReaderAgent()
        val mic = SeqRecorder(CaptureResult.Success(ByteArray(40), 300), CaptureResult.Success(ByteArray(40), 300))
        val tts = LogTts()
        val vc = controller(this, agent, tts, SeqAsr("read the screen", "that's enough"), mic)
        vc.start(); advanceUntilIdle()
        assertEquals("\"that's enough\" is not sent anywhere", listOf("read the screen"), agent.submitted)
        assertEquals("Okay.", tts.spoken.last())
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        vc.shutdown()
    }

    @Test fun brief_verbosity_never_cuts_a_reading() = runTest {
        val agent = ReaderAgent()
        val tts = LogTts()
        val vc = VoiceController(
            asr = SeqAsr(), tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = this,
            hasMicPermission = { true }, agent = agent, mic = SeqRecorder(), verbosity = { "brief" }
        )
        vc.submitText("read the screen"); advanceUntilIdle()
        assertTrue("the whole part, not just \"You're in Messages.\"", tts.spoken.last().contains("Kofi says the meeting moved."))
        vc.shutdown()
    }

    @Test fun a_typed_reading_does_not_open_the_mic() = runTest {
        val agent = ReaderAgent()
        val mic = SeqRecorder()
        val vc = controller(this, agent, LogTts(), SeqAsr(), mic)
        vc.submitText("read the screen"); advanceUntilIdle()
        assertEquals(0, mic.captures)
        vc.shutdown()
    }
}
