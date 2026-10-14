package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.ui.assistant.ButtonFit
import com.tekyerema.ui.assistant.ChoiceGate
import com.tekyerema.ui.assistant.ConfirmWords
import com.tekyerema.ui.assistant.MapsLink
import com.tekyerema.ui.assistant.QuestionChoices
import com.tekyerema.ui.assistant.SurfaceModel
import com.tekyerema.ui.assistant.spokenSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The surface's questions and confirmations (docs/assistant-ux, Stage 5): real choices, verb-labelled confirmations. */
class PromptChoicesTest {

    @Test fun a_which_question_offers_its_names_as_buttons() {
        // The baseline card (emulator, 14 Oct 2026) listed these only inside a sentence.
        assertEquals(listOf("Prince Tesano AG youth Sec", "Prince Ice P", "Prince Alhassan ending 323"),
            QuestionChoices.of("Which Prince: Prince Tesano AG youth Sec, Prince Ice P, or Prince Alhassan ending 323? Or someone else?"))
        assertEquals(listOf("Richmond", "Richmond Apenensu"),
            QuestionChoices.of("Which Richmond do you want to message? Richmond, or Richmond Apenensu?"))
    }

    @Test fun open_questions_numbers_and_long_text_get_no_buttons() {
        assertTrue(QuestionChoices.of("What should I buy — airtime, data, or a bundle? Say the amount.").isEmpty())
        assertTrue(QuestionChoices.of("Who should I send the airtime to? Say a name from your contacts, or the phone number.").isEmpty())
        assertTrue(QuestionChoices.of("Which number should I enter?").isEmpty())
        assertTrue(QuestionChoices.of("Which one: 0244123456, or 0552093690?").isEmpty())   // numbers are read and said, not tapped
    }

    @Test fun a_confirmation_names_what_will_happen() {
        assertEquals("Cancel" to "Send", ConfirmWords.buttons("Send this message to Ama?\n“I'm on my way.”", twi = false))
        assertEquals("Cancel" to "Buy", ConfirmWords.buttons("Buy 2 cedis of airtime for 0 5 0, 8 7 9, 7 3 2 3, a Telecel number?", twi = false))
        assertEquals("Cancel" to "Call", ConfirmWords.buttons("Call Ama?", twi = false))
        assertEquals("Cancel" to "Yes, do it", ConfirmWords.buttons("MTN says this sends ten pesewas. Go ahead?", twi = false))
        assertEquals("Daabi" to "Aane, yɛ", ConfirmWords.buttons("Soma sika?", twi = true))
        // The real read-back (emulator, 14 Oct 2026): the closing question names the act — "Send", not the first word "Text".
        val p = com.tekyerema.voice.SpokenText.PAUSE
        assertEquals("Cancel" to "Send", ConfirmWords.buttons("Text Prince Ice P, ending 3 6 1 1.${p}The message: \"I will be late today.\"${p}Send it?", twi = false))
    }

    @Test fun the_primary_pill_names_the_act_and_the_person_when_plainly_said() {
        val p = com.tekyerema.voice.SpokenText.PAUSE
        assertEquals("Send to Prince Ice P", ConfirmWords.label("Text Prince Ice P, ending 3 6 1 1.${p}The message: \"I will be late today.\"${p}Send it?", twi = false))
        assertEquals("Call Ama", ConfirmWords.label("Call Ama?", twi = false))
        assertEquals("Send to Ama", ConfirmWords.label("Send this message to Ama?\n“I'm on my way.”", twi = false))
        assertEquals("a number is not a name", "Buy", ConfirmWords.label("Buy 2 cedis of airtime for 0 5 0, 8 7 9, 7 3 2 3, a Telecel number?", twi = false))
        assertEquals("Call", ConfirmWords.label("Call 0 2 4 4 1 2 3 4 5 6?", twi = false))
        assertEquals("Yes, do it", ConfirmWords.label("MTN says this sends ten pesewas. Go ahead?", twi = false))
        assertEquals("Aane, yɛ", ConfirmWords.label("Soma sika?", twi = true))
    }

