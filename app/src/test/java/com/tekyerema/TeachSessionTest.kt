package com.tekyerema

import com.tekyerema.teach.RawEvent
import com.tekyerema.teach.StepKind
import com.tekyerema.teach.TeachParse
import com.tekyerema.teach.TeachSession
import com.tekyerema.teach.TeachRecorder
import com.tekyerema.teach.TeachStore
import com.tekyerema.voice.Confirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teach mode: what the user says (parsing) and the conversation that records, names, confirms and saves a skill. */
class TeachSessionTest {
    // ---------------------------------------------------------------- parsing

    @Test fun starting_phrases() {
        for (s in listOf("watch me", "Watch me.", "Tekyerema, watch me", "let me show you something", "teach mode", "start recording", "I want to teach you"))
            assertEquals(s, "", TeachParse.parseStart(s))
        assertEquals("check my data bundle", TeachParse.parseStart("Let me show you how to check my data bundle"))
        assertEquals("check my balance", TeachParse.parseStart("show you how to check my balance"))
        for (s in listOf("what time is it", "show me the time", "stop", "open settings", "teach me twi", "watch a video"))
            assertNull(s, TeachParse.parseStart(s))
    }

    @Test fun stop_phrases_with_and_without_a_name() {
        for (s in listOf("stop", "Stop recording", "that's it", "I'm done", "that’s all", "finished"))
            assertEquals(s, TeachParse.Stop.Plain, TeachParse.parseStop(s))
        assertEquals(TeachParse.Stop.Named("data bundle"), TeachParse.parseStop("stop, call it data bundle"))
        assertEquals(TeachParse.Stop.Named("data bundle"), TeachParse.parseStop("Stop and call it the data bundle."))
        assertEquals(TeachParse.Stop.Named("check balance"), TeachParse.parseStop("that's it, name it check balance"))
        assertNull(TeachParse.parseStop("call it data bundle"))
        assertNull(TeachParse.parseStop("stop the music"))
    }

    @Test fun naming_phrases() {
        assertEquals("data bundle", TeachParse.parseName("call it data bundle", bareOk = false))
        assertEquals("data bundle", TeachParse.parseName("It's called data bundle", bareOk = false))
        assertEquals("data bundle", TeachParse.parseName("Data bundle", bareOk = true))
        assertNull(TeachParse.parseName("Data bundle", bareOk = false))
    }

    @Test fun running_a_skill_by_its_name() {
        val names = listOf("data bundle", "music")
        for (s in listOf("data bundle", "Data bundle.", "do data bundle", "run my data bundle skill", "Tekyerema, do the data bundle", "use data bundle"))
            assertEquals(s, "data bundle", TeachParse.matchRun(s, names))
        // "play" / "start" / "open" are the phone's own commands: the word "skill" must be said
        assertNull(TeachParse.matchRun("play music", names))
        assertNull(TeachParse.matchRun("open music", names))
        assertEquals("music", TeachParse.matchRun("play my music skill", names))
        assertNull(TeachParse.matchRun("what is my data bundle", names))     // only the whole utterance
        assertNull(TeachParse.matchRun("data bundle", emptyList()))
    }

    @Test fun listing_deleting_and_reading_steps() {
        val names = listOf("data bundle")
        assertTrue(TeachParse.isList("What skills do I have?"))
        assertTrue(TeachParse.isList("what have I taught you"))
        assertFalse(TeachParse.isList("what do you know"))
        assertEquals("data bundle", TeachParse.parseDelete("delete the data bundle skill", names))
        assertEquals("data bundle", TeachParse.parseDelete("forget data bundle", names))
        assertNull(TeachParse.parseDelete("forget Ama", names))                   // not a skill: the phone's own "forget"
        assertNull(TeachParse.parseDelete("delete data bundle", emptyList()))
        assertEquals("data bundle", TeachParse.parseSteps("what does data bundle do", names))
        assertEquals("data bundle", TeachParse.parseSteps("what does the data bundle skill do?", names))
        assertNull(TeachParse.parseSteps("what does music do", names))
    }

    // ---------------------------------------------------------------- the conversation

    private var clock = 1_000_000L
    private val store = TeachStore()
    private val recorder = TeachRecorder()
    private fun answer(t: String) = when (t.trim().lowercase()) {
        "yes", "yes please" -> Confirmation.Answer.YES
        "no" -> Confirmation.Answer.NO
        "wait" -> Confirmation.Answer.HOLD
        else -> Confirmation.Answer.UNCLEAR
    }
    private val session = TeachSession(store, recorder, validName = { it != "time" && it.length >= 2 },
        routineExists = { it == "good morning" }, now = { clock }, answerOf = { answer(it) })

    private val app = "com.mtn.momo"
    private fun click(label: String) = RawEvent(RawEvent.Type.CLICK, app, clock, appName = "MyMTN", role = "Button", label = label, launchable = true)
    private fun typed(text: String) = RawEvent(RawEvent.Type.TEXT_CHANGED, app, clock, appName = "MyMTN", role = "EditText", hint = "Search",
        viewId = "$app:id/q", editable = true, text = text, launchable = true)
    private fun doStep(e: RawEvent, secure: Boolean = false): String? { clock += 1_000; return session.onEvent(e.copy(timeMs = clock), secure) }

