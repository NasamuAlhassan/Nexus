package com.tekyerema

import com.tekyerema.agent.ContactEnding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's MoMo test on the A20e (14 Oct 2026): corrections by a number's ending, and "none of them". */
class ContactEndingTest {
    @Test fun the_ending_the_owner_said_is_read() {
        assertEquals("2019", ContactEnding.digits("no I want you to send it to my mom like is ending and I think 2019"))
        assertEquals("2019", ContactEnding.digits("I said you should send it to Mom and then 2019"))
        assertEquals("019", ContactEnding.digits("the one that ends with 0 1 9"))
        assertEquals("323", ContactEnding.digits("Prince ending 323"))
    }

    @Test fun amounts_and_plain_answers_are_not_an_ending() {
        assertNull(ContactEnding.digits("send 500 cedis to Mom"))
        assertNull(ContactEnding.digits("yes"))
        assertNull(ContactEnding.digits("send it to Kofi instead"))
    }

    @Test fun none_of_them_is_heard() {
        assertTrue(ContactEnding.noneOfThem("none of them there's another dad check"))
        assertTrue(ContactEnding.noneOfThem("someone else"))
        assertTrue(ContactEnding.noneOfThem("it's not any of them"))
        assertFalse(ContactEnding.noneOfThem("Dad ending 159"))
    }

    @Test fun a_whole_number_in_the_answer_is_taken() {
        assertEquals("0244123456", ContactEnding.wholeNumber("it's 0 2 4 4 1 2 3 4 5 6"))
        assertEquals("0508797323", ContactEnding.wholeNumber("050 879 7323"))
        assertNull(ContactEnding.wholeNumber("Daddy"))
    }
}
