package com.tekyerema

import com.tekyerema.camera.GeminiVision
import com.tekyerema.camera.GroqVision
import com.tekyerema.camera.LastCapture
import com.tekyerema.camera.VisionLadder
import com.tekyerema.camera.VisionQa
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vision quick wins, pure parts: the safety lines in every prompt, the Gemini → Groq fallback order, the held
 * photo's short life, the follow-up routing predicate, and GroqVision's request/response shape.
 */
class VisionQaTest {

    @After fun drop() = LastCapture.clear()

    // ---- the VizWiz lesson: both prompts carry the safety lines ----

    @Test fun describe_and_qa_prompts_carry_the_safety_lines() {
        for (p in listOf(GeminiVision.PROMPT, GeminiVision.qaPrompt("what colour is the shirt?"))) {
            assertTrue(p.contains("Never guess amounts of money, medicine names or doses"))
            assertTrue(p.contains("say so plainly instead of guessing"))
            assertTrue(p.contains("Keep to what is actually visible"))
        }
        assertTrue(GeminiVision.PROMPT.contains("two short sentences"))   // the original describe contract stays
        val qa = GeminiVision.qaPrompt("  is there a chair?  ")
        assertTrue(qa.endsWith("Question: is there a chair?"))            // the user's words, trimmed, last
        assertTrue(qa.contains("not visible in the photo"))               // admit not-visible, plainly
    }

    // ---- the fallback order: Gemini first, Groq as the retry, nothing without keys ----

    @Test fun ladder_plan_is_gemini_then_groq() {
        assertEquals(listOf(VisionLadder.Rung.GROQ, VisionLadder.Rung.GEMINI), VisionLadder.plan("gk", listOf("k1", "k2")))   // Groq first since 21 Oct 2026
        assertEquals(listOf(VisionLadder.Rung.GEMINI, VisionLadder.Rung.GROQ), VisionLadder.plan("gk", listOf("k1"), order = "gemini,groq"))
        assertEquals(listOf(VisionLadder.Rung.GEMINI), VisionLadder.plan("gk", emptyList()))
        assertEquals(listOf(VisionLadder.Rung.GROQ), VisionLadder.plan("", listOf("k1")))   // keyless-Gemini days
        assertEquals(emptyList<VisionLadder.Rung>(), VisionLadder.plan("", listOf(" ")))
    }

    @Test fun groq_vision_model_is_the_current_one() {
        assertEquals("qwen/qwen3.8-27b", GroqVision.model())
    }

    // ---- LastCapture: memory only, and it expires by itself ----

    @Test fun last_capture_expires_and_clears() {
        val photo = byteArrayOf(1, 2, 3)
        LastCapture.hold(photo, nowMs = 1_000L)
        assertTrue(LastCapture.get(nowMs = 2_000L, keepMs = 5_000L) === photo)
        assertNull(LastCapture.get(nowMs = 6_001L, keepMs = 5_000L))    // past keepMs: expired…
        assertNull(LastCapture.get(nowMs = 2_000L, keepMs = 5_000L))    // …and the bytes went with it

        LastCapture.hold(photo, nowMs = 1_000L)
        LastCapture.clear()                                             // the camera screen closed
        assertNull(LastCapture.get(nowMs = 1_001L, keepMs = 5_000L))
    }

    // ---- the follow-up routing predicate ----

    @Test fun follow_up_questions_about_the_photo_match() {
        for (q in listOf(
            "what colour is the shirt?",
            "Is there a chair",
            "how many people are in it",
            "can you see a door",
            "does the cup have a handle",
            "who is standing there",
            "what does the label say",
            "which one is bigger"
        )) assertTrue(q, VisionQa.isFollowUp(q))
    }

    @Test fun commands_and_device_questions_never_go_to_the_photo() {
        for (q in listOf(
            "call my mother",                     // an imperative, not a question
            "can you call Ama",                   // question shape, but an action
            "open whatsapp",
            "play the next song",
            "what's the weather today",           // a world matter in question shape
            "is my battery low",
            "what time is it",
            "did anyone email me",
            "",
            "what would you say is the best way to arrange all the furniture in this whole room nicely"   // too long: a task
        )) assertFalse(q, VisionQa.isFollowUp(q))
    }

    // ---- GroqVision: the OpenAI-style request shape and the reply parse ----

    @Test fun groq_vision_body_and_parse() {
        val body = GroqVision.requestBody("qwen/qwen3.8-27b", "describe", "QUJD", 0.2)
        assertEquals("qwen/qwen3.8-27b", body.getString("model"))
        val turn = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        val parts = turn.getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("describe", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals(0.2, body.getDouble("temperature"), 1e-9)
        assertTrue(body.getInt("max_completion_tokens") > 0)

        assertEquals("A blue dress on a chair.",
            GroqVision.parse("""{"choices":[{"message":{"role":"assistant","content":" A blue dress on a chair. "}}]}"""))
        assertNull(GroqVision.parse("""{"choices":[{"message":{"content":null}}]}"""))
        assertNull(GroqVision.parse("""{"choices":[]}"""))
        assertNull(GroqVision.parse("not json"))
    }

    /** R-01 (J-18): a photo goes only to the providers the user allowed; "Groq only" keeps the camera working. */
    @Test fun photosGoOnlyToAllowedProviders() {
        assertEquals(setOf(VisionLadder.Rung.GROQ), VisionLadder.allowedRungs(geminiBackup = false, providers = listOf("groq")))
        assertEquals(setOf(VisionLadder.Rung.GROQ, VisionLadder.Rung.GEMINI), VisionLadder.allowedRungs(true, listOf("groq", "gemini")))
        assertEquals(setOf(VisionLadder.Rung.GEMINI), VisionLadder.allowedRungs(true, listOf("gemini")))
        assertEquals(emptySet<VisionLadder.Rung>(), VisionLadder.allowedRungs(false, listOf("gemini")))
    }
}
