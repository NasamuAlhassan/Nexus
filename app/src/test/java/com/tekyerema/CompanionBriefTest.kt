package com.tekyerema

import com.tekyerema.companion.CompanionBrief
import com.tekyerema.companion.CompanionBrief.Event
import com.tekyerema.companion.CompanionBrief.Inputs
import com.tekyerema.companion.CompanionBrief.Liked
import com.tekyerema.companion.CompanionBrief.Money
import com.tekyerema.companion.CompanionLines
import com.tekyerema.companion.CompanionMoney
import com.tekyerema.memory.MemEntry
import com.tekyerema.memory.MemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/** The daily brief and the money note: what they say, how gently, and what they leave out. */
class CompanionBriefTest {
    private val zone: TimeZone = TimeZone.getTimeZone("Africa/Accra")           // UTC+0 all year
    private val z: ZoneId = zone.toZoneId()
    private fun at(h: Int, m: Int = 0): Long = ZonedDateTime.of(2026, 11, 2, h, m, 0, 0, z).toInstant().toEpochMilli()
    private fun money(today: String, tc: Int, week: String, wc: Int) = Money(BigDecimal(today), tc, BigDecimal(week), wc)
    private fun brief(i: Inputs) = CompanionBrief.build(i)

    private val BANNED = Regex("(?i)\\b(?:too much|overspen[dt]|careful|you should|waste|wasteful|irresponsible|bad|reckless|stop spending|buy|purchase|deal|offer)\\b")

    @Test fun greets_by_the_hour_and_by_name() {
        assertTrue(brief(Inputs(at(8), zone, "Ama", emptyList())).startsWith("Good morning, Ama."))
        assertTrue(brief(Inputs(at(14), zone, null, emptyList())).startsWith("Good afternoon."))
        assertTrue(brief(Inputs(at(19), zone, "  ", emptyList())).startsWith("Good evening."))
    }

    @Test fun lists_what_is_ahead_today_in_time_order_and_skips_what_is_over() {
        val line = brief(Inputs(at(9, 30), zone, null, listOf(
            Event("Clinic visit", at(14, 30), false), Event("Standup", at(10), false), Event("Old meeting", at(7), false))))
        assertTrue(line, line.contains("On today: Standup at 10 AM, Clinic visit at 2:30 PM."))
        assertFalse(line, line.contains("Old meeting"))
    }

    @Test fun a_clear_calendar_and_an_unreadable_one_are_told_apart() {
        assertTrue(brief(Inputs(at(8), zone, null, emptyList())).contains("Your calendar is clear today."))
        val unreadable = brief(Inputs(at(8), zone, null, null))
        assertTrue(unreadable.contains("can't see your calendar"))
        assertFalse(unreadable.contains("clear"))
    }

    @Test fun all_the_events_over_is_not_a_clear_day() {
        val l = brief(Inputs(at(18), zone, null, listOf(Event("Standup", at(10), false))))
        assertTrue(l, l.contains("nothing else on your calendar today"))
    }

    @Test fun a_busy_day_names_three_and_counts_the_rest() {
        val ev = (1..8).map { Event("Item $it", at(8 + it), false) }
        val l = brief(Inputs(at(7), zone, null, ev.take(5), eventTotal = 8))
        assertTrue(l, l.contains("Item 1 at 9 AM, Item 2 at 10 AM, Item 3 at 11 AM, and 5 more."))
    }

    @Test fun an_all_day_event_has_no_clock_and_a_long_title_is_clipped() {
        val l = brief(Inputs(at(7), zone, null, listOf(Event("Independence Day", at(0), true), Event("x".repeat(80), at(12), false))))
        assertTrue(l, l.contains("Independence Day, all day"))
        assertFalse(l, l.contains("x".repeat(60)))
    }

    @Test fun twelve_noon_and_midnight_are_said_right() {
        assertEquals("12 PM", CompanionBrief.clock(at(12), zone))
        assertEquals("12 AM", CompanionBrief.clock(at(0), zone))
        assertEquals("12:05 PM", CompanionBrief.clock(at(12, 5), zone))
        assertEquals("9:07 PM", CompanionBrief.clock(at(21, 7), zone))
    }

