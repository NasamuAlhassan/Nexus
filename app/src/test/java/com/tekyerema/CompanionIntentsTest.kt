package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.BrainPrompts
import com.tekyerema.companion.CompanionIntents
import com.tekyerema.companion.CompanionIntents.Kind
import com.tekyerema.companion.CompanionSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Companion mode's words (companion/CompanionIntents) and their place in the fast-path classifier. */
class CompanionIntentsTest {
    @Before @After fun reset() { CompanionSession.resetForTest() }

    private fun k(s: String, chatting: Boolean = false, last: Kind? = null) = CompanionIntents.classify(s, chatting, last)

    @Test fun lets_talk_phrasings() {
        for (s in listOf("hey, let's talk", "Let's talk.", "lets chat", "let us just talk", "Nexus I just want to chat small",
            "hello hello, talk to me a bit", "talk to me", "chat with me", "can we talk", "i want to talk", "let's gist",
            "keep me company", "entertain me", "I need to talk to you"))
            assertEquals(s, Kind.CHAT, k(s))
    }

    @Test fun bored_phrasings() {
        for (s in listOf("i'm bored", "I am bored", "chale I'm so bored", "I'm so bored today", "bored", "i dey bore",
            "boredom is killing me", "I have nothing to do", "hey i'm bored"))
            assertEquals(s, Kind.CHAT, k(s))
    }

    @Test fun a_topic_goes_to_the_brain_in_companion_mode() {
        for (s in listOf("let's talk about football", "can we chat about my garden", "tell me a joke", "give me a riddle",
            "tell me a proverb", "make me laugh", "cheer me up", "do you know any jokes"))
            assertEquals(s, Kind.CHAT_TOPIC, k(s))
    }

    @Test fun brief_phrasings() {
        for (s in listOf("brief me", "give me my daily brief", "give me my morning briefing", "what's my day like", "What's my day like today?",
            "what's up with my day today", "how's my day looking", "what does my day look like", "tell me about my day",
            "what's up today", "run me through my day", "catch me up on my day", "daily brief"))
            assertEquals(s, Kind.BRIEF, k(s))
    }

    @Test fun teach_phrasings() {
        for (s in listOf("teach me something", "teach me something new", "tell me something interesting", "give me a quick lesson",
            "I want to learn something new", "word of the day", "lesson of the day"))
            assertEquals(s, Kind.TEACH, k(s))
    }

    @Test fun another_one_is_a_lesson_only_right_after_a_lesson() {
        assertNull(k("another one"))
        assertNull(k("another one", chatting = true, last = Kind.CHAT))
        assertEquals(Kind.TEACH, k("another one", chatting = true, last = Kind.TEACH))
        assertEquals(Kind.TEACH, k("one more please", chatting = true, last = Kind.TEACH))
    }

    @Test fun money_phrasings() {
        for (s in listOf("how am I doing with money this week", "how am i doing with my money", "how's my spending this week",
            "did I spend a lot today", "am I spending too much", "check my spending"))
            assertEquals(s, Kind.MONEY, k(s))
        assertTrue(CompanionIntents.wantsWeek("how am I doing with money this week"))
        assertFalse(CompanionIntents.wantsWeek("did I spend a lot today"))
    }

    @Test fun are_you_human_gets_the_honest_answer() {
        for (s in listOf("are you a human", "Are you a real person?", "am I talking to a robot", "are you an AI", "is there a person talking to me",
            "are you a human or a robot"))
            assertEquals(s, Kind.HONEST, k(s))
    }

    @Test fun ending_a_chat_works_only_while_one_is_on() {
        for (s in listOf("that's enough", "thanks for the chat", "thank you", "stop talking", "good night", "bye", "no more lessons"))
            assertEquals(s, Kind.END, k(s, chatting = true))
        for (s in listOf("that's enough", "thank you", "good night", "bye")) assertNull(s, k(s, chatting = false))
    }

