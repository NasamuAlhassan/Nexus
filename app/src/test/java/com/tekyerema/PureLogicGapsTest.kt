package com.tekyerema

import com.tekyerema.agent.DictationCleanup
import com.tekyerema.agent.References
import com.tekyerema.agent.ScreenEntities
import com.tekyerema.agent.ScreenMemory
import com.tekyerema.agent.TaskMemory
import com.tekyerema.core.Privacy
import com.tekyerema.policy.Sentinel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [E] Pure logic that had no unit test: on-screen phone numbers (ScreenEntities), dictation clean-up, the per-run
 * TaskMemory scratchpad, the parts of References the W7 tests don't reach (option lists, remembered numbers,
 * "forget"), the isolated injection guard's verdict parser (Sentinel.parse), the retention sweep (Privacy.sweep) and
 * the screen memory behind "what changed?" (ScreenMemory.rememberJson).
 */
class PureLogicGapsTest {

    @Before @After fun reset() { References.clear(); ScreenMemory.clear() }

    // ---------------------------------------------------------------- ScreenEntities (E-23 "call this number")

    @Test fun ghana_numbers_on_screen_are_found_in_order_without_duplicates() {
        val labels = listOf("Call Kofi on 024 123 4567", "or +233 55-123-4567", "again: 0241234567", "Ref 12345", "GHS 5000.00")
        assertEquals(listOf("0241234567", "+233551234567"), ScreenEntities.phoneNumbers(labels))
    }

    @Test fun digit_runs_that_are_not_phone_numbers_are_ignored() {
        assertEquals(emptyList<String>(), ScreenEntities.phoneNumbers(listOf("Order 1234567890123", "12:30", "PIN 1234", "")))
        assertEquals(emptyList<String>(), ScreenEntities.phoneNumbers(emptyList()))
    }

    // ---------------------------------------------------------------- DictationCleanup (B-12)

    @Test fun dictation_drops_fillers_and_function_word_stutters_only() {
        assertEquals("I will be there", DictationCleanup.clean("um I I will uh be there"))
        assertEquals("send the report", DictationCleanup.clean("Hmm, send the the report"))
        assertEquals("call her tomorrow", DictationCleanup.clean("Er, call her tomorrow"))
        // Real English repeats and words that merely contain a filler are left alone.
        assertEquals("he had had enough", DictationCleanup.clean("he had had enough"))
        assertEquals("bring the umbrella", DictationCleanup.clean("bring the umbrella"))
    }

    @Test fun dictation_that_is_only_fillers_is_returned_unchanged() {
        assertEquals("um uh", DictationCleanup.clean("  um uh "))
        assertEquals("", DictationCleanup.clean("   "))
    }

    // ---------------------------------------------------------------- TaskMemory (Phase II P0 scratchpad)

    @Test fun task_memory_renders_nothing_until_it_knows_something() {
        assertEquals("", TaskMemory().render())
        val m = TaskMemory()
        m.noteScreen(" ", null); m.noteEntity("recipient", "  "); m.noteFailure(null)
        assertEquals("", m.render())
    }

    @Test fun task_memory_renders_location_entities_and_the_last_three_failures() {
        val m = TaskMemory()
        m.noteScreen("com.android.settings", "Display")
        m.noteScreen(null, "Font size")                     // a blank app keeps the previous one
        m.noteEntity("recipient", " Kofi ")
        m.noteEntity("message", "x".repeat(60))            // capped at 48
        listOf("one", "two", "three", "four").forEach(m::noteFailure)
        val lines = m.render().lines()
        assertEquals("app=settings · screen=Font size", lines[0])
        assertEquals("known: recipient=Kofi, message=" + "x".repeat(48), lines[1])
        assertEquals("recent problems: two; three; four", lines[2])
    }

    // ---------------------------------------------------------------- References (C-22 / E-23 / H-12)

    @Test fun options_are_read_from_a_disambiguation_message() {
        assertEquals(listOf("Ama Mensah", "Ama Boateng"),
            References.optionsFrom("Multiple contacts match 'ama' (2): Ama Mensah (024…); Ama Boateng (055…). Which one?"))
        assertEquals(emptyList<String>(), References.optionsFrom("No colon here"))
    }

