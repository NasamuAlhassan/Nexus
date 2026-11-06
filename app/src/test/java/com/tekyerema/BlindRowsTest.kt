package com.tekyerema

import com.tekyerema.accessibility.BlindRows
import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telegram's chat rows name themselves to nobody: OCR reads them, one row one element (A20e, 6 Nov 2026; bounds as measured). */
class BlindRowsTest {
    private val row1 = Box(0, 356, 720, 497)
    private val row2 = Box(0, 497, 720, 638)
    private var next = 100
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) =
        UiElement(id = next++, role = "ocr_text", label = text, bounds = Box(l, t, r, b), source = ElementSource.VISION, clickable = true)

    @Test fun a_row_reads_as_a_person_reads_it_and_can_be_tapped() {
        val ocr = listOf(
            line("Ok see you", 130, 440, 400, 475),
            line("10:42", 620, 372, 700, 402),
            line("Richmond Apenensu", 130, 370, 450, 405))
        val (rows, rest) = BlindRows.merge(listOf(row1), ocr, startId = 18)
        assertEquals(1, rows.size)
        assertEquals("Richmond Apenensu, 10:42, Ok see you", rows[0].label)
        assertEquals(18, rows[0].id)
        assertEquals(row1, rows[0].bounds)
        assertEquals(ElementSource.VISION, rows[0].source)
        assertTrue(rows[0].actions.contains("tap"))
        assertTrue(rest.isEmpty())
    }

    @Test fun avatar_initials_are_not_the_name() {
        val ocr = listOf(line("RA", 30, 405, 90, 450), line("Richmond Apenensu", 130, 370, 450, 405))
        assertEquals("Richmond Apenensu", BlindRows.merge(listOf(row1), ocr, 0).first.single().label)
    }

    @Test fun rows_keep_their_order_and_their_own_words() {
        val ocr = listOf(line("Ama Owusu", 130, 510, 350, 545), line("Richmond Apenensu", 130, 370, 450, 405))
        val rows = BlindRows.merge(listOf(row2, row1), ocr, 5).first
        assertEquals(listOf("Richmond Apenensu", "Ama Owusu"), rows.map { it.label })
        assertEquals(listOf(5, 6), rows.map { it.id })
    }

    @Test fun a_row_with_no_words_is_dropped_and_loose_lines_stay_out() {
        val tab = line("All Chats", 31, 280, 300, 330)
        val (rows, rest) = BlindRows.merge(listOf(row1, row2), listOf(tab, line("Ama Owusu", 130, 510, 350, 545)), 0)
        assertEquals(listOf("Ama Owusu"), rows.map { it.label })
        assertEquals(listOf(tab), rest)
    }

    @Test fun only_row_shaped_boxes_count() {
        assertTrue(BlindRows.isRow(row1, 720, 1560))
        assertFalse("too narrow", BlindRows.isRow(Box(0, 356, 300, 497), 720, 1560))
        assertFalse("a whole page", BlindRows.isRow(Box(0, 0, 720, 1406), 720, 1560))
        assertFalse("a sliver", BlindRows.isRow(Box(0, 356, 720, 370), 720, 1560))
    }
}
