package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiRange
import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.LoopGuard
import com.tekyerema.agent.References
import com.tekyerema.agent.RetrievalNeed
import com.tekyerema.agent.RouterTiers
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.agent.search.LastSearch
import com.tekyerema.agent.search.Source
import com.tekyerema.core.NetworkMonitor
import com.tekyerema.core.TextEntities
import com.tekyerema.tools.Grounding
import com.tekyerema.tools.MatchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W7 remainder: loop guard (G-21/G-12), locators (G-07), retrieval need (C-05), entities (E-23), router (C-01/C-17/N-10),
 *  schedules / notification rules / source readback by voice (A-12/A-13/I-07). */
class AgentW7bTest {

    // ---------------------------------------------------------------- G-21 / G-12
    @Test fun a_repeated_back_and_forth_is_a_cycle_but_list_detail_browsing_is_not() {
        val g = LoopGuard(cycleNudge = 2, cycleStop = 3)
        assertEquals(LoopGuard.Cycle.NONE, g.onTransition("A", "B"))
        assertEquals(LoopGuard.Cycle.NONE, g.onTransition("B", "A"))
        assertEquals(LoopGuard.Cycle.NUDGE, g.onTransition("A", "B"))
        assertEquals(LoopGuard.Cycle.NONE, g.onTransition("B", "A"))
        assertEquals(LoopGuard.Cycle.STOP, g.onTransition("A", "B"))

        val browse = LoopGuard(cycleNudge = 2, cycleStop = 3)
        for (item in listOf("M1", "M2", "M3", "M4", "M5")) {
            assertEquals(LoopGuard.Cycle.NONE, browse.onTransition("LIST", item))
            assertEquals(LoopGuard.Cycle.NONE, browse.onTransition(item, "LIST"))
        }
        // A step that changed nothing is the stall guard's business.
        assertEquals(LoopGuard.Cycle.NONE, browse.onTransition("LIST", "LIST"))
        assertEquals(LoopGuard.Cycle.NONE, browse.onTransition("LIST", null))
    }

    @Test fun an_uncertain_send_is_not_retried_until_the_task_has_checked() {
        val g = LoopGuard()
        g.noteConsequential("send_sms", success = true, verified = false)
        assertTrue(g.mustCheckFirst("send_sms"))
        assertFalse(g.mustCheckFirst("make_call"))
        g.noteChecked()
        assertFalse(g.mustCheckFirst("send_sms"))
        g.noteConsequential("make_call", success = false, verified = false)
        assertTrue(g.mustCheckFirst("make_call"))
        g.noteConsequential("make_call", success = true, verified = true)
        assertFalse(g.mustCheckFirst("make_call"))
    }

    @Test fun the_user_is_asked_once_after_repeated_failures() {
        val g = LoopGuard(maxFailures = 3, maxEscalations = 1)
        assertFalse(g.shouldEscalate(2))
        assertTrue(g.shouldEscalate(3))
        assertFalse(g.shouldEscalate(5))   // at most once per task
        assertTrue(LoopGuard.wantsStop("No, leave it"))
        assertTrue(LoopGuard.wantsStop("stop"))
        assertFalse(LoopGuard.wantsStop("yes keep trying"))
        assertTrue(LoopGuard.ANOTHER_WAY.containsMatchIn("try another way"))
        assertTrue(LoopGuard.ANOTHER_WAY.containsMatchIn("that's not working, try something else"))
        assertFalse(LoopGuard.ANOTHER_WAY.containsMatchIn("send it to Kofi instead"))
    }

    // ---------------------------------------------------------------- G-07
    private fun el(id: Int, label: String?, viewId: String? = null, hint: String? = null, role: String = "Button",
                   clickable: Boolean = true, editable: Boolean = false, checkable: Boolean = false, range: UiRange? = null) =
        UiElement(id, role, label, Box(0, id * 100, 100, id * 100 + 90), clickable = clickable, editable = editable,
            checkable = checkable, viewId = viewId, hint = hint, range = range)

    @Test fun an_unlabelled_icon_is_found_by_its_resource_id() {
        val els = listOf(el(0, "Chats"), el(1, null, viewId = "com.whatsapp:id/send_button", role = "ImageButton"), el(2, "Calls"))
        val m = Grounding.bestMatch(els, "send")
        assertTrue(m is MatchOutcome.Found)
        assertEquals(1, (m as MatchOutcome.Found).element.id)
        // camelCase ids split too
        val m2 = Grounding.bestMatch(listOf(el(0, null, viewId = "app:id/btnSearch")), "search")
        assertEquals(0, (m2 as MatchOutcome.Found).element.id)
    }

