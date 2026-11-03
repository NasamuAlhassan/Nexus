package com.tekyerema

import com.tekyerema.agent.HeardWords
import com.tekyerema.policy.ActionSemantics
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.Lang
import com.tekyerema.voice.LanguagePipeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 3 Nov 2026: the brains read the words as SAID; digits are only for the rule layer. Plus the read-back fixes. */
class HeardWordsTest {
    @After fun reset() = HeardWords.clear()

    @Test fun the_pipeline_keeps_the_words_as_said_beside_the_digits() {
        val t = LanguagePipeline.process("send five cedis to Ama", Lang.GH_EN, Lang.GH_EN)
        assertEquals("send 5 cedis to Ama", t.canonical)        // the rule layer's reading (digits for the money parser)
        assertEquals("send five cedis to Ama", t.asSaid)         // the brains read the words as said
        // a pronoun "one" stays a word in both (voice/Normalizer, 3 Nov 2026) → nothing extra to carry
        assertEquals("", LanguagePipeline.process("please use that one", Lang.GH_EN, Lang.GH_EN).asSaid)
        val same = LanguagePipeline.process("open settings", Lang.GH_EN, Lang.GH_EN)
        assertEquals("", same.asSaid)                            // nothing rewritten → nothing extra
    }

    @Test fun the_brain_goal_is_the_words_said_with_the_phones_reading_as_a_hint() {
        HeardWords.note("add them as 1 time events", "add them as one time events", now = 1_000)
        val g = HeardWords.goalForBrain("add them as 1 time events", now = 2_000)
        assertTrue(g, g.startsWith("add them as one time events"))
        assertTrue(g, "1 time events" in g)
        // another goal, or a stale note → the goal as it is
        assertEquals("open settings", HeardWords.goalForBrain("open settings", now = 2_000))
        assertEquals("add them as 1 time events", HeardWords.goalForBrain("add them as 1 time events", now = 1_000 + 11 * 60_000L))
        HeardWords.note("open settings", "open settings")
        assertNull(HeardWords.asSaid("open settings"))
    }

    @Test fun a_question_at_a_read_back_repeats_it_but_a_correction_does_not() {
        for (s in listOf("what did you type", "which message is that", "who is it going to", "what are you sending"))
            assertTrue(s, Confirmation.asksToRepeat(s))
        for (s in listOf("send it to Ama instead", "what, no, change it to tomorrow", "yes"))
            assertFalse(s, Confirmation.asksToRepeat(s))
    }

    @Test fun the_last_typed_words_are_read_from_the_steps_even_when_cut() {
        assertEquals("I'm on my way", ActionSemantics.lastTyped(listOf(
            "open_app({\"app\":\"WhatsApp\"}) → success", "type({\"text\":\"I'm on my way\",\"label\":\"Message\"}) → success")))
        val long = "a".repeat(200)
        val cut = "type(" + "{\"text\":\"$long\",\"label\":\"Message\"}".take(160) + ") → success"
        assertTrue(ActionSemantics.lastTyped(listOf(cut))!!.startsWith("aaaa"))
        assertNull(ActionSemantics.lastTyped(listOf("type({\"text\":\"x\"}) → failed: no field")))
        val line = ActionSemantics.confirmation("tap", null, "Send", null, lastTyped = "I'm on my way")
        assertTrue("$line", line != null && "I typed: “I'm on my way”" in line)
    }
}
