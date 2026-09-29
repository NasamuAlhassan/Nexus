package com.tekyerema

import com.tekyerema.agent.LocalDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Days and dates answered on the phone. "Today" is fixed: Tuesday 29 September 2026. */
class LocalDatesTest {
    private val today = LocalDate.of(2026, 9, 29)
    private fun a(s: String) = LocalDates.answer(s.lowercase(), today)

    @Test fun countdown_to_a_holiday() {
        assertEquals("Christmas is in 87 days, on Friday, 25 December.", a("How many days until Christmas?"))
        assertEquals("Christmas is in 87 days, on Friday, 25 December.", a("how many days are left till xmas"))
        assertEquals("Christmas is in 87 days, on Friday, 25 December.", a("how long until Christmas"))
        assertEquals("Christmas is 12 weeks and 3 days away, on Friday, 25 December.", a("how many weeks until Christmas"))
        assertEquals("Christmas is 2 months and 26 days away, on Friday, 25 December.", a("how many months to christmas"))
        assertEquals("Independence Day is in 158 days, on Saturday, 6 March 2027.", a("how many days to independence day"))
        assertEquals("Farmers' Day is in 66 days, on Friday, 4 December.", a("how many days until farmers day"))
        assertEquals("New Year's Day is in 94 days, on Friday, 1 January 2027.", a("how many days until new year"))
        assertEquals("Christmas is in 87 days, on Friday, 25 December.", a("Please tell me, how far away is Christmas?"))
        assertEquals("The end of the year is in 93 days, on Thursday, 31 December.", a("how many days until the end of the year"))
        assertEquals("The end of the month is tomorrow, on Wednesday, 30 September.", a("how many days until the end of the month"))
    }

    @Test fun countdown_to_a_date_or_a_weekday() {
        assertEquals("Friday, 25 December is in 87 days.", a("how many days until 25 December"))
        assertEquals("Friday, 25 December is in 87 days.", a("how many days until December 25th"))
        assertEquals("Friday, 25 December is in 87 days.", a("how many days until the twenty-fifth of December"))
        assertEquals("Friday is in 3 days, on 2 October.", a("how many days until Friday"))
        assertEquals("Tuesday is today.", a("how many days until Tuesday"))
        assertEquals("Next Tuesday is in 7 days, on 6 October.", a("how many days until next Tuesday"))
        assertEquals("Friday, 6 March 2020 was 2398 days ago.", a("how many days until 6 March 2020"))
    }

    @Test fun what_day_a_date_falls_on() {
        assertEquals("6 March 2027 is a Saturday.", a("What day is 6 March 2027?"))
        assertEquals("6 March 2027 is a Saturday.", a("what day of the week is the 6th of March 2027"))
        assertEquals("6 March 1957 was a Wednesday.", a("what day was 6 March 1957"))
        assertEquals("6 March 1957 was a Wednesday.", a("which day was march 6th 1957"))
        assertEquals("6 March 1990 was a Tuesday.", a("what day was 6 march 19 90"))            // "nineteen ninety" from the recogniser
        assertEquals("Christmas is on Friday, 25 December, in 87 days.", a("what day is Christmas"))
        assertEquals("Christmas is on Friday, 25 December, in 87 days.", a("what day does christmas fall on"))
        assertEquals("Christmas is on Friday, 25 December, in 87 days.", a("christmas falls on which day"))
        assertEquals("Christmas was on Thursday, 25 December 2025.", a("what day was christmas last year"))
        assertEquals("Christmas was on Friday, 25 December 2020.", a("what day was christmas 2020"))
        assertEquals("Tomorrow is Wednesday, 30 September.", a("what day is tomorrow"))
        assertEquals("Tomorrow is Wednesday, 30 September.", a("what day is it tomorrow"))
        assertEquals("Yesterday was Monday, 28 September.", a("what day was yesterday"))
        assertEquals("29 February 2028 is a Tuesday.", a("what day is 29 February"))           // the next one that exists
    }

