package com.tekyerema

import com.tekyerema.audit.AuditChain
import com.tekyerema.audit.AuditEntry
import com.tekyerema.audit.AuditExport
import com.tekyerema.audit.AuditHooks
import com.tekyerema.audit.AuditLog
import com.tekyerema.audit.AuditView
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import com.tekyerema.history.HistoryEntry
import com.tekyerema.history.Outcome
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.policy.PolicyDecision
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.defaultTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

/** WS-C: the structured, hash-chained activity log — what each entry records, that edits show, and the export. */
class AuditTrailTest {
    private lateinit var dir: File
    private var now = 1_800_000_000_000L
    private val tools by lazy { defaultTools().associateBy { it.name } }

    @Before fun setUp() {
        AppConfig.loadFrom(emptyList())
        OperatorPolicy.resetForTest()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SecureStore.keyProvider = { key }
        dir = Files.createTempDirectory("audit").toFile()
        AuditLog.clock = { now }
        AuditLog.initDir(dir)
    }

    @After fun tearDown() {
        AuditLog.initDir(null)
        AuditLog.clock = { System.currentTimeMillis() }
        SecureStore.keyProvider = null
        OperatorPolicy.resetForTest()
        AppConfig.loadFrom(emptyList())
        dir.deleteRecursively()
    }

    private fun add(kind: String, text: String, task: Long = 1, step: Int = 0): AuditEntry {
        now += 1000
        return AuditLog.append(AuditEntry(0, task, "agent", kind, text, step = step))!!
    }

    @Test fun each_entry_links_to_the_one_before_it() {
        val a = add(AuditLog.PROPOSED, "text Ama", step = 1)
        val b = add(AuditLog.DONE, "texted Ama", step = 1)
        assertEquals(AuditChain.GENESIS, a.prev)
        assertEquals(a.hash, b.prev)
        assertEquals(64, b.hash.length)
        assertTrue(a.nonce.isNotEmpty())
        val c = AuditLog.verify()
        assertTrue(c.intact); assertEquals(2, c.checked); assertEquals(b.hash, c.head)
    }

    @Test fun verify_detects_an_edited_entry() {
        repeat(5) { add(AuditLog.DONE, "did thing $it") }
        AuditLog.tamperForTest(2) { it.copy(text = "did something else") }
        val c = AuditLog.verify()
        assertFalse(c.ok); assertEquals(2, c.firstBad); assertTrue(c.problem!!.contains("changed"))
        AuditLog.initDir(dir)   // reload the honest stored copy
        assertTrue(AuditLog.verify().intact)
        AuditLog.tamperForTest(4) { it.copy(approved = true) }        // any field counts, not only the words
        assertEquals(4, AuditLog.verify().firstBad)
        AuditLog.initDir(dir)
        AuditLog.tamperForTest(1) { it.copy(reason = "allowed by the user") }
        assertEquals(1, AuditLog.verify().firstBad)
    }

    @Test fun verify_detects_removed_and_reordered_entries() {
        repeat(4) { add(AuditLog.DONE, "step $it") }
        val all = AuditLog.all()
        assertTrue(AuditChain.verify(all, AuditChain.GENESIS).ok)
        val removed = all.filterIndexed { i, _ -> i != 1 }
        val r = AuditChain.verify(removed, AuditChain.GENESIS)
        assertFalse(r.ok); assertEquals(1, r.firstBad); assertTrue(r.problem!!.contains("removed"))
        assertFalse(AuditChain.verify(listOf(all[0], all[2], all[1], all[3]), AuditChain.GENESIS).ok)
        assertFalse(AuditChain.verify(all.drop(1), AuditChain.GENESIS).ok)   // the first entry cut off
    }

    @Test fun the_log_is_stored_encrypted_and_reloads_intact() {
        add(AuditLog.PROPOSED, "text Ama", step = 1)
        add(AuditLog.DONE, "texted Ama", step = 1)
        val file = File(dir, "audit.log")
        assertTrue(file.exists())
        assertFalse(file.readText().contains("Ama"))          // never in the clear
        AuditLog.initDir(dir)
        assertEquals(2, AuditLog.count())
        assertTrue(AuditLog.verify().intact)
        assertEquals("texted Ama", AuditLog.all()[1].text)
    }

    @Test fun a_line_removed_from_the_stored_file_is_detected() {
        repeat(4) { add(AuditLog.DONE, "step $it") }
        val file = File(dir, "audit.log")
        val lines = file.readLines()
        file.writeText(lines.filterIndexed { i, _ -> i != 2 }.joinToString("\n", postfix = "\n"))
        AuditLog.initDir(dir)
        val c = AuditLog.verify()
        assertFalse(c.ok); assertEquals(2, c.firstBad)
    }

