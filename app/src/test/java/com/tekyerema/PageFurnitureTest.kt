package com.tekyerema

import com.tekyerema.camera.PageFurniture
import org.junit.Assert.assertEquals
import org.junit.Test

/** P5-03 (2): page numbers and running headers/footers are not read out. */
class PageFurnitureTest {
    @Test fun a_bare_page_number_at_the_top_or_bottom_goes() {
        assertEquals("Dear Kofi,\nSee you Monday.", PageFurniture.strip("2\nDear Kofi,\nSee you Monday.", null))
        assertEquals("Dear Kofi,\nSee you Monday.", PageFurniture.strip("Dear Kofi,\nSee you Monday.\n- 2 -", null))
        assertEquals("Dear Kofi,\nSee you Monday.", PageFurniture.strip("Page 2 of 5\nDear Kofi,\nSee you Monday.\n2/5", null))
    }

    @Test fun a_running_header_and_footer_are_recognised_from_the_previous_page() {
        val page1 = "UNIVERSITY OF GHANA\nDear Kofi,\nSee you Monday.\nRegistrar's office"
        val page2 = "UNIVERSITY OF GHANA\nBring your ID.\nThank you.\nRegistrar's office"
        assertEquals("Bring your ID.\nThank you.", PageFurniture.strip(page2, page1))
        // the first page keeps its first line: nothing to compare it with
        assertEquals(page1, PageFurniture.strip(page1, null))
    }

    @Test fun a_number_in_the_header_does_not_hide_the_repeat() {
        assertEquals("Second page body", PageFurniture.strip("Notice 2 of 3\nSecond page body", "Notice 1 of 3\nFirst page body"))
    }

    @Test fun a_page_is_never_emptied_and_a_one_line_page_is_untouched() {
        assertEquals("3", PageFurniture.strip("3", null))
        assertEquals("8", PageFurniture.strip("7\n8", null))   // the top number goes, the last line stays
        assertEquals("Only line", PageFurniture.strip("Only line", "Only line"))
    }
}