    private fun teachDataBundle() {
        assertTrue(session.handle("watch me")!!.spoken.contains("watching"))
        assertTrue(recorder.recording)
        doStep(click("Data")); doStep(click("Balance"))
    }

    @Test fun record_name_confirm_and_save() {
        teachDataBundle()
        val read = session.handle("stop, call it data bundle")!!
        assertTrue(read.spoken, read.spoken.contains("“data bundle”") && read.spoken.contains("first open MyMTN, then tap Data, then tap Balance"))
        assertTrue(read.listenAfter); assertTrue(read.spoken.endsWith("Shall I save it?"))
        assertNull(store.get("data bundle"))                       // nothing is saved before the yes
        val saved = session.handle("yes")!!
        assertTrue(saved.spoken.startsWith("Saved."))
        val skill = store.get("data bundle")!!
        assertEquals(listOf(StepKind.OPEN_APP, StepKind.CLICK, StepKind.CLICK), skill.steps.map { it.kind })
        assertFalse(skill.endsAtPrivate)
        assertEquals(TeachSession.Phase.IDLE, session.phase)
        // …and the name now runs it
        assertEquals("data bundle", session.handle("data bundle")!!.run)
    }

    @Test fun stopping_first_and_naming_after() {
        teachDataBundle()
        val ask = session.handle("that's it")!!
        assertTrue(ask.spoken.contains("What shall I call it?")); assertTrue(ask.listenAfter)
        val read = session.handle("data bundle")!!
        assertTrue(read.spoken.contains("Shall I save it?"))
        assertNotNull(session.handle("yes"))
        assertNotNull(store.get("data bundle"))
    }

    @Test fun a_name_the_phone_already_knows_is_refused_and_asked_again() {
        teachDataBundle()
        session.handle("stop")
        val r = session.handle("time")!!
        assertTrue(r.spoken, r.spoken.contains("Say another name")); assertEquals(TeachSession.Phase.NEED_NAME, session.phase)
        val r2 = session.handle("good morning")!!
        assertTrue(r2.spoken, r2.spoken.contains("already have a routine")); assertEquals(TeachSession.Phase.NEED_NAME, session.phase)
        assertTrue(session.handle("call it data bundle")!!.spoken.contains("Shall I save it?"))
    }

    @Test fun saying_no_at_the_read_back_saves_nothing() {
        teachDataBundle()
        session.handle("stop, call it data bundle")
        assertTrue(session.handle("no")!!.spoken.contains("haven't saved"))
        assertTrue(store.all().isEmpty()); assertEquals(TeachSession.Phase.IDLE, session.phase)
    }

    @Test fun an_unclear_answer_is_asked_again_and_three_in_a_row_saves_nothing() {
        teachDataBundle()
        session.handle("stop, call it data bundle")
        assertTrue(session.handle("hmm")!!.spoken.startsWith("Please say yes or no"))
        assertTrue(session.handle("hmm")!!.spoken.startsWith("Please say yes or no"))
        assertTrue(session.handle("hmm")!!.spoken.contains("haven't saved"))
        assertTrue(store.all().isEmpty())
    }

    @Test fun cancel_throws_the_recording_away_at_any_point() {
        teachDataBundle()
        assertTrue(session.handle("cancel")!!.spoken.contains("thrown that away"))
        assertFalse(recorder.recording); assertEquals(TeachSession.Phase.IDLE, session.phase)
        teachDataBundle(); session.handle("stop"); session.handle("cancel")
        assertEquals(TeachSession.Phase.IDLE, session.phase); assertTrue(store.all().isEmpty())
    }

    @Test fun a_recording_with_no_steps_is_not_saved() {
        session.handle("watch me")
        val r = session.handle("stop, call it nothing")!!
        assertTrue(r.spoken.contains("didn't see any steps")); assertTrue(store.all().isEmpty())
    }

    @Test fun other_requests_while_recording_are_left_to_the_phone() {
        session.handle("watch me")
        assertNull(session.handle("what time is it"))
        assertNull(session.handle("open settings"))
        assertTrue(recorder.recording)
    }

    // ---------------------------------------------------------------- typed words

    private fun teachWithTyping(words: String) {
        session.handle("watch me")
        doStep(typed(words.take(2))); doStep(typed(words))
        doStep(click("Search"))
    }

    @Test fun typed_words_are_kept_only_when_the_user_says_they_are_not_private() {
        teachWithTyping("data bundle")
        val q = session.handle("stop, call it find data")!!
        assertTrue(q.spoken, q.spoken.contains("Search box") && q.spoken.contains("not private")); assertTrue(q.listenAfter)
        assertEquals(TeachSession.Phase.ASK_TEXT, session.phase)
        assertTrue(session.handle("yes")!!.spoken.contains("type “data bundle” into the Search box"))
        session.handle("yes")
        assertEquals("data bundle", store.get("find data")!!.steps.first { it.kind == StepKind.TYPE }.text)
    }

