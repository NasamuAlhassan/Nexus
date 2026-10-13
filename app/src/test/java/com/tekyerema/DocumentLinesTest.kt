package com.tekyerema

import com.tekyerema.camera.DocumentLines
import org.junit.Assert.assertEquals
import org.junit.Test

/** P5-03 v0: what the document reader says around a page's words. */
class DocumentLinesTest {
    @Test fun the_reader_orients_the_listener_and_offers_the_next_page() {
        assertEquals("A document with 2 pages. Page 1.", DocumentLines.intro(2))
        assertEquals("A one-page document.", DocumentLines.intro(1))
        assertEquals("Page 2.", DocumentLines.pageStart(1))
        assertEquals("End of page 1 of 2. Tap for the next page.", DocumentLines.pageEnd(0, 2))
        assertEquals("That was the last page, 2 of 2. Press back to leave.", DocumentLines.pageEnd(1, 2))
        assertEquals("That's the whole document. Tap to hear it again, or press back to leave.", DocumentLines.pageEnd(0, 1))
        assertEquals("I couldn't find any text on page 3.", DocumentLines.empty(2, 5))
        assertEquals("I couldn't find any text in this document.", DocumentLines.empty(0, 1))
    }
}
