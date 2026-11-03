package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.pocket.PocketDecoder
import com.tekyerema.agent.pocket.PocketOutputs
import com.tekyerema.agent.pocket.PocketSpec
import com.tekyerema.agent.pocket.PocketTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pocket brain without ONNX: spec parsing, the tokenizer's offsets, and logits -> decision (agent/pocket). */
class PocketBrainTest {
    private val specJson = """
    {"version":"t","tools":["send_sms","make_call","momo_send","set_timer","web_search","_none"],
     "statuses":["ready","missing_slot","ambiguous","unsafe","no_tool_fits"],
     "missing_slots":["amount","contact","message","time","place","app","query"],
     "risk_tools":["make_call","momo_send","send_sms"],
     "slot_keys":[
       {"tool":"send_sms","arg":"contact","mode":"span","slice":[0,2],"classes":["ABSENT","SPAN"]},
       {"tool":"send_sms","arg":"message","mode":"span","slice":[2,4],"classes":["ABSENT","SPAN"]},
       {"tool":"make_call","arg":"contact","mode":"span","slice":[4,6],"classes":["ABSENT","SPAN"]},
       {"tool":"momo_send","arg":"amount","mode":"span","slice":[6,9],"classes":["ABSENT","SPAN","50"]},
       {"tool":"momo_send","arg":"contact","mode":"span","slice":[9,11],"classes":["ABSENT","SPAN"]},
       {"tool":"set_timer","arg":"seconds","mode":"categorical","slice":[11,14],"classes":["ABSENT","SPAN",300]}],
     "span_arg_names":["amount","contact","message"],
     "ignored_keys":["web_search.query"],
     "temperatures":{"tool":1.0,"status":1.0},
     "policy":{"variant":"msp","tau_risk":0.84,"tau_other":0.0},
     "max_len":16,"pad_token_id":0,"cls_token_id":101,"sep_token_id":102,
     "input_format":"tokenizer(request, text_pair=..., truncation=only_second, max_length=16)",
     "tokenizer":{"do_lower_case":true,"request_char_cap":600,"context_lines":2,"context_char_cap":300}}
    """.trimIndent()

