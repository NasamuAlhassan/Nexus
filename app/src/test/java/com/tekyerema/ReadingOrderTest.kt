package com.tekyerema

import com.tekyerema.camera.ReadingOrder
import com.tekyerema.camera.ReadingOrder.Item
import org.junit.Assert.assertEquals
import org.junit.Test

/** P5-03 (3): the page's words in the order a sighted reader takes them — columns whole, titles first. */
class ReadingOrderTest {
    private fun it(text: String, left: Int, top: Int, right: Int, bottom: Int) = Item(text, left, top, right, bottom)

    @Test fun a_single_column_notice_with_ragged_indents_keeps_its_order() {
        val page = listOf(
            it("University of Ghana, Legon", 72, 60, 420, 90),
            it("Dear Kofi,", 72, 130, 200, 155),
            it("The meeting is on Monday", 90, 180, 520, 205),   // indented a little
            it("Thank you,", 72, 240, 190, 265))
        assertEquals(listOf("University of Ghana, Legon", "Dear Kofi,", "The meeting is on Monday", "Thank you,"),
            ReadingOrder.order(page, pageWidth = 612).map { it.text })
    }

    @Test fun two_columns_are_read_whole_left_then_right() {
        val page = listOf(
            it("L1", 40, 100, 280, 120), it("R1", 320, 102, 560, 122),
            it("L2", 40, 130, 280, 150), it("R2", 320, 131, 560, 151),
            it("L3", 40, 160, 280, 180), it("R3", 320, 162, 560, 182))
        assertEquals(listOf("L1", "L2", "L3", "R1", "R2", "R3"), ReadingOrder.order(page, pageWidth = 612).map { it.text })
    }

    @Test fun a_full_width_title_comes_first_and_a_full_width_footer_last() {
        val page = listOf(
            it("R1", 320, 102, 560, 122), it("L1", 40, 100, 280, 120),
            it("NOTICE TO ALL STUDENTS", 40, 30, 570, 70),
            it("L2", 40, 130, 280, 150), it("R2", 320, 131, 560, 151),
            it("Keep this notice safe.", 40, 300, 560, 320))
        assertEquals(listOf("NOTICE TO ALL STUDENTS", "L1", "L2", "R1", "R2", "Keep this notice safe."),
            ReadingOrder.order(page, pageWidth = 612).map { it.text })
        assertEquals("NOTICE TO ALL STUDENTS\nL1\nL2\nR1\nR2\nKeep this notice safe.", ReadingOrder.text(page, 612))
    }

    @Test fun one_block_or_none_is_itself() {
        assertEquals(emptyList<Item>(), ReadingOrder.order(emptyList()))
        assertEquals(listOf("only"), ReadingOrder.order(listOf(it("only", 0, 0, 10, 10))).map { it.text })
    }
}
