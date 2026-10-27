package com.tekyerema.phone

import com.tekyerema.phone.SimChoice.Decision
import com.tekyerema.phone.SimChoice.Sim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimChoiceTest {
    private val mtn = Sim(1, 0, "MTN GH", "mtn")
    private val voda = Sim(2, 1, "Vodafone", "vodafone")
    private val mtn2 = Sim(3, 1, "MTN", "mtn")

    @Test fun oneSimOrNoneAsksNothing() {
        assertEquals(Decision.Default, SimChoice.decide(emptyList(), null, null))
        assertEquals(Decision.Default, SimChoice.decide(listOf(mtn), "mtn", null))
    }

    @Test fun aTaskOfOneNetworkTakesTheSimOnThatNetwork() {
        assertEquals(Decision.Use(mtn), SimChoice.decide(listOf(mtn, voda), "mtn", null))
        assertEquals(Decision.Use(voda), SimChoice.decide(listOf(mtn, voda), "vodafone", null))
    }

    @Test fun otherwiseOneShortQuestion() {
        val d = SimChoice.decide(listOf(mtn, voda), null, null) as Decision.Ask
        assertEquals("Which SIM: MTN GH in slot 1, or Vodafone in slot 2?", d.question)
        assertTrue(SimChoice.decide(listOf(mtn, mtn2), "mtn", null) is Decision.Ask)       // two MTN SIMs: still asked
        assertTrue(SimChoice.decide(listOf(mtn, voda), "airteltigo", null) is Decision.Ask)
    }

    @Test fun anEarlierAnswerIsUsedWhileThatSimIsStillIn() {
        assertEquals(Decision.Use(voda), SimChoice.decide(listOf(mtn, voda), "mtn", voda))
        assertTrue(SimChoice.decide(listOf(mtn, mtn2), null, voda) is Decision.Ask)       // that SIM was taken out
    }

    @Test fun theAnswerIsANumberAnOrdinalOrANetwork() {
        val sims = listOf(mtn, voda)
        assertEquals(mtn, SimChoice.parseAnswer("one", sims))
        assertEquals(voda, SimChoice.parseAnswer("the second one", sims))
        assertEquals(voda, SimChoice.parseAnswer("SIM 2", sims))
        assertEquals(mtn, SimChoice.parseAnswer("MTN", sims))
        assertEquals(voda, SimChoice.parseAnswer("use vodafone please", sims))
        assertNull(SimChoice.parseAnswer("not sure", sims))
        assertNull(SimChoice.parseAnswer("MTN", listOf(mtn, mtn2)))                        // two MTN SIMs: the name does not tell
        assertEquals(mtn2, SimChoice.parseAnswer("second", listOf(mtn, mtn2)))
    }

    @Test fun aSimNamedInTheRequestIsTakenOutOfTheContact() {
        assertEquals("0552093690" to "two", SimChoice.extractMention("0552093690 using SIM two"))
        assertEquals("0552093690" to null, SimChoice.extractMention("0552093690 using sim tool"))
        assertEquals("Ama" to "second", SimChoice.extractMention("Ama on the second sim"))
        assertEquals("Ama" to "mtn", SimChoice.extractMention("Ama with my MTN sim"))
        assertEquals("Ama Simpson" to null, SimChoice.extractMention("Ama Simpson"))
        assertEquals("Simon" to null, SimChoice.extractMention("Simon"))
    }
}