    @Test fun a_line_edited_in_the_stored_file_is_detected() {
        repeat(3) { add(AuditLog.DONE, "step $it") }
        val file = File(dir, "audit.log")
        val lines = file.readLines().toMutableList()
        val raw = java.util.Base64.getDecoder().decode(lines[1])
        raw[raw.size - 5] = (raw[raw.size - 5].toInt() xor 0x01).toByte()   // flip a bit of the ciphertext
        lines[1] = java.util.Base64.getEncoder().encodeToString(raw)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        AuditLog.initDir(dir)
        val c = AuditLog.verify()
        assertFalse(c.intact)
        assertEquals(1, c.unreadable)
        assertFalse(c.ok)                                                     // …and the chain is broken where it was
    }

    @Test fun the_retention_limit_keeps_the_chain_verifiable_through_its_anchor() {
        AppConfig.loadFrom(listOf("config/privacy.json" to """{"privacy":{"retention":{"historyDays":1}}}"""))
        repeat(3) { add(AuditLog.DONE, "old $it") }
        now += 2 * 86_400_000L
        repeat(2) { add(AuditLog.DONE, "new $it") }
        AuditLog.initDir(dir)                          // retention is applied when the log is loaded
        assertEquals(listOf("new 0", "new 1"), AuditLog.all().map { it.text })
        assertNotEquals(AuditChain.GENESIS, AuditLog.anchor())
        assertTrue(AuditLog.verify().intact)
        AuditLog.initDir(dir)                          // the anchor is stored with the log
        assertTrue(AuditLog.verify().intact)
        assertEquals(2, AuditLog.count())
    }

    @Test fun the_size_limit_trims_in_batches_and_stays_verifiable() {
        AppConfig.loadFrom(listOf("config/privacy.json" to """{"audit":{"max":50}}"""))
        repeat(99) { add(AuditLog.DONE, "e$it") }
        assertEquals(99, AuditLog.count())             // below max + batch: not rewritten yet
        add(AuditLog.DONE, "e99")
        assertEquals(50, AuditLog.count())
        assertEquals("e50", AuditLog.all().first().text)
        assertTrue(AuditLog.verify().intact)
        AuditLog.initDir(dir)
        assertTrue(AuditLog.verify().intact)
        assertEquals(50, AuditLog.count())
    }

    @Test fun clearing_restarts_the_chain_with_a_visible_marker() {
        repeat(3) { add(AuditLog.DONE, "x$it") }
        AuditLog.clear()
        val all = AuditLog.all()
        assertEquals(1, all.size)
        assertEquals(AuditLog.CLEARED, all[0].kind)
        assertTrue(AuditLog.verify().intact)
        assertTrue(AuditLog.lastTask().isEmpty())
        assertEquals("I haven't done anything yet.", AuditLog.spokenLast(AuditLog.lastTask()))
    }

    @Test fun the_v1_log_is_migrated_into_the_chain_once() {
        val legacy = JSONArray().put(JSONObject().put("t", now - 60_000L).put("task", 5L).put("src", "agent").put("kind", "done").put("text", "texted Ama"))
        assertTrue(SecureStore.write(File(dir, "audit.enc"), legacy.toString()))
        AuditLog.initDir(dir)
        val all = AuditLog.all()
        assertEquals(1, all.size); assertEquals("texted Ama", all[0].text); assertEquals("migrated", all[0].outcome)
        assertTrue(AuditLog.verify().intact)
        assertFalse(File(dir, "audit.enc").exists())
        AuditLog.initDir(dir)
        assertEquals(1, AuditLog.count())
    }

