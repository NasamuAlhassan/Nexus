package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.ModelRoles
import com.tekyerema.agent.RouterRules
import com.tekyerema.voice.FallbackTts
import com.tekyerema.voice.SpeechProviders
import com.tekyerema.voice.TtsProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W9: speech providers from config (B-18), tree caps + snapshot meta (F-03), model roles (N-06), rule files (X-03). */
class AgentW9Test {

    private class FakeTts(override val name: String) : TtsProvider {
        override suspend fun speak(text: String, language: String) = true
        override fun stop() {}
        override fun shutdown() {}
    }

    @Test fun speech_providers_resolve_from_ids_and_never_lose_the_system_voice() {
        val system = FakeTts("system"); val lab = FakeTts("hcilab")
        val reg = mapOf("system" to system, "hcilab" to lab)
        val unknown = ArrayList<String>()
        assertEquals(listOf(lab, system), SpeechProviders.resolve(listOf("HCILab", "system", "hcilab"), reg) { unknown.add(it) })
        assertEquals(listOf(system), SpeechProviders.resolve(listOf("khaya", "system"), reg) { unknown.add(it) })
        assertEquals(listOf("khaya"), unknown)
        // default config: english → the system engine alone (no composite)
        assertTrue(SpeechProviders.tts("english", mapOf("system" to system), system) === system)
        // a configured id with no client still ends at the system engine
        assertTrue(SpeechProviders.tts("twi", emptyMap(), system) === system)
        assertNull("no online ASR configured ⇒ on-device", SpeechProviders.onlineAsr("twi", emptyMap()))
        assertTrue(FallbackTts(listOf(lab, system)).name.contains("hcilab"))
    }

    @Test fun snapshot_carries_an_id_and_the_focus_and_honours_caps() {
        fun el(id: Int, label: String, focused: Boolean = false) =
            UiElement(id, "Button", label, Box(0, id * 10, 10, id * 10 + 9), clickable = true, focused = focused)
        val snap = UiSnapshot("com.x", "Home", (0 until 60).map { el(it, "Item $it " + "x".repeat(80), focused = it == 3) }, 1L)
        val j = snap.toCompactJson(maxElements = 20, labelMax = 30)
        assertEquals(snap.snapshotId, j.getString("snap"))
        assertEquals(3, j.getInt("focus"))
        assertEquals(20, j.getJSONArray("elements").length())
        assertEquals(30, j.getJSONArray("elements").getJSONObject(0).getString("label").length)
        assertTrue(j.has("note"))
        assertFalse("the service didn't truncate its traversal", j.has("longScreen"))
        assertTrue(snap.copy(truncated = true).toCompactJson().has("longScreen"))
        assertEquals("same screen ⇒ same id", snap.snapshotId, snap.copy(timestampMs = 99L).snapshotId)
    }

    @Test fun planner_chain_defaults_to_groq_then_gemini() {
        assertEquals(listOf(ModelRoles.GROQ, ModelRoles.GEMINI), ModelRoles.plannerChain())
        assertEquals("fallback-model", ModelRoles.model("nonexistent-role", "fallback-model"))
    }

    // ---------------------------------------------------------------- audit fixes
    // ---------------------------------------------------------------- device-run fixes (A20e, 2026-09-28)
    @Test fun the_phone_hearing_its_own_reply_is_not_a_request() {
        val reply = "The cedi is trading at about GH₵ 11.55 per US dollar today. It was 11.40 last week."
        assertTrue(com.tekyerema.voice.SelfEcho.isEcho("trading at about", reply))
        assertTrue("nothing word-like", com.tekyerema.voice.SelfEcho.isEcho("", reply))
        assertFalse(com.tekyerema.voice.SelfEcho.isEcho("stop, call Kofi instead", reply))
        assertEquals("from the start of the cut sentence to the end", reply, com.tekyerema.voice.SelfEcho.resumeFrom("trading at about", reply))
        assertEquals("It was 11.40 last week.", com.tekyerema.voice.SelfEcho.resumeFrom("was 11.40", reply))
        assertEquals("", com.tekyerema.voice.SelfEcho.resumeFrom("banana", reply))
    }

    @Test fun a_misheard_app_name_suggests_the_closest_installed_apps() {
        val installed = listOf("YouTube", "Play Store", "Galaxy Store", "Video Player", "Game Launcher", "Calculator")
        val close = com.tekyerema.tools.closestNames("product video game", installed)
        assertTrue(close.toString(), "Video Player" in close && "Game Launcher" in close)
        assertFalse("Calculator" in close)
        assertTrue(com.tekyerema.tools.closestNames("zzz", installed).isEmpty())
        assertTrue("letter-pair noise is not a suggestion", com.tekyerema.tools.closestNames("zorblax", installed).isEmpty())
    }

    @Test fun undoing_a_new_schedule_says_it_was_removed() {
        assertEquals("Okay, I removed the new schedule.",
            com.tekyerema.audit.DeviceState.spoken(listOf(com.tekyerema.audit.Inverse.RemoveSchedule("s1")), emptyList()))
        assertEquals("Okay, I put back the flashlight and removed the new message alert.",
            com.tekyerema.audit.DeviceState.spoken(listOf(com.tekyerema.audit.Inverse.Torch(false), com.tekyerema.audit.Inverse.RemoveRule("r")), emptyList()))
    }

    @Test fun position_words_become_reading_order() {
        assertEquals("Tap the button just before it.", com.tekyerema.voice.SpeechLint.apply("Tap the button above."))
        assertEquals("Open the link just after it.", com.tekyerema.voice.SpeechLint.apply("Open the link below."))
        assertEquals("Tap the icon.", com.tekyerema.voice.SpeechLint.apply("Tap the icon on the right."))
    }

