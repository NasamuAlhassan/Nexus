package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.habits.HabitHost
import com.tekyerema.agent.habits.HabitOffer
import com.tekyerema.agent.habits.RepeatDetector
import com.tekyerema.settings.Settings
import com.tekyerema.voice.AgentBridge
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Practice
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.Verbalize
import com.tekyerema.voice.VoiceController
import com.tekyerema.voice.Welcome
import com.tekyerema.voice.Welcome.Input
import com.tekyerema.voice.Welcome.Reply
import com.tekyerema.voice.Welcome.Step
import com.tekyerema.voice.WelcomeHost
import com.tekyerema.voice.io.Heard
import com.tekyerema.voice.io.Hypothesis
import com.tekyerema.voice.io.InputCaps
import com.tekyerema.voice.io.ListenEvents
import com.tekyerema.voice.io.ListenSpec
import com.tekyerema.voice.io.VoiceInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** First-run voice welcome (voice/Welcome.kt): the pure state machine (answers, skips, a request instead of an answer,
 *  stop), its driver, the practice text that is never sent, and the VoiceController hooks (welcome, practice, habit offer). */
class WelcomeFlowTest {
    @After fun reset() { Welcome.userName = "" }

    private fun t(s: String, vararg alts: String) = Reply.Text(s, s, listOf(s) + alts)

    /** A scripted voice: [replies] answer each ask in turn (silence after), everything said is kept in order. */
    private class FakeIo(vararg replies: Reply, private val line: (Welcome.Effect) -> String? = { null }) : Welcome.Io {
        val queue = ArrayDeque(replies.toList())
        val said = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val applied = mutableListOf<Welcome.Effect>()
        var okCues = 0
        override suspend fun say(text: String) { said += text }
        override suspend fun ask(question: String, yesNo: Boolean, patient: Boolean): Reply {
            asked += question; said += question
            return queue.removeFirstOrNull() ?: Reply.Silence
        }
        override fun needsOk() { okCues++ }
        override fun apply(effect: Welcome.Effect): String? { applied += effect; return line(effect) }
    }

    // ------------------------------------------------------------ the machine: answers

    @Test fun names_are_heard_in_the_usual_ways() {
        mapOf(
            "Ama" to "Ama", "kofi mensah" to "Kofi Mensah", "My name is Nana Yaw." to "Nana Yaw", "call me Esi please" to "Esi",
            "I'm Kwame" to "Kwame", "It's Akosua" to "Akosua", "Prince" to "Prince", "Abena" to "Abena", "Grace" to "Grace",
            "my name is McCarthy" to "McCarthy"
        ).forEach { (said, name) -> assertEquals(said, Input.Name(name), Welcome.classify(Step.NAME, t(said))) }
        // "call me Ama" is a name, never a phone call.
        assertEquals(Input.Name("Ama"), Welcome.classify(Step.NAME, t("call me Ama")))
        assertEquals(Input.Skip, Welcome.classify(Step.NAME, t("I'm not sure")))
        assertEquals(Input.Unclear, Welcome.classify(Step.NAME, t("what")))
        assertEquals(Input.Unclear, Welcome.classify(Step.NAME, t("um hmm")))
    }

    @Test fun language_and_speed_answers() {
        assertEquals(Input.Language(Settings.LANG_MODE_ENGLISH), Welcome.classify(Step.LANGUAGE, t("English please")))
        assertEquals(Input.Language(Settings.LANG_MODE_TWI), Welcome.classify(Step.LANGUAGE, t("Twi")))
        assertEquals(Input.Language(Settings.LANG_MODE_BOTH), Welcome.classify(Step.LANGUAGE, t("both")))
        assertEquals(Input.Language(Settings.LANG_MODE_BOTH), Welcome.classify(Step.LANGUAGE, t("English and Twi")))
        assertEquals(Input.Language(Settings.LANG_MODE_BOTH), Welcome.classify(Step.LANGUAGE, t("either is fine")))
        assertEquals(Input.Rate("slower"), Welcome.classify(Step.RATE, t("it's too fast")))
        assertEquals(Input.Rate("slower"), Welcome.classify(Step.RATE, t("a bit slower")))
        assertEquals(Input.Rate("slower"), Welcome.classify(Step.RATE, t("slow down")))
        assertEquals(Input.Rate("faster"), Welcome.classify(Step.RATE, t("too slow")))
        assertEquals(Input.Rate("faster"), Welcome.classify(Step.RATE, t("faster")))
        assertEquals(Input.Rate("keep"), Welcome.classify(Step.RATE, t("that's fine")))
        assertEquals(Input.Rate("keep"), Welcome.classify(Step.RATE, t("yes")))
        assertEquals(Input.RateNo, Welcome.classify(Step.RATE, t("no")))
        assertEquals(Input.Rate("keep"), Welcome.classify(Step.RATE_WHICH, t("no")))
        assertEquals(Input.Practise, Welcome.classify(Step.PRACTICE, t("send a practice message")))
        assertEquals(Input.Practise, Welcome.classify(Step.PRACTICE, t("yes please")))
        assertEquals(Input.Skip, Welcome.classify(Step.PRACTICE, t("no thanks")))
    }