    @Test fun a_step_records_proposal_policy_model_confirmation_outcome_and_check() {
        OperatorPolicy.noteModelCall("groq/openai/gpt-oss-120b", 900)
        val sms = tools.getValue("send_sms")
        val args = JSONObject().put("contact", "Ama").put("message", "running late")
        val pd = PolicyDecision(PolicyOutcome.CONFIRM, "Meaningful external action requires confirmation.", "Send?", tier = RiskTier.TIER2)
        AuditHooks.taskStarted("agent", 42, "text Ama that I'm running late", "Hybrid on-device + cloud:Groq")
        val step = AuditHooks.proposed("agent", 42, 3, sms, args, pd, "", "calling the tool", null)!!
        step.approved(7, "Send “running late” to Ama?")
        step.executed(true)
        step.verified(true, "message shown as sent")
        AuditHooks.taskEnded("agent", 42, "COMPLETED")

        val es = AuditLog.all().filter { it.task == 42L }
        assertEquals(listOf(AuditLog.TASK_START, AuditLog.PROPOSED, AuditLog.APPROVED, AuditLog.DONE, AuditLog.VERIFIED, AuditLog.TASK_END),
            es.map { it.kind })
        val p = es[1]
        assertEquals("send_sms", p.tool); assertEquals("TIER2", p.tier); assertEquals("COMMS_SEND", p.scope)
        assertEquals("CONFIRM", p.policy); assertTrue(p.reason.startsWith("Meaningful external action"))
        assertEquals("groq/openai/gpt-oss-120b", p.decidedBy); assertEquals(3, p.step)
        assertEquals("text Ama", p.text)                              // the plan, never the message itself
        assertEquals(7L, es[2].confirmSeq); assertEquals(true, es[2].approved)
        assertEquals("texted Ama", es[3].text)
        assertEquals(true, es[4].verified)
        assertEquals("COMPLETED", es[5].outcome)
        assertTrue(AuditLog.verify().intact)
        assertEquals("I texted Ama.", AuditLog.spokenLast(AuditLog.lastTask()))   // "what did you just do?" unchanged

        val card = AuditView.group(AuditLog.all()).first { it.task == 42L }
        assertEquals("text Ama that I'm running late", card.title)
        assertEquals("Finished and checked", card.status)
        val line = card.steps.single()
        assertEquals("You said yes to question #7", line.answer)
        assertEquals("done", line.outcome); assertEquals(true, line.verified)
        assertEquals("Text Ama · asked you first · You said yes to question #7 · done · checked on screen", line.sentence())
    }

    @Test fun declined_blocked_and_not_run_steps_read_honestly() {
        val tap = tools.getValue("tap")
        val confirm = PolicyDecision(PolicyOutcome.CONFIRM, "r", tier = RiskTier.TIER2)
        AuditHooks.proposed("agent", 9, 1, tap, JSONObject().put("label", "Send"), confirm, "text=untrusted(copied from screen)", null, "Send")!!
            .declined(4, "Send this message to Ama?")
        AuditHooks.proposed("agent", 9, 2, tap, JSONObject(), PolicyDecision(PolicyOutcome.BLOCK, "Guide-only mode", operatorRule = "mode:GUIDE_ONLY"),
            "", "local:rule", "OK")!!.blocked()
        AuditHooks.proposed("agent", 9, 3, tap, JSONObject(), PolicyDecision(PolicyOutcome.ALLOW, "Low-risk action.", tier = RiskTier.TIER1),
            "", null, "Next")!!.notRun("the screen changed before it ran")
        assertNull(AuditHooks.proposed("agent", 9, 4, tools.getValue("read_state"), JSONObject(),
            PolicyDecision(PolicyOutcome.ALLOW, "Low-risk action."), "", null, null))   // pure reads aren't recorded
        val steps = AuditView.group(AuditLog.all()).single().steps
        assertEquals("You said no to question #4", steps[0].answer); assertEquals("not done", steps[0].outcome)
        assertEquals("text=untrusted(copied from screen)", steps[0].provenance)
        assertEquals("blocked", steps[1].policy); assertEquals("blocked", steps[1].outcome)
        assertEquals("on-device rules", steps[1].decidedBy)
        assertTrue(steps[1].reason.contains("mode:GUIDE_ONLY"))
        assertEquals("not run", steps[2].outcome); assertEquals("the screen changed before it ran", steps[2].reason)
        assertEquals("You said no to: Send this message to Ama — so I didn't. I tried, but it didn't work: tap “OK”.",
            AuditLog.spokenLast(AuditLog.lastTask()))
    }

    @Test fun fast_path_and_setting_changes_are_in_the_log_too() {
        AuditLog.record("fast", AuditLog.DONE, "turned the volume up", 100)
        now += 1000
        AuditLog.record("user", AuditLog.SETTING, "switched the cloud off", 101)
        val all = AuditLog.all()
        assertEquals("ALLOW", all[0].policy); assertEquals("on-device fast path", all[0].decidedBy)
        assertEquals("I switched the cloud off.", AuditLog.spokenLast(AuditLog.lastTask()))
        val cards = AuditView.group(all)
        assertEquals("switched the cloud off", cards[0].title); assertEquals("Your change", AuditView.sourceLabel(cards[0].source))
    }

    // ------------------------------------------------------------------ export

