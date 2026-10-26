package com.tekyerema

import com.tekyerema.agent.DoneCheck
import com.tekyerema.money.ussd.GoalRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The free-form menu walk decides an obvious screen on the phone (no cloud call, works offline); the rest stays with the brain. */
class GoalRowsTest {
    private val finance = listOf("1" to "Savings & Investments", "2" to "Yello Save", "3" to "Allow Cash Out", "4" to "Loans", "5" to "Insurance", "6" to "Back")

    @Test fun the_goals_own_words_pick_the_one_row_that_names_them() {
        assertEquals("2", GoalRows.pickByWords("send 5 cedis to my yello save", finance))
        assertEquals("2", GoalRows.pickByWords("put 5 cedis in my yellow save", finance))   // the recogniser's spelling
        assertEquals("1", GoalRows.pickByWords("I want to save some money", listOf("1" to "Savings & Investments", "2" to "Loans")))   // save ~ savings
        assertNull(GoalRows.pickByWords("I want to save some money", finance))              // "Savings" and "Yello Save" both name it: not obvious
    }

    @Test fun nothing_obvious_means_no_pick_so_the_brain_or_the_user_decides() {
        assertNull(GoalRows.pickByWords("send 5 cedis to Ama", finance))                    // no distinctive word on any row
        assertNull(GoalRows.pickByWords("buy data", listOf("1" to "Data Bundle", "2" to "Data Gift")))   // two rows name it
        assertNull(GoalRows.pickByWords("", finance))
    }

    @Test fun risky_rows_and_navigation_rows_are_never_picked_unless_the_goal_says_so() {
        assertNull(GoalRows.pickByWords("take out some money", finance))                    // not "cash out" — nothing says it
        assertEquals("3", GoalRows.pickByWords("allow cash out", finance))                  // the goal itself names it
        assertEquals("4", GoalRows.pickByWords("I need a loan", finance))
        assertNull(GoalRows.pickByWords("go back", finance))                                // Back is navigation, never a pick
    }

    /** A fake embedder: the vector is the word's index in a small vocabulary, so similar words line up. */
    private fun embedder(): (String) -> FloatArray {
        val axes = listOf("save", "school", "fee", "bill", "bank")
        return { s ->
            val v = FloatArray(axes.size)
            s.lowercase().split(" ").forEach { w -> axes.indexOf(w).takeIf { it >= 0 }?.let { v[it] = 1f } }
            val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
            FloatArray(v.size) { v[it] / n }
        }
    }

    @Test fun offline_the_clearest_meaning_wins_and_a_tie_does_not() {
        val rows = listOf("1" to "School Fee", "2" to "Pay Bill", "3" to "Savings")
        assertEquals("1", GoalRows.pickByMeaning("pay my school fee", rows, embedder(), minScore = 0.6, minMargin = 0.1))
        assertNull(GoalRows.pickByMeaning("school bill", listOf("1" to "School", "2" to "Bill"), embedder(), minScore = 0.6, minMargin = 0.1))   // two equally close
        assertNull(GoalRows.pickByMeaning("pay my school fee", rows, null))                                    // no embedder: no pick
    }

    @Test fun a_website_request_is_done_when_the_browser_is_in_front() {
        assertNull(DoneCheck.check("open the Apple website", "Chrome", listOf("New tab")))
        assertNull(DoneCheck.check("go to apple.com", "Chrome", listOf("New tab")))
        assertTrue(DoneCheck.targets("open settings and go to the battery page").isNotEmpty())   // app pages are still checked
        assertFalse(DoneCheck.check("open settings and go to the battery page", "Settings", listOf("Network", "Sounds")) == null)
    }
}
