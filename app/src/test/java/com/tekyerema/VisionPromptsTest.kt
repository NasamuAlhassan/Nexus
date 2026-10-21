package com.tekyerema

import com.tekyerema.camera.VisionPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPromptsTest {
    @Test fun moneyIsSummedOnThePhone() {
        val said = VisionPrompts.spokenMoney("""{"items":[{"kind":"note","value_ghs":20,"count":2,"sure":true},{"kind":"note","value_ghs":5,"count":1,"sure":true}],"other_currency":"","note":""}""")
        assertEquals("I see two 20 cedi notes and one 5 cedi note. That's 45 cedis in all.", said)
    }

    @Test fun coinsAndDoubt() {
        val said = VisionPrompts.spokenMoney("""Here: {"items":[{"kind":"coin","value_ghs":0.5,"count":1,"sure":true},{"kind":"note","value_ghs":10,"count":1,"sure":false}],"note":"Turn the note over."}""")!!
        assertTrue(said, said.startsWith("I see one 10 cedi note (not sure) and one 50 pesewa coin. That's about 10 cedis 50 pesewas in all."))
        assertTrue(said.endsWith("Turn the note over."))
    }

    @Test fun noMoneyAndForeignMoney() {
        assertTrue(VisionPrompts.spokenMoney("""{"items":[]}""")!!.startsWith("I don't see any money."))
        assertEquals("That doesn't look like cedis. I think it's US dollars.", VisionPrompts.spokenMoney("""{"items":[],"other_currency":"US dollars"}"""))
        assertNull(VisionPrompts.spokenMoney("no json here"))
    }

    @Test fun moneyQuestions() {
        for (q in listOf("how much am I holding", "count my money", "which note is this", "what coins are these", "how many cedis", "sika ahe"))
            assertTrue(q, VisionPrompts.isMoneyQuestion(q))
        for (q in listOf("what am I seeing", "what colour is this shirt", "read this label", "how much is this shirt", null))
            assertFalse("$q", VisionPrompts.isMoneyQuestion(q))
    }

    @Test fun noTotalWhenANoteIsUnreadable() {
        val said = VisionPrompts.spokenMoney("""{"items":[{"kind":"note","value_ghs":50,"count":1}],"unreadable":1,"confidence":"medium","fix":"Spread the notes out."}""")!!
        assertTrue(said, said.startsWith("I see one 50 cedi note. There is one more I can't read, so I can't give the total yet."))
        assertTrue(said.endsWith("Spread the notes out."))
    }

    @Test fun smallPrintGetsMorePixels() {
        assertEquals(1280, VisionPrompts.maxSide("when does this milk expire", money = false))
        assertEquals(768, VisionPrompts.maxSide("what am I holding", money = false))
        assertEquals(768, VisionPrompts.maxSide("how much money", money = true))
    }

    @Test fun theQuestionIsInThePrompt() {
        assertTrue(VisionPrompts.forQuestion("is the stove on?").contains("\"is the stove on?\""))
    }
}
