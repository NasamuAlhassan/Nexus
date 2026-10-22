package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.LocalBrain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit, 22 Oct 2026: once MoneyFlow.notYet stopped answering bills, a meter / bill top-up fell to LocalBrain's airtime rule. */
class BillTopUpNotAirtimeTest {
    private fun decide(goal: String) =
        runBlocking { LocalBrain().decide(BrainContext(goal, JSONObject(), emptyList(), JSONArray(), JSONArray())) }

    @Test fun aMeterOrBillTopUpIsNeverAirtimeForThisPhone() {
        for (g in listOf("recharge my ECG prepaid with 50 cedis", "top up my light with 20 cedis", "top up my DStv with 100 cedis")) {
            val d = decide(g)
            assertTrue("$g -> $d", d is AgentDecision.CannotDo)
        }
        val a = decide("top up my airtime with 5 cedis")
        assertTrue("$a", a is AgentDecision.CallTool && a.name == "mobile_money")
    }
}