    @Test fun messages_say_how_many_and_who_most() {
        val l = brief(Inputs(at(8), zone, null, emptyList(), messageCount = 5, topSender = "Ama", topSenderCount = 3, missedCalls = 1))
        assertTrue(l, l.contains("You have 5 new messages, 3 from Ama, and 1 missed call."))
        assertTrue(brief(Inputs(at(8), zone, null, emptyList(), messageCount = 1, topSender = "Kofi", topSenderCount = 1)).contains("1 new message, from Kofi."))
        assertTrue(brief(Inputs(at(8), zone, null, emptyList())).contains("Nothing new in your messages."))
        assertTrue(brief(Inputs(at(8), zone, null, emptyList(), missedCalls = 2)).contains("2 missed calls, and no new messages"))
    }

    @Test fun notification_access_off_is_not_no_messages() {
        val l = brief(Inputs(at(8), zone, null, emptyList(), messagesKnown = false))
        assertFalse(l, l.contains("Nothing new"))
        assertFalse(l, l.contains("message"))
    }

    @Test fun one_liked_thing_is_a_reminder_not_a_sales_pitch() {
        val l = brief(Inputs(at(8), zone, null, emptyList(), liked = Liked("like", "gospel music")))
        assertTrue(l, l.contains("You told me you like gospel music, so maybe make a little time for it today."))
        assertFalse(l, BANNED.containsMatchIn(l))
    }

    @Test fun a_quiet_spending_day_gets_no_note() {
        val l = brief(Inputs(at(8), zone, null, emptyList(), money = money("0", 0, "40", 2)))
        assertFalse(l, l.contains("spent"))
    }

    @Test fun a_spending_day_gets_a_plain_note() {
        val l = brief(Inputs(at(8), zone, null, emptyList(), money = money("20", 1, "60", 4)))
        assertTrue(l, l.contains("Today you've spent 20 cedis through me, in 1 payment."))
        assertFalse(l, BANNED.containsMatchIn(l))
    }

    @Test fun an_unusually_busy_day_gets_a_gentle_suggestion_never_a_judgement() {
        // earlier six days: 60 in all (10 a day); today 50 in three payments
        val m = money("50", 3, "110", 8)
        assertTrue(CompanionMoney.aboveUsual(m))
        val l = brief(Inputs(at(8), zone, null, emptyList(), money = m))
        assertTrue(l, l.contains("Today you've spent 50 cedis through me. That's a little more than your usual day, so maybe keep the rest for what you need today."))
        assertFalse(l, BANNED.containsMatchIn(l))
    }

    @Test fun usual_is_judged_against_the_other_days_and_needs_two_payments() {
        assertFalse(CompanionMoney.aboveUsual(money("50", 1, "110", 8)))           // one payment only
        assertFalse(CompanionMoney.aboveUsual(money("50", 3, "50", 3)))            // nothing earlier to compare with
        assertFalse(CompanionMoney.aboveUsual(money("12", 2, "72", 8)))            // 12 vs 10 a day: ordinary
    }

    @Test fun private_reading_gives_counts_only() {
        val l = brief(Inputs(at(8), zone, "Ama", listOf(Event("Doctor", at(10), false), Event("Bank", at(11), false)),
            messageCount = 4, topSender = "Kofi", topSenderCount = 3, liked = null, money = money("80", 3, "90", 4), hidePrivate = true))
        assertTrue(l, l.contains("You have 2 things on today, the next at 10 AM."))
        assertTrue(l, l.contains("You have 4 new messages."))
        assertFalse(l, l.contains("Kofi") || l.contains("Doctor") || l.contains("cedis") || l.contains("spent"))
    }

    @Test fun the_whole_brief_is_one_short_paragraph() {
        val l = brief(Inputs(at(8), zone, "Ama", listOf(Event("Standup", at(10), false), Event("Clinic", at(14, 30), false)),
            messageCount = 3, topSender = "Kofi", topSenderCount = 2, missedCalls = 1, liked = Liked("enjoy", "highlife"),
            money = money("20", 1, "60", 4)))
        assertTrue(l, l.split(Regex("\\s+")).size <= 90)
        assertFalse(l, l.contains("\n"))
        assertFalse(l, BANNED.containsMatchIn(l))
    }

    // ---------------------------------------------------------------- the money answer ("how am I doing with money")
    @Test fun the_asked_for_money_answer_is_honest_about_what_it_sees() {
        val week = CompanionMoney.answer(money("20", 1, "120", 5), week = true) { CompanionBrief.plainCedis(it) }
        assertTrue(week, week.contains("In the last 7 days you've spent 120 cedis through me, in 5 payments. Of that, 20 cedis was today."))
        assertTrue(week, week.contains("That's only what went through me"))
        val today = CompanionMoney.answer(money("20", 1, "120", 5), week = false) { CompanionBrief.plainCedis(it) }
        assertTrue(today, today.startsWith("Today you've spent 20 cedis through me, in 1 payment."))
        for (s in listOf(week, today)) assertFalse(s, BANNED.containsMatchIn(s))
    }

