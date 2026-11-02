package com.tekyerema

import com.tekyerema.camera.obstacle.Coco
import com.tekyerema.camera.obstacle.Detection
import com.tekyerema.camera.obstacle.FrameRotation
import com.tekyerema.camera.obstacle.Letterbox
import com.tekyerema.camera.obstacle.PixelSource
import com.tekyerema.camera.obstacle.YoloxCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.ln

/** The YOLOX pre/post-processing for the obstacle detector (camera/obstacle/YoloxCodec): letterbox, tensor fill, grid decode, NMS, rotation. */
class YoloxCodecTest {
    private val side = 416
    private val rows = YoloxCodec.anchorCount(side)

    private fun blank() = FloatArray(rows * YoloxCodec.ROW)

    /** Writes one anchor row: raw cx, cy, w, h (grid offsets / log sizes), objectness and the score of [cls]. */
    private fun put(out: FloatArray, anchor: Int, cx: Float, cy: Float, w: Float, h: Float, obj: Float, cls: Int, p: Float) {
        val o = anchor * YoloxCodec.ROW
        out[o] = cx; out[o + 1] = cy; out[o + 2] = w; out[o + 3] = h; out[o + 4] = obj; out[o + 5 + cls] = p
    }

    @Test fun anchor_counts_match_the_official_models() {
        assertEquals(3549, YoloxCodec.anchorCount(416))
        assertEquals(8400, YoloxCodec.anchorCount(640))
    }

    @Test fun coco_names_are_in_the_head_order() {
        assertEquals(80, Coco.NAMES.size)
        assertEquals("person", Coco.NAMES[0]); assertEquals("bicycle", Coco.NAMES[1]); assertEquals("car", Coco.NAMES[2])
        assertEquals("motorcycle", Coco.NAMES[3]); assertEquals("bus", Coco.NAMES[5]); assertEquals("truck", Coco.NAMES[7])
        assertEquals("bench", Coco.NAMES[13]); assertEquals("chair", Coco.NAMES[56]); assertEquals("toothbrush", Coco.NAMES[79])
    }

    // ---------------------------------------------------------------- letterbox + tensor

    @Test fun letterbox_scales_the_long_side_and_keeps_the_top_left() {
        val lb = Letterbox(480, 640, side)          // an upright portrait frame
        assertEquals(0.65f, lb.scale, 1e-4f)
        assertEquals(416, lb.newH)
        assertEquals(312, lb.newW)
        assertEquals(0, lb.xMap[0]); assertEquals(0, lb.yMap[0])
        assertTrue(lb.xMap.last() <= 479)
        assertTrue(lb.yMap.last() <= 639)
    }

    @Test fun tensor_is_bgr_planar_0_to_255_with_114_padding() {
        val lb = Letterbox(480, 640, side)
        val src = PixelSource { _, _ -> (10 shl 16) or (20 shl 8) or 30 }   // R=10 G=20 B=30
        val t = FloatArray(3 * side * side)
        YoloxCodec.fillInput(src, lb, bgr = true, out = t)
        val plane = side * side
        assertEquals(30f, t[0], 0f); assertEquals(20f, t[plane], 0f); assertEquals(10f, t[2 * plane], 0f)       // B, G, R, no normalisation
        assertEquals(114f, t[lb.newW], 0f)                      // first padded column of row 0
        assertEquals(114f, t[plane + lb.newW + 3], 0f)
        assertEquals(30f, t[(lb.newH - 1) * side + 5], 0f)      // still image on the last image row
        val rgb = FloatArray(3 * side * side)
        YoloxCodec.fillInput(src, lb, bgr = false, out = rgb)
        assertEquals(10f, rgb[0], 0f); assertEquals(30f, rgb[2 * plane], 0f)
    }

    @Test fun tensor_rows_below_the_image_are_all_padding() {
        val lb = Letterbox(640, 320, side)          // landscape: image is 416 x 208, the rest below is padding
        val t = FloatArray(3 * side * side)
        YoloxCodec.fillInput({ _, _ -> 0xFFFFFF }, lb, true, t)
        val plane = side * side
        assertEquals(255f, t[(lb.newH - 1) * side], 0f)
        for (c in 0..2) assertEquals(114f, t[c * plane + lb.newH * side + 7], 0f)
    }

    @Test fun tensor_samples_the_source_at_the_inverse_scale() {
        val lb = Letterbox(832, 416, side)          // scale 0.5: canvas column 10 reads source column 20
        val t = FloatArray(3 * side * side)
        YoloxCodec.fillInput({ x, _ -> (x and 0xFF) shl 16 }, lb, bgr = false, out = t)   // R = source x
        assertEquals(20f, t[10], 0f)
        assertEquals(40f, t[20], 0f)
    }

