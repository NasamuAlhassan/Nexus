package com.tekyerema

import com.tekyerema.agent.ReviseTurn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** "…, the reference is fine" keeps the reference the user gave (audit, 22 Oct 2026). */
class ReferenceIsFineTest {
    @Test fun theReferenceIsFineKeepsIt() {
        val nav = JSONObject().put("number", "0508797323").put("amount", "5").put("reference", "transport")
        val r = ReviseTurn.patch("ussd_navigate", nav, "no make it 10 cedis, the reference is fine")!!
        assertEquals("10", r.args.getString("amount"))
        assertEquals("transport", r.args.getString("reference"))
    }
}
