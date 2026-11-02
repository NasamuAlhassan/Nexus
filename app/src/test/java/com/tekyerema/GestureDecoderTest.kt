package com.tekyerema

import com.tekyerema.ime.GestureDecoder
import com.tekyerema.ime.GestureDecoder.KeyBox
import com.tekyerema.ime.GestureDecoder.Pt
import com.tekyerema.ime.GestureDecoder.Source
import com.tekyerema.ime.GestureDecoder.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-05 (2) — swipe typing, decided on the phone: which keys a finger path crossed (and where it turned), and which
 * word that was. The board is a QWERTY of 100 px squares; a "path" joins key centres with straight lines, as a finger
 * roughly does, so every key between two letters is crossed on the way.
 */
class GestureDecoderTest {

    /** Row 0 at y 0..100; row 1 shifted right by half a key; row 2 by one and a half (the real layout's spacers). */
    private fun board(): List<KeyBox> {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val offsets = listOf(0f, 50f, 150f)
        return rows.flatMapIndexed { r, row ->
            row.mapIndexed { i, c -> val l = offsets[r] + i * 100f; val t = r * 100f; KeyBox(c.toString(), l, t, l + 100f, t + 100f) }
        }
    }
    private fun centre(keys: List<KeyBox>, c: Char): Pt {
        val k = keys.first { it.text == c.toString() }
        return Pt((k.left + k.right) / 2, (k.top + k.bottom) / 2)
    }
    private fun path(keys: List<KeyBox>, word: String): List<Pt> = word.map { centre(keys, it) }

    private val english = listOf(
        "you" to 28_787_591, "the" to 22_761_659, "me" to 8_500_000, "they" to 5_000_000, "can" to 3_000_000,
        "don't" to 3_000_000, "hello" to 1_500_000, "made" to 1_200_000, "mean" to 500_000, "cat" to 200_000,
        "yo" to 100_000, "hell" to 90_000, "hero" to 60_000, "dont" to 20_000, "ho" to 10_000, "oat" to 500)
    private val qwerty = board()

    @Test fun the_keys_a_path_crosses_come_in_order_without_repeats_or_gaps() {
        val seq = GestureDecoder.keySequence(path(qwerty, "hello"), qwerty)
        assertEquals("h", seq.first())
        assertEquals("o", seq.last())
        assertTrue(seq.toString(), seq.indexOf("e") in 1 until seq.indexOf("l"))
        assertTrue("g lies on the straight line from h to e: $seq", "g" in seq)
        assertTrue("no key twice in a row", seq.zipWithNext().none { it.first == it.second })
        // Two far-apart samples (a fast finger) still visit every key between them.
        assertEquals("qwertyuiop".map { it.toString() }, GestureDecoder.keySequence(listOf(centre(qwerty, 'q'), centre(qwerty, 'p')), qwerty))
        assertTrue(GestureDecoder.keySequence(emptyList(), qwerty).isEmpty())
        // A point between two keys (in the gap) belongs to neither; the boxes the view builds include the margins.
        assertTrue(GestureDecoder.keySequence(listOf(Pt(-5f, 50f)), qwerty).isEmpty())
    }

    @Test fun the_keys_the_user_meant_are_the_ends_and_the_corners() {
        val steps = GestureDecoder.steps(path(qwerty, "hello"), qwerty)
        assertEquals(listOf("h", "e", "l", "o"), steps.filter { it.anchor }.map { it.key })   // start, two corners, end
        assertFalse("a key merely passed over", steps.first { it.key == "g" }.anchor)
        // A straight slide has no corners: only its ends are meant.
        val straight = GestureDecoder.steps(listOf(centre(qwerty, 'q'), centre(qwerty, 'p')), qwerty)
        assertEquals(listOf("q", "p"), straight.filter { it.anchor }.map { it.key })
    }

    @Test fun a_swipe_is_only_a_swipe_after_a_second_key_and_some_travel() {
        val q = centre(qwerty, 'q'); val w = centre(qwerty, 'w')
        assertFalse(GestureDecoder.isGesture(listOf(q), qwerty, 50f))
        assertFalse("a wobble inside one key", GestureDecoder.isGesture(listOf(q, Pt(q.x + 8, q.y + 4), Pt(q.x - 6, q.y)), qwerty, 50f))
        assertTrue(GestureDecoder.isGesture(listOf(q, Pt(q.x + 50, q.y), w), qwerty, 50f))
        assertFalse("not enough travel yet", GestureDecoder.isGesture(listOf(q, Pt(q.x + 50, q.y), w), qwerty, 500f))
    }

    @Test fun common_words_decode_from_their_paths() {
        fun top(word: String, limit: Int = 3) = GestureDecoder.decode(GestureDecoder.steps(path(qwerty, word), qwerty), listOf(Source(english)), limit)
        assertEquals("hello", top("hello").first())
        assertTrue(top("hello").toString(), "hero" in top("hello"))
        assertEquals("the", top("the").first())
        assertEquals("you", top("you").first())
        assertEquals("don't", top("dont").first())
        assertEquals("can", top("can").first())
        assertEquals(2, top("hello", 2).size)
        assertTrue(top("hello", 0).isEmpty())
        assertTrue("no word starts with x and ends on q", top("xq").isEmpty())
        assertTrue(GestureDecoder.decode(emptyList<Step>(), listOf(Source(english))).isEmpty())
        assertTrue(GestureDecoder.decodeKeys(listOf("t", "h", "e"), emptyList()).isEmpty())
        assertEquals("the", GestureDecoder.decodeKeys(listOf("t", "h", "e"), english).first())
    }

    @Test fun a_name_with_akan_letters_is_swiped_on_the_plain_keys() {
        val names = listOf(Source(listOf("kwabɛna" to 300, "medaase" to 1)), Source(english))
        fun top(word: String) = GestureDecoder.decode(GestureDecoder.steps(path(qwerty, word), qwerty), names)
        assertEquals("kwabɛna", top("kwabena").first())     // the e key reads as an ɛ
        assertEquals("medaase", top("medase").first())      // the double a needs no second visit
        assertTrue(GestureDecoder.match("e", 'ɛ') < 1.0 && GestureDecoder.match("e", 'ɛ') > 0.0)
        assertEquals("the o key is not a c", 0.0, GestureDecoder.match("o", 'c'), 0.0)
        assertEquals("accents fold to the base letter", 0.75, GestureDecoder.match("e", 'é'), 0.0)
    }

    @Test fun the_alignment_rules() {
        fun steps(vararg k: String) = k.mapIndexed { i, s -> Step(s, i == 0 || i == k.lastIndex) }
        val full = GestureDecoder.align("hello", steps("h", "e", "l", "o"))!!
        val missingE = GestureDecoder.align("hello", steps("h", "l", "o"))!!
        assertTrue("a letter the path never touched costs", full > missingE)
        assertNull("the first letter must be the first key", GestureDecoder.align("xyz", steps("a", "x")))
        assertNull("three letters the path never touched", GestureDecoder.align("abcd", steps("a", "z")))
        assertTrue("keys passed over cost a little",
            GestureDecoder.align("ho", steps("h", "o"))!! > GestureDecoder.align("ho", steps("h", "j", "k", "l", "o"))!!)
        assertEquals("apostrophes are free", GestureDecoder.align("dont", steps("d", "o", "n", "t")), GestureDecoder.align("don't", steps("d", "o", "n", "t")))
        assertTrue("a corner the word does not use counts against it",
            GestureDecoder.align("ho", steps("h", "o"))!! > GestureDecoder.align("ho", listOf(Step("h", true), Step("j", true), Step("o", true)))!!)
    }
}