    @Test fun the_spending_notes_switch() {
        for (s in listOf("stop the spending notes", "no more money notes", "don't mention my spending", "turn off the spending nudges"))
            assertEquals(s, Kind.NOTES_OFF, k(s))
        for (s in listOf("turn on the spending notes", "bring back the money notes"))
            assertEquals(s, Kind.NOTES_ON, k(s))
    }

    @Test fun distress_is_found_in_the_sentence() {
        assertEquals(Kind.DISTRESS, k("I'm so lonely"))
        assertEquals(Kind.DISTRESS, k("I feel like giving up"))
        assertEquals(Kind.CRISIS, k("I don't want to live anymore"))
        assertEquals(Kind.CRISIS, k("honestly i just want to die"))
    }

    @Test fun commands_and_compound_requests_are_left_alone() {
        for (s in listOf("what time is it", "call my mother", "open WhatsApp", "play some music", "what's on my calendar",
            "do I have any meetings today", "let's talk and then call Ama", "talk to Ama", "teach me how to use WhatsApp",
            "read my messages, I'm alone in the car", "how much have I sent today", "what's the weather", "tell me the news",
            "remind me to take my medicine", "I'm tired of this phone and want to give up the app", "close all apps, end it"))
            assertNull(s, k(s))
    }

    // ---------------------------------------------------------------- through the fast-path classifier
    private fun talk(s: String) = (FastIntentClassifier.classify(s) as? FastIntent.CompanionTalk)?.kind

    @Test fun the_fast_path_classifier_returns_companion_intents() {
        assertEquals("chat", talk("let's talk"))
        assertEquals("brief", talk("brief me"))
        assertEquals("teach", talk("teach me something"))
        assertEquals("money", talk("how am I doing with money this week"))
        assertEquals("distress", talk("I'm so lonely"))
        assertEquals("crisis", talk("I feel like ending my life"))
        assertEquals("honest", talk("are you a real person"))
    }

    @Test fun the_user_s_words_ride_on_the_intent_but_not_in_its_equality() {
        val a = FastIntentClassifier.classify("let's talk") as FastIntent.CompanionTalk
        assertEquals("let's talk", a.said)
        assertEquals(FastIntent.CompanionTalk("chat"), a)
        assertEquals("COMPANION_CHAT", a.name)
    }

    @Test fun existing_intents_keep_their_phrases() {
        assertEquals(FastIntent.CalendarToday, FastIntentClassifier.classify("what's on my calendar"))
        assertEquals(FastIntent.Digest("last"), FastIntentClassifier.classify("what did I miss"))
        assertNull(talk("what time is it"))
        assertNull(talk("thank you"))                       // no chat is on
    }

    @Test fun while_a_chat_is_on_thanks_ends_it() {
        CompanionSession.start(Kind.CHAT)
        assertEquals("end", talk("thanks for the chat"))
    }

    @Test fun companion_off_means_no_companion_intents() {
        val before = com.tekyerema.core.config.AppConfig.snapshot().toString()
        com.tekyerema.core.config.AppConfig.loadFrom(listOf("config/c.json" to """{"companion":{"enabled":false}}"""))
        try {
            assertNull(talk("let's talk"))
            assertNull(talk("I'm so lonely"))
        } finally { com.tekyerema.core.config.AppConfig.loadFrom(listOf("config/restore.json" to before)) }
    }

    // ---------------------------------------------------------------- the chat framing for the cloud brain
    @Test fun the_cloud_brain_gets_the_companion_rules_only_while_a_chat_is_on() {
        assertNull(BrainPrompts.goalRules("tell me about the Black Stars"))
        CompanionSession.start(Kind.CHAT)
        val r = BrainPrompts.goalRules("my day was fine")
        assertNotNull(r)
        assertTrue(r!!.contains("COMPANION CHAT"))
        // a phone request during a chat still reaches its own rules too
        assertTrue(BrainPrompts.goalRules("send a message to Ama")!!.contains("MESSAGING RULES"))
    }
}
