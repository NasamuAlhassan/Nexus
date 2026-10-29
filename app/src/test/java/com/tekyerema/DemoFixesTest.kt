package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.policy.ActionSemantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's demo test run on the Infinix, 29 Oct 2026. */
class DemoFixesTest {
    private fun field(id: Int, text: String, hint: String) = UiElement(id, "EditText", text, Box(0, id * 100, 1000, id * 100 + 80), editable = true, hint = hint)
    private val form = UiSnapshot("com.mtngh.mymtn", "Send MoMo", listOf(
        field(27, "0552093690", "Recipient number"), field(31, "10", "Enter Amount to Pay"), field(33, "food", "Reference"),
        UiElement(36, "Button", "Continue", Box(0, 2000, 1000, 2100), clickable = true)), 0L)
    private val names = { n: String -> if (n.endsWith("3690")) "My Wife" else null }

    @Test fun continueInMyMtnReadsTheFormBackAndSaysTheAmountChanged() {
        val line = ActionSemantics.confirmation("tap", form.elements[3], "Continue", form, goal = "send 5 cedis to my wife", nameOfNumber = names)!!
        assertTrue(line, line.startsWith("The amount is now 10 cedis, not 5. Send 10 cedis to My Wife"))
        assertTrue(line, "reference “food”" in line && line.endsWith("The PIN comes next."))
        val same = ActionSemantics.confirmation("tap", form.elements[3], "Continue", form, goal = "send 10 cedis to my wife", nameOfNumber = names)!!
        assertTrue(same, same.startsWith("Send 10 cedis to My Wife"))
    }

    @Test fun aSharePickersTitleIsNotThePerson() {
        val picker = UiSnapshot("org.telegram.messenger", "Select Chat", listOf(
            UiElement(0, "ViewGroup", "My Wife, last seen recently", Box(0, 300, 1000, 400), clickable = true),
            UiElement(12, "Button", "Share in 1 chat", Box(0, 2000, 1000, 2100), clickable = true)), 0L)
        val line = ActionSemantics.confirmation("tap", picker.elements[1], "Share in 1 chat", picker,
            goal = "take a selfie and send it to my wife on Telegram", handedOver = "photo")
        assertTrue(line, line != null && "Select Chat" !in line && "my wife" in line.lowercase())
        assertEquals("my wife", ActionSemantics.goalRecipient("take a selfie and send it to my wife on Telegram"))
        assertEquals("Kofi", ActionSemantics.goalRecipient("send the picture to Kofi"))
    }
}

class ReadingFixesTest {
    private fun el(id: Int, t: String) = com.tekyerema.accessibility.UiElement(id, "TextView", t, com.tekyerema.accessibility.Box(0, id * 50, 1000, id * 50 + 40))

    /** "read what's on my screen" on a PDF, Infinix 29 Oct 2026. */
    @Test fun theFirstPartReadsThePageNotOnlyWhereYouAre() {
        val page = "Page 1: UNIVERSITY OF GHANA Registered Courses STUDENT NAME " + "COURSE TITLE CREDITS ".repeat(40)
        val snap = com.tekyerema.accessibility.UiSnapshot("com.google.android.apps.nbu.files", null, listOf(el(1, "PDF view"), el(2, page)), 0L,
            appName = "Files by Google")
        val first = com.tekyerema.agent.ScreenReadout.compose(snap).first()
        assertTrue(first, first.startsWith("You're in Files by Google.") && "UNIVERSITY OF GHANA" in first && "PDF view" !in first)
    }

    @Test fun readWhileAReadingIsOpenGoesOn() {
        assertTrue(com.tekyerema.agent.ScreenReadIntent.isGoOnWhileOpen("read"))
        assertTrue(com.tekyerema.agent.ScreenReadIntent.isGoOnWhileOpen("Okay."))
        assertEquals(false, com.tekyerema.agent.ScreenReadIntent.isGoOnWhileOpen("read my messages"))
    }
}

class Oct29LogFixesTest {
    @Test fun oneWordPmIsATime() {
        val s = com.tekyerema.agent.triggers.ScheduleParser.parse("Remind me at 1pm to search for a visually impaired person")
        assertEquals(13, s?.hour); assertEquals(0, s?.minute)
        assertEquals(9, com.tekyerema.agent.triggers.ScheduleParser.parse("remind me at 9a.m. to call Ama")?.hour)
    }

    @Test fun aChatBarControlIsNotThePerson() {
        val chat = com.tekyerema.accessibility.UiSnapshot("com.whatsapp", "Call menu", listOf(
            com.tekyerema.accessibility.UiElement(45, "EditText", "Please do not forget", com.tekyerema.accessibility.Box(0, 2200, 900, 2300), editable = true, hint = "Message"),
            com.tekyerema.accessibility.UiElement(46, "Button", "Send", com.tekyerema.accessibility.Box(900, 2200, 1000, 2300), clickable = true)), 0L, appName = "WhatsApp")
        val line = ActionSemantics.confirmation("tap", chat.elements[1], "Send", chat)
        assertTrue(line.toString(), line == null || "Call menu" !in line)
    }
}

class StopGuardTest {
    @Test fun oneStrayTapDoesNotStopTwoTapsDo() {
        val g = com.tekyerema.ui.assistant.StopGuard
        assertEquals(false, g.shouldStop(10_000, true))     // first tap: armed only
        assertEquals(true, g.shouldStop(11_500, true))      // second within 3 s: stop
        assertEquals(false, g.shouldStop(20_000, true))     // a later lone tap arms again
        assertEquals(false, g.shouldStop(24_000, true))     // too late: arms again, no stop
        assertEquals(true, g.shouldStop(25_000, true))
        assertEquals(true, g.shouldStop(30_000, false))     // switched off: one tap stops
    }
}

class PracticeRunFixesTest {
    @Test fun aChatRowIsItsNameOnly() {
        assertEquals("My Wife", ActionSemantics.rowName("My Wife. Sent at 10:27 AM. https://chatgpt.com/s/t_6ae31f8e"))
        assertEquals("My Wife", ActionSemantics.rowName("My Wife, last seen recently"))
    }

    @Test fun aCorrectionSaidAsANewRequestStillSaysTheAmountChanged() {
        fun f(id: Int, text: String, hint: String) = com.tekyerema.accessibility.UiElement(id, "EditText", text, com.tekyerema.accessibility.Box(0, id * 100, 1000, id * 100 + 80), editable = true, hint = hint)
        fun form(amount: String) = com.tekyerema.accessibility.UiSnapshot("com.mtngh.mymtn", "Send MoMo", listOf(
            f(9, "0552093690", "Recipient number"), f(13, amount, "Enter Amount to Pay"), f(15, "food", "Reference")), 0L)
        ActionSemantics.lastReadBack = null
        val first = ActionSemantics.mtnReadBack(form("5"), "send 5 cedis to my wife", null)!!
        assertTrue(first, first.startsWith("Send 5 cedis"))
        val second = ActionSemantics.mtnReadBack(form("10"), "no, make it 10 cedis", null)!!
        assertTrue(second, second.startsWith("The amount is now 10 cedis, not 5."))
    }
}
