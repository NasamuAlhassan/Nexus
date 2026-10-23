package com.tekyerema

import com.tekyerema.money.TelcoUssd
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No menu walk is promised on a network the brain has no official root for (audit, 22 Oct 2026). */
class TelcoUnsupportedLineTest {
    @Test fun noMenuWalkIsPromisedOnANetworkWithoutAnOfficialRoot() {
        val r = TelcoUssd.parse("buy a mashup bundle", "telecel")
        assertTrue(r is TelcoUssd.Request.Unsupported)
        val line = (r as TelcoUssd.Request.Unsupported).line
        assertFalse(line, line.contains("main menu"))
        assertTrue(line, line.contains("never guess") && line.contains("Nothing was dialled"))
    }
}