    @Test fun tensor_size_is_checked() {
        try { YoloxCodec.fillInput({ _, _ -> 0 }, Letterbox(100, 100, side), true, FloatArray(10)); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---------------------------------------------------------------- decode

    @Test fun stride_8_anchor_decodes_to_a_frame_box() {
        val out = blank()
        // anchor (gx=10, gy=20) on the stride-8 grid (52 x 52): centre (10.5, 20.5) * 8 = (84, 164); size 40 x 80.
        put(out, 20 * 52 + 10, 0.5f, 0.5f, ln(5f), ln(10f), obj = 0.9f, cls = 2, p = 0.8f)
        val d = YoloxCodec.decode(out, rows, Letterbox(416, 416, side), decoded = false, scoreMin = 0.35f)
        assertEquals(1, d.size)
        assertEquals("car", d[0].cls)
        assertEquals(0.72f, d[0].score, 1e-4f)
        assertEquals(64f / 416, d[0].x0, 1e-4f); assertEquals(124f / 416, d[0].y0, 1e-4f)
        assertEquals(104f / 416, d[0].x1, 1e-4f); assertEquals(204f / 416, d[0].y1, 1e-4f)
    }

    @Test fun stride_16_and_32_anchors_use_their_own_grids() {
        val out = blank()
        // stride 16: grid 26, rows start at 52*52 = 2704. Anchor (gx=3, gy=4), offsets 0 -> centre (3, 4) * 16 = (48, 64), size 16 x 16.
        put(out, 2704 + 4 * 26 + 3, 0f, 0f, 0f, 0f, obj = 1f, cls = 0, p = 0.9f)
        // stride 32: grid 13, rows start at 2704 + 676 = 3380. Anchor (gx=12, gy=12), offsets 0 -> centre 384, size 32.
        put(out, 3380 + 12 * 13 + 12, 0f, 0f, 0f, 0f, obj = 1f, cls = 56, p = 0.7f)
        val d = YoloxCodec.decode(out, rows, Letterbox(416, 416, side), false, 0.35f).sortedBy { it.cls }
        assertEquals(listOf("chair", "person"), d.map { it.cls })
        val person = d.first { it.cls == "person" }
        assertEquals(40f / 416, person.x0, 1e-4f); assertEquals(56f / 416, person.y0, 1e-4f)
        assertEquals(56f / 416, person.x1, 1e-4f); assertEquals(72f / 416, person.y1, 1e-4f)
        val chair = d.first { it.cls == "chair" }
        assertEquals(368f / 416, chair.x0, 1e-4f); assertEquals(400f / 416, chair.x1, 1e-4f)
    }

    @Test fun boxes_are_mapped_back_through_the_letterbox_scale() {
        val out = blank()
        // 640 x 480 source -> scale 0.65. Canvas box (65, 65)-(195, 130) is source (100, 100)-(300, 200) = frame (0.15625, 0.2083)-(0.46875, 0.4167).
        // Centre (130, 97.5) on the stride-32 grid: gx = 4, offset (130/32 - 4) = 0.0625; gy = 3, offset 97.5/32 - 3 = 0.046875.
        put(out, 3380 + 3 * 13 + 4, 0.0625f, 0.046875f, ln(130f / 32), ln(65f / 32), obj = 0.95f, cls = 0, p = 0.95f)
        val d = YoloxCodec.decode(out, rows, Letterbox(640, 480, side), false, 0.35f)
        assertEquals(1, d.size)
        assertEquals(0.15625f, d[0].x0, 1e-3f); assertEquals(100f / 480, d[0].y0, 1e-3f)
        assertEquals(0.46875f, d[0].x1, 1e-3f); assertEquals(200f / 480, d[0].y1, 1e-3f)
    }

    @Test fun already_decoded_exports_are_read_as_canvas_pixels() {
        val out = blank()
        put(out, 0, 100f, 200f, 50f, 100f, obj = 0.9f, cls = 3, p = 0.9f)
        val d = YoloxCodec.decode(out, rows, Letterbox(416, 416, side), decoded = true, scoreMin = 0.35f)
        assertEquals(1, d.size)
        assertEquals("motorcycle", d[0].cls)
        assertEquals(75f / 416, d[0].x0, 1e-4f); assertEquals(150f / 416, d[0].y0, 1e-4f)
        assertEquals(125f / 416, d[0].x1, 1e-4f); assertEquals(250f / 416, d[0].y1, 1e-4f)
    }

    @Test fun weak_scores_and_filtered_classes_are_dropped() {
        val out = blank()
        put(out, 100, 0f, 0f, 0f, 0f, obj = 0.5f, cls = 0, p = 0.5f)      // 0.25 < 0.35
        put(out, 200, 0f, 0f, 0f, 0f, obj = 0.9f, cls = 41, p = 0.9f)     // a cup
        put(out, 300, 0f, 0f, 0f, 0f, obj = 0.9f, cls = 2, p = 0.9f)      // a car
        val all = YoloxCodec.decode(out, rows, Letterbox(416, 416, side), false, 0.35f)
        assertEquals(setOf("cup", "car"), all.map { it.cls }.toSet())
        val onlyCars = YoloxCodec.decode(out, rows, Letterbox(416, 416, side), false, 0.35f) { it == "car" }
        assertEquals(listOf("car"), onlyCars.map { it.cls })
    }

    @Test fun boxes_are_clamped_to_the_frame() {
        val out = blank()
        put(out, 3380, 0f, 0f, ln(40f), ln(40f), obj = 1f, cls = 0, p = 1f)     // 32 * 40 px wide at the top-left corner
        val d = YoloxCodec.decode(out, rows, Letterbox(416, 416, side), false, 0.35f)
        assertEquals(1, d.size)
        assertEquals(0f, d[0].x0, 0f); assertEquals(0f, d[0].y0, 0f)
        assertEquals(1f, d[0].x1, 0f); assertEquals(1f, d[0].y1, 0f)
    }

    @Test fun a_model_with_the_wrong_number_of_rows_is_refused() {
        try { YoloxCodec.decode(FloatArray(100 * 85), 100, Letterbox(416, 416, side), false, 0.35f); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---------------------------------------------------------------- NMS

    private fun d(cls: String, x0: Float, y0: Float, x1: Float, y1: Float, s: Float) = Detection(cls, s, x0, y0, x1, y1)

    @Test fun nms_keeps_the_best_of_overlapping_same_class_boxes() {
        val a = d("car", 0.10f, 0.10f, 0.50f, 0.50f, 0.9f)
        val b = d("car", 0.12f, 0.12f, 0.52f, 0.52f, 0.7f)
        val far = d("car", 0.70f, 0.70f, 0.90f, 0.90f, 0.5f)
        val kept = YoloxCodec.nms(listOf(b, far, a), 0.45f)
        assertEquals(listOf(0.9f, 0.5f), kept.map { it.score })
    }

    @Test fun nms_is_per_class_and_capped() {
        val car = d("car", 0.1f, 0.1f, 0.5f, 0.5f, 0.9f)
        val person = d("person", 0.1f, 0.1f, 0.5f, 0.5f, 0.8f)
        assertEquals(2, YoloxCodec.nms(listOf(car, person), 0.45f).size)
        assertEquals(1, YoloxCodec.nms(listOf(car, person), 0.45f, max = 1).size)
    }

    @Test fun iou_of_identical_and_disjoint_boxes() {
        val a = d("car", 0f, 0f, 0.5f, 0.5f, 1f)
        assertEquals(1f, YoloxCodec.iou(a, a), 1e-6f)
        assertEquals(0f, YoloxCodec.iou(a, d("car", 0.6f, 0.6f, 0.9f, 0.9f, 1f)), 0f)
    }

    // ---------------------------------------------------------------- rotation

    @Test fun rotation_maps_every_upright_pixel_back_to_its_sensor_pixel() {
        val sw = 4; val sh = 3
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val (uw, uh) = FrameRotation.uprightSize(sw, sh, rot)
            val seen = HashSet<Pair<Int, Int>>()
            for (sy in 0 until sh) for (sx in 0 until sw) {
                // forward: where a sensor pixel lands when the frame is turned `rot` degrees clockwise
                val (ux, uy) = when (rot) { 90 -> (sh - 1 - sy) to sx; 180 -> (sw - 1 - sx) to (sh - 1 - sy); 270 -> sy to (sw - 1 - sx); else -> sx to sy }
                assertTrue(ux in 0 until uw && uy in 0 until uh)
                val back = IntArray(2)
                FrameRotation.toSensor(ux, uy, uw, uh, rot, back)
                assertEquals("rot $rot ($sx,$sy)", sx to sy, back[0] to back[1])
                seen.add(ux to uy)
            }
            assertEquals(sw * sh, seen.size)
        }
    }

    @Test fun upright_size_swaps_for_quarter_turns() {
        assertEquals(640 to 480, FrameRotation.uprightSize(640, 480, 0))
        assertEquals(480 to 640, FrameRotation.uprightSize(640, 480, 90))
        assertEquals(640 to 480, FrameRotation.uprightSize(640, 480, 180))
        assertEquals(480 to 640, FrameRotation.uprightSize(640, 480, 270))
    }
}