    // ------------------------------------------------------------ skips, requests, stop

    @Test fun skip_later_and_silence_move_on() {
        for (step in Step.entries) {
            assertEquals(Input.Skip, Welcome.classify(step, t("skip")))
            assertEquals(Input.Skip, Welcome.classify(step, t("later")))
            assertEquals(Input.Silence, Welcome.classify(step, Reply.Silence))
        }
        assertEquals(Step.LANGUAGE, Welcome.step(Step.NAME, Input.Skip).next)
        assertEquals(Step.RATE, Welcome.step(Step.LANGUAGE, Input.Silence).next)
        assertNull(Welcome.step(Step.PRACTICE, Input.Skip).next)
    }

    @Test fun a_request_instead_of_an_answer_ends_the_welcome_and_runs() {
        for (step in Step.entries) {
            assertEquals(Input.Command("open WhatsApp"), Welcome.classify(step, t("open WhatsApp")))
            assertEquals(Input.Command("what's the time"), Welcome.classify(step, t("what's the time")))
        }
        assertEquals(Input.Command("call Kofi"), Welcome.classify(Step.NAME, t("call Kofi")))
        assertEquals(Welcome.Outcome.Command("open WhatsApp"), Welcome.step(Step.LANGUAGE, Input.Command("open WhatsApp")).end)
    }

    @Test fun stop_and_cancel_end_it() {
        for (step in Step.entries) for (w in listOf("stop", "cancel", "that's enough", "okay stop"))
            assertEquals("$step '$w'", Input.Stop, Welcome.classify(step, t(w)))
        val turn = Welcome.step(Step.RATE, Input.Stop)
        assertEquals(Welcome.Outcome.Stopped, turn.end)
        assertEquals(listOf(Welcome.OKAY), turn.say)
    }

    // ------------------------------------------------------------ the driver

    @Test fun the_full_welcome_saves_the_answers_rehearses_and_ends_with_what_you_can_say() = runTest {
        val io = FakeIo(t("Call me Ama"), t("Twi"), t("a bit slower"), t("send a practice message"), t("See you at 6"), t("yes"),
            line = { e -> if (e is Welcome.Effect.SetRate) "Okay, I'll speak slowly." else null })
        val end = Welcome.run(io, twiVoice = false) { Welcome.looksLikeCommand(it) }
        assertEquals(Welcome.Outcome.Finished, end)
        assertEquals(listOf(Welcome.Effect.SetName("Ama"), Welcome.Effect.SetLanguage(Settings.LANG_MODE_TWI),
            Welcome.Effect.SetRate("slower")), io.applied)
        assertEquals(Welcome.INTRO, io.said.first())
        assertTrue(io.said.contains("Nice to meet you, Ama."))
        assertTrue("honest about the English-only voice", io.said.contains(Welcome.languageAck(Settings.LANG_MODE_TWI, twiVoice = false)))
        assertTrue("the H-08 line is said", io.said.contains("Okay, I'll speak slowly."))
        assertTrue("the real confirmation's words", io.said.contains("You want me to text Practice contact: \"See you at 6\". Should I go ahead?"))
        assertEquals(1, io.okCues)
        assertTrue(io.said.contains("${Practice.ONLY_PRACTICE} ${Practice.AFTER_YES}"))
        assertEquals(Welcome.CLOSING, io.said.last())
        assertEquals(listOf(Welcome.question(Step.NAME), Welcome.question(Step.LANGUAGE), Welcome.question(Step.RATE),
            Welcome.question(Step.PRACTICE), Practice.ASK_MESSAGE), io.asked.take(5))
    }

    @Test fun skipping_everything_changes_nothing() = runTest {
        val io = FakeIo(t("skip"), t("later"), t("not now"), t("no"))
        assertEquals(Welcome.Outcome.Finished, Welcome.run(io))
        assertTrue(io.applied.isEmpty())
        assertEquals(Welcome.CLOSING, io.said.last())
        assertFalse(io.said.contains(Practice.START))
    }

