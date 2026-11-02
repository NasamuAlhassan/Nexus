package com.tekyerema

import com.tekyerema.camera.obstacle.Band
import com.tekyerema.camera.obstacle.BlockedWatch
import com.tekyerema.camera.obstacle.Detection
import com.tekyerema.camera.obstacle.Direction
import com.tekyerema.camera.obstacle.Group
import com.tekyerema.camera.obstacle.ObstacleConfig
import com.tekyerema.camera.obstacle.ObstacleEngine
import com.tekyerema.camera.obstacle.WalkPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The obstacle-warning rules (camera/obstacle/ObstacleEngine): bands, direction, approach, debounce, cooldowns, priority, honesty. */
class ObstacleEngineTest {
    private val cfg = ObstacleConfig()
    private val step = 150L

    private fun det(cls: String, x0: Float, y0: Float, x1: Float, y1: Float) = Detection(cls, 0.9f, x0, y0, x1, y1)

    // A big car dead ahead (height .80 x scale 1.1 = near), a person on the left at medium range, a person far ahead.
    private val carNear = det("car", 0.25f, 0.15f, 0.75f, 0.95f)
    private val personLeft = det("person", 0.05f, 0.30f, 0.25f, 0.62f)
    private val personRight = det("person", 0.75f, 0.30f, 0.95f, 0.62f)

    private class Event(val t: Long, val r: ObstacleEngine.Result) {
        val phrase: String? get() = r.warning?.phrase
        val haptic: List<Long>? get() = r.warning?.haptic?.toList()
    }

    /** Runs [engine] from [from] to [to] ms inclusive, one frame per [step], the detections coming from [dets] at that time. */
    private fun run(engine: ObstacleEngine, from: Long, to: Long, dets: (Long) -> List<Detection>): List<Event> {
        val out = ArrayList<Event>()
        var t = from
        while (t <= to) { out.add(Event(t, engine.update(t, dets(t)))); t += step }
        return out
    }

    // ---------------------------------------------------------------- bands and directions

    @Test fun band_comes_from_the_box_height_or_the_bottom_edge() {
        val b = cfg.bands
        assertEquals(Band.NEAR, ObstacleEngine.bandOf(0.60f, 0.8f, b))
        assertEquals(Band.MEDIUM, ObstacleEngine.bandOf(0.30f, 0.6f, b))
        assertEquals(Band.FAR, ObstacleEngine.bandOf(0.12f, 0.5f, b))
        assertNull(ObstacleEngine.bandOf(0.05f, 0.5f, b))
        // A box that runs off the bottom of the frame is close even if it is not very tall; a tiny one is still ignored.
        assertEquals(Band.NEAR, ObstacleEngine.bandOf(0.35f, 0.99f, b))
        assertEquals(Band.MEDIUM, ObstacleEngine.bandOf(0.15f, 0.90f, b))
        assertNull(ObstacleEngine.bandOf(0.05f, 1.0f, b))
    }

    @Test fun direction_comes_from_the_box_centre_and_a_wide_box_is_ahead() {
        val s = cfg.sides
        assertEquals(Direction.LEFT, ObstacleEngine.directionOf(0.0f, 0.2f, s))
        assertEquals(Direction.RIGHT, ObstacleEngine.directionOf(0.8f, 1.0f, s))
        assertEquals(Direction.AHEAD, ObstacleEngine.directionOf(0.4f, 0.6f, s))
        assertEquals(Direction.AHEAD, ObstacleEngine.directionOf(0.2f, 0.8f, s))        // spans the middle
        assertEquals(Direction.LEFT, ObstacleEngine.directionOf(0.30f, 0.45f, s))       // narrow, centre .375
        assertEquals(Direction.RIGHT, ObstacleEngine.directionOf(0.55f, 0.75f, s))      // narrow, centre .65
    }

    // ---------------------------------------------------------------- first warning

    @Test fun nothing_in_view_means_no_warning_ever() {
        val e = ObstacleEngine(cfg)
        val ev = run(e, 0, 20_000) { emptyList() }
        assertTrue(ev.all { it.r.warning == null && it.r.hazards.isEmpty() })
    }

    @Test fun a_hazard_needs_two_frames_before_it_is_reported() {
        val e = ObstacleEngine(cfg)
        assertNull(e.update(0, listOf(carNear)).warning)
        assertNotNull(e.update(step, listOf(carNear)).warning)
    }

