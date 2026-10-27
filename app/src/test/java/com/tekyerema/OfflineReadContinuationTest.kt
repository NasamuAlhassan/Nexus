package com.tekyerema

import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.FallbackBrain
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.RoutingBrain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Infinix, 27 Oct 2026, the judge's demo: "Open the team Nexus group on WhatsApp, open the most recent PDF and read it" got as
 * far as the PDF being open, then the internet dropped and the task paused for "carry on". Reading needs no internet, so when the
 * connection is lost and what is left is a reading, the phone reads what the last (verified) step opened.
 */
class OfflineReadContinuationTest {
    private class Dead : AgentBrain {
        override suspend fun decide(ctx: BrainContext): AgentDecision = AgentDecision.CannotDo(FallbackBrain.NETWORK_LOST, "I lost the internet connection")
    }

    private val goal = "Open the team Nexus group on Whatsapp, open the most recent PDF and read it"
    private val opened = "tap(element_id=44) → ok [node]: Tapped 'Executive Summary.pdf' in com.whatsapp.; verified=true (no element containing 'Message' remains)"
    private fun ctx(history: List<String>, g: String = goal) =
        BrainContext(g, JSONObject().put("app", "com.whatsapp").put("elements", JSONArray()), history, JSONArray(), JSONArray())
    private fun router() = RoutingBrain(LocalBrain(), Dead()) { true }

    @Test fun after_a_verified_step_a_dropped_connection_reads_the_screen_on_the_phone() = runBlocking {
        val d = router().decide(ctx(listOf("open_app(WhatsApp) → ok [intent]: Opened WhatsApp; verified=true (foreground)", opened)))
        assertTrue("got $d", d is AgentDecision.CallTool)
        assertEquals("read_screen", (d as AgentDecision.CallTool).name)
    }

    @Test fun once_it_has_read_it_finishes_instead_of_pausing() = runBlocking {
        val read = "read_screen(continue=false) → ok [node]; verified=true (read); Executive summary. Revenue grew by twelve percent."
        val d = router().decide(ctx(listOf(opened, read)))
        assertTrue("got $d", d is AgentDecision.Done)
        assertTrue((d as AgentDecision.Done).summary.contains("Revenue grew"))
    }

    @Test fun an_unverified_last_step_or_a_non_reading_goal_still_pauses() = runBlocking {
        val unverified = "tap(label=Nexus) → ok [node]: Tapped 'Nexus'; verified=false (not found)"
        assertTrue(router().decide(ctx(listOf(opened.replace("verified=true", "verified=false"), unverified))) is AgentDecision.CannotDo)
        assertTrue(router().decide(ctx(listOf(opened), "Open the Nexus group and send hello")) is AgentDecision.CannotDo)
    }
}