    @Test fun remembered_person_number_and_options_are_guarded() {
        References.notePerson("0244123456"); References.notePerson("A"); References.notePerson(null)
        assertNull("digits or a single letter are not a person", References.lastPerson)
        References.notePerson("  Kofi  ")
        assertEquals("Kofi", References.lastPerson)
        References.noteNumber("12345")
        assertNull("too short to be a phone number", References.lastNumber)
        References.noteNumber("call 024 123 4567 now")
        assertEquals("0241234567", References.lastNumber)
        References.noteOptions(listOf("only one"))
        assertEquals(emptyList<String>(), References.lastOptions)
        References.noteOptions((1..8).map { "Option $it" })
        assertEquals(6, References.lastOptions.size)
    }

    @Test fun that_number_is_the_one_just_read_out() {
        assertEquals("call 0241234567", References.rewrite("call that number", person = null, options = emptyList(), number = "0241234567"))
        assertNull("nothing remembered, nothing rewritten", References.rewrite("call that number", person = null, options = emptyList(), number = null))
        assertNull(References.rewrite("text him", person = null, options = emptyList(), number = null))
    }

    @Test fun forget_and_clear_drop_what_was_remembered() {
        References.notePerson("Ama Mensah")
        References.noteOptions(listOf("Ama Mensah", "Kofi Boateng", "ama serwaa"))
        References.forget("  ")
        assertEquals("Ama Mensah", References.lastPerson)
        References.forget("ama")
        assertNull(References.lastPerson)
        assertEquals(listOf("Kofi Boateng"), References.lastOptions)
        References.noteNumber("0241234567")
        References.clear()
        assertNull(References.lastNumber)
        assertEquals(emptyList<String>(), References.lastOptions)
    }

    // ---------------------------------------------------------------- Sentinel.parse (J-19 isolated injection guard)

    @Test fun guard_labels_decide_before_scores() {
        assertTrue(Sentinel.parse("MALICIOUS").malicious)
        assertTrue(Sentinel.parse("injection").malicious)
        assertTrue(Sentinel.parse("Jailbreak detected").malicious)
        assertTrue("'unsafe' is not the word 'safe'", Sentinel.parse("unsafe").malicious)
        assertFalse(Sentinel.parse("BENIGN").malicious)
        assertFalse("an explicit safe label wins over a high score", Sentinel.parse("safe 0.97").malicious)
    }

    @Test fun guard_scores_use_the_threshold_and_empty_is_not_a_block() {
        assertTrue(Sentinel.parse("0.93").malicious)
        assertTrue(Sentinel.parse("1").malicious)
        assertFalse(Sentinel.parse("0.2").malicious)
        assertEquals(0.93, Sentinel.parse("0.93").score!!, 1e-9)
        val empty = Sentinel.parse("   ")
        assertFalse(empty.malicious)
        assertNull(empty.score)
        assertEquals(60, Sentinel.parse("x".repeat(200)).detail.length)
    }

    // ---------------------------------------------------------------- Privacy.sweep (J-15 retention)

    @Test fun retention_sweep_removes_only_files_older_than_the_window() {
        val dir = Files.createTempDirectory("sweep").toFile()
        try {
            val now = 1_800_000_000_000L
            val old = File(dir, "old.log").apply { writeText("x"); setLastModified(now - 8 * 86_400_000L) }
            val fresh = File(dir, "fresh.log").apply { writeText("x"); setLastModified(now - 1 * 86_400_000L) }
            File(dir, "sub").mkdirs()
            assertEquals(0, Privacy.sweep(dir, 0, now))
            assertEquals(0, Privacy.sweep(File(dir, "missing"), 7, now))
            assertEquals(1, Privacy.sweep(dir, 7, now))
            assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(File(dir, "sub").isDirectory)
        } finally { dir.deleteRecursively() }
    }

    // ---------------------------------------------------------------- ScreenMemory (K-15 "what changed?")

    @Test fun screen_memory_keeps_readable_labels_and_the_app_name() {
        val els = JSONArray()
            .put(JSONObject().put("label", " Wi-Fi "))
            .put(JSONObject().put("label", "x"))                  // too short
            .put(JSONObject().put("label", "y".repeat(65)))       // too long
            .put(JSONObject().put("label", "Wi-Fi"))              // duplicate
            .put(JSONObject())                                    // no label
        ScreenMemory.rememberJson(JSONObject().put("app", "com.android.settings").put("appName", "Settings").put("elements", els))
        assertEquals("Settings", ScreenMemory.app)
        assertEquals(listOf("Wi-Fi"), ScreenMemory.labels)
        ScreenMemory.rememberJson(JSONObject().put("app", "com.android.settings"))
        assertEquals("com.android.settings", ScreenMemory.app)
        assertEquals(emptyList<String>(), ScreenMemory.labels)
        ScreenMemory.rememberJson(JSONObject())
        assertNull(ScreenMemory.app)
    }
}
