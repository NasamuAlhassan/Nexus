package com.tekyerema

import com.tekyerema.agent.ReviseTurn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner's live English corrections at a money read-back (22 Oct 2026): the reference is changed too, never dropped. */
class ReferenceCorrectionTest {
    private val nav = JSONObject().put("number", "0508797323").put("amount", "5").put("reference", "buy Airport")
    private fun patch(s: String) = ReviseTurn.patch("ussd_navigate", nav, s)!!.args

    @Test fun theReferenceIsChangedAsSaid() {
        patch("no make it 2 cedis and change the reference to 50 cedis").let { assertEquals("2", it.getString("amount")); assertEquals("50 cedis", it.getString("reference")) }
        assertEquals("food", patch("change the reference to food").getString("reference"))
        patch("no, the reference is school fees").let { assertEquals("school fees", it.getString("reference")); assertEquals("5", it.getString("amount")) }
        assertEquals("0508797323", patch("the reference is 2 cds").getString("number"))
        // the owner's exact words, as the recogniser wrote them
        patch("I'm so change the amount to 3 Ghana cedis and also change the reference to charger").let {
            assertEquals("3", it.getString("amount")); assertEquals("charger", it.getString("reference")) }
    }

    /** Owner's live test (22 Oct 2026): "I said buy 2 cities airtime for Loglo" was read back unchanged, for this phone. */
    @Test fun airtimeForSomeoneNamesThem() {
        val airtime = JSONObject().put("_kind", "buy_airtime").put("_amount", "2")
        ReviseTurn.patch("mobile_money", airtime, "I said buy 2 cities airtime for Loglo")!!.let {
            assertEquals("Loglo", it.args.getString("_recipient")); org.junit.Assert.assertFalse(it.unchanged) }
        // a restated amount with a target we can't parse is the brain's — never "unchanged"
        org.junit.Assert.assertNull(ReviseTurn.patch("ussd_navigate", JSONObject(nav.toString()).put("amount", "5"), "I said 5 cedis for Kofi"))
    }
}
