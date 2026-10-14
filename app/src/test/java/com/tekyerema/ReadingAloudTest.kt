package com.tekyerema

import com.tekyerema.money.ussd.MenuBrain
import com.tekyerema.voice.Verbalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the voice READS what the money flow says (owner, A20e, 13 October 2026: "the mentioning of numbers and some words is
 * so bad… you can't mention MTN… reading generally is bad"). Every line now passes Verbalize at the engine's door.
 */
class ReadingAloudTest {

    @Test fun the_mashup_read_back_is_words_the_voice_cannot_run_together() {
        val said = Verbalize.apply("Buy an MTN Mashup bundle for 5 cedis, for the number 0 5 5, 2 0 9, 3 6 9 0, an MTN number, paying with Mobile Money?")
        assertEquals("Buy an M T N Mash Up bundle for five cedis, for the number zero five five, two zero nine, three six nine zero, " +
            "an M T N number, paying with Mobile Money?", said)
        assertTrue(said, !said.contains("MTN") && !said.any { it.isDigit() })
    }

    @Test fun mtn_menu_words_are_said_as_words() {
        assertEquals("Mash Up Offers one. Mash Up five cedis", Verbalize.apply("MashUp Offers 1. MashUp GHC 5"))
        assertEquals("Yellow! the Mash Up five cedis Bundle gives you about three point one gigabytes.",
            Verbalize.apply("Y'ello! the MashUp GHC 5 Bundle gives you 3133.94 MB."))
        assertEquals("five hundred twenty two megabytes", Verbalize.apply("522.32 MB"))
        assertEquals("twenty five megabytes and eleven minutes", Verbalize.apply("25.45MB and 11.17Mins"))
        assertEquals("thirty six megabytes and seven minutes", Verbalize.apply("35.62MB and 6.7Mins"))
        assertEquals("two gigabytes", Verbalize.apply("2GB"))
        assertEquals("one point five gigabytes", Verbalize.apply("1.5 GB"))
        assertEquals("Enter Mobile Money PIN or two to cancel", Verbalize.apply("Enter MM PIN or 2 to cancel"))
        assertEquals("Proceed to buy bundle (No four G bonus)", Verbalize.apply("Proceed to buy bundle (No 4G bonus)"))
        assertEquals("only available on My M T N App", Verbalize.apply("only available on MyMTN App"))
        assertEquals("dial star one seven zero hash to approve", Verbalize.apply("dial *170# to approve"))
    }

    @Test fun verbalising_twice_changes_nothing() {
        for (line in listOf(
            "Send GH₵ 10.50 to Kwame on MTN?",
            "Buy an MTN Mashup bundle for 5 cedis, for the number 0552093690, paying with Mobile Money?",
            "Your balance is GHS 7.50. Dial *170# if the prompt delays.",
            "MTN will ask for your PIN in a moment."
        )) {
            val once = Verbalize.apply(line)
            assertEquals(line, once, Verbalize.apply(once))
        }
    }

    @Test fun the_price_a_user_says_in_words_is_a_figure_to_the_menu_brain() {
        assertEquals("1", MenuBrain.amountSaid("one cedi"))
        assertEquals("5", MenuBrain.amountSaid("five cedis"))
        assertEquals("5", MenuBrain.amountSaid("five"))
        assertEquals("25", MenuBrain.amountSaid("twenty five cedis"))
        assertEquals("5", MenuBrain.amountSaid("5 5"))                 // the same figure twice, never 55
        assertEquals("5", MenuBrain.amountSaid("I told you 5 cedis 5"))
        assertNull(MenuBrain.amountSaid("five or ten"))
    }
}