    @Test fun two_silences_in_a_row_finish_early_with_the_closing_line() = runTest {
        val io = FakeIo(Reply.Silence, Reply.Silence)
        assertEquals(Welcome.Outcome.Finished, Welcome.run(io))
        assertEquals(2, io.asked.size)
        assertEquals(Welcome.CLOSING, io.said.last())
    }

    @Test fun a_request_mid_welcome_is_handed_back_without_the_closing_line() = runTest {
        val io = FakeIo(t("Kofi"), t("what's the time"))
        assertEquals(Welcome.Outcome.Command("what's the time"), Welcome.run(io))
        assertEquals(listOf(Welcome.Effect.SetName("Kofi")), io.applied)
        assertFalse(io.said.contains(Welcome.CLOSING))
    }

    @Test fun stop_ends_it_at_once_and_a_tap_cancels_silently() = runTest {
        val io = FakeIo(t("Ama"), t("stop"))
        assertEquals(Welcome.Outcome.Stopped, Welcome.run(io))
        assertEquals(Welcome.OKAY, io.said.last())
        assertEquals(2, io.asked.size)
        val tapped = FakeIo(Reply.Cancelled)
        assertEquals(Welcome.Outcome.Cancelled, Welcome.run(tapped))
        assertFalse(tapped.said.contains(Welcome.CLOSING))
    }

    @Test fun an_unclear_answer_is_asked_again_once_then_moves_on() = runTest {
        val io = FakeIo(t("um hmm"), t("Kofi"))
        Welcome.run(io)
        assertEquals(Welcome.SORRY + Welcome.question(Step.NAME), io.asked[1])
        assertEquals(Welcome.Effect.SetName("Kofi"), io.applied.first())
        val twice = FakeIo(t("um hmm"), t("hmm"), t("English"))
        Welcome.run(twice)
        assertEquals("moved on to the language", Welcome.question(Step.LANGUAGE), twice.asked[2])
    }

    @Test fun no_to_the_speed_asks_which_way() = runTest {
        val io = FakeIo(t("skip"), t("skip"), t("no"), t("faster"), t("no"))
        Welcome.run(io)
        assertEquals(Welcome.question(Step.RATE_WHICH), io.asked[3])
        assertEquals(listOf(Welcome.Effect.SetRate("faster")), io.applied)
    }

    @Test fun with_a_twi_voice_the_choice_is_simply_confirmed() {
        assertEquals("Okay, Twi.", Welcome.languageAck(Settings.LANG_MODE_TWI, twiVoice = true))
        assertEquals("Okay, English.", Welcome.languageAck(Settings.LANG_MODE_ENGLISH, twiVoice = false))
    }

    // ------------------------------------------------------------ the practice: never sent

    @Test fun practice_phrases() {
        listOf("send a practice message", "Send a practice text please", "practise", "let's practice", "practice sending a message")
            .forEach { assertTrue(it, Practice.matches(it)) }
        listOf("send a message to Ama", "practice makes perfect", "send a message", "text Ama")
            .forEach { assertFalse(it, Practice.matches(it)) }
    }

    @Test fun practice_no_unclear_hold_and_a_disputed_yes_all_end_with_nothing_sent() = runTest {
        val no = FakeIo(t("hello"), t("no"))
        Practice.run(no)
        assertEquals(listOf("Okay, I won't.", Practice.ONLY_PRACTICE), no.said.takeLast(2))

        val unclear = FakeIo(t("hello"), t("what"), t("hmm"))
        Practice.run(unclear)
        assertTrue(unclear.asked.contains(Confirmation.REPEAT))
        assertEquals(listOf(Confirmation.DECLINED_UNCLEAR, Practice.ONLY_PRACTICE), unclear.said.takeLast(2))

        val hold = FakeIo(t("hello"), t("wait"), t("yes"))
        Practice.run(hold)
        assertEquals("${Practice.ONLY_PRACTICE} ${Practice.AFTER_YES}", hold.said.last())

        // J-02 discipline: a yes the recognizer also heard as "no" is not a yes.
        val disputed = FakeIo(t("hello"), t("yes", "no"), t("yes", "no"))
        Practice.run(disputed)
        assertEquals(Practice.ONLY_PRACTICE, disputed.said.last())
        assertFalse(disputed.said.any { it.contains(Practice.AFTER_YES) })
    }