    @Test fun flicker_never_confirms() {
        val e = ObstacleEngine(cfg)
        val ev = run(e, 0, 3_000) { t -> if ((t / step) % 2 == 0L) listOf(carNear) else emptyList() }
        assertTrue(ev.all { it.r.warning == null })
    }

    @Test fun a_close_car_ahead_buzzes_hard_and_says_it_briefly() {
        val e = ObstacleEngine(cfg)
        e.update(0, listOf(carNear))
        val w = e.update(step, listOf(carNear)).warning!!
        assertEquals("Car, close, ahead", w.phrase)
        assertEquals(longArrayOf(0, 250, 80, 250, 80, 250).toList(), w.haptic!!.toList())
        assertEquals(Band.NEAR, w.hazard.band); assertEquals(Direction.AHEAD, w.hazard.direction); assertEquals(Group.VEHICLE, w.hazard.group)
    }

    @Test fun a_person_at_medium_range_on_the_left_is_a_short_phrase_and_a_left_rhythm() {
        val e = ObstacleEngine(cfg)
        e.update(0, listOf(personLeft))
        val w = e.update(step, listOf(personLeft)).warning!!
        assertEquals("Person, left", w.phrase)
        assertEquals(longArrayOf(0, 250, 120, 80).toList(), w.haptic!!.toList())
    }

    @Test fun right_has_the_mirror_rhythm() {
        val e = ObstacleEngine(cfg)
        e.update(0, listOf(personRight))
        val w = e.update(step, listOf(personRight)).warning!!
        assertEquals("Person, right", w.phrase)
        assertEquals(longArrayOf(0, 80, 120, 250).toList(), w.haptic!!.toList())
    }

    @Test fun classes_outside_the_map_are_ignored() {
        val e = ObstacleEngine(cfg)
        val cup = det("cup", 0.1f, 0.1f, 0.9f, 0.95f)
        assertTrue(run(e, 0, 2_000) { listOf(cup) }.all { it.r.warning == null && it.r.hazards.isEmpty() })
    }

    @Test fun a_far_steady_thing_is_silent_but_still_in_the_hazard_list() {
        val e = ObstacleEngine(cfg)
        val far = det("person", 0.40f, 0.40f, 0.60f, 0.55f)     // height .15 = far
        val ev = run(e, 0, 5_000) { listOf(far) }
        assertTrue(ev.all { it.r.warning == null })
        assertEquals(Band.FAR, ev.last().r.hazards.single().band)
    }

    @Test fun an_optional_far_buzz_is_a_single_faint_tick_with_no_speech() {
        val e = ObstacleEngine(ObstacleConfig(haptics = ObstacleConfig.Haptics(farBuzz = true)))
        val far = det("person", 0.40f, 0.40f, 0.60f, 0.55f)
        val ws = run(e, 0, 4_000) { listOf(far) }.mapNotNull { it.r.warning }
        assertEquals(1, ws.size)
        assertEquals(longArrayOf(0, 40).toList(), ws[0].haptic!!.toList())
        assertNull(ws[0].phrase)
    }

    @Test fun a_far_thing_that_comes_nearer_is_reported_when_it_reaches_medium() {
        val e = ObstacleEngine(cfg)
        val far = det("person", 0.40f, 0.40f, 0.60f, 0.55f)
        val medium = det("person", 0.40f, 0.25f, 0.60f, 0.55f)   // height .30
        assertTrue(run(e, 0, 1_500) { listOf(far) }.all { it.r.warning == null })
        val w = e.update(1_650, listOf(medium)).warning!!
        assertEquals(Band.MEDIUM, w.hazard.band)           // it also doubled in size, so it may be called "coming"
        assertTrue(w.phrase!!.startsWith("Person") && w.phrase!!.endsWith("ahead"))
    }

    // ---------------------------------------------------------------- approach

    @Test fun a_growing_box_is_approaching_and_adds_the_approach_pulse() {
        val e = ObstacleEngine(cfg)
        fun p(y0: Float) = det("person", 0.05f, y0, 0.25f, 0.66f)
        e.update(0, listOf(p(0.40f)))                                  // height .26
        val second = e.update(step, listOf(p(0.38f)))                  // .28: reported, not yet approaching (two samples)
        assertFalse(second.hazards.single().approaching)
        val third = e.update(2 * step, listOf(p(0.32f)))               // .34: grew by 1.31x within the window
        assertTrue(third.hazards.single().approaching)
        assertEquals(longArrayOf(0, 250, 120, 80, 100, 80).toList(), third.warning!!.haptic!!.toList())
    }

