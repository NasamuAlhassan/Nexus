package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.BrainPrompts
import com.tekyerema.agent.LocalModelBrain
import com.tekyerema.agent.OnDeviceLlm
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hints from the user's own notes of past mistakes reach the ON-PHONE planner's "What I know" block, and nothing else. */
class FailureHintsBrainTest {
    private val hint = "Last time, tapping 'Search' in Bolt did not work. Try a different way."

    private class Capture : OnDeviceLlm {
        override val id = "capture"
        val users = ArrayList<String>()
        override suspend fun complete(prompt: String, maxTokens: Int, timeoutMs: Long): String? = null
        override suspend fun chat(system: String, user: String, jsonSchema: String?, maxTokens: Int, timeoutMs: Long): String? {
            users.add(user); return """{"tool":"set_alarm","args":{"hour":6}}"""
        }
    }

    private fun ctx(goal: String): BrainContext {
        val alarm = JSONObject().put("name", "set_alarm").put("description", "Set an alarm.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("hour", JSONObject().put("type", "integer"))).put("required", JSONArray(listOf("hour"))))
        return BrainContext(goal = goal, screen = JSONObject(), history = emptyList(), geminiTools = JSONArray(),
            openAiTools = JSONArray().put(JSONObject().put("type", "function").put("function", alarm)))
    }

    @Test fun theOnPhonePlannerReadsTheHintBeforeTheGoal() = runBlocking {
        val llm = Capture()
        val seen = ArrayList<String>()
        val d = LocalModelBrain(llm, hints = { g -> seen.add(g); listOf(hint) }).decide(ctx("open Bolt and search for Accra Mall"))
        assertTrue(d is AgentDecision.CallTool)
        assertEquals(listOf("open Bolt and search for Accra Mall"), seen)
        val u = llm.users.single()
        assertTrue(u.contains("What I know"))
        assertTrue(u.contains("- $hint"))
        assertTrue("the hint comes before the goal", u.indexOf(hint) < u.indexOf("Goal: open Bolt"))
    }

    @Test fun noHintsMeansTheSamePromptAsBefore() = runBlocking {
        val llm = Capture()
        LocalModelBrain(llm, hints = { emptyList() }).decide(ctx("set an alarm"))
        assertFalse(llm.users.single().contains("What I know"))
    }

    @Test fun aBrokenHintSourceNeverBreaksPlanning() = runBlocking {
        val llm = Capture()
        val d = LocalModelBrain(llm, hints = { throw IllegalStateException("store unreadable") }).decide(ctx("set an alarm"))
        assertTrue(d is AgentDecision.CallTool)
        assertFalse(llm.users.single().contains("What I know"))
    }

    @Test fun theCloudPromptHasNoDoorForHints() {
        // BrainContext carries no hints and BrainPrompts never reads learning/: the cloud brains' per-step message is built from the
        // goal, the history, the progress and the screen only.
        val text = BrainPrompts.userText(ctx("open Bolt and search for Accra Mall"))
        assertFalse(text.contains("Last time"))
        assertFalse(text.contains("What I know"))
    }
}
