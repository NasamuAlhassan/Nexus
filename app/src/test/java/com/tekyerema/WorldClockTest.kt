package com.tekyerema

import com.tekyerema.agent.WorldClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The time in another city, from the phone's clock and time-zone rules. "Now" is 3:05 PM in Accra, 29 Sep 2026. */
class WorldClockTest {
    private val accra = ZoneId.of("Africa/Accra")
    private val now = ZonedDateTime.of(2026, 9, 29, 15, 5, 0, 0, accra)
    private fun a(s: String, at: ZonedDateTime = now) = WorldClock.answer(s.lowercase(), at)

    @Test fun cities_and_single_zone_countries() {
        assertEquals("In London it's 4:05 PM, 1 hour ahead of Ghana.", a("What time is it in London?"))    // British Summer Time
        assertEquals("In New York it's 11:05 AM, 4 hours behind Ghana.", a("what's the time in New York"))
        assertEquals("In Lagos it's 4:05 PM, 1 hour ahead of Ghana.", a("time in Lagos"))
        assertEquals("In Nigeria it's 4:05 PM, 1 hour ahead of Ghana.", a("what is the current time in nigeria"))
        assertEquals("In the UK it's 4:05 PM, 1 hour ahead of Ghana.", a("what time is it in the UK right now"))
        assertEquals("In Delhi it's 8:35 PM, 5 and a half hours ahead of Ghana.", a("what time is it in delhi"))
        assertEquals("In Tokyo it's 12:05 AM on Wednesday, 9 hours ahead of Ghana.", a("what time is it in tokyo"))
        assertEquals("In Lomé it's 3:05 PM, the same time as Ghana.", a("what time is it in lome"))
        assertEquals("In Kumasi it's 3:05 PM.", a("what time is it in Kumasi"))
        assertEquals("In Toronto it's 11:05 AM, 4 hours behind Ghana.", a("please tell me the time in toronto"))
        assertEquals("In London it's 4:05 PM, 1 hour ahead of Ghana.", a("Can you tell me what time it is in London?"))
        assertEquals("In London it's 4:05 PM, 1 hour ahead of Ghana.", a("what's London time now"))
        assertNull("not a place", a("what's my screen time"))
        assertFalse(WorldClock.asksElsewhere("what's my screen time"))
    }

    @Test fun summer_time_is_followed() {
        val december = ZonedDateTime.of(2026, 12, 1, 15, 5, 0, 0, accra)
        assertEquals("In London it's 3:05 PM, the same time as Ghana.", a("what time is it in london", december))
        assertEquals("In New York it's 10:05 AM, 5 hours behind Ghana.", a("what time is it in new york", december))
    }

    @Test fun countries_with_several_zones_get_two_cities() {
        assertEquals("The United States has several time zones: in New York it's 11:05 AM, and in Los Angeles it's 8:05 AM.",
            a("what time is it in America"))
        assertEquals("Canada has several time zones: in Toronto it's 11:05 AM, and in Vancouver it's 8:05 AM.", a("time in canada"))
    }

    @Test fun time_difference() {
        assertEquals("London is 1 hour ahead of Ghana.", a("what's the time difference with London"))
        assertEquals("Dubai is 4 hours ahead of Ghana.", a("time difference to dubai"))
    }

    @Test fun away_from_ghana_it_says_you() {
        val london = ZonedDateTime.of(2026, 9, 29, 16, 5, 0, 0, ZoneId.of("Europe/London"))
        assertEquals("In Accra it's 3:05 PM, 1 hour behind you.", a("what time is it in accra", london))
    }

    @Test fun unknown_places_are_flagged_not_answered() {
        assertNull(a("what time is it in brazil"))
        assertTrue(WorldClock.asksElsewhere("what time is it in brazil"))
        assertTrue(WorldClock.asksElsewhere("what's the time in timbuktu"))
        for (s in listOf("what time is it", "what's the time", "tell me the time", "what time is it now", "what is the current time",
            "what's the time at the moment", "what time is my alarm", "what time does the match start"))
            assertFalse(s, WorldClock.asksElsewhere(s))
    }
}
