package com.tekyerema

import com.tekyerema.language.EntityTags
import com.tekyerema.money.ussd.UssdNavigatorTool
import com.tekyerema.voice.ConfirmTemplates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-phone money walk's read-back in a Twi conversation (A20e, 3 October 2026: "Send 5 cedis to Prince Alhassan, on
 * 0 5 5…" stayed English in Twi mode). Clean sends get the Twi template — name AND number, the amount and the number for
 * the English voice; any warning keeps the full English sentence.
 */
class TwiMoneyConfirmTest {
    private fun send(vararg extra: Pair<String, Any>) = JSONObject().put("number", "0552093690").put("amount", "5")
        .put("contact", "Prince Alhassan").put("_sim", "mtn").apply { extra.forEach { (k, v) -> put(k, v) } }

    @Test fun `a clean send is read back in Twi with the name, the number and the amount`() {
        val spec = UssdNavigatorTool.twiSpec(send())
        assertNotNull(spec)
        val twi = ConfirmTemplates.twi(spec!!)
        // owner, 4 October 2026: ONE voice — the amount in Twi words and the name, said by the lab's Twi voice (no English stretch)
        assertEquals("Menbue Mobile Money sɛ wode sidi anum kɔma Prince Alhassan, nɔma a n'awieɛ yɛ mmiɛnsa nsia nkron sero? Wo ankasa na wobɛhyɛ PIN. Ka aane anaa daabi.", twi)
        assertEquals(listOf(twi to false), EntityTags.segments(twi))
    }

    @Test fun `a warning keeps the English read-back`() {
        assertNull("a number the user never said", UssdNavigatorTool.twiSpec(send("_unsaid" to true)))
        assertNull("another network than the SIM", UssdNavigatorTool.twiSpec(send("number" to "0508797323")))
    }

    @Test fun `a reference is read back in the Twi frame, not machine-translated`() {
        val twi = ConfirmTemplates.twi(UssdNavigatorTool.twiSpec(send("reference" to "aduane"))!!)
        assertEquals("Menbue Mobile Money sɛ wode sidi anum kɔma Prince Alhassan, nɔma a n'awieɛ yɛ mmiɛnsa nsia nkron sero? Nkyerɛkyerɛmu: aduane. Wo ankasa na wobɛhyɛ PIN. Ka aane anaa daabi.", twi)
    }

    @Test fun `a balance check has no template`() {
        assertNull(UssdNavigatorTool.twiSpec(JSONObject().put("intent", "check_balance")))
    }
}
