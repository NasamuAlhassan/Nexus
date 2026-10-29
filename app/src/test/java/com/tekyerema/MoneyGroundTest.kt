package com.tekyerema

import com.tekyerema.agent.MoneyGround
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Your MoMo balance is 57 cedis" with the balance hidden (Infinix, 29 Oct 2026). */
class MoneyGroundTest {
    @Test fun aBalanceNobodySawIsNotSaid() {
        assertEquals("57", MoneyGround.unseen("Your MoMo balance is 57 cedis.", "MoMo Balance GHS **** Show MoMo balance 7:57", emptyList(), "What's my momo balance"))
    }

    @Test fun aBalanceOnScreenOrInAToolResultIs() {
        assertNull(MoneyGround.unseen("Your MoMo balance is 57 cedis.", "MoMo Balance GHS 57.00", emptyList(), "What's my momo balance"))
        assertNull(MoneyGround.unseen("Your balance is GHS 1,250.50.", "", listOf("ussd_navigate → success: Current Balance: 1250.50"), "check my balance"))
        assertNull(MoneyGround.unseen("I sent 5 cedis to My Wife.", "", emptyList(), "send 5 cedis to my wife"))
        assertNull(MoneyGround.unseen("Done. I opened myMTN.", "", emptyList(), "open mtn"))
    }
}

class UnseenDetailTest {
    @Test fun anUnconfirmedAnswersMadeUpDetailsAreCaught() {
        assertEquals("27", MoneyGround.unseenDetail("It is 27 degrees in Accra.", "Weather Accra Sunny", emptyList(), "what's the weather"))
        assertEquals(true, MoneyGround.unseenDetail("Kofi wrote “See you at 6”.", "Kofi Hello", emptyList(), "read Kofi's message") != null)
        assertEquals("see you soon", MoneyGround.unseenDetail("Kofi wrote “see you soon”.", "Kofi Hello", emptyList(), "read Kofi's message"))
        assertNull(MoneyGround.unseenDetail("I opened WhatsApp.", "Chats", emptyList(), "open whatsapp"))
        assertNull(MoneyGround.unseenDetail("Your alarm is set for 6:30.", "Alarm 6:30 AM", emptyList(), "set an alarm for 6:30"))
    }
}