    @Test fun practice_uses_a_default_message_on_silence_and_stops_on_stop() = runTest {
        val quiet = FakeIo(Reply.Silence, t("yes"))
        Practice.run(quiet)
        assertTrue(quiet.asked.any { it.contains(Practice.DEFAULT_MESSAGE) })
        val stop = FakeIo(t("stop"))
        assertEquals(Welcome.Outcome.Stopped, Practice.run(stop))
        assertEquals(Practice.ONLY_PRACTICE, stop.said.last())
    }

    @Test fun spoken_greetings_carry_the_name() {
        assertEquals("Good morning", Welcome.withName("Good morning", ""))
        assertEquals("Good morning, Ama", Welcome.withName("Good morning", "Ama"))
        Welcome.userName = "Kofi"
        assertEquals("Maakye, Kofi", Welcome.withName("Maakye"))
    }

    // ------------------------------------------------------------ VoiceController hooks

    private class ScriptedInput(vararg heard: Heard) : VoiceInput {
        val queue = ArrayDeque(heard.toList())
        override val id = "scripted"
        override val caps = InputCaps(true, true, transcribesAudio = false, languages = setOf(Lang.GH_EN), secureListen = false, patientEndpointing = true)
        override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard {
            events.onReady()
            val h = queue.removeFirstOrNull() ?: Heard.NoSpeech
            if (h is Heard.Speech) { events.onSpeechStart(); events.onCaptured() }
            return h
        }
    }
    private fun speech(text: String, vararg alts: String) =
        Heard.Speech(Transcript(raw = text, canonical = text, language = Lang.GH_EN), (listOf(text) + alts).map { Hypothesis(it, null) })
    /** Unused: the scripted input does the listening. */
    private class NoAsr : com.tekyerema.voice.AsrProvider {
        override val name = "none"
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript =
            throw com.tekyerema.voice.AsrException("not used")
    }
    private class LogTts : TtsProvider {
        override val name = "fake"; val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: String): Boolean { spoken += text; return true }
        override fun stop() {}; override fun shutdown() {}
    }
    /** Completes every goal at once with [result] ([status] COMPLETED unless told otherwise); records what it was given. */
    private class DoneAgent(private val result: String, private val status: AgentStatus = AgentStatus.COMPLETED) : AgentBridge {
        private val _s = MutableStateFlow(AgentUiState())
        override val state: StateFlow<AgentUiState> = _s
        val goals = mutableListOf<String>()
        override fun submitGoal(goal: String) {
            goals += goal
            _s.value = AgentUiState(status = status, goal = goal, log = listOf(LogEntry(0, LogEntry.Kind.RESULT, result)))
        }
        override fun stop() {}
    }
    private class FakeHost : WelcomeHost {
        override val twiVoice = false
        val effects = mutableListOf<Welcome.Effect>()
        override fun apply(effect: Welcome.Effect): String? { effects += effect; return null }
    }
    private class FakeHabits : HabitHost {
        val calls = mutableListOf<Triple<String, String?, Boolean>>()
        var answer: RepeatDetector.Answer? = null
        val pattern = RepeatDetector.Pattern(RepeatDetector.Kind.WEATHER, RepeatDetector.Cadence.WEEKLY, java.util.Calendar.FRIDAY, 9, 0)
        override fun completed(goal: String, fastIntent: String?, now: Long, canAsk: Boolean): HabitOffer? {
            calls += Triple(goal, fastIntent, canAsk)
            return if (canAsk) HabitOffer(pattern, RepeatDetector.offerLine(pattern)) else null
        }
        override fun answered(offer: HabitOffer, answer: RepeatDetector.Answer): String {
            this.answer = answer
            return when (answer) { RepeatDetector.Answer.YES -> "Okay. I'll remind you every Friday at 9 AM."
                RepeatDetector.Answer.NO -> RepeatDetector.NO_LINE; else -> RepeatDetector.LATER_LINE }
        }
    }
    private fun controller(scope: kotlinx.coroutines.CoroutineScope, agent: AgentBridge, tts: TtsProvider, input: VoiceInput,
                           host: WelcomeHost? = null, habits: HabitHost? = null) = VoiceController(
        asr = NoAsr(), tts = tts, sessionLanguage = { Lang.GH_EN }, onLanguageResolved = {}, scope = scope,
        hasMicPermission = { true }, agent = agent, input = input, welcome = host, habits = habits)

    @Test fun the_welcome_runs_by_voice_and_never_touches_the_agent() = runTest {
        val agent = DoneAgent("unused"); val tts = LogTts(); val host = FakeHost()
        val vc = controller(this, agent, tts, ScriptedInput(speech("Ama"), speech("English"), speech("okay"), speech("no")), host)
        assertTrue(vc.startWelcome())
        advanceUntilIdle()
        assertEquals(listOf(Welcome.Effect.SetName("Ama"), Welcome.Effect.SetLanguage(Settings.LANG_MODE_ENGLISH)), host.effects)
        assertEquals(Verbalize.apply(Welcome.CLOSING), tts.spoken.last())
        assertTrue(agent.goals.isEmpty())
        vc.shutdown()
    }

    @Test fun without_a_host_there_is_no_welcome() = runTest {
        val vc = controller(this, DoneAgent("x"), LogTts(), ScriptedInput())
        assertFalse(vc.startWelcome())
        vc.shutdown()
    }

    @Test fun a_request_during_the_welcome_runs_as_a_normal_turn() = runTest {
        val agent = DoneAgent("It's 9 AM."); val tts = LogTts()
        val vc = controller(this, agent, tts, ScriptedInput(speech("what's the time")), FakeHost())
        vc.startWelcome(); advanceUntilIdle()
        assertEquals(listOf("what's the time"), agent.goals)
        assertEquals(Verbalize.apply("It's 9 AM."), tts.spoken.last())
        vc.shutdown()
    }

    @Test fun send_a_practice_message_never_reaches_the_agent() = runTest {
        val agent = DoneAgent("Message sent."); val tts = LogTts()
        val vc = controller(this, agent, tts, ScriptedInput(speech("send a practice message"), speech("hello there"), speech("yes")))
        vc.start(); advanceUntilIdle()
        assertTrue("nothing reached the agent or any tool", agent.goals.isEmpty())
        assertTrue(tts.spoken.contains(Verbalize.apply("You want me to text Practice contact: \"hello there\". Should I go ahead?")))
        assertEquals(Verbalize.apply("${Practice.ONLY_PRACTICE} ${Practice.AFTER_YES}"), tts.spoken.last())
        vc.shutdown()
    }

    @Test fun a_habit_offer_follows_a_verified_success_and_a_yes_is_passed_on() = runTest {
        val agent = DoneAgent("It's sunny and 30 degrees."); val tts = LogTts(); val habits = FakeHabits()
        val vc = controller(this, agent, tts, ScriptedInput(speech("what's the weather"), speech("yes")), habits = habits)
        vc.start(); advanceUntilIdle()
        assertEquals(listOf(Triple("what's the weather", null as String?, true)), habits.calls)
        assertTrue(tts.spoken.contains(Verbalize.apply(RepeatDetector.offerLine(habits.pattern))))
        assertEquals(RepeatDetector.Answer.YES, habits.answer)
        assertEquals(Verbalize.apply("Okay. I'll remind you every Friday at 9 AM."), tts.spoken.last())
        assertEquals("only the request itself ever reached the agent", listOf("what's the weather"), agent.goals)
        vc.shutdown()
    }

    @Test fun no_habit_offer_after_an_unverified_result_or_a_typed_request() = runTest {
        val habits = FakeHabits()
        val unverified = controller(this, DoneAgent("I tried.", AgentStatus.COMPLETED_UNVERIFIED), LogTts(),
            ScriptedInput(speech("what's the weather")), habits = habits)
        unverified.start(); advanceUntilIdle()
        assertTrue("an unverified result is not noted", habits.calls.isEmpty())
        unverified.shutdown()

        val typed = controller(this, DoneAgent("It's sunny."), LogTts(), ScriptedInput(), habits = habits)
        typed.submitText("what's the weather"); advanceUntilIdle()
        assertEquals("noted, but not asked about", listOf(Triple("what's the weather", null as String?, false)), habits.calls)
        assertNull(habits.answer)
        typed.shutdown()
    }

    @Test fun silence_after_a_habit_offer_says_nothing_more() = runTest {
        val tts = LogTts(); val habits = FakeHabits()
        val vc = controller(this, DoneAgent("It's sunny."), tts, ScriptedInput(speech("what's the weather")), habits = habits)
        vc.start(); advanceUntilIdle()
        assertEquals(RepeatDetector.Answer.NONE, habits.answer)
        assertEquals(Verbalize.apply(RepeatDetector.offerLine(habits.pattern)), tts.spoken.last())
        vc.shutdown()
    }
}