    @Test fun the_date_of_another_day() {
        assertEquals("Next Friday is 2 October.", a("What's the date next Friday?"))
        assertEquals("Friday is 2 October.", a("what is the date on friday"))
        assertEquals("Last Friday was 25 September.", a("what was the date last friday"))
        assertEquals("In 10 days, it will be Friday, 9 October.", a("what's the date in 10 days"))
        assertEquals("In 2 weeks, it will be Tuesday, 13 October.", a("what date will it be in 2 weeks"))
        assertEquals("In a week, it will be Tuesday, 6 October.", a("what will the date be in a week"))
        assertEquals("100 days from now is Thursday, 7 January 2027.", a("what is the date 100 days from now"))
        assertEquals("3 weeks ago was Tuesday, 8 September.", a("what was the date 3 weeks ago"))
        assertEquals("Tomorrow is Wednesday, 30 September.", a("what's the date tomorrow"))
        assertEquals("Independence Day is on Saturday, 6 March 2027, in 158 days.", a("what is the date of independence day"))
        assertEquals("Founders' Day is on Tuesday, 21 September 2027, in 357 days.", a("When is Founders' Day?"))
        assertEquals("Founders' Day is on Tuesday, 21 September 2027, in 357 days.", a("when is kwame nkrumah memorial day"))
        assertEquals("Republic Day is on Thursday, 1 July 2027, in 275 days.", a("when is republic day"))
        assertEquals("Next Friday is 2 October.", a("when is next friday"))
    }

    @Test fun easter_follows_the_church_rule() {
        assertEquals(LocalDate.of(2024, 3, 31), LocalDates.easter(2024))
        assertEquals(LocalDate.of(2025, 4, 20), LocalDates.easter(2025))
        assertEquals(LocalDate.of(2026, 4, 5), LocalDates.easter(2026))
        assertEquals(LocalDate.of(2027, 3, 28), LocalDates.easter(2027))
        assertEquals(LocalDate.of(2019, 4, 21), LocalDates.easter(2019))
        assertEquals(LocalDate.of(2000, 4, 23), LocalDates.easter(2000))
        assertEquals("Good Friday is on Friday, 26 March 2027, in 178 days.", a("when is good friday"))
        assertEquals("Easter Monday is on Monday, 29 March 2027, in 181 days.", a("when is easter monday"))
        assertEquals("Easter Sunday was on Sunday, 5 April.", a("when was easter"))
    }

    @Test fun how_long_ago() {
        assertEquals("Independence Day was 207 days ago, on Friday, 6 March.", a("how many days since independence day"))
        assertEquals("1957 was 69 years ago.", a("how many years since 1957"))
        assertEquals("1957 was 69 years ago.", a("how long ago was 1957"))
        assertEquals("Wednesday, 6 March 1957 was 69 years and 6 months ago.", a("how long ago was 6 March 1957"))
        assertEquals("Christmas was 278 days ago, on Thursday, 25 December 2025.", a("how long has it been since christmas"))
        assertNull("since Ghana's independence means 1957, not last March", a("how many years since independence day"))
    }

    @Test fun ages() {
        assertEquals("Someone born in 1990 turns 36 this year.", a("How old is someone born in 1990?"))
        assertEquals("If you were born in 1990, you turn 36 this year.", a("how old am I if I was born in 1990"))
        assertEquals("If you were born in 1990, you turn 36 this year.", a("if I was born in 1990 how old am I"))
        assertEquals("If you were born on 6 March 1990, you are 36 years old.", a("how old am i if i was born on 6 March 1990"))
        assertEquals("Someone born on 30 September 1990 is 35 years old.", a("how old would someone born on 30 September 1990 be"))
        assertEquals("Someone born on 29 September 2000 turns 26 today.", a("how old is a person born on the 29th of September 2000"))
        assertEquals("Someone born on 1 May 2026 is 4 months old.", a("how old is a baby born on 1 May 2026"))
        assertEquals("Someone born in 2026 is under a year old.", a("how old is a child born in 2026"))
        assertNull("no year: can't know", a("how old is someone born on 6 March"))
        assertNull("not born yet", a("how old is someone born in 2030"))
        assertNull("the user's own age isn't known", a("how old am i"))
    }

