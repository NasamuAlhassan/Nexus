package com.tekyerema

import com.tekyerema.ui.assistant.ConfirmWords
import com.tekyerema.ui.assistant.MapsLink
import com.tekyerema.ui.assistant.QuestionChoices
import com.tekyerema.ui.assistant.SurfaceModel
import com.tekyerema.ui.assistant.spokenSummary
import org.junit.Assert.assertEquals
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
    }

    @Test fun talkback_hears_the_title_and_the_words_in_one_line() {
        val s = SurfaceModel.State(1, SurfaceModel.Mode.PRESENTING, "Explain photosynthesis",
            preview = SurfaceModel.Preview.Answer("Explain photosynthesis", "Plants make food from light.", "Plants make food from light."))
        assertEquals("Explain photosynthesis. Plants make food from light.", spokenSummary(s))
        assertEquals("geo:0,0?q=nearest%20shopping%20mall", MapsLink.of("nearest shopping mall"))
    }
}