    private val spec = PocketSpec.parse(specJson)
    private val vocab: Map<String, Int> = listOf("[PAD]", "text", "mum", "i", "am", "late", "send", "50", "cedis", "to", "money", "-", "|", "user", ":")
        .withIndex().associate { (i, t) -> t to i + 1 } + mapOf("[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102)
    private val tok = PocketTokenizer(vocab, spec)
    private val dec = PocketDecoder(spec)

    // ---- crafting logits -------------------------------------------------------------------------------------------

    private fun peak(n: Int, at: Int, v: Float = 9f) = FloatArray(n).also { it[at] = v }

    /** [tool] and [status] by name; [slots] = slot-vector index -> class; [tags] = token -> (arg name -> label 1 B / 2 I). */
    private fun outs(
        tool: String, status: String = "ready", missing: String = "query", statusStrength: Float = 9f, toolStrength: Float = 9f,
        slots: Map<Int, Int> = emptyMap(), tags: Map<Int, Pair<String, Int>> = emptyMap(), tokens: Int = 9
    ): PocketOutputs {
        val sl = FloatArray(14)
        // every slot head defaults to ABSENT (class 0) unless set
        for ((from, to) in listOf(0 to 2, 2 to 4, 4 to 6, 6 to 9, 9 to 11, 11 to 14)) sl[from] = 5f
        for ((from, c) in slots) { val to = listOf(2, 4, 6, 9, 11, 14).first { it > from }; for (i in from until to) sl[i] = 0f; sl[from + c] = 5f }
        val tag = Array(tokens) { FloatArray(9).also { f -> for (a in 0 until 3) f[a * 3] = 5f } }
        for ((j, al) in tags) { val a = spec.spanArgNames.indexOf(al.first); tag[j][a * 3] = 0f; tag[j][a * 3 + al.second] = 5f }
        return PocketOutputs(peak(spec.tools.size, spec.tools.indexOf(tool), toolStrength), peak(5, spec.statuses.indexOf(status), statusStrength),
            peak(spec.missingSlots.size, spec.missingSlots.indexOf(missing)), sl, tag)
    }

    private fun decide(request: String, o: PocketOutputs, context: List<String> = emptyList()) =
        dec.decode(request, tok.encode(request, tok.contextText(context)), o)

    private fun question(d: AgentDecision) = (d as? AgentDecision.CallTool)?.takeIf { it.name == "ask_user" }?.args?.optString("question")

    // ---- spec & tokenizer ------------------------------------------------------------------------------------------

    @Test fun the_spec_is_read() {
        assertEquals(listOf("send_sms", "make_call", "momo_send", "set_timer", "web_search", "_none"), spec.tools)
        assertEquals(0.84f, spec.tauRisk, 1e-6f); assertEquals("only_second", spec.truncation)
        assertTrue(spec.isRisk("momo_send")); assertFalse(spec.isRisk("set_timer"))
        val k = spec.keysOf("set_timer").single()
        assertEquals(11, k.from); assertEquals(14, k.to); assertEquals(300, k.classes[2])
        assertTrue("web_search.query" in spec.ignoredKeys)
    }

    @Test fun the_tokenizer_pairs_request_and_context_with_offsets() {
        val e = tok.encode("Text MUM, I am late", tok.contextText(emptyList()))
        // [CLS] text mum , i am late [SEP] - [SEP]   ("," is not in the test vocab -> [UNK])
        assertEquals(listOf(101, 2, 3, 100, 4, 5, 6, 102, 12, 102), e.ids.toList())
        assertEquals(listOf(-1, 0, 0, 0, 0, 0, 0, -1, 1, -1), e.seq.toList())
        assertEquals("MUM", "Text MUM, I am late".substring(e.start(2), e.end(2)))
        assertEquals("late", "Text MUM, I am late".substring(e.start(6), e.end(6)))
        assertEquals("-", tok.contextText(emptyList()))
        assertEquals("User: a | Nexus: b", tok.contextText(listOf("zz", "User: a", "Nexus: b")))
    }

    @Test fun truncation_only_second_keeps_the_whole_request() {
        val e = tok.encode("text mum i am late", "user : " + "text ".repeat(40))
        assertEquals(16, e.size)
        assertEquals(5, e.seq.count { it == 0 })
    }

    // ---- decoder ---------------------------------------------------------------------------------------------------

    @Test fun ready_gives_the_tool_with_arguments_cut_from_the_request() {
        val req = "text mum i am late"   // tokens: 1 text, 2 mum, 3 i, 4 am, 5 late
        val r = decide(req, outs("send_sms", slots = mapOf(0 to 1, 2 to 1),
            tags = mapOf(2 to ("contact" to 1), 3 to ("message" to 1), 4 to ("message" to 2), 5 to ("message" to 2))))
        val c = r.decision as AgentDecision.CallTool
        assertEquals("send_sms", c.name); assertEquals("mum", c.args.getString("contact")); assertEquals("i am late", c.args.getString("message"))
        assertEquals("ready", r.status); assertTrue(r.p > 0.9f)
    }

    @Test fun a_constant_class_fills_a_categorical_argument() {
        val c = decide("text mum", outs("set_timer", slots = mapOf(11 to 2))).decision as AgentDecision.CallTool
        assertEquals("set_timer", c.name); assertEquals(300, c.args.getInt("seconds"))
    }

    @Test fun web_search_carries_the_request_itself() {
        val c = decide("who is mum", outs("web_search")).decision as AgentDecision.CallTool
        assertEquals("who is mum", c.args.getString("query"))
    }

    @Test fun a_missing_slot_status_asks_the_right_question() {
        assertEquals("What should the message say?", question(decide("text mum", outs("send_sms", "missing_slot", "message")).decision))
        assertEquals("How much?", question(decide("send money to mum", outs("momo_send", "missing_slot", "amount")).decision))
        assertEquals("Who should I call?", question(decide("call", outs("make_call", "missing_slot", "contact")).decision))
        assertEquals("What time?", question(decide("remind me", outs("web_search", "missing_slot", "time")).decision))
    }

    @Test fun a_required_argument_the_heads_did_not_find_is_asked_for_not_guessed() {
        // ready send_sms, contact cut, message ABSENT
        val r = decide("text mum", outs("send_sms", slots = mapOf(0 to 1), tags = mapOf(2 to ("contact" to 1))))
        assertEquals("What should the message say?", question(r.decision))
    }

    @Test fun below_the_risk_threshold_it_asks_and_never_acts() {
        // P(ready) about 0.65 < tau_risk 0.84 for a risk tool
        val r = decide("text mum i am late", outs("send_sms", statusStrength = 2f, slots = mapOf(0 to 1, 2 to 1),
            tags = mapOf(2 to ("contact" to 1), 3 to ("message" to 1))))
        assertEquals("Did you mean to send a message?", question(r.decision))
        assertTrue(r.p < 0.84f)
    }

    @Test fun a_non_risk_tool_with_the_same_confidence_acts() {
        val r = decide("who is mum", outs("web_search", statusStrength = 2f))
        assertEquals("web_search", (r.decision as AgentDecision.CallTool).name)
    }

    @Test fun ambiguous_offers_the_top_two_tools() {
        val o = outs("send_sms", "ambiguous")
        o.tool[1] = 8.5f   // make_call is a close second
        assertEquals("Did you mean to send a message, or to make a call?", question(decide("mum", o).decision))
    }

    @Test fun unsafe_and_no_tool_fits_are_honest_refusals() {
        val u = decide("do the thing", outs("_none", "unsafe")).decision as AgentDecision.CannotDo
        assertEquals(PocketDecoder.UNSAFE, u.reasonClass)
        val n = decide("fly me to the moon", outs("_none", "no_tool_fits")).decision as AgentDecision.CannotDo
        assertEquals(PocketDecoder.NO_TOOL, n.reasonClass); assertTrue(n.detail.isNotBlank())
    }

    // ---- money -----------------------------------------------------------------------------------------------------

    @Test fun money_with_the_amount_and_recipient_in_the_request_is_proposed() {
        val req = "send 50 cedis to mum"   // 1 send 2 50 3 cedis 4 to 5 mum
        val r = decide(req, outs("momo_send", slots = mapOf(6 to 1, 9 to 1), tags = mapOf(2 to ("amount" to 1), 5 to ("contact" to 1))))
        val c = r.decision as AgentDecision.CallTool
        assertEquals("momo_send", c.name); assertEquals("50", c.args.getString("amount")); assertEquals("mum", c.args.getString("contact"))
    }

    @Test fun money_never_uses_an_amount_the_request_does_not_say() {
        // the head picks the memorised constant "50" but the request has no figure
        val r = decide("send money to mum", outs("momo_send", slots = mapOf(6 to 2, 9 to 1), tags = mapOf(4 to ("contact" to 1))))
        assertEquals("How much?", question(r.decision))
    }

    @Test fun money_never_guesses_the_recipient() {
        val r = decide("send 50 cedis", outs("momo_send", slots = mapOf(6 to 1), tags = mapOf(2 to ("amount" to 1))))
        assertEquals("Who should I send the money to?", question(r.decision))
        // a recipient that is not in the request is dropped too
        val o = outs("momo_send", slots = mapOf(6 to 1, 9 to 1), tags = mapOf(2 to ("amount" to 1)))
        assertEquals("Who should I send the money to?", question(decide("send 50 cedis", o).decision))
    }

    @Test fun money_in_words_is_asked_not_converted() {
        // a span with no figure ("fifty") is not an amount the money flow can trust
        val r = decide("send money to mum", outs("momo_send", slots = mapOf(6 to 1, 9 to 1), tags = mapOf(2 to ("amount" to 1), 4 to ("contact" to 1))))
        assertNotNull(question(r.decision)); assertEquals("How much?", question(r.decision))
    }

    @Test fun a_learned_value_gives_way_to_the_one_the_user_said() {
        // A20e, 3 Nov 2026: "anything new on WhatsApp" opened Messages (a value learned in training, not in the words).
        val sp = PocketSpec.parse(specJson.replace("\"tools\":[\"send_sms\"", "\"tools\":[\"open_app\",\"send_sms\"")
            .replace("\"slot_keys\":[", "\"slot_keys\":[{\"tool\":\"open_app\",\"arg\":\"app\",\"mode\":\"categorical\",\"slice\":[14,18],\"classes\":[\"ABSENT\",\"SPAN\",\"Messages\",\"WhatsApp\"]},"))
        val d = PocketDecoder(sp)
        val slot = FloatArray(18).also { for (f in listOf(0, 2, 4, 6, 9, 11)) it[f] = 5f; it[16] = 9f }   // open_app.app → "Messages"
        val o = PocketOutputs(peak(sp.tools.size, sp.tools.indexOf("open_app")), peak(5, 0), peak(sp.missingSlots.size, 0), slot,
            Array(12) { FloatArray(9).also { f -> for (a in 0 until 3) f[a * 3] = 5f } })
        val c = d.decode("anything new on whatsapp", PocketTokenizer(vocab, sp).encode("anything new on whatsapp", "-"), o).decision as AgentDecision.CallTool
        assertEquals("WhatsApp", c.args.getString("app"))
    }
}
