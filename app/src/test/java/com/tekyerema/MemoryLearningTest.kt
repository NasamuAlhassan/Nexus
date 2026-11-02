package com.tekyerema

import com.tekyerema.memory.OfferTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #19 ask-once learning: "Should I remember that 'my wife' means Pearl?" — once, never with money, never twice for the same fact. */
class MemoryLearningTest {
    private val known: (String) -> Boolean = { false }

    private fun tracker() = OfferTracker(OfferTracker.MemoryAsked())

    @Test fun a_relation_word_then_a_chosen_contact_makes_one_offer() {
        val t = tracker()
        t.noteSaid("wife", "my wife", 1_000)
        assertTrue(t.contactUsed("Pearl Asante", 20_000, known))
        val o = t.take("tell my wife I'm on my way", 30_000, secure = false)!!
        assertEquals("Should I remember that “my wife” means Pearl Asante?", o.question)
        assertEquals("wife", o.relation)
        assertEquals("Pearl Asante", o.name)
    }

    @Test fun never_asked_twice_for_the_same_fact() {
        val asked = OfferTracker.MemoryAsked()
        val t = OfferTracker(asked)
        t.noteSaid("wife", "my wife", 0); t.contactUsed("Pearl Asante", 1_000, known)
        assertNotNull(t.take("text my wife hi", 2_000, false))
        assertNull("taking it clears it", t.take("text my wife hi", 3_000, false))
        // The same thing happens again later, in this run or the next (the asked hashes are stored): no new offer.
        t.noteSaid("wife", "my wife", 10_000)
        assertFalse(t.contactUsed("Pearl Asante", 11_000, known))
        val later = OfferTracker(asked)
        later.noteSaid("wife", "my wife", 20_000)
        assertFalse(later.contactUsed("pearl asante", 21_000, known))
        // A different person for the same word is a different fact.
        later.noteSaid("wife", "my wife", 30_000)
        assertTrue(later.contactUsed("Adwoa Pearl", 31_000, known))
    }

    @Test fun nothing_to_learn_when_the_relation_is_already_saved() {
        val t = tracker()
        t.noteSaid("wife", "my wife", 0)
        assertFalse(t.contactUsed("Pearl Asante", 1_000) { it == "wife" })
    }

    @Test fun a_contact_the_word_already_points_at_is_not_worth_a_question() {
        assertFalse(OfferTracker.worthAsking("my wife", "Wife"))
        assertFalse(OfferTracker.worthAsking("my wife", "My Wife"))
        assertFalse(OfferTracker.worthAsking("wife", "Wife Pearl"))
        assertFalse(OfferTracker.worthAsking("Mum", "Mum 2"))
        assertFalse(OfferTracker.worthAsking("my wife", "0241234567"))
        assertFalse(OfferTracker.worthAsking("my wife", "him"))
        assertTrue(OfferTracker.worthAsking("my wife", "Pearl Asante"))
        assertTrue(OfferTracker.worthAsking("Mum", "Grace Mensah"))
    }

    @Test fun a_word_looked_up_long_ago_or_a_contact_used_without_one_makes_no_offer() {
        val t = tracker()
        assertFalse("no relation word was looked up", t.contactUsed("Pearl Asante", 1_000, known))
        t.noteSaid("wife", "my wife", 0)
        assertFalse("looked up two minutes and more before", t.contactUsed("Pearl Asante", 130_000, known))
        t.noteSaid("wife", "my wife", 0)
        assertTrue(t.contactUsed("Pearl Asante", 100_000, known))
        assertFalse("the lookup is used up by the first contact", t.contactUsed("Kofi Mensah", 101_000, known))
    }

    @Test fun never_with_a_secret_being_typed_or_a_money_request() {
        val t = tracker()
        t.noteSaid("mother", "Mum", 0); t.contactUsed("Grace Mensah", 1_000, known)
        assertNull("PIN screen", t.take("call Mum", 2_000, secure = true))
        assertNull("money", t.take("send 50 cedis to Mum on MoMo", 3_000, secure = false))
        assertNull("money, however it is worded", t.take("pay my mum", 4_000, secure = false))
        val o = t.take("what time is it", 5_000, secure = false)
        assertNotNull("it waits for an ordinary task", o)
        assertEquals("Should I remember that “Mum” means Grace Mensah?", o!!.question)
    }

    @Test fun an_offer_not_asked_within_five_minutes_is_dropped() {
        val t = tracker()
        t.noteSaid("wife", "my wife", 0); t.contactUsed("Pearl Asante", 1_000, known)
        assertNull(t.take("what time is it", 400_000, false))
    }

    @Test fun the_id_hides_the_name_and_tells_facts_apart() {
        val id = OfferTracker.idOf("wife", "Pearl Asante")
        assertEquals(12, id.length)
        assertFalse(id.contains("pearl", true))
        assertEquals(id, OfferTracker.idOf("Wife", " pearl asante "))
        assertNotEquals(id, OfferTracker.idOf("mother", "Pearl Asante"))
    }

    @Test fun the_answer_can_be_looked_up_after_the_offer_is_taken() {
        val t = tracker()
        t.noteSaid("wife", "my wife", 0); t.contactUsed("Pearl Asante", 1_000, known)
        val o = t.take("hi", 2_000, false)!!
        assertEquals("Pearl Asante", t.takenOffer(o.id)!!.name)
        assertNull(t.takenOffer("other"))
    }
}
