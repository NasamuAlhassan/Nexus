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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 24 Oct 2026: "when I start a new task, the system keeps saying the previous one is [stopped], and the new task
 * halts current processes … I was completing a transaction, switched the language to English, and began a new task,
 * then Twi returned." A new request replaces the task waiting on the user — and only that task — without announcing the
 * old one, in the language now in force.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskSwitchTest {
    private class Tts : TtsProvider {
        override val name = "fake"
        val spoken = mutableListOf<Pair<String, String>>()   // text to language
        override suspend fun speak(text: String, language: String): Boolean { spoken += text to language; return true }
        override fun stop() {}
        override fun shutdown() {}
    }
    private class Asr(private val text: String = "") : AsrProvider {
        override val name = "fake"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?) =
            Transcript(raw = text, canonical = text, language = Lang.GH_EN)
    }
    private class NoMic : Recorder {
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult = CaptureResult.NoSpeech
    }
    /** Hears something once (the answer at the read-back), then nothing. */
    private class OnceMic : Recorder {
        var used = false
        override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult {
            onListening(); if (used) return CaptureResult.NoSpeech; used = true; return CaptureResult.Success(ByteArray(100), 600)
        }
    }

    /** A money goal stops at its read-back (busy, waiting on the user); any other goal completes at once. */
    private class Agent : AgentBridge {
        val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val submitted = mutableListOf<String>()
        var stopped = 0
        var busyFlag = false
        override val busy: Boolean get() = busyFlag
        override fun submitGoal(goal: String) {
            submitted += goal
            val seq = _s.value.runSeq + 1
            if (goal.startsWith("send")) {
                busyFlag = true
                _s.value = AgentUiState(status = AgentStatus.WAITING_CONFIRMATION, goal = goal, runSeq = seq,
                    pendingConfirmation = "Send 5 Ghana cedis to Ama?", pendingConfirmationTwi = "Mede sidi 5 rekɔma Ama?")
            } else {
                busyFlag = false
                _s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = goal, runSeq = seq,
                    log = listOf(LogEntry(0, LogEntry.Kind.RESULT, "Opening Settings.")))
            }
        }
        override fun stop() { stopped++; busyFlag = false; _s.value = _s.value.copy(status = AgentStatus.STOPPED, pendingConfirmation = null) }
        override fun steer(text: String) {}
        override fun statusLine() = ""
        override fun confirm(approved: Boolean) {}
        var revised: String? = null
        override fun revise(text: String) { revised = text }
        override fun secureInputDone() {}
    }

    private fun controller(scope: CoroutineScope, agent: AgentBridge, tts: TtsProvider, session: () -> String, forced: () -> String?,
                           heard: String = "", voiceConfirm: Boolean = false) =
        VoiceController(asr = Asr(heard), tts = tts, sessionLanguage = session, onLanguageResolved = {}, scope = scope,
            hasMicPermission = { true }, agent = agent, mic = NoMic(), answerMic = if (voiceConfirm) OnceMic() else NoMic(),
            voiceConfirmation = { voiceConfirm }, forceLanguage = forced)

    @Test fun a_new_request_replaces_the_waiting_transfer_without_announcing_it_in_the_language_now_in_force() = runTest {
        val agent = Agent()
        val tts = Tts()
        var forced: String? = null
        val vc = controller(this, agent, tts, session = { Lang.TWI }, forced = { forced })
        assertTrue(vc.submitText("send 5 cedis to Ama")); runCurrent()
        assertEquals(AgentStatus.WAITING_CONFIRMATION, agent.state.value.status)

        forced = Lang.GH_EN                                   // the owner switches the language to English
        vc.submitTyped("open settings"); advanceUntilIdle()

        assertEquals("only the waiting transfer is stopped", 1, agent.stopped)
        assertEquals(listOf("send 5 cedis to Ama", "open settings"), agent.submitted)
        val lines = tts.spoken.map { it.first }
        assertFalse("the replaced task is not announced: $lines", lines.any { it.startsWith("Stopped") || it.startsWith("Magyae") })
        val reply = tts.spoken.last()
        assertEquals("Opening Settings.", reply.first)
        assertEquals("the new task answers in the language now in force", Lang.GH_EN, reply.second)
        vc.shutdown()
    }

    @Test fun the_outcome_closer_follows_the_forced_language() = runTest {
        val agent = Agent()
        val tts = Tts()
        // A Twi conversation, English forced: a task that completes without a result line closes in English.
        val a = object : AgentBridge by agent {
            override fun submitGoal(goal: String) {
                agent.submitted += goal
                agent._s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = goal, runSeq = agent._s.value.runSeq + 1)
            }
        }
        val vc = controller(this, a, tts, session = { Lang.TWI }, forced = { Lang.GH_EN })
        assertTrue(vc.submitText("turn on wifi")); advanceUntilIdle()
        val closer = tts.spoken.last()
        assertEquals(Lang.GH_EN, closer.second)
        assertFalse("an English closer, not a Twi one: ${closer.first}", closer.first.contains(Regex("[ɛɔ]")))
        vc.shutdown()
    }

    @Test fun a_new_request_said_at_the_read_back_replaces_the_transfer_and_says_nothing_was_sent() = runTest {
        val agent = Agent()
        val tts = Tts()
        val vc = controller(this, agent, tts, session = { Lang.GH_EN }, forced = { null }, heard = "open settings", voiceConfirm = true)
        assertTrue(vc.submitText("send 5 cedis to Ama")); advanceUntilIdle()
        assertEquals(1, agent.stopped)
        assertEquals("not taken as a correction of the transfer", null, agent.revised)
        assertEquals(listOf("send 5 cedis to Ama", "open settings"), agent.submitted)
        val lines = tts.spoken.map { it.first }
        assertTrue("says once that nothing went: $lines", lines.count { it == "I left the transfer. Nothing was sent." } == 1)
        assertFalse(lines.any { it.startsWith("Stopped") || it.startsWith("Okay, I won't") })
        assertEquals("Opening Settings.", lines.last())
        vc.shutdown()
    }

    @Test fun a_correction_at_the_read_back_still_corrects_the_transfer() = runTest {
        val agent = Agent()
        val tts = Tts()
        val vc = controller(this, agent, tts, session = { Lang.GH_EN }, forced = { null }, heard = "send it to Kofi instead", voiceConfirm = true)
        assertTrue(vc.submitText("send 5 cedis to Ama")); advanceUntilIdle()
        assertEquals("send it to Kofi instead", agent.revised)
        // (The fake planner never answers the correction, so the 10-minute "nobody answered" cap ends the turn later in
        // virtual time — not part of this check.)
        assertEquals("no new task started", listOf("send 5 cedis to Ama"), agent.submitted)
        assertFalse(tts.spoken.any { it.first.startsWith("I left the transfer") })
        vc.shutdown()
    }
}
