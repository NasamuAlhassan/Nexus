package com.tekyerema

import com.tekyerema.live.nav.DistanceAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "How far is X (from here)" is answered on the phone from its own location, never by the planner. */
class DistanceAskTest {
    @Test fun `the owner's question`() {
        assertEquals("atlantic mall", DistanceAsk.parse("how far is the Atlantic mall from here"))
    }

    @Test fun `plain wordings that mean from where I am`() {
        assertEquals("accra mall", DistanceAsk.parse("How far is Accra Mall?"))
        assertEquals("kumasi", DistanceAsk.parse("how far is kumasi from me"))
        assertEquals("legon", DistanceAsk.parse("okay how far away is legon from where I am"))
        assertEquals("the airport".removePrefix("the "), DistanceAsk.parse("how far to the airport"))
        assertEquals("osu", DistanceAsk.parse("what's the distance to Osu"))
        assertEquals("accra mall", DistanceAsk.parse("how long does it take to get to Accra Mall"))
        assertEquals("makola market", DistanceAsk.parse("distance to Makola market"))
    }

    @Test fun `another starting point, planets and parts of the phone are not ours`() {
        assertNull(DistanceAsk.parse("how far is Kumasi from Accra"))
        assertNull(DistanceAsk.parse("how far is the moon"))
        assertNull(DistanceAsk.parse("how far is the sun from earth"))
        assertNull(DistanceAsk.parse("how far is it"))
        assertNull(DistanceAsk.parse("how far is the battery"))
        assertNull(DistanceAsk.parse("how long does it take to charge"))
        assertNull(DistanceAsk.parse("what time is it"))
    }
}
