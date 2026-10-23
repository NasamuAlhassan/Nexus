package com.tekyerema

import com.tekyerema.money.ussd.UssdNavigatorTool
import com.tekyerema.tools.MoMoSendTool
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A goal walk read back while the user is on a call carries the same scam warning as a send (audit, 22 Oct 2026). */
class GoalWalkCallWarningTest {
    @Test fun aGoalWalkOnACallCarriesTheScamWarning() {
        val args = JSONObject().put("intent", "menu_goal").put("goal", "pay my ECG bill").put("root", "*170#")
        assertFalse(UssdNavigatorTool.confirmationPrompt(args).contains("phone call"))
        val p = UssdNavigatorTool.confirmationPrompt(JSONObject(args.toString()).put(MoMoSendTool.ARG_ON_CALL, true))
        assertTrue(p, p.contains("You're on a phone call") && p.contains("stop at your PIN"))
    }
}
