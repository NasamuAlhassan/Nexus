package com.tekyerema

import com.tekyerema.companion.CompanionGuard
import com.tekyerema.companion.CompanionGuard.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Companion mode's hard rules: who counts as distressed, and the warm, honest, number-free-of-invention replies. */
class CompanionGuardTest {
    @Test fun self_harm_words_are_a_crisis_anywhere_in_the_sentence() {
        for (s in listOf("I don't want to live anymore", "I'm thinking of ending my life", "everyone would be better off without me",
            "I want to kill myself", "sometimes I wish I would not wake up", "I am a burden to everyone, I wish to disappear",
            "I think it would be better if I were gone", "i feel suicidal", "I want to hurt myself", "there is no point in living",
            "I just want to die", "I wish I was dead"))
            assertEquals(s, Level.CRISIS, CompanionGuard.level(s))
    }

    @Test fun low_mood_statements_about_oneself() {
        for (s in listOf("I'm so lonely", "I feel so alone", "I feel like giving up", "I am hopeless", "I feel worthless", "i'm so sad",
            "nobody cares about me", "I can't go on", "I'm tired of everything", "my heart is heavy", "chale I feel so lonely tonight",
            "I don't feel good in my heart today"))
            assertEquals(s, Level.LOW, CompanionGuard.level(s))
    }

    @Test fun ordinary_requests_are_never_taken_for_distress() {
        for (s in listOf("read my messages", "I'm alone in the car, read my messages", "call my mother", "give up",
            "I give up, I can't find the button", "I'm tired", "I don't want to go on this call", "kill the app", "end the call",
            "I'm sad that Ghana lost, who won", "turn off the lights, I'm sleepy", "I am lost, where am I", "I am lost",
            "what is the time", "I'm at the market"))
            assertEquals(s, Level.NONE, CompanionGuard.level(s))
    }

    @Test fun a_long_sentence_with_a_sad_word_is_not_the_statement() {
        assertEquals(Level.NONE, CompanionGuard.level("I'm lonely but please call Kofi and tell him I will be at the shop by four o'clock today"))
    }

    @Test fun the_low_reply_is_warm_offers_the_helper_and_asks_for_no_return() {
        val withHelper = CompanionGuard.reply(Level.LOW, helper = "Ama")
        assertTrue(withHelper.contains("call my helper"))
        assertTrue(withHelper.contains("Ama"))
        val without = CompanionGuard.reply(Level.LOW, helper = null)
        assertTrue(without.contains("person you trust"))
        assertFalse(without.contains("call my helper"))
        for (r in listOf(withHelper, without)) {
            assertFalse(r, r.contains("diagnos", ignoreCase = true))
            assertFalse(r, Regex("(?i)\\b(?:come back|don't leave|stay with me always|promise)\\b").containsMatchIn(r))
            assertTrue(r.length < 300)
        }
    }

    @Test fun the_crisis_reply_has_the_helper_the_emergency_line_and_the_truth_about_being_an_ai() {
        val r = CompanionGuard.reply(Level.CRISIS, helper = "Ama", emergency = "112")
        assertTrue(r.contains("call my helper"))
        assertTrue(r.contains("1 1 2"))                      // the emergency line, said digit by digit
        assertTrue(r.contains("I'm an AI"))
        assertTrue(r.contains("person you trust"))
        assertTrue(r.trimEnd().endsWith("?"))                // ends by asking if they are safe
        val noHelper = CompanionGuard.reply(Level.CRISIS, helper = null)
        assertFalse(noHelper.contains("call my helper"))
        assertTrue(noHelper.contains("person you trust"))
    }

    @Test fun no_phone_number_is_invented() {
        // Only the emergency line the app already has; a helpline appears only when the owner configures one.
        val none = CompanionGuard.reply(Level.CRISIS, helper = null)
        assertFalse(none.contains("helpline"))
        assertEquals(listOf("1 1 2"), Regex("\\d(?: \\d)+").findAll(none).map { it.value }.toList())
        val set = CompanionGuard.reply(Level.CRISIS, helper = null, helpline = "0 5 5 1", helplineName = "the mental health line")
        assertTrue(set.contains("the mental health line on 0 5 5 1"))
    }

    @Test fun honest_about_being_an_ai() {
        assertTrue(CompanionGuard.HONEST_AI.startsWith("I'm an AI"))
        assertTrue(CompanionGuard.HONEST_AI.contains("not a person"))
    }

    @Test fun closing_lines_do_not_guilt_or_cling() {
        for (s in listOf("that's enough", "good night", "bye", "thank you", "talk later")) {
            val c = CompanionGuard.closing(s)
            assertFalse(c, Regex("(?i)\\b(?:miss you|don't go|stay|please come back|lonely)\\b").containsMatchIn(c))
        }
        assertTrue(CompanionGuard.closing("good night").contains("Good night"))
    }

    @Test fun the_chat_rules_hold_every_guardrail() {
        val r = CompanionGuard.CHAT_RULES
        for (w in listOf("You are an AI", "never claim to be human", "Never guilt", "never push spending", "No diagnosis", "call my helper", "under 30 words"))
            assertTrue(w, r.contains(w, ignoreCase = true))
        assertTrue(r.length < 1_500)
    }
}
