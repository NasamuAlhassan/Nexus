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
}