    /** A20e 2026-09-29: the model asked for "Library" in YT Music's song menu and got "Remove from library". */
    @Test fun a_partial_label_match_never_adds_an_action() {
        val menu = listOf(el(0, "Play next"), el(1, "Remove from library"), el(2, "Download"), el(3, "Share"))
        assertTrue(Grounding.bestMatch(menu, "Library") is MatchOutcome.OnlyWeak)
        assertTrue(Grounding.bestMatch(menu, "Remove from library") is MatchOutcome.Found)   // asked for exactly: fine
        assertEquals(2, (Grounding.bestMatch(menu, "download") as MatchOutcome.Found).element.id)
        val nav = listOf(el(0, "Home"), el(1, "Library tab"))
        assertEquals(1, (Grounding.bestMatch(nav, "Library") as MatchOutcome.Found).element.id)
    }

    @Test fun a_filled_text_box_is_found_by_its_hint_and_the_only_box_by_its_role() {
        val box = el(3, "Hello Ama", hint = "Message", role = "EditText", editable = true)
        val els = listOf(el(0, "Attach"), box, el(4, "Send"))
        assertEquals(3, (Grounding.bestMatch(els, "message") as MatchOutcome.Found).element.id)
        assertEquals(3, (Grounding.bestMatch(els, "the text box") as MatchOutcome.Found).element.id)
        // two boxes → "the text box" is ambiguous, not guessed
        val two = els + el(5, "", role = "EditText", editable = true)
        assertTrue(Grounding.bestMatch(two, "the text box") !is MatchOutcome.Found)
    }

    @Test fun a_label_match_still_wins_over_other_attributes() {
        val els = listOf(el(0, "Send", viewId = "x:id/other"), el(1, null, viewId = "x:id/send"))
        assertEquals(0, (Grounding.bestMatch(els, "Send") as MatchOutcome.Found).element.id)
        assertNull(Grounding.attributeMatch(listOf(el(0, "Chats")), "zorblax"))
    }

    // ---------------------------------------------------------------- C-05
    @Test fun retrieval_need_three_classes() {
        assertEquals(RetrievalNeed.Need.NONE, RetrievalNeed.classify("What is the capital of Ghana?"))
        assertEquals(RetrievalNeed.Need.NONE, RetrievalNeed.classify("Explain inflation"))
        assertEquals(RetrievalNeed.Need.SINGLE, RetrievalNeed.classify("What is the cedi to dollar rate today?"))
        assertEquals(RetrievalNeed.Need.SINGLE, RetrievalNeed.classify("Who won the match yesterday"))
        assertEquals(RetrievalNeed.Need.SINGLE, RetrievalNeed.classify("What's the weather in Kumasi"))
        assertEquals(RetrievalNeed.Need.MULTI, RetrievalNeed.classify("Compare MTN and Telecel data prices"))
        assertEquals(RetrievalNeed.Need.MULTI, RetrievalNeed.classify("Is the cedi stronger than last month?"))
        assertNull(RetrievalNeed.hint(RetrievalNeed.Need.NONE))
        assertTrue(RetrievalNeed.hint(RetrievalNeed.Need.SINGLE)!!.contains("web_search"))
    }

    // ---------------------------------------------------------------- E-23
    @Test fun entities_in_a_message() {
        val e = TextEntities.detectPatterns("Call me on 024 412 3456 or email ama@example.com. See graphic.com.gh on Friday at 5 pm.")
        val types = e.map { it.type }
        assertTrue(types.toString(), TextEntities.PHONE in types)
        assertTrue(TextEntities.EMAIL in types)
        assertTrue(TextEntities.URL in types)
        assertTrue(TextEntities.DATE in types)
        assertFalse("an e-mail's domain is not a separate link", e.any { it.type == TextEntities.URL && it.text == "example.com" })
        assertEquals("0244123456", e.first { it.type == TextEntities.PHONE }.text.filter { it.isDigit() })
        assertTrue(TextEntities.summary(e).startsWith("found: "))
        assertEquals("", TextEntities.summary(emptyList()))
        assertTrue(TextEntities.detectPatterns("Your code expires soon").isEmpty())
    }

