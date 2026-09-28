package com.tekyerema

import com.tekyerema.camera.ChangeAnnouncer
import com.tekyerema.camera.ColorNames
import com.tekyerema.camera.FrameStats
import com.tekyerema.camera.GeminiVision
import com.tekyerema.camera.LightLevel
import com.tekyerema.camera.QualityGate
import com.tekyerema.camera.TextStabilizer
import com.tekyerema.camera.Verdict
import com.tekyerema.camera.YuvColor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Camera perception, pure parts: P-03 text stabilising, P-06 colour and light, P-09 quality gate, P-01 parsing. */
class CameraTest {

    // ---- P-03 TextStabilizer ----

    @Test fun text_is_spoken_only_after_two_consecutive_equal_readings() {
        val s = TextStabilizer()
        assertNull(s.offer("Fire exit"))                          // seen once: could be a half-framed sign
        assertEquals("Fire exit", s.offer("Fire   exit"))         // same text (whitespace normalised) → speak
        assertNull(s.offer("FIRE EXIT"))                          // same again (case normalised) → already said

        val t = TextStabilizer()
        assertNull(t.offer("Push"))
        assertNull(t.offer("Pull"))                               // changed between frames → not stable
        assertEquals("Pull", t.offer("Pull"))
    }

    @Test fun text_near_duplicates_of_what_was_spoken_are_suppressed() {
        val s = TextStabilizer()
        s.offer("Keep this door closed")
        assertEquals("Keep this door closed", s.offer("Keep this door closed"))
        assertNull(s.offer("Keep this dor closed"))               // one-letter OCR jitter
        assertNull(s.offer("Keep this dor closed"))
        assertNull(s.offer("this door closed Keep"))              // blocks in another order: first sighting…
        assertNull(s.offer("this door closed Keep"))              // …stable, but the same words as what was said
        assertNull(s.offer("Push to open"))                     // genuinely new, seen once
        assertEquals("Push to open", s.offer("Push to open"))     // …and stable → speak
    }

    @Test fun text_numbers_are_never_near_duplicates() {
        val s = TextStabilizer()
        s.offer("Room 101")
        assertEquals("Room 101", s.offer("Room 101"))
        assertNull(s.offer("Room 102"))
        assertEquals("Room 102", s.offer("Room 102"))             // one digit apart is a different door
    }

    @Test fun text_short_blank_and_long_readings() {
        val s = TextStabilizer()
        assertNull(s.offer("ab"))
        assertNull(s.offer("ab"))                                 // fewer than 3 letters is noise
        assertNull(s.offer("12 %"))
        assertNull(s.offer("12 %"))                               // digits alone are not letters

        val gap = TextStabilizer()
        assertNull(gap.offer("Exit door"))
        assertNull(gap.offer(""))                                 // a blank frame breaks the pair
        assertNull(gap.offer("Exit door"))
        assertEquals("Exit door", gap.offer("Exit door"))

        val lines = TextStabilizer()
        lines.offer("EXIT\nFire door")
        assertEquals("EXIT. Fire door", lines.offer("EXIT\n  Fire door "))   // each block its own sentence

        val page = (1..120).joinToString(" ") { "word$it" }       // ~850 chars
        val capped = TextStabilizer().run { offer(page); offer(page) }!!
        assertTrue(capped.length <= 400)
        assertTrue(page.startsWith(capped))
        assertTrue(!capped.endsWith(" "))
    }

    @Test fun text_similarity_measures() {
        assertEquals(1.0, TextStabilizer.similarity("fire exit", "fire exit"), 1e-9)
        assertEquals(1.0, TextStabilizer.similarity("exit fire", "fire exit"), 1e-9)   // token overlap
        assertTrue(TextStabilizer.similarity("push", "pull") < 0.85)
        assertEquals("fire exit", TextStabilizer.normalise("  FIRE — exit! "))
    }

    // ---- P-06 ColorNames / LightLevel ----

    @Test fun colour_names_for_primaries_and_greys() {
        assertEquals("red", ColorNames.name(255, 0, 0))
        assertEquals("green", ColorNames.name(0, 255, 0))
        assertEquals("blue", ColorNames.name(0, 0, 255))
        assertEquals("white", ColorNames.name(255, 255, 255))
        assertEquals("black", ColorNames.name(0, 0, 0))
        assertEquals("grey", ColorNames.name(128, 128, 128))
        assertEquals("yellow", ColorNames.name(255, 255, 0))
        assertEquals("orange", ColorNames.name(255, 165, 0))
    }

    @Test fun colour_names_with_dark_and_light_qualifiers() {
        assertEquals("dark red", ColorNames.name(139, 0, 0))
        assertEquals("dark blue", ColorNames.name(0, 0, 139))
        assertEquals("light blue", ColorNames.name(173, 216, 230))
        assertEquals("light grey", ColorNames.name(200, 200, 200))
        assertEquals("brown", ColorNames.name(139, 69, 19))      // dark orange is brown
        assertEquals("pink", ColorNames.name(255, 192, 203))     // light red is pink
        assertEquals("purple", ColorNames.name(128, 0, 128))
    }