    @Test fun nothing_to_report_is_said_without_alarm() {
        val none = CompanionMoney.answer(money("0", 0, "0", 0), week = true) { CompanionBrief.plainCedis(it) }
        assertTrue(none, none.contains("nothing to report"))
        val noneToday = CompanionMoney.answer(money("0", 0, "30", 2), week = false) { CompanionBrief.plainCedis(it) }
        assertTrue(noneToday, noneToday.startsWith("I haven't seen any payment go through me today."))
    }

    @Test fun cedis_are_said_without_empty_pesewas() {
        assertEquals("5 cedis", CompanionBrief.plainCedis(BigDecimal("5")))
        assertEquals("5.50 cedis", CompanionBrief.plainCedis(BigDecimal("5.5")))
        assertEquals("1,200 cedis".replace(",", ""), CompanionBrief.plainCedis(BigDecimal("1200.00")))
    }

    @Test fun the_ledger_slice_counts_today_and_the_week() {
        val now = at(15)
        val dayStart = at(0)
        val day = 24 * 3_600_000L
        val items = listOf(
            now - 3_600_000L to BigDecimal("10"),          // today
            at(1) to BigDecimal("5.5"),                    // today
            now - 2 * day to BigDecimal("20"),             // this week
            now - 9 * day to BigDecimal("99"),             // too old
            now - day to BigDecimal.ZERO)                  // nothing paid
        val m = CompanionMoney.slice(items, dayStart, now)
        assertEquals(BigDecimal("15.5"), m.today)
        assertEquals(2, m.todayCount)
        assertEquals(BigDecimal("35.5"), m.week)
        assertEquals(3, m.weekCount)
    }

    // ---------------------------------------------------------------- one remembered preference
    private fun fact(key: String, value: String, told: Boolean = true, first: Long = 1L) =
        MemEntry(MemKind.FACT, key, value, told, 1, 1.0, first, first)

    @Test fun only_a_liked_thing_the_user_told_us_is_used() {
        assertNull(CompanionBrief.pickLiked(emptyList(), 5))
        assertNull(CompanionBrief.pickLiked(listOf(fact("my mother", "Ama"), fact("i live in", "Kumasi"), fact("i like", "jollof", told = false)), 5))
        val one = CompanionBrief.pickLiked(listOf(fact("i love", "highlife music")), 100)
        assertEquals(Liked("love", "highlife music"), one)
    }

    @Test fun the_liked_thing_changes_from_day_to_day() {
        val facts = listOf(fact("i like", "football", first = 1), fact("i enjoy", "gospel music", first = 2))
        val a = CompanionBrief.pickLiked(facts, 10)
        val b = CompanionBrief.pickLiked(facts, 11)
        assertNotNull(a); assertNotNull(b)
        assertTrue(a != b)
        assertEquals(Liked("like", "football"), a)
        assertEquals(Liked("enjoy", "gospel music"), b)
    }

    // ---------------------------------------------------------------- the fixed lines
    @Test fun lessons_are_short_kind_and_sell_nothing() {
        for (l in CompanionLines.LESSONS) {
            assertTrue(l.topic, l.text.split(Regex("\\s+")).size <= 40)
            assertFalse(l.topic, Regex("(?i)\\b(?:buy|purchase|subscribe|invest in|bet|gamble|loan)\\b").containsMatchIn(l.text))
        }
        assertEquals(CompanionLines.LESSONS.size, CompanionLines.LESSONS.map { it.topic }.toSet().size)
        assertEquals(CompanionLines.lesson(0, 3), CompanionLines.lesson(CompanionLines.LESSONS.size, 3))   // it wraps round
    }

    @Test fun openers_ask_one_easy_question_and_stay_short() {
        for (bored in listOf(false, true)) for (p in 0..5) {
            val o = CompanionLines.opener(bored, p)
            assertEquals(o, 1, o.count { it == '?' })
            assertTrue(o, o.split(Regex("\\s+")).size <= 22)
        }
    }

    @Test fun the_money_notes_switch_lines_say_what_changed() {
        assertTrue(CompanionLines.NOTES_OFF.contains("won't mention your spending"))
        assertTrue(CompanionLines.NOTES_ON.contains("gentle note"))
    }
}
