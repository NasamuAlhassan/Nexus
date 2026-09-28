package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.ScreenDiff
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Phrases
import com.tekyerema.voice.SpeechLint
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W6: K-01/F-10, K-09, K-15, K-16, L-04, P-01/03/06 entry phrases, X-06. */
class UxW6Test {
    @After fun reset() = AppConfig.loadFrom(emptyList())
    private fun c(s: String) = FastIntentClassifier.classify(s)

    @Test fun what_changed_speaks_only_the_difference() {
        assertEquals("Nothing has changed.", ScreenDiff.describe("WhatsApp", listOf("Ama", "Kofi"), "WhatsApp", listOf("Kofi", "Ama")))
        assertEquals("New: Esi. Gone: Kofi.", ScreenDiff.describe("WhatsApp", listOf("Ama", "Kofi"), "WhatsApp", listOf("Ama", "Esi")))
        assertEquals("You're now in Chrome.", ScreenDiff.describe("WhatsApp", listOf("Ama"), "Chrome", listOf("Search")))
        assertTrue(ScreenDiff.describe(null, emptyList(), "X", listOf("a")).startsWith("I haven't described"))
    }

    @Test fun visual_wording_is_rewritten_for_listening() {
        assertEquals("Tap Send.", SpeechLint.apply("Click Send."))
        assertEquals("I tapped it.", SpeechLint.apply("I clicked it."))
        assertEquals("It's on.", SpeechLint.apply("As you can see, it's on."))
        assertEquals("Wi-Fi is on.", SpeechLint.firstSentence("Wi-Fi is on. I also checked Bluetooth."))
        assertEquals("Which Ama? Ama Mensah or Ama Boateng?", SpeechLint.firstSentence("Which Ama? Ama Mensah or Ama Boateng?"))
    }

    @Test fun camera_magnify_and_emergency_phrases() {
        assertEquals(FastIntent.Camera("text"), c("What does this label say?"))
        assertEquals(FastIntent.Camera("text"), c("Read the letter"))
        assertEquals(FastIntent.Camera("color"), c("What colour is this?"))
        assertEquals(FastIntent.Camera("describe"), c("What's in front of me?"))
        assertTrue(c("What's on my screen?") !is FastIntent.Camera)          // the screen, not the camera
        assertEquals(FastIntent.Magnify("in"), c("Magnify the screen"))
        assertEquals(FastIntent.Magnify("reset"), c("Stop magnifying"))
        assertEquals(FastIntent.WhatChanged, c("What changed?"))
        assertEquals(FastIntent.SetEmergencyContact("ama"), c("Set my emergency contact to Ama"))
        assertEquals(FastIntent.EmergencyLine("ambulance"), c("Call an ambulance"))
        assertEquals(FastIntent.EmergencyLine("police"), c("Call the police"))
    }

    private fun decide(brain: LocalBrain, goal: String, screen: JSONObject = JSONObject()): AgentDecision = runBlocking {
        brain.decide(BrainContext(goal, screen, emptyList(), JSONArray(), JSONArray()))
    }

    @Test fun i_need_help_goes_to_the_confirmed_emergency_tool() {
        for (s in listOf("I need help", "Emergency!", "Help me please", "Mmoa"))
            assertEquals(s, "emergency", (decide(LocalBrain(), s) as AgentDecision.CallTool).name)
        assertTrue(decide(LocalBrain(), "help me send a message to Ama") !is AgentDecision.CallTool ||
            (decide(LocalBrain(), "help me send a message to Ama") as AgentDecision.CallTool).name != "emergency")
    }

    @Test fun screen_summary_orients_first() {
        val screen = JSONObject().put("app", "com.whatsapp").put("appName", "WhatsApp").put("screen", "Chats").put("elementCount", 30)
            .put("elements", JSONArray()
                .put(JSONObject().put("label", "Search").put("role", "ImageButton").put("flags", JSONArray().put("clickable")))
                .put(JSONObject().put("label", "Ama").put("role", "TextView"))
                .put(JSONObject().put("label", "Kofi").put("role", "TextView")))
        val brain = LocalBrain()
        val first = decide(brain, "what's on my screen", screen)
        val d = if (first is AgentDecision.CallTool) decide(brain, "what's on my screen", screen) else first
        val text = (d as AgentDecision.Done).summary
        assertTrue(text, text.startsWith("You're in WhatsApp, on Chats."))
        assertTrue(text, text.contains("You can use: Search."))
        assertTrue(text, text.contains("I can see: Ama, Kofi."))
        assertTrue(text, text.endsWith("There's more if you scroll."))
    }

    @Test fun phrases_come_from_the_language_pack_with_code_fallback() {
        assertEquals("fallback", Phrases.get("missing", false, "fallback"))
        AppConfig.loadFrom(listOf("l" to """{"locales":{"tw":{"holdAck":"Twɛn kakra."}}}"""))
        assertEquals("Twɛn kakra.", Phrases.get("holdAck", true, "Yoo, twɛn."))
        assertEquals("Okay.", Phrases.get("holdAck", false, "Okay."))
    }
}
