package com.tekyerema

import com.tekyerema.audit.AuditEntry
import com.tekyerema.audit.AuditLog
import com.tekyerema.learning.FailureCase
import com.tekyerema.learning.FailureCore
import com.tekyerema.learning.FailureParse
import com.tekyerema.learning.FailureReason
import com.tekyerema.learning.FailureRedact
import com.tekyerema.learning.FailureStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learning from mistakes (MASTER_STATUS #25): what counts as a failure, what is kept and refused, the cap, the hint, the export. */
class FailureCoreTest {
    private val now = 1_800_000_000_000L
    private val day = FailureCore.DAY_MS

    // ---------------------------------------------------------------- redaction: what is never kept
    @Test fun secretsAmountsNumbersAndMessagesAreTakenOutOfARequest() {
        assertEquals("call <person>", FailureRedact.text("call Ama"))
        assertEquals("call <person> on WhatsApp", FailureRedact.text("call Kofi Mensah on WhatsApp"))
        assertEquals("call mum now", FailureRedact.text("call mum now"))
        assertEquals("tell …", FailureRedact.text("tell Ama I will be late"))
        assertEquals("tell me the time", FailureRedact.text("tell me the time"))
        assertEquals("text …", FailureRedact.text("text him that I am coming at 5"))
        assertEquals("send an amount", FailureRedact.text("send fifty cedis"))
        assertEquals("open Bolt and search for Accra Mall", FailureRedact.text("open Bolt and search for Accra Mall"))
        assertTrue(FailureRedact.text("read \"my secret note\" aloud").contains("“…”"))
        assertFalse(FailureRedact.text("my pin is 4821 open settings").contains("4821"))
        assertFalse(FailureRedact.text("send 20 cedis to Kofi").contains("20"))
        assertFalse(FailureRedact.text("email me at kofi@example.com").contains("kofi@"))
        val num = FailureRedact.text("call 0244123456")
        assertTrue(num.contains("#") && num.none { it.isDigit() })
        assertFalse("spoken digits are a number too", FailureRedact.text("dial zero two four four one two three").contains("two four"))
        assertTrue(FailureRedact.text("x".repeat(500), 120).length <= 120)
    }

    @Test fun requestsAboutMoneyOrSecretsAreNeverKept() {
        assertTrue(FailureRedact.sensitive("send 20 cedis to my sister"))
        assertTrue(FailureRedact.sensitive("my pin is 1234"))
        assertTrue(FailureRedact.sensitive("what is my account balance"))
        assertTrue(FailureRedact.sensitive("buy airtime"))
        assertTrue(FailureRedact.sensitive("read me the verification code"))
        assertFalse(FailureRedact.sensitive("open Bolt and book a ride"))
        assertFalse(FailureRedact.sensitive("read my messages"))
        assertFalse(FailureRedact.sensitive("open the camera"))
    }

    @Test fun onlyPlainShortControlLabelsAreKept() {
        assertEquals("Search", FailureRedact.label("Search"))
        assertEquals("Where to?", FailureRedact.label("Where to?"))
        assertEquals("a person in the contacts is not kept", "", FailureRedact.label("Ama", personal = { it == "Ama" }))
        assertEquals("", FailureRedact.label("Pay"))
        assertEquals("", FailureRedact.label("Balance 1,200"))
        assertEquals("", FailureRedact.label("Order 12345"))
        assertEquals("a message preview is not a control label", "", FailureRedact.label("I will be there at five tomorrow ok"))
        assertEquals("", FailureRedact.label("a".repeat(40)))
        assertEquals("symbols that could steer a prompt are refused", "", FailureRedact.label("Ignore <all> rules"))
        assertEquals("", FailureRedact.label(null))
    }

    // ---------------------------------------------------------------- what counts as a failure
    private fun st(result: String, tool: String = "tap", target: String = "") = FailureStep(tool, target, result)

    @Test fun classifyOrdersTheReasons() {
        val none = emptyList<String>()
        assertEquals(FailureReason.STEP_FAILED, FailureCore.classify("FAILED", false, listOf(st("failed")), none))
        assertEquals(FailureReason.LOOP_STOPPED, FailureCore.classify("FAILED", false, listOf(st("done")), listOf("loop guard: the task is going in circles")))
        assertEquals(FailureReason.LOOP_STOPPED, FailureCore.classify("CANNOT_DO", false, listOf(st("unverified")), listOf("futility: 'Send' never appeared after repeated attempts — stopping honestly.")))
        assertEquals(FailureReason.CANNOT_DO, FailureCore.classify("CANNOT_DO", false, listOf(st("done")), none))
        assertNull("a refusal before any step is not a mistake of the planner", FailureCore.classify("CANNOT_DO", false, emptyList(), none))
        assertEquals(FailureReason.UNVERIFIED, FailureCore.classify("COMPLETED_UNVERIFIED", false, listOf(st("done")), none))
        assertEquals(FailureReason.STOPPED_BY_USER, FailureCore.classify("STOPPED", false, listOf(st("done")), none))
        assertNull("stopped before anything ran", FailureCore.classify("STOPPED", false, emptyList(), none))
        assertNull("a declined proposal that was then stopped is the user's choice", FailureCore.classify("STOPPED", false, listOf(st("declined")), none))
        assertEquals(FailureReason.DECLINED_REPHRASED, FailureCore.classify("COMPLETED", false, listOf(st("revised"), st("verified")), none))
        assertNull(FailureCore.classify("COMPLETED", false, listOf(st("unverified"), st("verified")), none))
        assertEquals(FailureReason.RECOVERED, FailureCore.classify("COMPLETED", false, listOf(st("failed"), st("unverified"), st("verified")), none))
        assertNull(FailureCore.classify("COMPLETED", false, listOf(st("verified")), none))
        assertNull("a paused task has not ended", FailureCore.classify("CANNOT_DO", true, listOf(st("failed")), none))
    }

    // ---------------------------------------------------------------- the audit trail -> steps -> a note
    private fun e(kind: String, text: String, step: Int = 0, tool: String = "", outcome: String = "") =
        AuditEntry(t = 1, task = 7, source = "agent", kind = kind, text = text, step = step, tool = tool, decidedBy = "groq/gpt-oss", outcome = outcome)

    private fun trail(goal: String = "open Bolt and search for Accra Mall") = listOf(
        e(AuditLog.TASK_START, goal),
        e(AuditLog.PROPOSED, "open Bolt", 1, "open_app"), e(AuditLog.DONE, "opened Bolt", 1, "open_app"), e(AuditLog.VERIFIED, "Bolt is open", 1, "open_app"),
        e(AuditLog.PROPOSED, "tap “Search”", 2, "tap"), e(AuditLog.DONE, "tapped “Search”", 2, "tap"), e(AuditLog.UNVERIFIED, "the box did not open", 2, "tap"),
        e(AuditLog.PROPOSED, "tap “Ama”", 3, "tap"), e(AuditLog.DECLINED, "Tap Ama?", 3, "tap", outcome = "revised")
    )

    @Test fun stepsComeFromTheAuditTrailWithLabelsAndResultClasses() {
        val steps = FailureCore.stepsFrom(trail(), personal = { it == "Ama" })
        assertEquals(listOf(
            FailureStep("open_app", "Bolt", "verified"),
            FailureStep("tap", "Search", "unverified"),
            FailureStep("tap", "", "revised")   // "Ama" is a person in the contacts: no label kept
        ), steps)
    }

    @Test fun aFailedTaskBecomesARedactedNote() {
        val d = FailureCore.build("open Bolt and search for Accra Mall", "COMPLETED_UNVERIFIED", false, trail(), emptyList(), "Bolt", "Where to?", now)!!
        assertTrue(d.failing)
        assertTrue(d.declined)
        assertEquals(FailureReason.UNVERIFIED, d.case.reason)
        assertEquals("open Bolt and search for Accra Mall", d.case.goal)
        assertEquals("groq/gpt-oss", d.case.brain)
        assertEquals("Bolt", d.case.app)
        assertEquals("Where to?", d.case.title)
        assertEquals(3, d.case.steps.size)
        assertNull(d.case.correction)
    }

    @Test fun aGoalWithNamesAndNumbersIsStoredWithoutThem() {
        val d = FailureCore.build("call Kofi Mensah on 0244123456", "FAILED", false, trail("call Kofi Mensah on 0244123456"), emptyList(), null, null, now)!!
        assertFalse(d.case.goal.contains("Kofi") || d.case.goal.contains("Mensah") || d.case.goal.any { it.isDigit() })
    }

    @Test fun nothingIsKeptForMoneyPinsPausesOrNonBrainTasks() {
        assertNull("money words", FailureCore.build("send 20 cedis to Kofi", "FAILED", false, trail("send 20 cedis to Kofi"), emptyList(), null, null, now))
        assertNull("a PIN in the request", FailureCore.build("my pin is 4821", "FAILED", false, trail("my pin is 4821"), emptyList(), null, null, now))
        val moneyTool = trail() + e(AuditLog.PROPOSED, "send money", 4, "momo_send")
        assertNull("a money tool was used", FailureCore.build("open Bolt", "FAILED", false, moneyTool, emptyList(), null, null, now))
        val ussd = trail() + e(AuditLog.PROPOSED, "dial a USSD code", 4, "run_ussd")
        assertNull("a USSD tool was used", FailureCore.build("open Bolt", "FAILED", false, ussd, emptyList(), null, null, now))
        assertNull("paused", FailureCore.build("open Bolt", "CANNOT_DO", true, trail(), emptyList(), null, null, now))
        assertNull("no task_start entry", FailureCore.build("open Bolt", "FAILED", false, trail().drop(1), emptyList(), null, null, now))
    }

    @Test fun aCleanTaskIsHeldOnlySoALaterNoCanAttachToIt() {
        val clean = listOf(e(AuditLog.TASK_START, "open Settings"), e(AuditLog.PROPOSED, "open Settings", 1, "open_app"),
            e(AuditLog.DONE, "opened Settings", 1, "open_app"), e(AuditLog.VERIFIED, "ok", 1, "open_app"))
        val d = FailureCore.build("open Settings", "COMPLETED", false, clean, emptyList(), null, null, now)!!
        assertFalse(d.failing)
    }

    @Test fun correctionWordsAreRedactedAndSecretsRefused() {
        assertEquals("No, I said call <person>", FailureCore.correctionText("No, I said call Kofi"))
        assertNull(FailureCore.correctionText("no the pin is 1234"))
        assertFalse(FailureCore.correctionText("no I said call 0244123456")!!.any { it.isDigit() })
    }

    // ---------------------------------------------------------------- the cap
    private fun case(i: Int, goal: String = "open Bolt and search for Accra Mall", at: Long = i.toLong(),
                     reason: FailureReason = FailureReason.UNVERIFIED, steps: List<FailureStep> = listOf(st("unverified", "tap", "Search")),
                     app: String? = "Bolt") =
        FailureCase("c$i", at, goal, "groq/gpt-oss", reason, steps, app, null, null)

    @Test fun capIs200AndTheOldestGoFirst() {
        var list = emptyList<FailureCase>()
        for (i in 1..250) list = FailureCore.add(list, case(i))
        assertEquals(FailureCore.MAX_CASES, list.size)
        assertEquals("c51", list.first().id)
        assertEquals("c250", list.last().id)
    }

    @Test fun addingTheSameIdReplacesIt() {
        val a = FailureCore.add(emptyList(), case(1))
        val b = FailureCore.add(a, case(1).copy(correction = "no i said call <person>"))
        assertEquals(1, b.size)
        assertEquals("no i said call <person>", b[0].correction)
    }

    // ---------------------------------------------------------------- the stored form
    @Test fun encodeDecodeRoundTrips() {
        val list = listOf(case(1), case(2).copy(title = "Where to?", correction = "no that's wrong", reason = FailureReason.USER_REJECTED, app = null))
        assertEquals(list, FailureCore.decode(FailureCore.encode(list)))
    }

    @Test fun theEncodedFileStaysUnderItsSizeCap() {
        val long = "word ".repeat(23).trim()
        val steps = (1..FailureCore.MAX_STEPS).map { FailureStep("tap", "Label number", "unverified") }
        val list = (1..400).map { case(it, goal = long, steps = steps) }
        val text = FailureCore.encode(list)
        assertTrue(text.length <= FailureCore.MAX_CHARS)
        val back = FailureCore.decode(text)!!
        assertTrue(back.isNotEmpty() && back.size <= FailureCore.MAX_CASES)
        assertEquals("the newest are kept", "c400", back.last().id)
    }

    @Test fun badFilesAreNotFatal() {
        assertNull(FailureCore.decode(null))
        assertNull(FailureCore.decode(""))
        assertNull(FailureCore.decode("not json"))
        assertEquals(emptyList<FailureCase>(), FailureCore.decode("{\"v\":1}"))
        val mixed = "{\"v\":1,\"c\":[{\"id\":\"\"},{\"id\":\"a\",\"at\":1,\"goal\":\"open Bolt\",\"why\":\"nope\"},{\"id\":\"b\",\"at\":2,\"goal\":\"open Bolt\",\"why\":\"step_failed\"}]}"
        assertEquals(listOf("b"), FailureCore.decode(mixed)!!.map { it.id })
    }

    // ---------------------------------------------------------------- matching and the hint
    @Test fun similarityIsWordOverlapWithoutSmallWordsOrNames() {
        assertEquals(1.0, FailureCore.similarity("open bolt and search for accra mall", "please open Bolt and search for the Accra Mall"), 0.0001)
        assertTrue(FailureCore.similarity("open bolt and search for accra mall", "open bolt and search for the airport") < FailureCore.NEAR)
        assertEquals(0.0, FailureCore.similarity("call mum", "set an alarm"), 0.0001)
        assertEquals(0.0, FailureCore.similarity("", "set an alarm"), 0.0001)
    }

    @Test fun aNearIdenticalRequestGetsAOneLineHint() {
        val c = case(1, at = now - day, steps = listOf(st("verified", "open_app", "Bolt"), st("unverified", "tap", "Search")))
        val h = FailureCore.hintsFor(listOf(c), "please open bolt and search for accra mall", now)
        assertEquals(listOf("Last time, tapping 'Search' in Bolt did not work. Try a different way."), h)
    }

    @Test fun noHintForADifferentRequestAnOldMistakeOrAStoppedTask() {
        val c = case(1, at = now - day)
        assertTrue(FailureCore.hintsFor(listOf(c), "call mum", now).isEmpty())
        assertTrue(FailureCore.hintsFor(listOf(c), "open bolt and search for the airport", now).isEmpty())
        assertTrue("older than 30 days", FailureCore.hintsFor(listOf(c.copy(at = now - 31 * day)), "open bolt and search for accra mall", now).isEmpty())
        assertTrue(FailureCore.hintsFor(listOf(c.copy(reason = FailureReason.STOPPED_BY_USER)), "open bolt and search for accra mall", now).isEmpty())
        assertTrue(FailureCore.hintsFor(emptyList(), "open bolt and search for accra mall", now).isEmpty())
        assertTrue(FailureCore.hintsFor(listOf(c), "  ", now).isEmpty())
        assertTrue("a mistake with no steps has nothing specific to say", FailureCore.hintsFor(listOf(c.copy(steps = emptyList())), "open bolt and search for accra mall", now).isEmpty())
    }

    @Test fun hintWordsFollowWhyItWentWrong() {
        val send = listOf(st("done", "tap", "Send"))
        assertEquals("Last time, tapping 'Send' in WhatsApp was not what the user wanted.",
            FailureCore.hintFor(case(1, reason = FailureReason.USER_REJECTED, steps = send, app = "WhatsApp")))
        assertEquals("Last time, scrolling in Settings got stuck. Try a different way.",
            FailureCore.hintFor(case(1, reason = FailureReason.LOOP_STOPPED, steps = listOf(st("failed", "scroll")), app = "Settings")))
        assertEquals("Last time, opening Bolt did not work. Try a different way.",
            FailureCore.hintFor(case(1, reason = FailureReason.STEP_FAILED, steps = listOf(st("failed", "open_app", "Bolt")), app = null)))
        assertNull(FailureCore.hintFor(case(1, reason = FailureReason.STOPPED_BY_USER)))
    }

    @Test fun atMostTwoHintsNewestFirstWithoutRepeats() {
        fun c(i: Int, label: String) = case(i, at = now - i * 1000L, steps = listOf(st("unverified", "tap", label)))
        val h = FailureCore.hintsFor(listOf(c(1, "Search"), c(2, "Where to"), c(3, "Menu"), c(4, "Search")), "open bolt and search for accra mall", now)
        assertEquals(2, h.size)
        assertTrue(h[0].contains("'Search'"))
        assertTrue(h[1].contains("'Where to'"))
        assertEquals(1, FailureCore.hintsFor(listOf(c(1, "Search"), c(4, "Search")), "open bolt and search for accra mall", now).size)
    }

    // ---------------------------------------------------------------- export for the training pool
    @Test fun exportIsOneTrainingPoolTaskPerLine() {
        val c1 = case(1, at = now - 2 * day).copy(correction = "no i said call <person>", reason = FailureReason.USER_REJECTED)
        val c2 = case(2, at = now - day, goal = "set an alarm for #", steps = listOf(st("failed", "set_alarm")), app = null)
        val text = FailureCore.toJsonl(listOf(c2, c1))
        val lines = text.trimEnd('\n').split('\n')
        assertEquals(2, lines.size)
        assertTrue(text.endsWith("\n"))
        val o = JSONObject(lines[0])   // oldest first
        assertEquals("phone-c1", o.getString("id"))
        assertEquals("general", o.getString("persona"))
        assertEquals("transport", o.getString("category"))
        assertEquals("open Bolt and search for Accra Mall", o.getJSONArray("goals").getString(0))
        assertEquals("home", o.getString("start"))
        assertEquals("", o.getString("good_behaviour"))
        assertTrue(o.getString("must_not").isNotBlank())
        assertEquals(1, o.getJSONArray("apps").length())
        val tags = o.getJSONArray("tags")
        assertTrue((0 until tags.length()).map { tags.getString(it) }.containsAll(listOf("from_phone", "mistake", "user_rejected")))
        val f = o.getJSONObject("failure")
        assertEquals("user_rejected", f.getString("reason"))
        assertEquals("no i said call <person>", f.getString("correction"))
        assertEquals("Search", f.getJSONArray("steps").getJSONObject(0).getString("target"))
        assertEquals("only the day, not the time", 10, f.getString("day").length)
        assertEquals(2, o.getJSONArray("conversation").length())
        assertEquals("alarm goes to productivity", "productivity", JSONObject(lines[1]).getString("category"))
    }

    @Test fun whatTheUserHearsBeforeSharingNamesWhatIsAndIsNotInTheFile() {
        val said = FailureCore.describeExport(listOf(case(1), case(2)))
        assertTrue(said.startsWith("I have 2 notes"))
        assertTrue(said.contains("never has PINs"))
        assertTrue(said.contains("you choose where the file goes"))
        assertTrue(FailureCore.describeExport(listOf(case(1))).startsWith("I have 1 note "))
    }

    // ---------------------------------------------------------------- the words
    @Test fun voiceCommandsAreRecognised() {
        assertEquals(FailureParse.Cmd.ON, FailureParse.classify("learn from your mistakes"))
        assertEquals(FailureParse.Cmd.ON, FailureParse.classify("Nexus, start learning from your mistakes."))
        assertEquals(FailureParse.Cmd.ON, FailureParse.classify("turn on learning from mistakes"))
        assertEquals(FailureParse.Cmd.OFF, FailureParse.classify("stop learning from your mistakes"))
        assertEquals(FailureParse.Cmd.OFF, FailureParse.classify("please don't learn from your mistakes"))
        assertEquals(FailureParse.Cmd.FORGET, FailureParse.classify("forget my mistakes"))
        assertEquals(FailureParse.Cmd.FORGET, FailureParse.classify("delete all your mistakes"))
        assertEquals(FailureParse.Cmd.SHARE, FailureParse.classify("share my mistakes for training"))
        assertEquals(FailureParse.Cmd.SHARE, FailureParse.classify("export my mistakes"))
        assertEquals(FailureParse.Cmd.STATUS, FailureParse.classify("what mistakes have you kept"))
        assertEquals(FailureParse.Cmd.STATUS, FailureParse.classify("how many mistakes have you noted"))
    }

    @Test fun ordinaryRequestsAboutMistakesAreNotCommands() {
        assertNull(FailureParse.classify("I made a mistake"))
        assertNull(FailureParse.classify("send Kofi a message about my mistakes"))
        assertNull(FailureParse.classify("tell Kofi I made a mistake"))
        assertNull(FailureParse.classify("open settings"))
        assertNull(FailureParse.classify(""))
    }

    @Test fun aCorrectionAfterATaskIsRecognised() {
        for (s in listOf("No, that's wrong.", "that's not what I asked", "That is not what I asked for!", "you got it wrong", "wrong",
            "no I said call Kofi", "I didn't ask for that", "no that's not what I meant, call Kofi", "oh no that's the wrong person"))
            assertTrue(s, FailureParse.isCorrection(s))
        for (s in listOf("call Kofi", "no", "that's great", "what's wrong with my phone", "that is correct", "", "wrong " + "word ".repeat(20)))
            assertFalse(s, FailureParse.isCorrection(s))
    }

    @Test fun theSettingNameIsLearnFromMistakes() {
        // The Settings property the UI switch binds to: see settings/Settings.kt (default OFF).
        assertNotNull(com.tekyerema.settings.Settings::class.java.getMethod("getLearnFromMistakes"))
    }
}
