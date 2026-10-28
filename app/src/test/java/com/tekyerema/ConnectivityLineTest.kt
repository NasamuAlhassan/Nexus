package com.tekyerema

import com.tekyerema.core.ConnectivityLine
import com.tekyerema.core.ConnectivityLine.Health
import com.tekyerema.core.LinkHealthTracker.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityLineTest {
    @Test fun healthComesFromRequestsNotSignal() {
        assertEquals(Health.FAILING, ConnectivityLine.health(Verdict.STALLED, 200, 3000))
        assertEquals(Health.SLOW, ConnectivityLine.health(Verdict.SUSPECT, 200, 3000))
        assertEquals(Health.SLOW, ConnectivityLine.health(Verdict.GOOD, 4200, 3000))
        assertEquals(Health.GOOD, ConnectivityLine.health(Verdict.GOOD, 400, 3000))
        assertEquals(Health.UNKNOWN, ConnectivityLine.health(Verdict.UNKNOWN, 0, 3000))
    }

    @Test fun eachStateIsSaidPlainly() {
        assertTrue(ConnectivityLine.describe(null, false, Health.UNKNOWN, 0).startsWith("You're offline"))
        assertTrue(ConnectivityLine.describe("Wi-Fi", false, Health.GOOD, 0).contains("isn't reaching the internet"))
        assertTrue(ConnectivityLine.describe("Wi-Fi", true, Health.SLOW, 4200).contains("about 4 seconds"))
        assertTrue(ConnectivityLine.describe("mobile data", true, Health.FAILING, 0).contains("failing"))
        assertEquals("You're online over Wi-Fi, and the internet is responding well.", ConnectivityLine.describe("Wi-Fi", true, Health.GOOD, 300))
    }
}
