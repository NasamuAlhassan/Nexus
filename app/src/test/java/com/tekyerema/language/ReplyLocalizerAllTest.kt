package com.tekyerema.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A whole English reply into Twi, in pieces (owner, 20 Oct 2026: whatever is said in Twi is Twi). */
class ReplyLocalizerAllTest {
    @Test fun sentencesAreGatheredIntoPiecesUnderTheLimit() {
        val text = "You have a message from Ama. It says: come home soon. Another one from Kofi | reply when you can. Last one."
        val p = ReplyLocalizer.pieces(text, 60)
        assertTrue(p.all { it.length <= 60 })
        assertEquals(text.replace(" | ", " ").replace(Regex("\\s+"), " "), p.joinToString(" "))
    }

    @Test fun aLongSentenceIsCutAtACommaOrSpace() {
        val text = "word ".repeat(100).trim()
        val p = ReplyLocalizer.pieces(text, 80)
        assertTrue(p.size > 1)
        assertTrue(p.all { it.length <= 80 })
    }

    @Test fun aMessageInQuotesIsTranslatedNotKept() {
        val s = "It says: \"Please send me the report tomorrow morning\" from \"Mum\"."
        assertTrue("Please send me the report tomorrow morning" in ReplyLocalizer.protectedTerms(s))
        val all = ReplyLocalizer.protectedTerms(s, keepLongQuotes = false)
        assertTrue("Please send me the report tomorrow morning" !in all)
        assertTrue("Mum" in all)
    }
}
