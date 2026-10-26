package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.BrainPrompts
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.MessageToSomeone
import com.tekyerema.money.BankUssd
import com.tekyerema.money.MoMoAfterCare
import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.TelcoUssd
import com.tekyerema.verifier.Expectation
import com.tekyerema.verifier.Expects
import com.tekyerema.verifier.OutcomeVerifier
import com.tekyerema.verifier.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 26 Oct 2026: a message FOR someone must never fire a phone keyword rule ("tell my wife to send me mashup" opened MTN's bundle menu). */
class MessageToSomeoneTest {
    @Test fun a_message_for_a_person_is_recognised_and_the_phones_own_requests_are_not() {
        for (s in listOf("tell my wife to send me mashup", "Tell Ama my battery is dead", "ask Kofi to buy me a data bundle",
            "please text Prince I'm on my way", "tell Kofi to go back to the shop", "let Ama know I'm late", "whatsapp Richmond hello",
            "can you message my brother to call me", "remind Kofi to mute his phone"))
            assertTrue(s, MessageToSomeone.matches(s))
        for (s in listOf("tell me my data balance", "ask me later", "let me know when Ama messages", "remind me at six", "text size bigger",
            "ask chatgpt what a cedi is", "what's my battery", "go back", "buy a mashup bundle", "message notifications off", "send 5 cedis to Ama"))
            assertFalse(s, MessageToSomeone.matches(s))
    }

    @Test fun every_phone_side_keyword_router_steps_aside_for_it() {
        for (s in listOf("tell Ama my battery is dead", "tell Kofi to go back to the shop", "tell Ama to mute her phone", "ask Ama to pause the music"))
            assertNull(s, FastIntentClassifier.classify(s))
        assertNull(TelcoUssd.parse("tell my wife to send me mashup", "mtn"))
        assertNull(BankUssd.parse("tell Kofi to send money from my Stanbic account"))
        assertFalse(MoMoAfterCare.isWrongTransfer("tell Kofi I sent money to the wrong number"))
        assertNull(MoneyAsk.parse("tell Kofi to send me 50 cedis"))
        // the user's own requests still reach them
        assertNotNull(FastIntentClassifier.classify("go back"))
        assertTrue(MoMoAfterCare.isWrongTransfer("I sent money to the wrong number"))
        assertNotNull(MoneyAsk.parse("send 50 cedis to Ama"))
    }

    // ---- the verifier: "the search box has the cursor" is a claim it can check (Infinix trace: field_focused was 'not understood')
    private fun el(id: Int, editable: Boolean, focused: Boolean, hint: String? = null) =
        UiElement(id, "EditText", null, Box(0, id * 100, 600, id * 100 + 80), editable = editable, focused = focused, hint = hint)
    private fun snap(vararg e: UiElement) = UiSnapshot("org.telegram.messenger", null, e.toList(), 0L, PerceptionRoute("tree", "test"))

    @Test fun field_focused_is_a_claim_the_verifier_understands() {
        val e = Expects.parse("field_focused: Search Chats")
        assertTrue(e is Expectation.FieldFocused)
        assertFalse(Expects.isVacuous(e))
        assertEquals(Expectation.FieldFocused(null), Expects.parse("field_focused"))
        val withCursor = snap(el(1, editable = true, focused = true, hint = "Search Chats"))
        val without = snap(el(1, editable = true, focused = false, hint = "Search Chats"))
        val weak = VerificationResult(true, "tapped")
        assertTrue(OutcomeVerifier.verify(e, without, withCursor, true, weak, false).verified)
        assertFalse(OutcomeVerifier.verify(e, withCursor, without, true, weak, false).verified)
    }

    @Test fun the_prompt_says_to_act_on_a_target_already_on_screen() {
        assertTrue(BrainPrompts.SYSTEM.contains("act on it now, never search for it again"))
        assertTrue(BrainPrompts.SYSTEM.contains("field_focused"))
        assertFalse(BrainPrompts.SYSTEM.contains("a search button then its text"))
    }
}
