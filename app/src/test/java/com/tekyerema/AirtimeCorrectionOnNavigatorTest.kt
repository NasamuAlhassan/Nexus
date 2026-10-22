package com.tekyerema

import com.tekyerema.agent.ReviseTurn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The live airtime read-back is the navigator's (MoneyFlow.airtime -> ussd_navigate, intent buy_airtime) — audit, 22 Oct 2026. */
class AirtimeCorrectionOnNavigatorTest {
    @Test fun aPersonNamedForAirtimeMakesItSendAirtime() {
        val pending = JSONObject().put("amount", "2").put("intent", "buy_airtime").put("_sim", "mtn")
        val r = ReviseTurn.patch("ussd_navigate", pending, "I said buy 2 cedis airtime for Loglo")!!
        assertEquals("Loglo", r.args.getString("contact"))
        assertEquals("send_airtime", r.args.getString("intent"))
        assertFalse(r.unchanged)
    }
}
