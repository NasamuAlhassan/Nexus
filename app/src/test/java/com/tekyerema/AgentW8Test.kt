package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.audit.ActionWords
import com.tekyerema.audit.AuditEntry
import com.tekyerema.audit.AuditLog
import com.tekyerema.audit.DeviceSnap
import com.tekyerema.audit.DeviceState
import com.tekyerema.audit.Inverse
import com.tekyerema.audit.UndoGroup
import com.tekyerema.audit.UndoLog
import com.tekyerema.core.SecureStore
import com.tekyerema.core.TraceRedact
import com.tekyerema.core.telemetry.LlmUsage
import com.tekyerema.core.telemetry.Resources
import com.tekyerema.core.telemetry.Telemetry
import com.tekyerema.history.HistoryStore
import com.tekyerema.history.Outcome
import com.tekyerema.notifications.IndexedMessage
import com.tekyerema.notifications.MessageIndex
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/** W8: telemetry (Q-01..Q-10, N-02, N-03), audit + undo (J-12/J-13), retention + redaction + encryption (J-15),
 *  memory controls (H-12) and the message index (H-09). */
class AgentW8Test {

    @After fun reset() { SecureStore.keyProvider = null; UndoLog.clear() }

    // ---------------------------------------------------------------- J-15
    @Test fun encrypted_store_round_trips_and_rejects_foreign_data() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SecureStore.keyProvider = { key }
        val f = java.io.File.createTempFile("tky", ".enc").apply { deleteOnExit() }
        assertTrue(SecureStore.write(f, "Ama: see you at 5"))
        assertFalse("ciphertext, not the words", String(f.readBytes(), Charsets.ISO_8859_1).contains("Ama"))
        assertEquals("Ama: see you at 5", SecureStore.read(f))
        f.writeText("plain text an older build wrote")
        assertNull(SecureStore.read(f))
        SecureStore.keyProvider = { null }
        assertFalse("no key → nothing written in the clear", SecureStore.write(f, "secret"))
    }

    @Test fun trace_lines_keep_the_shape_but_not_the_person() {
        val mark = "[untrusted]"
        val plan = TraceRedact.line("PLAN", "Step 3: send_sms({\"contact\":\"Ama\",\"message\":\"meet me at 5\"}) expects[...]", mark)
        assertTrue(plan, plan.contains("send_sms"))
        assertFalse(plan.contains("Ama")); assertFalse(plan.contains("meet me"))
        val act = TraceRedact.line("ACTION", "make_call → ok [intent]: Calling 0244123456", mark)
        assertFalse(act.contains("0244123456")); assertTrue(act.contains("make_call"))
        assertEquals("[12 chars]", TraceRedact.line("USER", "call Ama now", mark))
        val hist = TraceRedact.line("INFO", "read_sms → ok $mark Kofi: the code is here", mark)
        assertFalse(hist.contains("Kofi")); assertTrue(hist.contains("third-party text removed"))
    }

    @Test fun history_rows_from_older_builds_still_parse() {
        val rows = HistoryStore.parse("""[{"t":1,"title":"Called Ama","outcome":"COMPLETED"},{"t":2,"title":"Open X","ok":false}]""")
        assertEquals(2, rows.size)
        assertEquals(Outcome.COMPLETED, rows[0].outcome)
        assertEquals(Outcome.FAILED, rows[1].outcome)
        assertTrue(HistoryStore.parse("not json").isEmpty())
    }

    // ---------------------------------------------------------------- Q-01 / N-02 / Q-02..Q-04 / N-03 / Q-10
    @Test fun stage_durations_and_budgets() {
        val marks = linkedMapOf("end_of_turn" to 3000L, "asr_final" to 5200L, "route" to 5250L, "first_decision" to 7000L,
            "action_done" to 9000L, "reply_start" to 9400L)
        val d = Telemetry.durations(marks)
        assertEquals(2200L, d["asr"]); assertEquals(50L, d["route"]); assertEquals(1750L, d["first_decision"])
        assertEquals(400L, d["tts_start"]); assertEquals(6400L, d["response"])
        val over = Telemetry.overBudget(d, Telemetry.DEFAULT_BUDGETS)
        assertTrue(over.containsAll(listOf("asr", "response")))
        assertFalse("route" in over)
        // a fast-path turn has no action_done: TTS start is measured from the route decision
        assertEquals(300L, Telemetry.durations(mapOf("route" to 100L, "reply_start" to 400L))["tts_start"])
    }

    @Test fun token_usage_from_both_providers() {
        val groq = LlmUsage.fromOpenAi("""{"usage":{"prompt_tokens":4000,"completion_tokens":120,"prompt_tokens_details":{"cached_tokens":3584}}}""")
        assertEquals(LlmUsage(4000, 120, 3584), groq)
        assertEquals(0, LlmUsage.fromOpenAi("""{"usage":{"prompt_tokens":10,"completion_tokens":2}}""")!!.cached)
        assertNull(LlmUsage.fromOpenAi("""{"choices":[]}"""))
        val gem = LlmUsage.fromGemini("""{"usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":40,"thoughtsTokenCount":60,"cachedContentTokenCount":512}}""")
        assertEquals(LlmUsage(900, 100, 512), gem)
    }

    @Test fun resource_deltas() {
        val a = Resources.Sample(1000, 10_240, 2048, 3_000_000, 900, false)
        val b = Resources.Sample(1600, 61_440, 4096, 2_998_500, 850, true)
        val d = Resources.delta(a, b)!!
        assertEquals(600L, d.getLong("cpuMs")); assertEquals(50L, d.getLong("rxKb")); assertEquals(2L, d.getLong("txKb"))
        assertEquals(1500L, d.getLong("batteryUah")); assertEquals(850L, d.getLong("availRamMb")); assertTrue(d.getBoolean("lowMemory"))
        assertNull(Resources.delta(null, b))
    }

    @Test fun summary_reports_tiers_success_and_fast_path_coverage() {
        fun rec(tier: String, grade: String, calls: Int, shots: Int = 0, over: Int = 0) = JSONObject()
            .put("tier", tier).put("grade", grade)
            .put("llm", JSONObject().put("calls", calls).put("prompt", 1000 * calls).put("completion", 50 * calls).put("cached", 500 * calls))
            .put("vision", JSONObject().put("screenshots", shots))
            .put("durations", JSONObject().put("response", 1000 + calls))
            .put("overBudget", org.json.JSONArray().apply { repeat(over) { put("asr") } })
        val s = Telemetry.summary(listOf(rec("fast_path", "full", 0), rec("fast_path", "full", 0), rec("cloud_operator", "partial", 4, shots = 2, over = 1),
            rec("cloud_operator", "failed", 6)))
        assertTrue(s, s.contains("turns=4"))
        assertTrue(s, s.contains("fast_path:50%"))
        assertTrue(s, s.contains("cheap(no planner, no screenshot, ≤1 model turn)=50%"))
        assertTrue(s, s.contains("verified success=50%"))
        assertTrue(s, s.contains("over budget=1"))
        assertEquals("No turns recorded yet.", Telemetry.summary(emptyList()))
    }

    // ---------------------------------------------------------------- J-12
    @Test fun actions_in_plain_words_never_repeat_the_message() {
        val sms = ActionWords.describe("send_sms", JSONObject().put("contact", "Ama").put("message", "secret plan"))
        assertEquals("texted Ama", sms)
        assertEquals("called 0 2 4 4 1 2 3 4 5 6", ActionWords.describe("make_call", JSONObject().put("number", "0244123456")))
        assertEquals("opened WhatsApp", ActionWords.describe("open_app", JSONObject().put("app", "WhatsApp")))
        assertEquals("tapped “Send”", ActionWords.describe("tap", JSONObject(), element = "Send"))
        assertEquals("typed some text", ActionWords.describe("type", JSONObject().put("text", "my PIN is")))
        assertEquals("the message to Ama", ActionWords.noun("send_sms", JSONObject().put("contact", "Ama")))
    }

    @Test fun what_did_you_just_do() {
        fun e(kind: String, text: String) = AuditEntry(1, 7, "agent", kind, text)
        assertEquals("I opened WhatsApp and texted Ama.",
            AuditLog.spokenLast(listOf(e(AuditLog.DONE, "opened WhatsApp"), e(AuditLog.DONE, "texted Ama"))))
        assertTrue(AuditLog.spokenLast(listOf(e(AuditLog.DECLINED, "Send 'hi' to Ama?"))).contains("You said no to: Send 'hi' to Ama"))
        assertEquals("I haven't done anything yet.", AuditLog.spokenLast(emptyList()))
        assertEquals("I only looked — I didn't change anything.", AuditLog.spokenLast(listOf(e("read", "x"))))
        val today = AuditLog.spokenToday(listOf(e(AuditLog.DONE, "a"), e(AuditLog.DONE, "b"), e(AuditLog.DONE, "c"), e(AuditLog.DONE, "d")))
        assertTrue(today, today.startsWith("Today I did 4 things. Most recently, I d, c and b."))
    }

    // ---------------------------------------------------------------- J-13
    @Test fun undo_reverses_only_what_changed_last_first() {
        val prefs = mapOf("speech_rate" to "1")
        val before = DeviceSnap(music = 5, ring = 3, ringer = 2, brightnessMode = 0, brightness = 120, torch = false, prefs = prefs)
        val after = before.copy(music = 9, torch = true, prefs = mapOf("speech_rate" to "0"))
        val inv = DeviceState.diff(before, after)
        assertTrue(inv.any { it is Inverse.Volume && it.level == 5 })
        assertTrue(inv.contains(Inverse.Torch(false)))
        assertTrue(inv.contains(Inverse.Prefs(prefs)))
        assertFalse(inv.any { it is Inverse.Brightness || it is Inverse.Ringer })
        assertTrue(DeviceState.diff(before, before).isEmpty())
        assertEquals("Okay, I put back the volume and the flashlight. I can't undo the message to Ama.",
            DeviceState.spoken(listOf(Inverse.Volume(3, 5), Inverse.Torch(false)), listOf(Inverse.Irreversible("the message to Ama"))))
        assertEquals("There's nothing I can undo.", DeviceState.spoken(emptyList(), emptyList()))
    }

    @Test fun undo_window() {
        UndoLog.push(UndoGroup(1_000, "old", listOf(Inverse.Torch(false))))
        UndoLog.push(UndoGroup(500_000, "new", listOf(Inverse.Torch(true))))
        assertEquals("new", UndoLog.pop(nowMs = 600_000, ttlMs = 600_000)?.label)
        assertNull("older than the window", UndoLog.pop(nowMs = 700_000, ttlMs = 600_000))
        UndoLog.push(UndoGroup(1, "empty", emptyList()))
        assertNull("an empty group is never stored", UndoLog.pop(nowMs = 2, ttlMs = 600_000))
    }

    // ---------------------------------------------------------------- H-09 / H-12 by voice
    @Test fun memory_audit_and_undo_by_voice() {
        fun c(s: String) = FastIntentClassifier.classify(s)
        assertEquals(FastIntent.Undo, c("Undo that"))
        assertEquals(FastIntent.Undo, c("take it back"))
        assertEquals(FastIntent.WhatDidYouDo(today = false), c("What did you just do?"))
        assertEquals(FastIntent.WhatDidYouDo(today = true), c("what have you done today"))
        assertEquals(FastIntent.MemoryQuery, c("What do you remember about me?"))
        assertEquals(FastIntent.Forget("last"), c("forget that"))
        assertEquals(FastIntent.Forget("all"), c("Forget everything"))
        assertEquals(FastIntent.Forget("all"), c("clear your memory"))
        assertEquals(FastIntent.Forget("person", "Ama"), c("forget Ama's messages"))
        assertEquals(FastIntent.Forget("person", "Kofi"), c("forget everything about Kofi"))
        assertEquals(FastIntent.ClearHistory, c("clear my history"))
    }

    @Test fun message_index_matches_by_sender_conversation_or_app() {
        val m = IndexedMessage(1, "com.whatsapp", "WhatsApp", "Ama", "Family group", "see you")
        assertTrue(MessageIndex.matches(m, "ama"))
        assertTrue(MessageIndex.matches(m, "family"))
        assertTrue(MessageIndex.matches(m, "WhatsApp"))
        assertFalse(MessageIndex.matches(m, "Kofi"))
        assertFalse(MessageIndex.matches(m, " "))
        assertNotNull(m)
    }
}
