package com.tekyerema

import com.tekyerema.core.Privacy
import com.tekyerema.phone.WifiJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiJoinTest {
    @Test fun networkAndPasswordAreTakenFromOneSentence() {
        assertEquals(WifiJoin.Req("Home", "12345678"), WifiJoin.parse("Connect to Home Wi-Fi. The password is one two three four five six seven eight."))
        assertEquals(WifiJoin.Req("Office Guest", "kofi2024"), WifiJoin.parse("connect to Office Guest wifi, password is kofi 2024"))
        assertEquals(WifiJoin.Req("MTN Turbo", null), WifiJoin.parse("connect to MTN Turbo wifi"))
        assertEquals(WifiJoin.Req(null, "abc12345"), WifiJoin.parse("the password is abc 12345"))
        assertNull(WifiJoin.parse("turn on wifi"))
        assertNull(WifiJoin.parse("what is the time"))
    }

    @Test fun whichNetworkIsAQuestionNotAToggle() {
        for (q in listOf("which Wi-Fi am I connecting", "which Wi-Fi am I connected to", "what network am I on", "what's my wifi name",
                "what wifi is this"))
            assertTrue(q, WifiJoin.isWhich(q))
        for (q in listOf("turn off wifi", "connect to Home wifi", "is wifi on")) org.junit.Assert.assertFalse(q, WifiJoin.isWhich(q))
    }

    @Test fun howManyAppsIsCountedOnThePhone() {
        for (q in listOf("how many apps do I have", "How many apps are on my phone?", "how many applications are installed"))
            assertTrue(q, com.tekyerema.phone.DeviceFacts.isAppCount(q))
        org.junit.Assert.assertFalse(com.tekyerema.phone.DeviceFacts.isAppCount("open my apps"))
    }

    @Test fun thePasswordNeverSurvivesMasking() {
        val said = "Connect to Home Wi-Fi. The password is kofi2024"
        val masked = Privacy.maskCredentials(said)
        assertTrue(masked, !masked.contains("kofi2024") && masked.contains(Privacy.MASK))
        assertEquals("Home", WifiJoin.parse(masked)?.ssid)          // the masked request still names the network
    }
}
