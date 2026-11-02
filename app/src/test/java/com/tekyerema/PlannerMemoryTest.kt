package com.tekyerema

import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.BrainPrompts
import com.tekyerema.agent.llm.PlannerPrompt
import com.tekyerema.memory.MemEntry
import com.tekyerema.memory.MemKind
import com.tekyerema.memory.MemState
import com.tekyerema.memory.PlannerMemory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** #19: the "What I know" lines for the on-phone planner (the benchmark's wording), and none of it for a cloud brain by default. */
class PlannerMemoryTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(10, 0).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    private val now = at(2026, 11, 2)

    private fun entry(kind: MemKind, key: String, value: String, told: Boolean = false, count: Int = 1, last: Long = now) =
        MemEntry(kind, key, value, told, count, count.toDouble(), last, last)

    private fun build(goal: String, entries: List<MemEntry> = emptyList(), relations: Map<String, String> = emptyMap(),
                      skills: List<String> = emptyList(), off: Set<MemKind> = emptySet(), max: Int = PlannerMemory.MAX_LINES) =
        PlannerMemory.build(goal, MemState(entries, off), relations, skills, now, zone = utc, max = max)

    private val wifeChannel = entry(MemKind.PREF, PlannerMemory.channelKey("Pearl Asante", "WhatsApp"), "WhatsApp", count = 3)

    @Test fun relation_and_usual_channel_in_the_benchmark_wording() {
        // campaign/agent_bench/cases/memory.jsonl memory-wife-01, byte for byte.
        assertEquals(listOf("\"my wife\" is the contact Pearl Asante", "Pearl Asante is usually messaged on WhatsApp"),
            build("tell my wife I'm on my way", listOf(wifeChannel), mapOf("wife" to "Pearl Asante")))
    }

    @Test fun a_relation_word_without_my_keeps_the_way_it_was_said() {
        // memory-reference-05: "Mum" is the contact Grace Mensah (the goal's own amount is not carried into the block).
        val lines = build("send 50 cedis to Mum", relations = mapOf("mother" to "Grace Mensah"))
        assertEquals(listOf("\"Mum\" is the contact Grace Mensah"), lines)
        assertTrue(lines.none { it.contains("50") })
    }

    @Test fun a_first_name_means_the_one_contact_used_most_and_the_channel_follows() {
        val entries = listOf(entry(MemKind.CONTACT, "richmond boateng#1234", "Richmond Boateng", count = 5),
            entry(MemKind.PREF, PlannerMemory.channelKey("Richmond Boateng", "Telegram"), "Telegram", count = 2))
        assertEquals(listOf("\"Richmond\" usually means Richmond Boateng", "Richmond Boateng is usually messaged on Telegram"),
            build("tell Richmond I'll be late", entries))
    }

    @Test fun two_people_with_the_same_first_name_are_not_guessed() {
        val entries = listOf(entry(MemKind.CONTACT, "ama mensah#1", "Ama Mensah", count = 4), entry(MemKind.CONTACT, "ama boateng#2", "Ama Boateng", count = 4))
        assertTrue(build("call Ama", entries).isEmpty())
    }

    @Test fun a_channel_used_once_is_not_yet_a_habit() {
        val once = entry(MemKind.PREF, PlannerMemory.channelKey("Pearl Asante", "WhatsApp"), "WhatsApp", count = 1)
        assertEquals(listOf("\"my wife\" is the contact Pearl Asante"), build("text my wife hello", listOf(once), mapOf("wife" to "Pearl Asante")))
    }

    @Test fun a_told_fact_carries_the_day_it_was_told_and_goes_stale_when_dated() {
        val fact = entry(MemKind.FACT, "my exam", "on Monday 3 Nov at 9:00 AM in the Great Hall", told = true, last = at(2026, 10, 20))
        assertEquals(listOf("told on 20 Oct: the user's exam is on Monday 3 Nov at 9:00 AM in the Great Hall"), build("when is my exam?", listOf(fact)))
        val old = fact.copy(lastAt = at(2026, 9, 1), firstAt = at(2026, 9, 1))
        assertTrue("a dated fact told 2 months ago is not carried", build("when is my exam?", listOf(old)).isEmpty())
        val undated = entry(MemKind.FACT, "my church", "Holy Trinity", told = true, last = at(2026, 1, 5))
        assertEquals(1, build("which church do I go to, my church?", listOf(undated)).size)
    }

    @Test fun a_preference_settles_the_app() {
        val pref = entry(MemKind.PREF, "rides", "Bolt", told = true)
        assertEquals(listOf("prefers Bolt for rides"), build("get me a taxi to Accra Mall", listOf(pref)))
        assertTrue("speech settings are applied by the app, not told to the planner",
            build("speak slowly", listOf(entry(MemKind.PREF, "rate", "slow", told = true))).isEmpty())
    }

    @Test fun taught_skills_by_name_when_the_goal_looks_like_them() {
        assertEquals(listOf("taught skill: \"check my data bundle\""), build("check data bundle please", skills = listOf("check my data bundle", "order food")))
    }

    @Test fun nothing_relevant_means_no_block() {
        val entries = listOf(wifeChannel, entry(MemKind.FACT, "my exam", "on Monday", told = true))
        assertTrue(build("what time is it", entries, mapOf("wife" to "Pearl Asante")).isEmpty())
        assertTrue(build("   ", entries).isEmpty())
    }

    @Test fun at_most_ten_lines_most_relevant_first() {
        val names = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "kilo", "lima", "quebec", "romeo", "tango", "yankee")
        val facts = names.mapIndexed { i, n -> entry(MemKind.FACT, "my hobby $n", "chess club $n", told = true, last = now - i) }
        val lines = build("what is my hobby", facts, mapOf("wife" to "Pearl Asante"))
        assertEquals(10, lines.size)
        val withWife = build("tell my wife about my hobby", facts, mapOf("wife" to "Pearl Asante"))
        assertEquals("people come before facts", "\"my wife\" is the contact Pearl Asante", withWife.first())
        assertEquals(10, withWife.size)
        assertEquals(3, build("tell my wife about my hobby", facts, mapOf("wife" to "Pearl Asante"), max = 3).size)
    }

    @Test fun no_secret_amount_or_message_text_reaches_a_line() {
        val bad = listOf(
            entry(MemKind.FACT, "my momo pin", "is 4821", told = true),
            entry(MemKind.FACT, "my balance", "is 200 cedis", told = true),
            entry(MemKind.FACT, "my account", "number 0241234567", told = true))
        assertTrue(build("what is my momo pin balance account", bad).isEmpty())
        val lines = build("tell my wife I owe her 500 cedis, pin 1234", listOf(wifeChannel), mapOf("wife" to "Pearl Asante"))
        assertTrue(lines.none { it.contains("500") || it.contains("1234") || it.contains("owe") })
    }

    @Test fun switched_off_kinds_are_left_out() {
        val pref = entry(MemKind.PREF, "rides", "Bolt", told = true)
        assertTrue(build("get me a taxi", listOf(pref), off = setOf(MemKind.PREF)).isEmpty())
        val contact = entry(MemKind.CONTACT, "richmond boateng#1234", "Richmond Boateng", count = 5)
        assertTrue(build("tell Richmond hi", listOf(contact), off = setOf(MemKind.CONTACT)).isEmpty())
    }

    @Test fun merge_keeps_every_failure_hint_and_caps_the_total() {
        val mem = (1..10).map { "m$it" }
        val merged = PlannerMemory.merge(mem, listOf("Last time, tapping 'Search' in Bolt did not work."))
        assertEquals(10, merged.size)
        assertEquals("m1", merged.first())
        assertEquals("Last time, tapping 'Search' in Bolt did not work.", merged.last())
        assertEquals(listOf("hint"), PlannerMemory.merge(emptyList(), listOf("hint")))
        assertEquals(listOf("m1", "m2"), PlannerMemory.merge(listOf("m1", "m2"), emptyList()))
    }

    @Test fun the_planner_prompt_wraps_the_lines_as_the_benchmark_does() {
        val lines = listOf("\"my wife\" is the contact Pearl Asante", "Pearl Asante is usually messaged on WhatsApp")
        val turn = PlannerPrompt.firstTurn("tell my wife I'm on my way", null, memory = lines)
        assertTrue(turn, turn.contains("What I know (the user's own saved memory: use it; actions still get the usual confirmation):\n" +
            "- \"my wife\" is the contact Pearl Asante\n- Pearl Asante is usually messaged on WhatsApp\n\nGoal: tell my wife I'm on my way"))
    }

    // ---------------------------------------------------------------- the cloud gets none by default

    private fun cloudCtx(goal: String) = BrainContext(goal, JSONObject().put("elements", JSONArray()), emptyList(), JSONArray(), JSONArray())

    @Test fun the_cloud_prompt_has_no_memory_lines_by_default() {
        val oldShare = PlannerMemory.shareWithCloud
        val oldSource = PlannerMemory.source
        try {
            var asked = 0
            PlannerMemory.source = { asked++; listOf("\"my wife\" is the contact Pearl Asante") }
            // Default: no phone context in a JVM test, and the Settings flag defaults to false — the flag decides, not the context.
            for (separate in listOf(false, true)) {
                val text = BrainPrompts.userText(cloudCtx("tell my wife I'm on my way"), screenSeparate = separate, nonce = "n")
                assertFalse(text, text.contains("Pearl"))
                assertFalse(text, text.contains("WHAT I KNOW"))
            }
            assertEquals("the memory is not even read when the user has not shared it", 0, asked)
            assertTrue(PlannerMemory.forCloud("tell my wife hi").isEmpty())
            assertEquals("", PlannerMemory.cloudSection("tell my wife hi"))

            PlannerMemory.shareWithCloud = { true }
            val shared = BrainPrompts.userText(cloudCtx("tell my wife I'm on my way"))
            assertTrue(shared, shared.contains("WHAT I KNOW") && shared.contains("- \"my wife\" is the contact Pearl Asante"))
            assertTrue(BrainPrompts.userText(cloudCtx("x"), screenSeparate = true, nonce = "n").contains("WHAT I KNOW"))
        } finally {
            PlannerMemory.shareWithCloud = oldShare
            PlannerMemory.source = oldSource
        }
    }

    @Test fun the_settings_flag_is_off_by_default_and_named_in_the_source() {
        // The accessor exists with a false default (Settings needs a Context, so the JVM test reads the source text).
        val text = java.io.File("src/main/java/com/tekyerema/settings/Settings.kt").takeIf { it.exists() }?.readText() ?: return
        assertTrue(text.contains("var shareMemoryWithCloud: Boolean"))
        assertTrue(text.contains("getBoolean(\"share_memory_with_cloud\", false)"))
    }
}
