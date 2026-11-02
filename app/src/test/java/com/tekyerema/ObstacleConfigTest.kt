package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.camera.obstacle.Band
import com.tekyerema.camera.obstacle.Group
import com.tekyerema.camera.obstacle.ObstacleConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** assets/config/obstacles.json and the walk-mode voice phrases. */
class ObstacleConfigTest {
    private fun assetText(): String {
        val f = listOf(File("src/main/assets/config/obstacles.json"), File("app/src/main/assets/config/obstacles.json")).first { it.isFile }
        return f.readText()
    }

    private val fromFile: ObstacleConfig by lazy { ObstacleConfig.fromJson(JSONObject(assetText()).getJSONObject("obstacles")) }

    @Test fun the_shipped_file_is_valid_json_and_the_feature_is_off_by_default() {
        assertFalse(fromFile.enabled)
        assertEquals(1, JSONObject(assetText()).getInt("schema_version"))
    }

    @Test fun the_file_and_the_built_in_defaults_agree() {
        val d = ObstacleConfig()
        assertEquals(d.model, fromFile.model)
        assertEquals(d.bands, fromFile.bands)
        assertEquals(d.sides, fromFile.sides)
        assertEquals(d.approach, fromFile.approach)
        assertEquals(d.debounce, fromFile.debounce)
        assertEquals(d.cooldown, fromFile.cooldown)
        assertEquals(d.fps, fromFile.fps)
        assertEquals(d.watch, fromFile.watch)
        assertEquals(d.phrases, fromFile.phrases)
        assertEquals(ObstacleConfig.DEFAULT_CLASSES, fromFile.classes)
        for (dir in listOf(d.haptics.ahead to fromFile.haptics.ahead, d.haptics.left to fromFile.haptics.left, d.haptics.right to fromFile.haptics.right))
            for (b in Band.entries) assertEquals(dir.first.getValue(b).toList(), dir.second.getValue(b).toList())
        assertEquals(d.haptics.approachingSuffix.toList(), fromFile.haptics.approachingSuffix.toList())
    }

    @Test fun every_class_in_the_map_is_a_real_coco_class() {
        val coco = com.tekyerema.camera.obstacle.Coco.NAMES.toSet()
        assertTrue(fromFile.classes.keys.all { it in coco })
        assertEquals(Group.VEHICLE, fromFile.classes.getValue("car").group)
        assertEquals(Group.PERSON, fromFile.classes.getValue("person").group)
        assertEquals(Group.MOTORBIKE, fromFile.classes.getValue("motorcycle").group)
    }

    @Test fun a_missing_or_empty_config_gives_working_defaults() {
        assertEquals(false, ObstacleConfig.fromJson(null).enabled)
        val c = ObstacleConfig.fromJson(JSONObject("{}"))
        assertEquals(ObstacleConfig.DEFAULT_CLASSES, c.classes)
        assertEquals(3000L, c.cooldown.speakPerClassMs)
        assertTrue(c.phrases.disclaimer.contains("may miss"))
    }

    @Test fun the_spoken_cooldown_cannot_be_configured_below_one_second_and_the_disclaimer_cannot_be_blanked() {
        val c = ObstacleConfig.fromJson(JSONObject("""{"cooldown":{"speakPerClassMs":50},"phrases":{"disclaimer":"  "},"debounce":{"confirmFrames":0}}"""))
        assertEquals(1000L, c.cooldown.speakPerClassMs)
        assertTrue(c.phrases.disclaimer.contains("may miss"))
        assertEquals(1, c.debounce.confirmFrames)
    }

    @Test fun thresholds_and_classes_can_be_overridden() {
        val c = ObstacleConfig.fromJson(JSONObject("""{"enabled":true,"bands":{"near":{"minHeight":0.7}},"classes":{"_note":"x","dog":{"say":"Dog","group":"animal","scale":2}}}"""))
        assertTrue(c.enabled)
        assertEquals(0.7f, c.bands.near.minHeight, 0f)
        assertEquals(0.25f, c.bands.medium.minHeight, 0f)            // untouched
        assertEquals(setOf("dog"), c.classes.keys)
        assertEquals(2f, c.classes.getValue("dog").scale, 0f)
    }

    // ---------------------------------------------------------------- voice entry

    private fun c(s: String) = FastIntentClassifier.classify(s)

    @Test fun walk_phrases_open_the_camera_in_walk_mode() {
        for (s in listOf("walk with me", "Walk with me.", "warn me about obstacles", "please watch ahead", "watch the way for me", "watch for obstacles",
            "alert me to hazards", "turn on obstacle warnings", "start walking mode", "obstacle detection", "warn me about obstacles while I walk"))
            assertEquals("'$s'", FastIntent.Camera("walk"), c(s))
    }

    @Test fun stop_watching_phrases_end_it() {
        for (s in listOf("stop watching", "stop the obstacle warnings", "turn off walking mode", "please stop warnings"))
            assertEquals("'$s'", FastIntent.Camera("walk_stop"), c(s))
    }

    @Test fun neighbouring_requests_keep_their_old_meaning() {
        assertEquals(FastIntent.Camera("describe"), c("what's in front of me"))
        assertEquals(FastIntent.MediaStop, c("stop"))
        assertNotNull(c("what is the time"))
        assertFalse(c("walk to the market") is FastIntent.Camera)
    }
}