    @Test fun a_steady_box_with_jitter_is_not_approaching() {
        val e = ObstacleEngine(cfg)
        val ev = run(e, 0, 2_000) { t -> listOf(det("person", 0.05f, if ((t / step) % 2 == 0L) 0.32f else 0.31f, 0.25f, 0.62f)) }
        assertTrue(ev.drop(1).all { !it.r.hazards.single().approaching })
    }

    @Test fun approach_wording_says_coming() {
        val e = ObstacleEngine(cfg)
        val h = ObstacleEngine.Hazard(1, "car", "Car", Group.VEHICLE, Band.MEDIUM, Direction.AHEAD, approaching = true, height = 0.3f)
        assertEquals("Car coming, ahead", e.phraseFor(h))
        assertEquals("Bicycle coming, far, left", e.phraseFor(ObstacleEngine.Hazard(2, "bicycle", "Bicycle", Group.BICYCLE, Band.FAR, Direction.LEFT, true, 0.1f)))
    }

    // ---------------------------------------------------------------- cooldowns: haptic can repeat, speech cannot spam

    @Test fun a_hazard_that_stays_buzzes_again_but_is_not_re_announced() {
        val e = ObstacleEngine(cfg)
        val ev = run(e, 0, 6_000) { listOf(carNear) }
        assertEquals(1, ev.count { it.phrase != null })
        assertTrue("haptics: ${ev.count { it.haptic != null }}", ev.count { it.haptic != null } >= 5)
    }

    @Test fun the_same_unchanged_hazard_is_announced_again_only_after_the_repeat_window() {
        val e = ObstacleEngine(cfg)
        val said = run(e, 0, 11_500) { listOf(carNear) }.filter { it.phrase != null }
        assertEquals(2, said.size)
        assertTrue(said[1].t - said[0].t >= cfg.cooldown.repeatSameMs)
    }

    @Test fun two_people_are_not_both_announced_inside_the_class_cooldown_and_the_second_is_said_later_without_a_buzz() {
        val e = ObstacleEngine(cfg)
        val ev = run(e, 0, 4_500) { t -> if (t >= 1_050) listOf(personLeft, personRight) else listOf(personLeft) }
        val said = ev.filter { it.phrase != null }
        assertEquals(listOf("Person, left", "Person, right"), said.map { it.phrase })
        assertTrue("second person said at ${said[1].t}", said[1].t - said[0].t >= cfg.cooldown.speakPerClassMs)
        assertNull("the held-back line is not another buzz", said[1].haptic)
        // The second person still got their buzz when they appeared.
        assertTrue(ev.any { it.t in 1_050..1_350 && it.haptic != null && it.phrase == null })
    }

    @Test fun different_classes_are_spaced_apart_and_the_held_back_one_is_still_said() {
        val e = ObstacleEngine(cfg)
        val ev = run(e, 0, 6_000) { listOf(carNear, personLeft) }
        val said = ev.filter { it.phrase != null }
        assertEquals(listOf("Car, close, ahead", "Person, left"), said.map { it.phrase })
        assertTrue(said[1].t - said[0].t >= cfg.cooldown.speakAnyMs)
        assertTrue("the car is not repeated inside 10 s", said.size == 2)
    }

    @Test fun at_most_one_warning_comes_out_of_a_frame() {
        val e = ObstacleEngine(cfg)
        e.update(0, listOf(carNear, personLeft, personRight))
        val r = e.update(step, listOf(carNear, personLeft, personRight))
        assertEquals(3, r.hazards.size)
        assertNotNull(r.warning)
    }

    // ---------------------------------------------------------------- priority

    @Test fun a_nearer_band_beats_a_higher_priority_class() {
        val e = ObstacleEngine(cfg)
        val personNear = det("person", 0.05f, 0.10f, 0.30f, 0.95f)
        val carMedium = det("car", 0.60f, 0.40f, 0.90f, 0.70f)       // height .30 x 1.1 = medium
        e.update(0, listOf(personNear, carMedium))
        val r = e.update(step, listOf(personNear, carMedium))
        assertEquals("person", r.warning!!.hazard.cls)
        assertEquals(listOf("person", "car"), r.hazards.map { it.cls })
    }

