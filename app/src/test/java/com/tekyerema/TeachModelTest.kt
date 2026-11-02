package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.teach.HandOverWhy
import com.tekyerema.teach.ReplayResult
import com.tekyerema.teach.StepKind
import com.tekyerema.teach.StepWords
import com.tekyerema.teach.TeachCommands
import com.tekyerema.teach.TeachSkill
import com.tekyerema.teach.TeachStep
import com.tekyerema.teach.TeachStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Teach mode: the step format, the words steps are read back in, the store, and the config switch. */
class TeachModelTest {
    @After fun reset() { AppConfig.loadFrom(emptyList()) }

    private val mtn = "com.mtn.momo"
    private val steps = listOf(
        TeachStep(StepKind.OPEN_APP, mtn, appName = "MyMTN"),
        TeachStep(StepKind.CLICK, mtn, appName = "MyMTN", screenTitle = "Home", label = "Data", viewId = "$mtn:id/data_tile", role = "Button"),
        TeachStep(StepKind.TYPE, mtn, hint = "Search", viewId = "$mtn:id/q", text = "bundle", textLength = 6, submit = true),
        TeachStep(StepKind.SCROLL, mtn, direction = "down", optional = true),
        TeachStep(StepKind.CLICK, mtn, label = "Send", sensitive = true)
    )

    @Test fun a_skill_survives_json_and_has_no_coordinates() {
        val skill = TeachSkill("data bundle", steps, createdAt = 42L, endsAtPrivate = true)
        val json = skill.toJson().toString()
        assertEquals(skill, TeachSkill.fromJson(JSONObject(json)))
        for (bad in listOf("\"x\"", "\"y\"", "bounds", "coord")) assertFalse(bad, json.contains(bad))
    }

    @Test fun a_step_without_a_text_stores_no_text() {
        val j = TeachStep(StepKind.TYPE, mtn, hint = "Search", textLength = 5).toJson()
        assertFalse(j.has("text")); assertEquals(5, j.getInt("len"))
    }

    @Test fun broken_records_are_skipped_not_fatal() {
        assertNull(TeachSkill.fromJson(JSONObject("{\"name\":\"x\",\"steps\":[]}")))
        assertNull(TeachSkill.fromJson(JSONObject("{\"name\":\"x\",\"steps\":[{\"k\":\"NOPE\",\"pkg\":\"a\"}]}")))
        assertNull(TeachSkill.fromJson(JSONObject("{\"steps\":[{\"k\":\"CLICK\",\"pkg\":\"a\"}]}")))
    }

    @Test fun steps_are_described_in_plain_words() {
        assertEquals("open MyMTN", StepWords.describe(steps[0]))
        assertEquals("tap Data", StepWords.describe(steps[1]))
        assertEquals("type “bundle” into the Search box and search", StepWords.describe(steps[2]))
        assertEquals("type into the Search box", StepWords.describe(steps[2].copy(text = null, submit = false)))
        assertEquals("scroll down", StepWords.describe(steps[3]))
        assertEquals("tap a button I couldn't name", StepWords.describe(TeachStep(StepKind.CLICK, mtn)))
        assertEquals("tap data tile", StepWords.describe(TeachStep(StepKind.CLICK, mtn, viewId = "$mtn:id/dataTile")))
        assertEquals("press and hold Data", StepWords.describe(steps[1].copy(kind = StepKind.LONG_CLICK)))
    }

    @Test fun the_read_back_is_a_short_sentence_even_for_a_long_skill() {
        assertEquals("first open MyMTN, then tap Data.", StepWords.spoken(steps.take(2)))
        val many = (1..12).map { TeachStep(StepKind.CLICK, mtn, label = "Item $it") }
        val said = StepWords.spoken(many, max = 8)
        assertTrue(said, said.startsWith("first tap Item 1, then tap Item 2") && said.endsWith("and 5 more steps."))
    }

    @Test fun the_hand_over_goal_leaves_out_scrolls_and_names_the_rest() {
        assertEquals("open MyMTN, tap Data, type “bundle” into the Search box and search and tap Send", StepWords.asGoal(steps.filter { it.kind != StepKind.SCROLL }))
        assertEquals("tap Data", StepWords.asGoal(listOf(steps[1], steps[3])))
        val h = ReplayResult.HandOver(2, HandOverWhy.MISSING, "I couldn't find Data.", listOf(steps[1], steps[4]))
        assertEquals("I couldn't find Data. Do the rest for me: Tap Data and tap Send.", TeachCommands.handOverGoal(h))
        val t = ReplayResult.HandOver(0, HandOverWhy.NEEDS_TEXT, "I need you to tell me what to type in the Search box.", listOf(steps[2].copy(text = null)))
        assertTrue(TeachCommands.handOverGoal(t).endsWith("Ask me what to type before you type it."))
    }

    @Test fun steps_the_policy_would_ask_about_are_flagged_from_the_label() {
        assertTrue(StepWords.isSensitive(TeachStep(StepKind.CLICK, mtn, label = "Send")))
        assertTrue(StepWords.isSensitive(TeachStep(StepKind.CLICK, mtn, label = "Pay now")))
        assertTrue(StepWords.isSensitive(TeachStep(StepKind.CLICK, mtn, label = "Delete")))
        assertFalse(StepWords.isSensitive(TeachStep(StepKind.CLICK, mtn, label = "Data")))
        assertFalse(StepWords.isSensitive(TeachStep(StepKind.OPEN_APP, mtn)))
        assertFalse(StepWords.isSensitive(TeachStep(StepKind.TYPE, mtn, hint = "Search", submit = true)))   // a search box
        assertTrue(StepWords.isSensitive(TeachStep(StepKind.TYPE, mtn, hint = "Type a message", submit = true)))   // may send
    }

    @Test fun the_store_keeps_replaces_and_removes() {
        val s = TeachStore()
        s.put(TeachSkill("a", steps)); s.put(TeachSkill("b", steps.take(2)))
        s.put(TeachSkill("a", steps.take(1)))
        assertEquals(listOf("a", "b"), s.names()); assertEquals(1, s.get("a")!!.steps.size)
        assertTrue(s.remove("b")); assertFalse(s.remove("b")); assertEquals(listOf("a"), s.names())
    }

    @Test fun teach_is_on_by_default_and_the_shipped_config_is_valid() {
        assertTrue(TeachCommands.enabled())
        val f = listOf(File("src/main/assets/config/teach.json"), File("app/src/main/assets/config/teach.json")).first { it.isFile }
        AppConfig.loadFrom(listOf("config/teach.json" to f.readText()))
        assertTrue(AppConfig.bool("teach.enabled", false)); assertEquals(30, AppConfig.int("teach.maxSteps", 0))
        AppConfig.loadFrom(listOf("config/teach.json" to "{\"teach\":{\"enabled\":false}}"))
        assertFalse(TeachCommands.enabled())
    }
}
