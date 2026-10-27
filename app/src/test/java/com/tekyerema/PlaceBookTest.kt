package com.tekyerema

import com.tekyerema.live.nav.AreaNote
import com.tekyerema.live.nav.PlaceBook
import com.tekyerema.live.nav.PlaceNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceBookTest {
    private val now = 1_000_000_000L

    @Test fun sameCellReplacesOlderNote() {
        val a = AreaNote("Ring Road", "Osu", 5.5610, -0.1810, now - 5_000)
        val b = AreaNote("Oxford Street", "Osu", 5.5611, -0.1811, now)
        val list = PlaceBook.addArea(PlaceBook.addArea(emptyList(), a), b)
        assertEquals(1, list.size)
        assertEquals("Oxford Street", list[0].street)
    }

    @Test fun emptyNoteIsIgnoredAndListIsCapped() {
        assertTrue(PlaceBook.addArea(emptyList(), AreaNote(null, " ", 5.0, 0.0, now)).isEmpty())
        var l = emptyList<AreaNote>()
        for (i in 0 until 80) l = PlaceBook.addArea(l, AreaNote("S$i", "A", 5.0 + i * 0.01, 0.0, now + i))
        assertEquals(PlaceBook.MAX_AREAS, l.size)
        assertEquals("S79", l[0].street)
    }

    @Test fun nearestAreaOnlyWithinRadius() {
        val l = listOf(AreaNote("Ring Road", "Osu", 5.5600, -0.1800, now))
        assertNotNull(PlaceBook.nearestArea(l, 5.5620, -0.1800))   // about 220 m
        assertNull(PlaceBook.nearestArea(l, 5.5700, -0.1800))      // about 1.1 km
    }

    @Test fun placeLookupByNameIgnoresCaseTheAndPunctuation() {
        val l = PlaceBook.addPlace(emptyList(), PlaceNote("Atlantic Mall", 5.6, -0.2, now))
        assertEquals("Atlantic Mall", PlaceBook.findPlace(l, "the atlantic mall!")?.name)
        assertEquals("Atlantic Mall", PlaceBook.findPlace(l, "atlantic")?.name)
        assertNull(PlaceBook.findPlace(l, "accra mall"))
        assertNull(PlaceBook.findPlace(l, "at"))
    }

    @Test fun addPlaceReplacesSameName() {
        var l = PlaceBook.addPlace(emptyList(), PlaceNote("Accra Mall", 5.0, 0.0, now))
        l = PlaceBook.addPlace(l, PlaceNote("accra mall", 5.1, 0.1, now + 1))
        assertEquals(1, l.size)
        assertEquals(5.1, l[0].lat, 0.0)
    }

    @Test fun ageWording() {
        assertEquals("moments ago", PlaceBook.ageWords(30_000))
        assertEquals("5 minutes ago", PlaceBook.ageWords(5 * 60_000L))
        assertEquals("an hour ago", PlaceBook.ageWords(70 * 60_000L))
        assertEquals("3 hours ago", PlaceBook.ageWords(3 * 3_600_000L))
        assertEquals("3 days ago", PlaceBook.ageWords(3 * 86_400_000L))
    }

    @Test fun whereOfflineSaysOfflineAgeAndDirection() {
        val note = AreaNote("Ring Road", "Osu", 5.0, 0.0, now - 3 * 3_600_000L)
        val s = PlaceBook.whereOffline(note, 120.0, 225.0, now, "14 meters", null)
        assertTrue(s, s.startsWith("I'm offline"))
        assertTrue(s, s.contains("3 hours ago"))
        assertTrue(s, s.contains("south-west of Ring Road, Osu"))
        assertTrue(s, s.contains("14 meters"))
        val near = PlaceBook.whereOffline(note, 20.0, 0.0, now, null, 90.0)
        assertTrue(near, near.contains("right by Ring Road, Osu"))
        assertTrue(near, near.contains("heading east"))
    }

    @Test fun whereOfflineDoesNotRepeatAreaEqualToStreet() {
        val note = AreaNote("Osu", "osu", 5.0, 0.0, now)
        assertTrue(PlaceBook.whereOffline(note, 10.0, 0.0, now, null, null).contains("right by Osu."))
    }

    @Test fun distanceOfflineIsStraightLineAndHonest() {
        val p = PlaceNote("Atlantic Mall", 5.6, -0.2, now - 2 * 86_400_000L)
        val s = PlaceBook.distanceOffline(p, 1240.0, 90.0, now, 10_000L)
        assertTrue(s, s.contains("Atlantic Mall is about 1.2 kilometres from you"))
        assertTrue(s, s.contains("east"))
        assertTrue(s, s.contains("2 days ago"))
        assertTrue(s, s.contains("without internet"))
        assertTrue(s, !s.contains("Your position is from"))
        assertTrue(PlaceBook.distanceOffline(p, 100.0, 0.0, now, 10 * 60_000L).contains("Your position is from 10 minutes ago"))
    }

    @Test fun jsonRoundTrip() {
        val areas = listOf(AreaNote("Ring Road", null, 5.56, -0.18, now))
        assertEquals(areas, PlaceBook.areasFromJson(PlaceBook.areasToJson(areas)))
        val places = listOf(PlaceNote("Accra Mall", 5.62, -0.17, now))
        assertEquals(places, PlaceBook.placesFromJson(PlaceBook.placesToJson(places)))
        assertTrue(PlaceBook.areasFromJson("garbage").isEmpty())
        assertTrue(PlaceBook.placesFromJson(null).isEmpty())
    }
}