    @Test fun in_the_same_band_vehicles_come_before_motorbikes_bicycles_people_poles_and_the_rest() {
        val order = listOf("car", "motorcycle", "bicycle", "person", "stop sign", "dog", "bench")
        val boxes = order.mapIndexed { i, c -> det(c, 0.02f + i * 0.13f, 0.05f, 0.14f + i * 0.13f, 0.97f) }   // all tall: near
        val e = ObstacleEngine(cfg)
        e.update(0, boxes.reversed())
        val r = e.update(step, boxes.reversed())
        assertEquals(order, r.hazards.map { it.cls })
        assertEquals("car", r.warning!!.hazard.cls)
        assertEquals(listOf(Group.VEHICLE, Group.MOTORBIKE, Group.BICYCLE, Group.PERSON, Group.POLE, Group.ANIMAL, Group.OTHER), r.hazards.map { it.group })
    }

    @Test fun class_names_are_spoken_the_way_the_map_says() {
        val e = ObstacleEngine(cfg)
        val moto = det("motorcycle", 0.1f, 0.05f, 0.5f, 0.97f)
        e.update(0, listOf(moto))
        assertEquals("Motorbike, close, left", e.update(step, listOf(moto)).warning!!.phrase)
    }

    // ---------------------------------------------------------------- honesty

    @Test fun no_phrase_ever_says_the_way_is_clear_or_safe() {
        val e = ObstacleEngine(cfg)
        val all = ArrayList<String>()
        for (band in Band.entries) for (dir in Direction.entries) for (appr in listOf(true, false)) for ((cls, info) in cfg.classes)
            all.add(e.phraseFor(ObstacleEngine.Hazard(1, cls, info.say, info.group, band, dir, appr, 0.3f)))
        val bad = Regex("(?i)\\b(clear|safe|free|nothing)\\b")
        assertTrue(all.none { bad.containsMatchIn(it) })
        val p = cfg.phrases
        for (s in listOf(p.disclaimer, p.stopped, p.blocked, p.timeout, p.notInstalled, p.loadFailed, p.disabled))
            assertFalse("'$s'", Regex("(?i)\\b(clear|all clear|safe)\\b").containsMatchIn(s))
        assertTrue(p.disclaimer.contains("may miss"))
        assertTrue(p.disclaimer.contains("cane"))
        assertTrue(p.stopped.contains("may have missed"))
    }

    @Test fun phrases_are_short() {
        val e = ObstacleEngine(cfg)
        for (band in Band.entries) for (dir in Direction.entries)
            assertTrue(e.phraseFor(ObstacleEngine.Hazard(1, "car", "Car", Group.VEHICLE, band, dir, true, 0.3f)).split(' ').size <= 6)
    }

    // ---------------------------------------------------------------- pacing and blind camera

    @Test fun frame_rate_drops_when_nothing_is_near() {
        val f = cfg.fps
        assertEquals(f.idleIntervalMs, WalkPacing.intervalMs(10_000, ObstacleEngine.NEVER, f))
        assertEquals(f.activeIntervalMs, WalkPacing.intervalMs(10_000, 9_000, f))
        assertEquals(f.idleIntervalMs, WalkPacing.intervalMs(10_000, 5_000, f))
        assertTrue(1000.0 / f.activeIntervalMs in 5.0..10.0)          // 5-10 fps while something is near
    }

    @Test fun the_engine_tracks_when_a_hazard_was_last_in_view() {
        val e = ObstacleEngine(cfg)
        assertEquals(ObstacleEngine.NEVER, e.lastHazardAt)
        e.update(0, listOf(personLeft)); e.update(step, listOf(personLeft))
        assertEquals(step, e.lastHazardAt)
        e.update(2 * step, emptyList())
        assertEquals(step, e.lastHazardAt)
    }

    @Test fun a_blocked_camera_is_noticed_only_after_the_window() {
        val w = BlockedWatch(cfg.watch)
        assertTrue(w.isBlindFrame(10f, 50f))            // dark
        assertTrue(w.isBlindFrame(120f, 2f))            // flat
        assertFalse(w.isBlindFrame(120f, 40f))
        assertFalse(w.update(0, true))
        assertFalse(w.update(2_000, true))
        assertTrue(w.update(3_000, true))
        assertFalse(w.update(3_100, false))             // one good frame resets it
        assertFalse(w.update(3_200, true))
        assertFalse(w.update(5_000, true))
        assertTrue(w.update(6_200, true))
    }
}