    @Test fun the_main_list_is_the_largest_scrollable() {
        val carousel = UiElement(1, "HorizontalScrollView", null, Box(0, 0, 1080, 200), scrollable = true)
        val list = UiElement(2, "RecyclerView", null, Box(0, 200, 1080, 2200), scrollable = true)
        val snap = UiSnapshot("com.x", null, listOf(carousel, list), 1L)
        assertEquals(2, com.tekyerema.tools.mainScrollable(snap)?.id)
        assertNull(com.tekyerema.tools.mainScrollable(UiSnapshot("com.x", null, emptyList(), 1L)))
    }

    @Test fun worked_examples_reach_the_tool_schema() {
        try {
            com.tekyerema.core.config.AppConfig.loadFrom(listOf("tools.json" to
                """{"tools":{"send_sms":{"feature":"E-03","examples":[{"contact":"Ama","message":"On my way."}]}}}"""))
            val reg = com.tekyerema.tools.ToolRegistry(listOf(com.tekyerema.tools.SendSmsTool)) { true }
            val d = reg.functionDeclarations().getJSONObject(0).getString("description")
            assertTrue(d, d.contains("Example: {\"contact\":\"Ama\""))
        } finally { com.tekyerema.core.config.AppConfig.loadFrom(emptyList()) }
    }

    @Test fun rule_files_add_phrasings_without_code() {
        val rules = RouterRules.parse(JSONArray("""[
            {"locale":"en","pattern":"^how much charge do i have$","intent":"BATTERY"},
            {"locale":"tw","pattern":"^bue (.+)$","intent":"OPEN_APP","arg":"$1"},
            {"locale":"en","pattern":"^zoom it$","intent":"MAGNIFY","arg":"in"},
            {"locale":"en","pattern":"([bad","intent":"HOME"},
            {"locale":"en","pattern":"^send money$","intent":"MOMO_SEND"}
        ]"""))
        assertEquals("the bad pattern is skipped", 4, rules.size)
        assertEquals(FastIntent.Battery, RouterRules.match("how much charge do i have", rules))
        assertEquals(FastIntent.OpenApp("whatsapp"), RouterRules.match("bue whatsapp", rules))
        assertEquals(FastIntent.Magnify("in"), RouterRules.match("zoom it", rules))
        assertNull("a rule can't reach anything outside the fast path", RouterRules.match("send money", rules))
        assertNull(RouterRules.match("something else", rules))
    }

    /** Owner, on the A20e: "it keeps mentioning the step number when performing an action". */
    @Test fun spoken_progress_never_says_a_step_number() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        try {
            com.tekyerema.core.config.AppConfig.loadFrom(dir.listFiles { f -> f.name.endsWith(".json") }!!.map { "config/${it.name}" to it.readText() })
            for (twi in listOf(false, true)) {
                val line = com.tekyerema.voice.Phrases.get("progress", twi, "Still working on it.")
                assertFalse(line, Regex("(?i)step|\\{").containsMatchIn(line))
            }
        } finally { com.tekyerema.core.config.AppConfig.loadFrom(emptyList()) }
    }

    /** A20e: the model swiped the home screen left, right and up hunting for an icon instead of calling open_app. */
    @Test fun launcher_guard_stops_icon_hunting_on_the_home_screen() {
        val g = com.tekyerema.agent.LauncherGuard
        fun chk(tool: String, onHome: Boolean = true, labelMissing: Boolean = false, goal: String = "opening the calculator.",
                named: String? = "calculator", tried: Boolean = false, missing: String? = null) =
            g.check(tool, onHome, labelMissing, goal, named, tried, missing)
        assertTrue(chk("swipe")!!.contains("open_app({\"app\":\"calculator\"})"))
        assertTrue(chk("scroll")!!.contains("open_app"))
        assertTrue(chk("tap", labelMissing = true)!!.contains("open_app"))
        assertNull("a visible icon may be tapped", chk("tap"))
        assertNull("inside an app, scrolling is fine", chk("scroll", onHome = false))
        assertNull("the user asked to swipe", chk("swipe", goal = "swipe left on the home screen"))
        assertNull(chk("open_app"))
        assertTrue(chk("swipe", tried = true, missing = "calculator")!!.contains("ask_user"))
        assertTrue(chk("swipe", named = null)!!.contains("open_app"))
    }

    @Test fun app_mention_finds_the_app_in_polite_and_ing_phrasings() {
        val m = com.tekyerema.agent.AppMention
        assertEquals("calculator", m.named("opening the calculator."))
        assertEquals("whatsapp", m.named("can you please open whatsapp and message kofi"))
        assertEquals("youtube", m.openTarget("Could you open the YouTube app please"))
        assertNull(m.openTarget("open whatsapp and message kofi"))
        assertNull(m.openTarget("opening hours of Melcom"))
    }

    @Test fun not_installed_is_a_question_for_the_user_not_a_dead_end() {
        val k = com.tekyerema.agent.Recovery.classify(isAccelerator = true, success = false, recoverable = false, needsUser = true)
        assertEquals(com.tekyerema.agent.Recovery.Kind.ASK_USER, k)
        val hint = com.tekyerema.agent.Recovery.hint("open_app", k)
        assertTrue(hint, hint.contains("ask_user") && !hint.contains("on-screen UI"))
        val dead = com.tekyerema.agent.Recovery.hint("call_log", com.tekyerema.agent.Recovery.Kind.DEAD_END)
        assertTrue("a missing permission is the user's to grant", dead.contains("never open Settings"))
    }
}