    @Test fun typed_words_are_dropped_on_a_no_and_never_reach_the_saved_skill() {
        teachWithTyping("my secret note")
        session.handle("stop, call it note")
        val readBack = session.handle("no")!!
        assertFalse(readBack.spoken.contains("secret"))
        assertTrue(readBack.spoken.contains("type into the Search box"))
        session.handle("yes")
        val step = store.get("note")!!.steps.first { it.kind == StepKind.TYPE }
        assertNull(step.text); assertEquals(14, step.textLength)
        assertFalse(step.toJson().toString().contains("secret"))
    }

    @Test fun no_clear_yes_means_the_words_are_not_kept() {
        teachWithTyping("hello there")
        session.handle("stop, call it hi")
        for (i in 1..3) session.handle("umm")
        session.handle("yes")
        assertNull(store.get("hi")!!.steps.first { it.kind == StepKind.TYPE }.text)
    }

    @Test fun a_number_only_entry_is_not_even_offered() {
        teachWithTyping("482913")
        val r = session.handle("stop, call it code")!!
        assertEquals(TeachSession.Phase.CONFIRM_SAVE, session.phase)       // straight to the read-back: no "remember these words?"
        assertFalse(r.spoken.contains("not private"))
        session.handle("yes")
        assertNull(store.get("code")!!.steps.first { it.kind == StepKind.TYPE }.text)
        assertTrue(TeachSession.looksPrivate("4829")); assertTrue(TeachSession.looksPrivate("0244 123 456")); assertFalse(TeachSession.looksPrivate("data 2"))
    }

    // ---------------------------------------------------------------- privacy stops

    @Test fun a_private_screen_stops_the_recording_and_says_why() {
        teachDataBundle()
        val notice = doStep(typed("1234").copy(hint = "Enter PIN", viewId = null))
        assertNotNull(notice); assertTrue(notice!!, notice.contains("private") && notice.contains("kept 3 steps"))
        assertFalse(recorder.recording); assertEquals(TeachSession.Phase.NEED_NAME, session.phase)
        val read = session.handle("call it send money")!!
        assertTrue(read.spoken, read.spoken.contains("stops before the private part"))
        session.handle("yes")
        val skill = store.get("send money")!!
        assertTrue(skill.endsAtPrivate)
        assertTrue(skill.steps.none { it.kind == StepKind.TYPE })
    }

    @Test fun the_phones_own_dialog_stops_it_too() {
        teachDataBundle()
        val notice = session.onEvent(click("1").copy(pkg = "com.android.phone", timeMs = clock + 1), false)
        assertNotNull(notice); assertTrue(notice!!.contains("phone's own dialog"))
    }

    @Test fun a_credential_screen_found_afterwards_drops_what_was_done_on_it() {
        teachDataBundle()
        val before = clock
        doStep(click("5")); doStep(click("7"))
        val notice = session.onPrivateScreen(before + 500)
        assertNotNull(notice); assertTrue(notice!!.contains("kept 3 steps"))
        assertEquals(TeachSession.Phase.NEED_NAME, session.phase)
    }

    @Test fun a_secure_step_stops_it() {
        teachDataBundle()
        assertNotNull(doStep(click("Next"), secure = true))
    }

    @Test fun nothing_is_recorded_when_not_in_teach_mode() {
        assertNull(session.onEvent(click("Data"), false))
        assertTrue(recorder.steps().isEmpty())
    }

    // ---------------------------------------------------------------- list / delete / read

    private fun saveOne(name: String) { teachDataBundle(); session.handle("stop, call it $name"); session.handle("yes") }

    @Test fun listing_deleting_and_reading_back() {
        assertTrue(session.handle("what skills do I have")!!.spoken.contains("haven't taught me anything"))
        saveOne("data bundle")
        assertTrue(session.handle("what skills do I have")!!.spoken.contains("“data bundle”"))
        assertTrue(session.handle("what does data bundle do")!!.spoken.contains("tap Data"))
        assertTrue(session.handle("delete the data bundle skill")!!.spoken.contains("forgotten"))
        assertTrue(store.all().isEmpty())
        assertNull(session.handle("data bundle"))                  // gone: not ours any more
    }

    @Test fun an_old_half_finished_conversation_is_dropped() {
        teachDataBundle(); session.handle("stop")
        clock += 6 * 60_000L
        assertNull(session.handle("what time is it"))              // reset to idle, and it is not ours
        assertEquals(TeachSession.Phase.IDLE, session.phase)
    }

    @Test fun saving_over_an_existing_name_says_so() {
        saveOne("data bundle")
        teachDataBundle(); session.handle("stop, call it data bundle")
        assertTrue(session.handle("yes")!!.spoken.contains("replaces the old one"))
    }
}
