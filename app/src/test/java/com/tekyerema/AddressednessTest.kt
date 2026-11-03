package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.Addressedness
import com.tekyerema.voice.Addressedness.Origin
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.CaptureResult
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Recorder
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.WakeGate
import com.tekyerema.voice.WakeScorer
import com.tekyerema.voice.WakeSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Is this addressed to me?" — a judge's session on the A20e (2026-09-29): the follow-up microphone heard the room and
 * the planner answered it ("I will eat today." → "Okay, enjoy your meal!"). Every transcript of that session is here:
 * the room talk is ignored, every real request still goes through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressednessTest {
    private val follow = Origin.FOLLOW_UP

    /** The live gate (the real fast-path classifier; no contacts in unit tests). */
    private fun live(s: String, pending: Boolean = false, origin: Origin = follow) = Addressedness.check(s, origin, pending).addressed

    /** Room talk the follow-up heard in the judge's session, and what the planner said to it. */
    private val room = listOf(
        "I thought this will make it the the head is right",          // → "Could you clarify what you would like to adjust or do?"
        "I will eat today.",                                          // → "Okay, enjoy your meal!"
        "at the same",                                                // → "…do you want to play the same playlist again on YouTube?"
        "people we want of you know do something easy to share my ask the share my ask the share my the ask asking in " +
            "even the English was in the pronunciation of the share"  // → "What would you like to share?"
    )

    /** Real requests from the same session — and short answers and questions — that must still work. */
    private val requests = listOf(
        "I would like to make a call to 05535323011.", "Call 055323011.", "Send money to 055.", "Send money to 0553238.",
        "Send cash to 05533238011.", "Switch the account on YouTube to prints.", "Play the playlist on the screen right now.",
        "Play my downloads on YouTube music.", "Take a selfie.", "Open YouTube.", "read my messages", "Kenkan menkra no.",
        "Bue whatsapp ne ka kyerɛ print sɛ ɔmbre fie.", "yes", "no", "the second one", "what's the time", "how far is Kumasi"
    )

    @Test fun the_judges_room_talk_is_ignored_in_a_follow_up() {
        for (s in room) {
            assertFalse(s, live(s))
            assertFalse("even right after a question: $s", live(s, pending = true))
            assertFalse("a marginal wake: $s", live(s, origin = Origin.WAKE_MARGINAL))
        }
        assertEquals("narration", Addressedness.judge(room[0], follow).reason)
        assertEquals("narration", Addressedness.judge(room[1], follow).reason)
        assertEquals("fragment", Addressedness.judge(room[2], follow, questionPending = true).reason)
        assertEquals("repetition", Addressedness.judge(room[3], follow).reason)
    }

    @Test fun every_real_request_from_that_session_still_works() {
        for (s in requests) {
            assertTrue(s, live(s))
            assertTrue("after a question: $s", live(s, pending = true))
            // The rules alone keep them — not only the fast-path classifier.
            assertTrue("without the fast path: $s", Addressedness.judge(s, follow).addressed)
        }
    }

    @Test fun a_turn_the_user_started_is_never_filtered() {
        for (o in listOf(Origin.USER, Origin.TYPED, Origin.ANSWER, Origin.WAKE)) for (s in room + "thank you" + "hmm")
            assertTrue("$o: $s", live(s, origin = o))
    }

    @Test fun small_talk_and_narration_are_ignored() {
        for (s in listOf("thank you", "Thanks.", "hello", "how are you", "okay bye", "we went to the market", "my mother is coming",
            "I want to eat", "he said he's coming", "I can't come today", "the food was nice", "we need to buy rice", "hmm", "um",
            "the head is right", "you know"))
            assertFalse(s, live(s))
    }

    @Test fun follow_ons_and_references_are_kept() {
        for (s in listOf("now call him", "and then text her", "and her?", "what about tomorrow", "same for Ama", "also tomorrow",
            "okay, read it again", "please send it", "quickly call Ama", "can you call my mother", "is it raining", "louder",
            "stop", "continue", "I need help", "Tɛkyerɛma, the time", "I'd like to hear the news", "weather in Accra",
            "I'm looking for Kofi's number", "look up the cedi rate", "and him too", "the first one please", "I think so"))
            assertTrue(s, live(s))
    }

    @Test fun a_contact_or_relation_named_keeps_the_turn() {
        assertFalse("a bare name the phone doesn't know", Addressedness.judge("Kofi Mensah", follow).addressed)
        assertTrue(Addressedness.judge("Kofi Mensah", follow, names = listOf("Kofi Mensah", "Home")).addressed)
        assertTrue(Addressedness.judge("kofi", follow, names = listOf("Kofi Mensah")).addressed)
        assertFalse("a contact saved as an everyday word", Addressedness.judge("home is far", follow, names = listOf("Home")).addressed)
        assertTrue(Addressedness.judge("my mother", follow).addressed)
        assertTrue(Addressedness.judge("WhatsApp", follow).addressed)
        assertTrue("an app named in a complaint", Addressedness.judge("my WhatsApp is not working", follow).addressed)
    }

    @Test fun an_answer_to_a_question_needs_real_content() {
        for (s in listOf("my location", "Kofi", "tomorrow morning", "the one I downloaded yesterday", "the red one", "fifty cedis"))
            assertTrue(s, Addressedness.judge(s, follow, questionPending = true).addressed)
        for (s in listOf("at the same", "the", "and so", "I will eat today", "thank you"))
            assertFalse(s, Addressedness.judge(s, follow, questionPending = true).addressed)
        assertFalse("without a question, a bare phrase is not a request", Addressedness.judge("tomorrow morning", follow).addressed)
    }

    @Test fun twi_commands_are_kept_and_twi_talk_is_not() {
        for (s in listOf("Kenkan menkra no", "Frɛ maame", "Bue YouTube", "Me pɛ sɛ me frɛ Ama", "Mesrɛ wo, soma Kofi nkra", "Ɛhe na mewɔ?"))
            assertTrue(s, live(s))
        // (The rules alone: "…fie" is also the fast path's Twi "home", which is that classifier's call, not this gate's.)
        for (s in listOf("Ɔrekɔ fie", "Ɔbaa nnɛra", "Medaase", "Maakye"))
            assertFalse(s, Addressedness.judge(s, follow).addressed)
    }

    @Test fun a_loop_needs_a_long_utterance() {
        assertTrue(Addressedness.isLoop(Addressedness.tokens(room[3])))
        assertFalse("a request said twice is not a loop",
            Addressedness.isLoop(Addressedness.tokens("send money to 055 send money to 0553238")))
        assertFalse(Addressedness.isLoop(Addressedness.tokens("call call mum")))
        // A long, natural answer repeats phrases far apart — not a loop (A20e, 2 Nov 2026: 28 s of an answer were dropped).
        val answer = "okay so for me I want a small car I want something cheap because I am a student and the car should be " +
            "good on fuel and I want a car that is easy to repair around here and if it is not too old then I want it"
        assertFalse(Addressedness.isLoop(Addressedness.tokens(answer)))
        assertTrue(Addressedness.judge(answer, follow, questionPending = true).addressed)
    }

    // ------------------------------------------------------------------ a wake that only just cleared the bar

    @Test fun a_marginal_wake_opens_a_gated_turn_once() {
        WakeSignal.fired(0.996f, now = 1_000L)
        assertEquals(Origin.WAKE_MARGINAL, WakeSignal.consume(now = 2_000L, marginalBelow = 0.997f))
        assertEquals("one wake opens one turn", Origin.USER, WakeSignal.consume(now = 2_100L, marginalBelow = 0.997f))
        WakeSignal.fired(0.9999f, now = 3_000L)
        assertEquals(Origin.WAKE, WakeSignal.consume(now = 3_500L, marginalBelow = 0.997f))
        WakeSignal.fired(0.996f, now = 10_000L)
        assertEquals("a wake long ago did not open this turn (a tap)", Origin.USER,
            WakeSignal.consume(now = 10_000L + WakeSignal.WINDOW_MS + 1, marginalBelow = 0.997f))
        assertTrue(Addressedness.judge("I will eat today", Origin.WAKE_MARGINAL).addressed.not())
        assertTrue(Addressedness.judge("I will eat today", Origin.WAKE).addressed)
    }

    @Test fun the_wake_gate_remembers_the_peak_of_the_run_that_fired() {
        val g = WakeGate(bar = 0.995f)
        val frames = listOf(0.996f, 0.9985f, 0.997f)
        val fired = frames.map { g.feed(0.05f, mapOf(WakeScorer.FIRE_HEAD to it)) }
        assertEquals(listOf(false, false, true), fired)
        assertEquals(0.9985f, g.lastFirePeak, 1e-6f)
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
    /** Replies with [reply] to every goal. */
    private class ReplyAgent(private val reply: (String) -> String) : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val submitted = mutableListOf<String>()
        override fun submitGoal(goal: String) {
            submitted += goal
            _s.value = AgentUiState(status = AgentStatus.COMPLETED, goal = goal, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, reply(goal))))
        }
        override fun stop() {}
    }
    private fun speech() = CaptureResult.Success(ByteArray(40), 300)
    private fun controller(scope: CoroutineScope, agent: AgentBridge, tts: TtsProvider, asr: AsrProvider, mic: Recorder) = VoiceController(
        asr = asr, tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = scope,
        hasMicPermission = { true }, agent = agent, mic = mic
    )

    @Test fun a_follow_up_that_hears_the_room_stays_quiet() = runTest {
        val agent = ReplyAgent { "Okay, enjoy your meal!" }
        val tts = LogTts()
        val vc = controller(this, agent, tts, SeqAsr("I will eat today."), SeqRecorder(speech()))
        vc.followUp(); advanceUntilIdle()
        assertEquals("no model call", emptyList<String>(), agent.submitted)
        assertEquals("no reply", emptyList<String>(), tts.spoken)
        assertEquals(VoiceState.IDLE, vc.state.value.state)
        vc.shutdown()
    }

    @Test fun a_follow_up_request_still_runs() = runTest {
        val agent = ReplyAgent { "Opening YouTube." }
        val vc = controller(this, agent, LogTts(), SeqAsr("Open YouTube."), SeqRecorder(speech()))
        vc.followUp(); advanceUntilIdle()
        assertEquals(listOf("Open YouTube."), agent.submitted)
        vc.shutdown()
    }

    @Test fun a_tap_is_never_filtered_even_for_small_talk() = runTest {
        val agent = ReplyAgent { "You're welcome." }
        val vc = controller(this, agent, LogTts(), SeqAsr("I will eat today."), SeqRecorder(speech()))
        vc.start(); advanceUntilIdle()
        assertEquals("the user asked to talk: it is theirs to say", listOf("I will eat today."), agent.submitted)
        vc.shutdown()
    }

    @Test fun the_question_tail_keeps_an_answer_and_drops_the_room() = runTest {
        // "share" → "What would you like to share?" → the room: "at the same" — ignored, the chain ends there.
        val agent = ReplyAgent { g -> if (g == "share") "What would you like to share?" else "Okay." }
        val tts = LogTts()
        val vc = controller(this, agent, tts, SeqAsr("share", "at the same"), SeqRecorder(speech(), speech()))
        vc.start(); advanceUntilIdle()
        assertEquals(listOf("share"), agent.submitted)
        assertEquals(listOf("What would you like to share?"), tts.spoken)
        vc.shutdown()

        // …and a real answer to the same question goes through.
        val agent2 = ReplyAgent { g -> if (g == "share") "What would you like to share?" else "Sharing your location." }
        val vc2 = controller(this, agent2, LogTts(), SeqAsr("share", "my location"), SeqRecorder(speech(), speech()))
        vc2.start(); advanceUntilIdle()
        assertEquals(listOf("share", "my location"), agent2.submitted)
        vc2.shutdown()
    }

    /** How both English recognisers wrote the owner saying the name (models session, 24 Oct 2026). */
    @Test fun the_name_as_the_recognisers_hear_it_is_still_the_name() {
        for (s in listOf("techma what is the time", "hatechema I am going", "the germa I will eat today", "tetch ramah I will eat today"))
            assertEquals("named: $s", "named", Addressedness.judge(s, follow).reason)
        assertFalse(Addressedness.judge("the german I will eat today", follow).reason == "named")
    }
}
