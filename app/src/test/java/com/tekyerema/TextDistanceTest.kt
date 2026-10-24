package com.tekyerema

import com.tekyerema.core.TextDistance
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one edit distance every fuzzy match uses (contacts, apps, Twi commands, the network name, camera labels). */
class TextDistanceTest {
    @Test fun classicLevenshtein() {
        assertEquals(3, TextDistance.levenshtein("kitten", "sitting"))
        assertEquals(0, TextDistance.levenshtein("mtn", "mtn"))
        assertEquals(3, TextDistance.levenshtein("", "mtn"))
        assertEquals(1, TextDistance.levenshtein("kofi", "kofii"))
    }

    @Test fun similarityIsZeroWhenEitherIsEmpty() {
        assertEquals(1.0, TextDistance.similarity("ama", "ama"), 1e-9)
        assertEquals(0.0, TextDistance.similarity("", "ama"), 1e-9)
        assertEquals(0.0, TextDistance.similarity("", ""), 1e-9)
        assertEquals(1.0 - 1.0 / 5, TextDistance.similarity("kwame", "kwami"), 1e-9)
    }
}