    @Test fun yes_no_weekday_and_this_year() {
        assertEquals("Yes. Christmas is on Friday, 25 December, in 87 days.", a("Is Christmas on a Friday?"))
        assertEquals("No. Independence Day is on Saturday, 6 March 2027, in 158 days.", a("does independence day fall on a monday"))
        assertEquals("Yes. 6 March 2027 is a Saturday.", a("is 6 march 2027 a saturday"))
        assertEquals("No. Tomorrow is Wednesday, 30 September.", a("is tomorrow friday"))
        assertNull("the plain day question owns this", a("is today monday"))
        assertNull(a("is it friday"))
        assertEquals("It's 2026.", a("what year is it"))
        assertEquals("It's September 2026.", a("which month are we in"))
    }

    @Test fun leap_years_and_month_lengths() {
        assertEquals("February has 28 days, or 29 in a leap year. The next leap year is 2028.", a("how many days has february got"))
        assertEquals("Yes, 2028 is a leap year: February has 29 days.", a("Is 2028 a leap year?"))
        assertEquals("No, 2026 is not a leap year. The next one after it is 2028.", a("is this year a leap year"))
        assertEquals("No, 2100 is not a leap year. The next one after it is 2104.", a("will 2100 be a leap year"))
        assertEquals("February has 28 days, or 29 in a leap year. The next leap year is 2028.", a("how many days are there in February"))
        assertEquals("February 2028 has 29 days.", a("how many days in february 2028"))
        assertEquals("April has 30 days.", a("how many days does april have"))
        assertEquals("September 2026 has 30 days.", a("how many days in this month"))
        assertEquals("A year has 365 days, or 366 in a leap year.", a("how many days in a year"))
    }

    @Test fun akan_day_names() {
        assertEquals("A boy born on a Friday is named Kofi.", a("What is the Akan name for a boy born on Friday?"))
        assertEquals("A girl born on a Tuesday is named Abena.", a("what's the day name for a girl born on a tuesday"))
        assertEquals("A boy born on a Saturday is named Kwame, and a girl Ama.", a("akan names for saturday"))
        assertEquals("6 March 1990 was a Tuesday. A boy born on a Tuesday is named Kwabena, and a girl Abena.",
            a("what is my akan name if I was born on 6 March 1990"))
        assertEquals("A girl born on a Sunday is named Akosua.", a("what is the twi name for a girl born on sunday"))
        assertEquals("Kojo is a day name for a boy born on a Monday.", a("what day was kojo born"))
        assertEquals("Ama is a day name for a girl born on a Saturday.", a("what day is ama born on"))
        assertEquals("Efua is a day name for a girl born on a Friday.", a("which day is efua born"))
        assertNull("not a day name", a("what day was obama born"))
        assertNull("no name kind asked", a("what is the name of a boy born on friday"))
    }

    @Test fun anything_unclear_goes_to_the_model() {
        for (s in listOf("how many days until my birthday", "when is eid", "when is the next public holiday", "what day is eid",
            "how long to cook rice", "how many weeks to lose weight", "how far is kumasi", "when is my exam",
            "how many days between 1 march and 5 april", "what day is it", "what day is today", "what's today's date",
            "what date is it", "what's the date today", "what's the date", "what day of the week is it", "how many days in a week",
            "what time is it", "what is 15 times 12", "how old is the president of ghana", "how many hours until christmas"))
            assertNull(s, a(s))
    }

    @Test fun other_day_questions_it_cannot_answer_are_flagged() {
        // These must not get "Today is Tuesday" from the plain day/date rules.
        for (s in listOf("what day is eid", "what day is my birthday", "what's the date of my exam", "what day is it in london", "what date is easter in 2400"))
            assertTrue(s, LocalDates.asksOtherDay(s))
        for (s in listOf("what day is it", "what day is today", "what day is it today", "what date is it", "what's the date today",
            "what's today's date", "what day of the week is it", "which day is today", "what's the date", "is today monday"))
            assertFalse(s, LocalDates.asksOtherDay(s))
    }
}
