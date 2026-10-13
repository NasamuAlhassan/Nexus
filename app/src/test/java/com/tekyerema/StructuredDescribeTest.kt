package com.tekyerema

import com.tekyerema.camera.GeminiVision
import com.tekyerema.camera.StructuredDescribe
import com.tekyerema.camera.StructuredDescribe.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-07: the describer's JSON contract, its abstentions, and the OCR divergence rule. */
class StructuredDescribeTest {
    @Test fun strict_json_is_read_and_a_fenced_or_wrapped_one_too() {
        val p = StructuredDescribe.parse("""{"description":"A red cup on a table.","visible_text":"","unanswerable_reason":null}""")
        assertTrue(p.structured); assertEquals("A red cup on a table.", p.description); assertNull(p.reason); assertEquals("", p.visibleText)
        val f = StructuredDescribe.parse("```json\n{\"description\": \"A bus ticket.\", \"visible_text\": \"GHS 20\", \"unanswerable_reason\": \"null\"}\n```")
        assertTrue(f.structured); assertEquals("GHS 20", f.visibleText); assertNull(f.reason)
        val w = StructuredDescribe.parse("Sure! {\"description\":\"A door.\",\"visible_text\":\"\",\"unanswerable_reason\":\"blur\"} hope this helps")
        assertEquals(Reason.BLUR, w.reason)
    }

    @Test fun prose_is_kept_as_prose() {
        val p = StructuredDescribe.parse("A television sits on a cabinet. The screen shows a checkerboard.")
        assertFalse(p.structured); assertNull(p.reason)
        assertEquals("A television sits on a cabinet. The screen shows a checkerboard.", StructuredDescribe.spoken(p, null))
    }

    @Test fun each_reason_has_its_own_honest_line_and_unknown_reasons_are_ignored() {
        for (r in Reason.entries) {
            val p = StructuredDescribe.parse("""{"description":"","visible_text":"","unanswerable_reason":"${r.name.lowercase()}"}""")
            assertEquals(r, p.reason); assertEquals(StructuredDescribe.abstention(r), StructuredDescribe.spoken(p, null))
        }
        assertNull(StructuredDescribe.parse("""{"description":"A cat.","visible_text":"","unanswerable_reason":"tired"}""").reason)
        // a framing abstention keeps a short description of what IS visible
        val fr = StructuredDescribe.parse("""{"description":"The top of a cereal box.","visible_text":"","unanswerable_reason":"framing"}""")
        assertEquals(StructuredDescribe.abstention(Reason.FRAMING) + " The top of a cereal box.", StructuredDescribe.spoken(fr, null))
    }

    @Test fun text_the_model_read_is_stated_when_the_ocr_agrees_and_hedged_when_it_does_not() {
        val p = StructuredDescribe.parse("""{"description":"A mobile money receipt.","visible_text":"Sent GHS 20.00 to Ama","unanswerable_reason":null}""")
        assertEquals("A mobile money receipt. It says: “Sent GHS 20.00 to Ama”.", StructuredDescribe.spoken(p, "Sent GHS 20.00 to Ama Mensah Ref 1234"))
        assertEquals("A mobile money receipt. It says: “Sent GHS 20.00 to Ama”.", StructuredDescribe.spoken(p, null))   // no OCR → nothing to contradict
        assertEquals("A mobile money receipt. I think it says “Sent GHS 20.00 to Ama”, but I couldn't read that clearly.",
            StructuredDescribe.spoken(p, "Sent GHS 50.00 to Kofi"))
        assertTrue(StructuredDescribe.diverges("GH 50 MTN", "GHS 20.00 MTN"))
        assertFalse(StructuredDescribe.diverges("Battery Saver", "Battery Saver is off"))
        assertTrue(StructuredDescribe.diverges("Paracetamol 500 mg", ""))
        assertFalse(StructuredDescribe.diverges("", "anything"))
    }

    @Test fun text_already_in_the_description_is_not_repeated() {
        val p = StructuredDescribe.parse("""{"description":"A sign that says EXIT above a door.","visible_text":"EXIT","unanswerable_reason":null}""")
        assertEquals("A sign that says EXIT above a door.", StructuredDescribe.spoken(p, "EXIT"))
    }

    @Test fun the_request_carries_the_json_mime_low_resolution_and_minimal_thinking_only_when_asked() {
        val plain = GeminiVision.requestBody("p", "AAAA", 0.2)
        val gen = plain.getJSONObject("generationConfig")
        assertFalse(gen.has("responseMimeType")); assertFalse(gen.has("mediaResolution")); assertFalse(gen.has("thinkingConfig"))
        val s = GeminiVision.requestBody("p", "AAAA", 0.2, json = true, mediaResolution = "low", thinking = "minimal").getJSONObject("generationConfig")
        assertEquals("application/json", s.getString("responseMimeType"))
        assertEquals("MEDIA_RESOLUTION_LOW", s.getString("mediaResolution"))
        assertEquals("MINIMAL", s.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }
}
