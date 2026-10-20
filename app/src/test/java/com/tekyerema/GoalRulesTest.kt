package com.tekyerema

import com.tekyerema.agent.BrainPrompts
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidance rides after the goal only for the requests it is about (BrainPrompts.goalRules). */
class GoalRulesTest {
    @Test fun `a place request gets the location rule so the assistant never asks where from`() {
        val r = BrainPrompts.goalRules("how far is the Atlantic mall from here")
        assertNotNull(r)
        assertTrue(r!!.contains("never ask where from"))
        assertTrue(r.contains("action=distance"))
        assertNotNull(BrainPrompts.goalRules("what is the nearest pharmacy"))
    }

    @Test fun `a messaging request gets the messaging rule and not the place rule`() {
        val r = BrainPrompts.goalRules("send a message to Ama")!!
        assertTrue(r.contains("MESSAGING RULES"))
        assertTrue(!r.contains("PLACE RULES"))
    }

    @Test fun `an unrelated request costs nothing`() {
        assertNull(BrainPrompts.goalRules("what time is it"))
        assertNull(BrainPrompts.goalRules("turn the flashlight on"))
    }

    @Test fun `both rules can ride together`() {
        val r = BrainPrompts.goalRules("tell Ama how far the nearest mall is")!!
        assertTrue(r.contains("MESSAGING RULES") && r.contains("PLACE RULES"))
    }
}
