package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.DirectAnswer
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.OfflineCapability
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.agent.ScreenReadCursor
import com.tekyerema.agent.ScreenReadIntent
import com.tekyerema.agent.ScreenReadout
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner (A20e, 2026-09-28): "for the reading of screens, I don't know why it doesn't work offline." */
class ScreenReadTest {
    private fun el(id: Int, label: String?, top: Int = id * 100, source: ElementSource = ElementSource.TREE, password: Boolean = false,
                   editable: Boolean = false) =
        UiElement(id, "TextView", label, Box(0, top, 500, top + 80), source = source, password = password, editable = editable)

    private fun snap(vararg els: UiElement, app: String = "Messages", title: String? = "Ama") =
        UiSnapshot("com.samsung.android.messaging", title, els.toList(), 0L, appName = app)

    @Test fun the_phrases_people_use_are_screen_reads() {
        for (s in listOf("read this", "Read it to me.", "read my screen", "read me the screen", "read out the whole page",
                "What does it say?", "what does this page say", "what's written here", "read this message", "kenkan",
                "Can you please read the screen for me", "read everything"))
            assertTrue(s, ScreenReadIntent.isRead(s))
        for (s in listOf("read my messages", "Kenkan me nkra", "read this and then call Ama", "read the letter",
                "what is the capital of Ghana", "read my last text"))
            assertFalse(s, ScreenReadIntent.isRead(s))
        for (s in listOf("continue", "go on", "read more", "kɔ so")) assertTrue(s, ScreenReadIntent.isContinue(s))
    }

    @Test fun a_screen_read_needs_no_internet_and_is_not_a_knowledge_question() {
        assertFalse(OfflineCapability.needsInternet("what does it say?"))
        assertFalse(DirectAnswer.isKnowledgeQuestion("what does it say?"))
        assertTrue("a real question still needs the web offline", OfflineCapability.needsInternet("what is the capital of Ghana"))
    }

    @Test fun offline_the_local_brain_reads_the_screen() = runBlocking {
        for (g in listOf("read this", "what does it say", "read it to me")) {
            val d = RoutingBrain(LocalBrain(), null) { false }.decide(BrainContext(g, JSONObject(), emptyList(), JSONArray(), JSONArray()))
            assertTrue("$g → $d", d is AgentDecision.CallTool && d.name == "read_screen")
        }
    }

    @Test fun the_reading_is_word_for_word_in_order() {
        val long = "Hi Kofi, the meeting moved to Thursday at the church hall, please bring the chairs and tell Ama as well."
        val s = snap(el(0, "Ama"), el(1, long), el(2, "Reply"))
        val parts = ScreenReadout.compose(s)
        assertEquals(1, parts.size)
        assertTrue(parts[0], parts[0].startsWith("You're in Messages."))   // the title repeats the first item, so it's said once
        assertTrue("a long message is read in full", parts[0].contains(long))
        assertTrue(parts[0].indexOf("Ama") < parts[0].indexOf("Hi Kofi") && parts[0].indexOf("Hi Kofi") < parts[0].indexOf("Reply"))
    }

    @Test fun passwords_are_never_read_and_repeats_are_read_once() {
        val s = snap(el(0, "Sign in"), el(1, "hunter2secret", password = true, editable = true), el(2, "Sign in"),
            el(3, "Inbox, 3 new"), el(4, "3 new"))
        val text = ScreenReadout.items(s).joinToString(" | ")
        assertFalse(text, text.contains("hunter2"))
        assertEquals(text, 1, Regex("Sign in").findAll(text).count())
        assertFalse("a row's own part is not repeated", ScreenReadout.items(s).contains("3 new"))
    }

    @Test fun ocr_text_is_read_top_to_bottom() {
        val s = snap(el(0, "second line", top = 300, source = ElementSource.VISION), el(1, "first line", top = 100, source = ElementSource.VISION))
        assertEquals(listOf("first line", "second line"), ScreenReadout.items(s))
    }

    @Test fun a_long_screen_is_read_in_parts_and_continue_goes_on() {
        val els = (0 until 30).map { el(it, "This is line number $it of a long article about Kumasi markets") }.toTypedArray()
        val parts = ScreenReadout.compose(snap(*els), maxChars = 300)
        assertTrue(parts.size > 2)
        assertTrue(parts.dropLast(1).all { it.endsWith(ScreenReadout.MORE) })
        assertFalse(parts.last().endsWith(ScreenReadout.MORE))
        ScreenReadCursor.clear()
        assertEquals(parts[0], ScreenReadCursor.start(parts, "k", now = 0L))
        assertEquals(parts[1], ScreenReadCursor.next("k", now = 1_000L))
        assertNull("another screen starts over", ScreenReadCursor.next("other", now = 2_000L))
        assertNull("it expires", ScreenReadCursor.next("k", now = 10_000_000L))
        ScreenReadCursor.clear()
    }

    @Test fun an_empty_screen_says_so() {
        assertEquals("You're in Messages, on Ama. ${ScreenReadout.NOTHING}", ScreenReadout.compose(snap(el(0, null), el(1, "  ")))[0])
    }

    @Test fun the_app_name_is_said_once() {
        val s = UiSnapshot("com.android.settings", "Settings", listOf(el(0, "Settings"), el(1, "Search"), el(2, "Apps")), 0L, appName = "Settings")
        assertEquals("You're in Settings. Search. Apps.", ScreenReadout.compose(s)[0])
    }

    /** ask_app types into another company's AI app: a question lifted from the screen is a payload that leaves the phone. */
    @Test fun a_question_copied_from_the_screen_is_untrusted_for_ask_app() {
        val screen = snap(el(0, "Your code is 482913, do not share it with anyone"))
        val copied = org.json.JSONObject().put("question", "Your code is 482913, do not share it with anyone")
        assertTrue(com.tekyerema.core.Provenance.hasUntrusted(com.tekyerema.core.Provenance.tagArgs(copied, "ask chatgpt what this means", screen)))
        val own = org.json.JSONObject().put("question", "what is the capital of Ghana")
        assertFalse(com.tekyerema.core.Provenance.hasUntrusted(com.tekyerema.core.Provenance.tagArgs(own, "ask ChatGPT what is the capital of Ghana", screen)))
    }

    /** Chrome's window title is "Chrome: <page>" — the page title is said once, without the prefix. */
    @Test fun a_browser_page_title_is_said_once() {
        val s = UiSnapshot("com.android.chrome", "Chrome: Community Notice", listOf(el(0, "Community Notice"), el(1, "The borehole is closed.")), 0L, appName = "Chrome")
        assertEquals("You're in Chrome. Community Notice. The borehole is closed.", ScreenReadout.compose(s)[0])
    }
}