    @Test fun light_level_thresholds() {
        assertEquals("very dark", LightLevel.describe(10.0))
        assertEquals("dim", LightLevel.describe(40.0))
        assertEquals("dim", LightLevel.describe(89.9))
        assertEquals("normal light", LightLevel.describe(90.0))
        assertEquals("normal light", LightLevel.describe(128.0))
        assertEquals("bright", LightLevel.describe(190.0))
        assertEquals("bright", LightLevel.describe(250.0))
    }

    @Test fun colour_announcer_needs_a_change_that_holds_and_a_gap() {
        val a = ChangeAnnouncer(minIntervalMs = 2000)
        assertNull(a.offer("red, dim", 0))                        // first sighting
        assertEquals("red, dim", a.offer("red, dim", 500))        // held → speak
        assertNull(a.offer("red, dim", 3000))                     // unchanged → silent
        assertNull(a.offer("blue, dim", 3500))                    // changed, seen once
        assertEquals("blue, dim", a.offer("blue, dim", 4000))     // held, gap passed → speak
        assertNull(a.offer("red, dim", 4500))
        assertNull(a.offer("red, dim", 5000))                     // held, but only 1 s since the last line
        assertEquals("red, dim", a.offer("red, dim", 6100))
    }

    @Test fun yuv_to_rgb_and_centre_sampling() {
        assertArrayEquals(intArrayOf(128, 128, 128), YuvColor.toRgb(128.0, 128.0, 128.0))
        val red = YuvColor.toRgb(76.245, 84.97, 255.5)            // BT.601 full-range encoding of pure red
        assertTrue(red[0] >= 250 && red[1] <= 5 && red[2] <= 5)
        assertArrayEquals(intArrayOf(256, 192, 384, 288), FrameStats.centreBox(640, 480, 0.2))

        // 4×2 plane with row padding; the padding (99) must never be averaged in.
        val plane = byteArrayOf(10, 20, 30, 40, 99, 99, 50, 60, 70, 80, 99, 99)
        assertEquals(45.0, FrameStats.mean(plane, rowStride = 6, pixelStride = 1, left = 0, top = 0, right = 4, bottom = 2), 1e-9)
        assertEquals(40.0, FrameStats.mean(plane, rowStride = 6, pixelStride = 2, left = 0, top = 0, right = 2, bottom = 2), 1e-9)
    }

    // ---- P-09 QualityGate ----

    private fun frame(w: Int, h: Int, stride: Int = w, px: (Int, Int) -> Int): ByteArray {
        val b = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) b[y * stride + x] = px(x, y).toByte()
        return b
    }

    @Test fun quality_gate_verdicts_on_synthetic_frames() {
        val w = 64
        val h = 48
        assertEquals(Verdict.TOO_DARK, QualityGate.assess(frame(w, h) { _, _ -> 10 }, w, h, w).verdict)
        assertEquals(Verdict.TOO_BRIGHT, QualityGate.assess(frame(w, h) { _, _ -> 250 }, w, h, w).verdict)

        val flat = QualityGate.assess(frame(w, h) { _, _ -> 128 }, w, h, w)
        assertEquals(Verdict.BLURRY, flat.verdict)                // well lit but no edges at all
        assertEquals(0.0, flat.blurScore, 1e-9)
        assertEquals(128.0, flat.meanLuma, 1e-9)

        val checker = { x: Int, y: Int -> if ((x / 8 + y / 8) % 2 == 0) 20 else 235 }
        val sharp = QualityGate.assess(frame(w, h, px = checker), w, h, w)
        assertEquals(Verdict.OK, sharp.verdict)
        assertTrue(sharp.blurScore > QualityGate.BLUR_MIN)
        assertEquals(127.5, sharp.meanLuma, 1e-9)

        // Row padding (rowStride > width) is skipped, not read as dark pixels.
        val padded = QualityGate.assess(frame(w, h, stride = w + 16, px = checker), w, h, w + 16)
        assertEquals(Verdict.OK, padded.verdict)
        assertEquals(127.5, padded.meanLuma, 1e-9)
    }

    @Test fun quality_gate_guidance_is_short_and_specific() {
        assertEquals("It's too dark — try turning on a light.", QualityGate.guidance(Verdict.TOO_DARK))
        assertEquals("Too much glare — tilt the phone a little.", QualityGate.guidance(Verdict.TOO_BRIGHT))
        assertEquals("Hold the phone still for a moment.", QualityGate.guidance(Verdict.BLURRY))
        assertEquals("", QualityGate.guidance(Verdict.OK))
    }

    // ---- P-01 GeminiVision request/response shape ----

    @Test fun gemini_vision_body_and_parse() {
        val body = GeminiVision.requestBody("describe", "QUJD", 0.2)
        val turn = body.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        assertEquals("describe", turn.getJSONArray("parts").getJSONObject(0).getString("text"))
        val image = turn.getJSONArray("parts").getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/jpeg", image.getString("mime_type"))
        assertEquals("QUJD", image.getString("data"))
        assertEquals(0.2, body.getJSONObject("generationConfig").getDouble("temperature"), 1e-9)

        val reply = """{"candidates":[{"content":{"parts":[{"text":"thinking…","thought":true},""" +
            """{"text":"A red mug on a table."},{"text":" It says Best Dad. "}]}}]}"""
        assertEquals("A red mug on a table. It says Best Dad.", GeminiVision.parse(reply))
        assertNull(GeminiVision.parse("""{"candidates":[]}"""))
        assertNull(GeminiVision.parse("not json"))
    }
}