    @Test fun the_voices_pause_marks_are_line_breaks_on_screen() {
        val p = com.tekyerema.voice.SpokenText.PAUSE
        val b = com.tekyerema.voice.SpokenText.BREAK
        // …and the spaced digits of a number stay on one line (the emulator wrapped "3 6 1 | 1.", 14 Oct 2026)
        val nb = ' '
        assertEquals("Text Prince Ice P, ending 3${nb}6${nb}1${nb}1.\nThe message: \"I will be late today.\"\nSend it?",
            com.tekyerema.ui.assistant.ScreenText.of("Text Prince Ice P, ending 3 6 1 1.${p}The message: \"I will be late today.\"${p}Send it?"))
        assertEquals("From Ama.\n\nFrom Kofi.", com.tekyerema.ui.assistant.ScreenText.of("From Ama.${b}${b}${b}From Kofi."))
        assertEquals("Buy 2 cedis for 0${nb}5${nb}0, 8${nb}7${nb}9", com.tekyerema.ui.assistant.ScreenText.of("Buy 2 cedis for 0 5 0, 8 7 9"))
    }

    @Test fun buttons_stack_when_a_word_would_not_fit_at_the_users_text_size() {
        val w = 393f - 24f - 36f   // a Pixel 4-class phone: the card's inner width in dp
        val three = listOf("Stop", "Type", "Answer"); val tw = listOf(1f, 1f, 1.3f)
        assertTrue(ButtonFit.sideBySide(three, tw, w, fontScale = 1f))
        assertFalse("at 130 % text a row of three breaks words", ButtonFit.sideBySide(three, tw, w, fontScale = 1.3f))
        assertTrue(ButtonFit.sideBySide(listOf("Cancel", "Send"), listOf(1f, 1.4f), w, fontScale = 1.3f))
        assertFalse(ButtonFit.sideBySide(listOf("Cancel", "Send"), listOf(1f, 1.4f), w, fontScale = 2f))
        assertTrue("a label of several words may wrap", ButtonFit.sideBySide(listOf("Cancel", "Yes, do it"), listOf(1f, 1.4f), w, fontScale = 1.3f))
        assertTrue("landscape has room", ButtonFit.sideBySide(three, tw, 560f - 36f, fontScale = 1.3f))
        assertTrue("one button is never stacked", ButtonFit.sideBySide(listOf("Ask a follow-up"), listOf(1f), 100f, fontScale = 2f))
    }

    @Test fun a_choice_tap_never_answers_a_confirmation() {
        // Review, 14 Oct 2026: a late or double tap on a choice went through submitTyped, which during a confirmation reads
        // a short label as a typed "yes" (or "no", or a change) — approving without the arming delay.
        assertTrue(ChoiceGate.accepts(AgentStatus.ASKING, engineQuestion = true))
        assertFalse("answered already; the task moved on", ChoiceGate.accepts(AgentStatus.THINKING, engineQuestion = true))
        assertFalse(ChoiceGate.accepts(AgentStatus.WAITING_CONFIRMATION, engineQuestion = true))
        assertFalse(ChoiceGate.accepts(AgentStatus.WAITING_CONFIRMATION, engineQuestion = false))
        assertFalse(ChoiceGate.accepts(AgentStatus.SECURE_INPUT, engineQuestion = false))
        assertTrue("a question at the end of a reply starts a new turn", ChoiceGate.accepts(AgentStatus.COMPLETED, engineQuestion = false))
    }

    @Test fun talkback_hears_the_title_and_the_words_in_one_line() {
        val s = SurfaceModel.State(1, SurfaceModel.Mode.PRESENTING, "Explain photosynthesis",
            preview = SurfaceModel.Preview.Answer("Explain photosynthesis", "Plants make food from light.", "Plants make food from light."))
        assertEquals("Explain photosynthesis. Plants make food from light.", spokenSummary(s))
        assertEquals("geo:0,0?q=nearest%20shopping%20mall", MapsLink.of("nearest shopping mall"))
    }
}