    @Test fun that_number_refers_to_the_number_just_read() {
        assertEquals("call 0244123456", References.rewrite("call that number", person = null, options = emptyList(), number = "0244123456"))
        assertNull(References.rewrite("call that number", person = null, options = emptyList(), number = null))
        assertNull("'this number' is on the screen — LocalBrain reads it there",
            References.rewrite("call this number", person = null, options = emptyList(), number = "0244123456"))
    }

    // ---------------------------------------------------------------- C-01 / C-17 / N-10
    @Test fun router_inputs() {
        assertTrue(RouterTiers.enabled(RouterTiers.FAST_PATH))
        assertTrue(RouterTiers.enabled(RouterTiers.CLOUD))
        assertTrue(RoutingBrain.deviated(listOf("open_app({}) → success [native]; verified=false (x); ...")))
        assertTrue(RoutingBrain.deviated(listOf("tap({}) → rejected; element 9 is not listed")))
        assertFalse(RoutingBrain.deviated(listOf("open_app({}) → success [native]; verified=true (foreground)")))
        assertFalse(RoutingBrain.deviated(emptyList()))
        // QA D10 (3 October 2026): a READ that ran is its own result — "verified=false (no expectation given…)" on it sent
        // "read my last message" to the cloud after the local read_notifications had already answered.
        assertFalse(RoutingBrain.deviated(listOf("read_notifications({}) → success [none]; verified=false (no expectation given; screen changed=false is only a weak signal)")))
        assertTrue(RoutingBrain.deviated(listOf("read_notifications({}) → fail [none]; no notification access")))
        assertTrue(NetworkMonitor.isWeakLink(150, 400))
        assertFalse(NetworkMonitor.isWeakLink(5000, 400))
        assertFalse("no estimate is not weak", NetworkMonitor.isWeakLink(0, 400))
    }

    // ---------------------------------------------------------------- A-12 / A-13 / I-07 by voice
    private fun c(s: String) = FastIntentClassifier.classify(s)

    @Test fun schedules_by_voice() {
        assertTrue(c("Every day at 7 read my calendar") is FastIntent.CreateSchedule)
        assertTrue(c("At 9 pm remind me to take my medicine") is FastIntent.CreateSchedule)
        assertTrue(c("remind me every day at 9 p.m. to take my pills") is FastIntent.CreateSchedule)
        assertTrue("no time → still ours, so we can ask when", c("remind me to call mum") is FastIntent.CreateSchedule)
        assertEquals("Every day at 7 read my calendar", (c("Every day at 7 read my calendar") as FastIntent.CreateSchedule).text)
        assertFalse("an alarm is not a schedule", c("set an alarm at 7 every day") is FastIntent.CreateSchedule)
        assertFalse(c("what time is it") is FastIntent.CreateSchedule)
        assertEquals(FastIntent.ListSchedules, c("What are my reminders?"))
        assertEquals(FastIntent.ClearSchedules, c("cancel all my reminders"))
    }

    @Test fun notification_rules_and_sources_by_voice() {
        assertEquals(FastIntent.WatchSender("Ama", on = true), c("Tell me when Ama messages me"))
        assertEquals(FastIntent.WatchSender("Kofi Mensah", on = true), c("let me know when Kofi Mensah texts"))
        assertEquals(FastIntent.WatchSender("Ama", on = false), c("stop telling me when Ama messages"))
        assertEquals(FastIntent.Sources(links = false), c("Where did you get that?"))
        assertEquals(FastIntent.Sources(links = false), c("what's the source"))
        assertEquals(FastIntent.Sources(links = true), c("read me the links for that"))
        assertFalse("links on a page are the screen's business", c("read the links") is FastIntent.Sources)
    }

    @Test fun source_readback_wording() {
        val src = listOf(Source("", "https://www.bbc.com/news/world"), Source("", "https://www.graphic.com.gh/business"))
        assertEquals("That came from BBC and Graphic Online.", LastSearch.spoken(src, links = false))
        val links = LastSearch.spoken(src, links = true)
        assertTrue(links, links.contains("bbc dot com slash news slash world"))
        assertEquals("", LastSearch.spoken(emptyList(), links = false))
    }
}
