package com.tekyerema

import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speech repair, item 1 (research note docs/agent/research/speech_repair_deep_2026-11-03.md §1; owner's A20e 2–3 Nov 2026,
 * audit/real-requests-2026-11-03): the rule layer must not rewrite ordinary words — "that one" is not "that 1", and "to buy"
 * is not the contact "Boy". The numbers people mean still become digits.
 */
class SpeechRepairTest {
    private fun en(s: String) = Normalizer.normalize(s, "en")

    @Test fun one_as_a_pronoun_stays_a_word() {
        assertEquals("please use that one", en("please use that one"))          // r47
        assertEquals("one time events", en("one time events"))                  // r45
        assertTrue(en("which one is it").contains("which one"))
        assertTrue(en("no one called").contains("no one"))
        assertTrue(en("one of them is fine").startsWith("one of"))
        assertTrue(en("the blue one").endsWith("blue one"))
    }

    @Test fun numbers_people_mean_still_become_digits() {
        assertTrue(en("send one cedi to Ama"), en("send one cedi to Ama").contains("1"))
        assertTrue(en("set volume to one"), en("set volume to one").endsWith("1"))
        assertTrue(en("that one cedi"), en("that one cedi").contains("1"))     // a money word after "one": numeric
        assertTrue(en("one hundred cedis"), en("one hundred cedis").contains("100"))
        assertTrue(en("call zero two four one one two"), en("call zero two four one one two").contains("0 2 4 1 1 2") ||
            en("call zero two four one one two").contains("024112"))
    }

    private val vocab = EntityCorrector.Vocab.of(listOf("Boy", "Prince Mensah", "Richmond Apenensu"), emptyList(),
        common = setOf("buy", "the", "to", "car", "for", "me", "price", "want", "you"))

    @Test fun a_common_word_in_a_weak_slot_is_not_turned_into_a_contact() {
        // r20: "...the type of car to buy" → "...to Boy"; o05: "I want you to buy 1 cedis mashup" → "to Boy"
        assertEquals("I wanted to buy a car but I don't know the type of car to buy",
            EntityCorrector.correct("I wanted to buy a car but I don't know the type of car to buy", vocab).text)
        assertEquals("buy mashup for me I want you to buy 1 cedis mashup",
            EntityCorrector.correct("buy mashup for me I want you to buy 1 cedis mashup", vocab).text)
    }

    @Test fun a_contact_verb_still_corrects_a_sound_alike_name() {
        // A strong slot keeps the repair: "call Richmund" is a person.
        assertEquals("call Richmond", EntityCorrector.correct("call Richmund", vocab).text)
    }

    // ---- #3 names by sound across words (r41 "reach money" → Richmond; "bam" → Balme)
    private val people = EntityCorrector.Vocab.of(listOf("Richmond Apenensu", "Balme", "Raymond Osei", "Ama"), emptyList(),
        common = setOf("reach", "money", "send", "my", "to", "on", "remind", "me", "call", "the", "it", "brother", "bread"))

    @Test fun a_name_heard_as_two_words_is_matched_by_sound() {
        assertEquals("send my class schedules to Richmond on telegram",
            EntityCorrector.correct("send my class schedules to reach money on telegram", people).text)
        assertEquals("call Balme", EntityCorrector.correct("call bam", people).text)
    }

    @Test fun sound_matching_leaves_ordinary_words_alone() {
        assertEquals("call remind me", EntityCorrector.correct("call remind me", people).text)        // common word
        assertEquals("send it to my brother", EntityCorrector.correct("send it to my brother", people).text)
        assertEquals("call beam", EntityCorrector.correct("call beam", people).text)                  // too far from Balme
    }

    // ---- #2 the alternative the rule layer understands
    private fun h(vararg xs: Pair<String, Float?>) = xs.map { com.tekyerema.voice.io.Hypothesis(it.first, it.second) }

    @Test fun an_alternative_that_parses_wins_for_a_short_command() {
        val parses = { t: String -> t.contains("volume") }
        assertEquals(1, com.tekyerema.voice.io.NBest.pickByGrammar(h("make the follow me the highest" to 0.6f,
            "make the volume the highest" to 0.5f), parses = parses))
        // the top already parses → keep it
        assertEquals(0, com.tekyerema.voice.io.NBest.pickByGrammar(h("volume up" to 0.9f, "volume cup" to 0.4f), parses = parses))
        // a long sentence for the brain is never swapped
        assertEquals(0, com.tekyerema.voice.io.NBest.pickByGrammar(h("I want you to play I Believe on YouTube and make the follow me the highest" to 0.6f,
            "I want you to play I Believe on YouTube and make the volume the highest" to 0.5f), parses = parses))
        // a much less confident alternative is not taken
        assertEquals(0, com.tekyerema.voice.io.NBest.pickByGrammar(h("follow me up" to 0.9f, "volume up" to 0.2f), parses = parses))
    }

    // ---- #4 the last partial when the recogniser gives up
    @Test fun a_partial_is_used_only_when_it_has_real_words() {
        assertTrue(com.tekyerema.voice.io.PartialRescue.usable("turn the volume"))
        assertTrue(!com.tekyerema.voice.io.PartialRescue.usable("um"))
        assertTrue(!com.tekyerema.voice.io.PartialRescue.usable("  "))
        assertTrue(!com.tekyerema.voice.io.PartialRescue.usable(null))
    }

    // ---- #5 a sentence that can't end there keeps listening
    @Test fun hanging_words_keep_the_turn_open_but_real_endings_do_not() {
        assertTrue(com.tekyerema.voice.TurnDetector.looksUnfinished("send a message to Kofi saying"))
        assertTrue(com.tekyerema.voice.TurnDetector.looksUnfinished("meet me at"))
        assertTrue(!com.tekyerema.voice.TurnDetector.looksUnfinished("turn the flashlight on"))
        assertTrue(!com.tekyerema.voice.TurnDetector.looksUnfinished("I think so"))
        assertTrue(!com.tekyerema.voice.TurnDetector.looksUnfinished("I missed a call"))
    }
}
