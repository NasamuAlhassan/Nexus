package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.money.MoneyFlow
import com.tekyerema.money.ussd.UssdNavigatorTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 22 Oct 2026: the brain walks the network's own menus toward the user's words (a midnight bundle, Allow Cash Out). */
class MenuGoalTest {
    @Test fun thePlannersMenuGoalBecomesTheNavigatorsGoalWalk() {
        val planner = AgentDecision.CallTool("mobile_money", JSONObject().put("_kind", "menu_goal")
            .put("_goal", "buy the 3 cedi midnight bundle").put("_root", "*138#").put("_amount", "3"), "planner")
        val d = MoneyFlow.fromPlanner(planner, "buy midnight bundle for me, the standard plan, 3 Ghana cedis") as AgentDecision.CallTool
        assertEquals(UssdNavigatorTool.NAME, d.name)
        assertEquals(UssdNavigatorTool.MENU_GOAL, d.args.getString("intent"))
        assertEquals("buy the 3 cedi midnight bundle", d.args.getString("goal"))
        assertEquals("*138#", d.args.getString("root"))
        assertEquals("3", d.args.getString("amount"))
    }

    @Test fun onlyTheOfficialRootsMayStartAWalk() {
        assertEquals(listOf("*170#", "*138#", "*124#"), UssdNavigatorTool.goalRoots("mtn"))
        assertTrue(UssdNavigatorTool.goalRoots("unknown").isEmpty())
    }

    @Test fun theUserHearsWhatWillHappenBeforeAnythingIsDialled() {
        val p = UssdNavigatorTool.confirmationPrompt(JSONObject().put("intent", "menu_goal")
            .put("goal", "allow cash out").put("root", "*170#"))
        assertTrue(p, p.contains("allow cash out") && p.contains("stop at your PIN"))
    }

    /** Owner's live test (22 Oct 2026, 07:18): Twi "send 5 cedis to my wife" reached the planner as "give [A1] to my wife" — no
     *  money word left, no money tool loaded, and it asked for the wife's number. The amount tag loads the money tools. */
    @Test fun aTwiAmountTagLoadsTheMoneyTools() {
        val tools = com.tekyerema.agent.ToolSelector.select("give [A1] to my wife", emptySet(), emptySet(),
            listOf("read_state", "tap", "ask_user", "mobile_money", "momo_send", "run_ussd"))
        assertTrue(tools.toString(), "momo_send" in tools && "mobile_money" in tools)
        // …and a cedi glued to the word before it (07:28: "soma wacedi ma me yere")
        assertTrue("momo_send" in com.tekyerema.agent.ToolSelector.select("soma wacedi ma me yere", emptySet(), emptySet(),
            listOf("read_state", "tap", "ask_user", "mobile_money", "momo_send", "run_ussd")))
    }
}