    private fun sampleLog() {
        OperatorPolicy.noteModelCall("groq/openai/gpt-oss-120b", 900)
        AuditHooks.taskStarted("agent", 7, "text Ama that the code is 4471", "cloud")
        val step = AuditHooks.proposed("agent", 7, 1, tools.getValue("send_sms"),
            JSONObject().put("contact", "Ama").put("message", "the code is 4471"),
            PolicyDecision(PolicyOutcome.CONFIRM, "target 'Pay GH₵ 50 to Kwame' spends money", tier = RiskTier.TIER3), "", null, null)!!
        step.approved(2, "Send “the code is 4471” to Ama?")
        step.executed(true)
        AuditHooks.taskEnded("agent", 7, "COMPLETED")
    }

    private val history = listOf(HistoryEntry(1_800_000_000_000L, "Texted Ama that the code is 4471", Outcome.COMPLETED))

    @Test fun a_redacted_export_leaves_out_the_users_words_and_still_verifies() {
        sampleLog()
        val doc = AuditExport.build(AuditLog.all(), AuditLog.anchor(), history, includeText = false, OperatorPolicy.toJson(), now)
        val s = doc.toString()
        for (secret in listOf("code is", "Kwame", "that the code")) assertFalse(secret, s.contains(secret))
        // Digits are checked only in the word fields (hex digests may contain any digits by chance).
        val words = (0 until doc.getJSONObject("audit").getJSONArray("entries").length()).flatMap { i ->
            doc.getJSONObject("audit").getJSONArray("entries").getJSONObject(i).let { listOf(it.optString("text"), it.optString("reason")) }
        } + doc.getJSONArray("history").getJSONObject(0).getString("title")
        assertTrue(words.toString(), words.none { it.contains("4471") || it.contains("50") })
        assertFalse(s.contains("\"nonce\""))
        assertEquals(false, doc.getBoolean("includesMessageText"))
        val audit = doc.getJSONObject("audit")
        assertTrue(audit.getJSONObject("chain").getBoolean("verifiedOnExport"))
        assertTrue(AuditChain.verifyExport(audit).ok)
        val entries = audit.getJSONArray("entries")
        assertTrue(entries.getJSONObject(0).getString("text").matches(Regex("\\[\\d+ chars]")))   // the user's own words: length only
        assertEquals("send_sms", entries.getJSONObject(1).getString("tool"))                   // structure stays
        assertEquals("target “…” spends money", entries.getJSONObject(1).getString("reason"))
        assertEquals("text Ama", entries.getJSONObject(1).getString("text"))                   // who, never what
        val h = doc.getJSONArray("history").getJSONObject(0)
        assertEquals("message", h.getString("kind")); assertTrue(h.getString("title").startsWith("["))
        assertEquals("STANDARD", doc.getJSONObject("settings").getString("executionMode"))
    }

    @Test fun a_full_export_includes_the_words_and_their_digests_match() {
        sampleLog()
        val doc = AuditExport.build(AuditLog.all(), AuditLog.anchor(), history, includeText = true, null, now)
        assertTrue(doc.toString().contains("the code is 4471"))
        assertTrue(AuditChain.verifyExport(doc.getJSONObject("audit")).ok)
    }

    @Test fun an_edited_export_fails_verification() {
        sampleLog()
        fun fresh(include: Boolean) = AuditExport.build(AuditLog.all(), AuditLog.anchor(), history, include, null, now).getJSONObject("audit")
        // a field changed
        var a = fresh(false); a.getJSONArray("entries").getJSONObject(2).put("approved", false)
        assertEquals(2, AuditChain.verifyExport(a).firstBad)
        // the words changed where they were exported
        a = fresh(true); a.getJSONArray("entries").getJSONObject(1).put("text", "call Kofi")
        assertFalse(AuditChain.verifyExport(a).ok)
        // an entry deleted
        a = fresh(false); a.getJSONArray("entries").remove(1)
        assertEquals(1, AuditChain.verifyExport(a).firstBad)
        // entries swapped
        a = fresh(false)
        val arr = a.getJSONArray("entries"); val one = arr.getJSONObject(1); arr.put(1, arr.getJSONObject(2)); arr.put(2, one)
        assertFalse(AuditChain.verifyExport(a).ok)
    }

    @Test fun redaction_rules() {
        assertEquals("tapped “…”", AuditExport.redact("tapped “Send to Ama”"))
        assertEquals("send “…” to #", AuditExport.redact("send 'hi there' to 024 123 4567"))
        assertEquals("I don't know", AuditExport.redact("I don't know"))
        assertEquals("mail …@…", AuditExport.redact("mail ama.mensah@example.com"))
        assertEquals("[12 chars]", AuditExport.redactEntryText(AuditLog.TASK_START, "call my mum"  + "!"))
        assertEquals("called Ama", AuditExport.redactEntryText(AuditLog.DONE, "called Ama"))
    }
}
