package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.agent.LeftOff
import com.tekyerema.agent.TaskMemory
import com.tekyerema.agent.journal.ChatCheck
import com.tekyerema.agent.journal.Checkpoint
import com.tekyerema.agent.journal.Effect
import com.tekyerema.agent.journal.EffectKeys
import com.tekyerema.agent.journal.EffectKind
import com.tekyerema.agent.journal.EffectLedger
import com.tekyerema.agent.journal.EffectLines
import com.tekyerema.agent.journal.EffectState
import com.tekyerema.agent.journal.PauseReason
import com.tekyerema.agent.journal.PauseRules
import com.tekyerema.agent.journal.ResumeIntent
import com.tekyerema.agent.journal.ResumeOffer
import com.tekyerema.agent.journal.TaskJournal
import com.tekyerema.agent.journal.TaskState
import com.tekyerema.agent.journal.WorldVerdict
import com.tekyerema.core.Health
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import com.tekyerema.history.HistoryEntry
import com.tekyerema.history.Outcome
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** WS-A — task journal, effect ledger, pause/resume rules, Health exit reasons, History outcomes (pure parts). */
class TaskJournalTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("tky-journal").toFile()
        SecureStore.keyProvider = { SecretKeySpec(ByteArray(32) { 3 }, "AES") }
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
    }

    @After fun tearDown() {
        TaskJournal.resetForTest(); EffectLedger.resetForTest()
        SecureStore.keyProvider = null
        AppConfig.loadFrom(emptyList())
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- SecureStore

    @Test fun secure_store_writes_through_a_synced_temp_file_and_leaves_none_behind() {
        val f = File(dir, "x/y.enc")
        assertTrue(SecureStore.write(f, "first"))
        assertTrue(SecureStore.write(f, "second"))   // replaces an existing file (the rename path on every platform)
        assertEquals("second", SecureStore.read(f))
        assertFalse(File(dir, "x/y.enc.tmp").exists())
        assertFalse("never in the clear", f.readText(Charsets.ISO_8859_1).contains("second"))
    }

    // ---------------------------------------------------------------- TaskJournal

    @Test fun the_journal_survives_a_restart_and_a_running_task_becomes_interrupted() {
        val tasks = File(dir, "tasks")
        TaskJournal.load(tasks, now = 1_000L)
        val rec = TaskJournal.begin("send Ama a message", "agent", now = 1_000L)
        TaskJournal.checkpoint(rec.copy(checkpoint = "{\"step\":2}", stepsDone = 2, progress = "opened WhatsApp"))
        // A new process.
        TaskJournal.resetForTest()
        val interrupted = TaskJournal.load(tasks, now = 5_000L, exitReason = "killed for low memory")
        assertEquals(listOf(rec.id), interrupted.map { it.id })
        val r = TaskJournal.resumable(now = 6_000L)!!
        assertEquals(TaskState.INTERRUPTED, r.state)
        assertEquals(PauseReason.PROCESS_DEATH, r.pauseReason)
        assertEquals("killed for low memory", r.exitReason)
        assertEquals(2, r.stepsDone)
        assertEquals("opened WhatsApp", r.progress)
        assertEquals(5_000L + TaskJournal.resumeWindowMs(), r.resumeDeadlineMs)
        // A second cold start does not "interrupt" it again.
        TaskJournal.resetForTest()
        assertTrue(TaskJournal.load(tasks, now = 7_000L).isEmpty())
    }

    @Test fun a_new_task_supersedes_an_unfinished_one_and_finish_is_final() {
        TaskJournal.load(File(dir, "tasks"))
        val a = TaskJournal.begin("send Ama a message", "agent")
        TaskJournal.pause(a.id, PauseReason.NETWORK)
        assertEquals(a.id, TaskJournal.resumable()!!.id)
        val b = TaskJournal.begin("open YouTube", "agent")
        assertEquals(TaskState.CANCELLED, TaskJournal.get(a.id)!!.state)
        assertNull(TaskJournal.resumable())
        TaskJournal.finish(b.id, TaskState.COMPLETED)
        assertEquals(TaskState.COMPLETED, TaskJournal.latest()!!.state)
    }

    @Test fun a_quota_pause_stays_resumable_until_after_the_quota_refills() {
        TaskJournal.load(File(dir, "tasks"))
        val a = TaskJournal.begin("download five songs", "agent", now = 0L)
        TaskJournal.pause(a.id, PauseReason.QUOTA, resumeAfterMs = 3_600_000L, now = 1_000L)
        val r = TaskJournal.get(a.id)!!
        assertEquals(3_600_000L + TaskJournal.resumeWindowMs(), r.resumeDeadlineMs)
        assertNotNull(TaskJournal.resumable(now = 3_600_000L))
    }

    @Test fun the_offer_is_made_once_and_that_is_remembered_across_a_restart() {
        val tasks = File(dir, "tasks")
        TaskJournal.load(tasks)
        val a = TaskJournal.begin("send Ama a message", "agent")
        TaskJournal.pause(a.id, PauseReason.NETWORK)
        assertEquals("Earlier I paused “send Ama a message” because it needs the internet, and the phone is offline. Say “carry on” when you want me to continue.",
            ResumeOffer.take()!!.second)   // e.g. when the app is next opened
        TaskJournal.resetForTest(); TaskJournal.load(tasks)
        assertNull("already offered before the restart", ResumeOffer.take())
        assertNotNull(TaskJournal.resumable())
    }

    @Test fun the_network_back_offer_is_only_for_a_network_pause() {
        TaskJournal.load(File(dir, "tasks"))
        val a = TaskJournal.begin("send Ama a message", "agent")
        TaskJournal.pause(a.id, PauseReason.QUOTA)
        assertNull(ResumeOffer.take(networkOnly = true))
        TaskJournal.pause(a.id, PauseReason.NETWORK)
        val (_, line) = ResumeOffer.take(networkOnly = true)!!
        assertEquals("The internet is back. Earlier I paused “send Ama a message”. Say “carry on” when you want me to continue.", line)
    }

    @Test fun checkpoint_and_task_memory_round_trip() {
        val m = TaskMemory().apply {
            noteScreen("com.whatsapp", "Ama"); noteEntity("recipient", "Ama"); noteFailure("tap failed: not found")
        }
        val cp = Checkpoint(step = 4, at = 9L, history = (1..20).map { "line $it" }, memory = m.toJson(),
            lastVerifiedApp = "WhatsApp", lastVerifiedTitle = "Ama", lastVerifiedSig = Checkpoint.sigHash("sig"),
            mutatingCount = 3, mutatingVerified = 2, partialCount = 1, lastMutatingVerified = true, failStreak = 1, usedRead = true,
            usedTools = listOf("tap"), discoveredTools = listOf("send_sms"), untrustedRead = listOf("a", "b", "c", "d"),
            injectionSource = null, done = listOf("opened WhatsApp"))
        val back = Checkpoint.fromJson(cp.toJson())!!
        assertEquals(Checkpoint.MAX_HISTORY, back.history.size)
        assertEquals("line 20", back.history.last())
        assertEquals(listOf("b", "c", "d"), back.untrustedRead)
        assertEquals(cp.copy(history = back.history, untrustedRead = back.untrustedRead, memory = back.memory), back)
        assertEquals(m.render(), TaskMemory.fromJson(back.memory).render())
        assertNull(Checkpoint.fromJson("not json"))
    }

    // ---------------------------------------------------------------- EffectLedger

    @Test fun the_ledger_keys_on_the_consequence_and_turns_in_flight_rows_unknown_at_a_cold_start() {
        val tasks = File(dir, "tasks")
        EffectLedger.load(tasks, now = 1_000L)
        val e = EffectLedger.intend(EffectKind.SMS, "+233 24 412 3456", "See you at 6.", "t1", now = 1_000L)
        assertTrue(EffectLedger.lastWriteDurable)
        // Case / punctuation / spacing don't make a different message; SMS ≡ a chat message to the same target.
        assertNotNull(EffectLedger.recentSame(EffectKind.CHAT_MESSAGE, "233244123456", "see you at 6", 60_000L, now = 2_000L))
        assertNull(EffectLedger.recentSame(EffectKind.CALL, "233244123456", "see you at 6", 60_000L, now = 2_000L))
        assertNull(EffectLedger.recentSame(EffectKind.SMS, "233244123456", "see you at 7", 60_000L, now = 2_000L))
        assertNull("outside the window", EffectLedger.recentSame(EffectKind.SMS, "233244123456", "see you at 6", 60_000L, now = 70_000L))
        // The process dies before the outcome is written.
        EffectLedger.resetForTest()
        assertEquals(1, EffectLedger.load(tasks, now = 3_000L))
        assertEquals(EffectState.UNKNOWN, EffectLedger.get(e.id)!!.state)
        EffectLedger.mark(e.id, EffectState.FAILED, "checked: not in the sent box")
        assertNull("a FAILED row is not a duplicate", EffectLedger.recentSame(EffectKind.SMS, "233244123456", "see you at 6", 60_000L, now = 4_000L))
        assertFalse(File(tasks, "effects.enc").readText(Charsets.ISO_8859_1).contains("see you"))
    }

    @Test fun without_a_keystore_key_nothing_is_written_in_the_clear() {
        SecureStore.keyProvider = { null }
        EffectLedger.load(File(dir, "tasks"))
        EffectLedger.intend(EffectKind.SMS, "Ama", "hello", "t1")
        assertFalse(EffectLedger.lastWriteDurable)
        assertFalse(File(dir, "tasks/effects.enc").exists())
        assertEquals(1, EffectLedger.all().size)   // still dedupes in this process
    }

    @Test fun effect_keys_name_the_same_consequence_across_paths() {
        val chat = Screens.chat(composer = "see you at 6")
        val send = chat.element(Screens.SEND_ID)
        val tap = EffectKeys.describe("tap", JSONObject().put("element_id", Screens.SEND_ID), send, null, false, chat)!!
        val typed = EffectKeys.describe("type", JSONObject().put("text", "See you at 6").put("submit", true), chat.element(Screens.COMPOSER_ID), null, true, Screens.chat(null))!!
        val sms = EffectKeys.describe("send_sms", JSONObject().put("contact", "Ama").put("message", "see you at 6!"), null, null, false, null)!!
        for (s in listOf(tap, typed, sms)) {
            assertEquals(EffectKind.CHAT_MESSAGE, EffectLedger.family(s.kind))
            assertEquals("ama", EffectLedger.normalizeTarget(s.target))
            assertEquals(EffectLedger.hash("see you at 6"), EffectLedger.hash(s.payload))
        }
        assertNull("typing without submit sends nothing", EffectKeys.describe("type", JSONObject().put("text", "hi"), null, null, false, chat))
        assertNull("a keypad digit is navigation", EffectKeys.describe("tap", JSONObject().put("label", "1"), null, "1", false, chat))
        assertEquals(EffectKind.CALL, EffectKeys.describe("make_call", JSONObject().put("number", "0244123456"), null, null, false, null)!!.kind)
        assertEquals(EffectKind.PAYMENT, EffectKeys.describe("momo_send", JSONObject().put("number", "0244123456").put("amount", "5"), null, null, false, null)!!.kind)
        assertEquals(EffectKind.INSTALL, EffectKeys.describe("install_app", JSONObject().put("app", "Telegram"), null, null, false, null)!!.kind)
        // A generic caption is keyed on its screen: two different "Allow" dialogs are two consequences, a re-tap is one.
        fun dialog(q: String) = com.tekyerema.accessibility.UiSnapshot("com.android.permissioncontroller", null, listOf(
            UiElement(0, "android.widget.TextView", q, Box(0, 800, 1080, 900)),
            UiElement(1, "android.widget.Button", "Allow", Box(0, 1000, 1080, 1100), clickable = true)), 1L, appName = "Permissions")
        fun allow(s: com.tekyerema.accessibility.UiSnapshot) = EffectKeys.describe("tap", JSONObject().put("element_id", 1), s.element(1), null, false, s)!!
        val cam = allow(dialog("Allow Jiji to take pictures?"))
        val loc = allow(dialog("Allow Jiji to access this device's location?"))
        assertFalse(EffectLedger.hash(cam.payload) == EffectLedger.hash(loc.payload))
        assertEquals(EffectLedger.hash(cam.payload), EffectLedger.hash(allow(dialog("Allow Jiji to take pictures?")).payload))
    }

    @Test fun the_chat_on_screen_says_whether_a_message_went() {
        val e = Effect("e1", "t1", EffectKind.CHAT_MESSAGE, "ama", EffectLedger.hash("see you at 6"), EffectState.UNKNOWN, 0L, label = "Ama")
        assertEquals(WorldVerdict.NOT_DONE, ChatCheck.check(e, "see you at 6", Screens.chat(composer = "see you at 6")).verdict)
        assertEquals(WorldVerdict.DONE, ChatCheck.check(e, "see you at 6", Screens.chat(null, listOf("see you at 6 10:02"))).verdict)
        assertEquals("with only the hash, an exact bubble still counts", WorldVerdict.DONE,
            ChatCheck.check(e, null, Screens.chat(null, listOf("See you at 6"))).verdict)
        assertEquals("someone else's chat proves nothing", WorldVerdict.UNDETERMINED,
            ChatCheck.check(e, "see you at 6", Screens.chat(null, listOf("see you at 6"), title = "Kofi")).verdict)
        assertEquals(WorldVerdict.UNDETERMINED, ChatCheck.check(e, "see you at 6", Screens.connectionDialog()).verdict)
    }

    @Test fun the_spoken_effect_lines() {
        val at = 0L
        val fmt: (Long) -> String = { "10:02" }
        val sent = Effect("e", "t", EffectKind.CHAT_MESSAGE, "ama", "h", EffectState.CONFIRMED, at, label = "Ama")
        assertEquals("I already sent this to Ama at 10:02 — send it again?", EffectLines.already(sent, fmt))
        assertEquals("I may already have sent this to Ama at 10:02 — I couldn't confirm it went. Send it again?", EffectLines.maybe(sent, fmt))
        val call = sent.copy(kind = EffectKind.CALL)
        assertEquals("I already called Ama at 10:02 — call again?", EffectLines.already(call, fmt))
        assertEquals("I may already have done this at 10:02 — I couldn't confirm it went. Do it again?",
            EffectLines.maybe(sent.copy(kind = EffectKind.OTHER), fmt))
        assertEquals(listOf("ALREADY DONE: sent this to Ama at 10:02 — do not do it again."), EffectLines.facts(listOf(sent), fmt))
    }

    // ---------------------------------------------------------------- pause rules + resume words

    @Test fun which_endings_pause_and_what_the_user_hears() {
        assertEquals(PauseReason.NETWORK, PauseRules.forCannotDo("network_lost"))
        assertEquals(PauseReason.NETWORK, PauseRules.forCannotDo("network_unavailable"))
        assertEquals(PauseReason.QUOTA, PauseRules.forCannotDo(PauseRules.QUOTA_EXHAUSTED))
        assertEquals(PauseReason.NO_CAPABLE_MODEL, PauseRules.forCannotDo(PauseRules.NO_CAPABLE_MODEL))
        assertEquals(PauseReason.BUDGET, PauseRules.forCannotDo(PauseRules.BUDGET_EXHAUSTED))
        assertNull(PauseRules.forCannotDo("not_installed"))
        assertEquals(PauseReason.QUOTA, PauseRules.forFail("HTTP 429: Rate limit reached (tokens per day). Please try again in 7m30.5s."))
        assertEquals(PauseReason.QUOTA, PauseRules.forFail("Groq: daily quota exhausted"))
        assertEquals(PauseReason.NO_CAPABLE_MODEL, PauseRules.forFail("Gemini 503: The model is overloaded (high demand)"))
        assertNull(PauseRules.forFail("HTTP 401: invalid API key"))
        assertEquals(1_000L + 450_500L, PauseRules.resumeAfter("Please try again in 7m30.5s.", 1_000L))
        assertEquals(1_000L + 45_000L, PauseRules.resumeAfter("retry in 45s", 1_000L))
        assertNull(PauseRules.resumeAfter("rate limited", 1_000L))
        assertEquals("I've paused this because it needs the internet, and the phone is offline. Say “carry on” when you want me to continue.",
            PauseRules.pausedLine(PauseReason.NETWORK))
        assertEquals("I've paused this because I've used up my AI allowance for now. It should be back around 10:30. " +
            "Say “carry on” when you want me to continue.", PauseRules.pausedLine(PauseReason.QUOTA, 5L) { "10:30" })
    }

    @Test fun carry_on_resumes_only_when_something_is_resumable_and_a_reading_keeps_its_continue() {
        for (s in listOf("carry on", "Continue.", "resume", "continue the task", "Carry on with the task please", "keep going",
                "carry on from where I left off", "Tɛkyerɛma, carry on", "yes please, continue"))
            assertTrue(s, ResumeIntent.shouldResume(s, hasResumable = true, readingPending = false))
        // Nothing resumable: "continue" stays what it was (media play / the agent).
        assertFalse(ResumeIntent.shouldResume("carry on", hasResumable = false, readingPending = false))
        for (s in listOf("send Ama a message", "continue to the next song", "resume the music video", "carry on texting Ama"))
            assertFalse(s, ResumeIntent.isRequest(s))
        // A screen reading waiting for "continue" keeps it; naming the task still resumes it.
        assertFalse(ResumeIntent.shouldResume("continue", hasResumable = true, readingPending = true))
        assertTrue(ResumeIntent.shouldResume("continue the task", hasResumable = true, readingPending = true))
    }

    // ---------------------------------------------------------------- Health + History

    @Test fun exit_reasons_are_labelled_right_and_a_force_stop_explains_screen_control() {
        // API 30 constants: REASON_USER_REQUESTED = 10 (Force stop / swiped from Recents), REASON_USER_STOPPED = 11.
        assertEquals("force-stopped or swiped away by the user", Health.exitReasonLabel(10))
        assertEquals("its user profile was stopped", Health.exitReasonLabel(11))
        assertEquals("killed for low memory", Health.exitReasonLabel(3))
        assertTrue(Health.isForceStop(10))
        assertFalse(Health.isForceStop(11))
        assertEquals(Health.SCREEN_CONTROL_OFF_AFTER_FORCE_STOP, Health.screenControlNotice(a11yEnabled = false, lastExit = 10))
        assertEquals(Health.SCREEN_CONTROL_OFF, Health.screenControlNotice(a11yEnabled = false, lastExit = 3))
        assertNull(Health.screenControlNotice(a11yEnabled = true, lastExit = 10))
    }

    @Test fun history_names_paused_and_interrupted_tasks_honestly() {
        assertEquals("Paused before it finished", Outcome.PAUSED.label)
        assertEquals("Interrupted before it finished", Outcome.INTERRUPTED.label)
        assertFalse(Outcome.PAUSED.done)
        assertFalse(Outcome.INTERRUPTED.done)
        val now = 10 * 60_000L
        assertEquals("Your last request, 10 minutes ago, was “Send Ama a message” — it was interrupted before it finished. Say it again and I'll try again.",
            LeftOff.line(HistoryEntry(0L, "Send Ama a message", Outcome.INTERRUPTED), false, null, now))
        assertEquals("You were in WhatsApp. “Send Ama a message” is paused because the internet dropped. Say “carry on” to continue it.",
            LeftOff.line(null, false, "WhatsApp", now, resumable = "“Send Ama a message” is paused because the internet dropped. Say “carry on” to continue it."))
    }

    @Test fun left_off_names_the_resumable_task() {
        TaskJournal.load(File(dir, "tasks"))
        val a = TaskJournal.begin("send Ama a message", "agent")
        TaskJournal.checkpoint(a.copy(progress = "opened WhatsApp"))
        TaskJournal.pause(a.id, PauseReason.NETWORK)
        assertEquals("“send Ama a message” is paused because it needs the internet, and the phone is offline. So far I opened WhatsApp. Say “carry on” to continue it.",
            ResumeOffer.leftOffLine())
    }
}
